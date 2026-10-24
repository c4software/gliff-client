package io.github.c4software.gliff.data.session

import io.github.c4software.gliff.data.video.FrameSink
import io.github.c4software.gliff.data.video.H264
import io.github.c4software.gliff.data.video.VideoDecoder
import io.github.c4software.gliff.domain.clipboard.ClipboardSync
import io.github.c4software.gliff.domain.input.BrowserInput
import io.github.c4software.gliff.domain.input.KeyLayout
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.machine.MachineRepository
import io.github.c4software.gliff.domain.session.SessionError
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.domain.session.StreamInfo
import io.github.c4software.gliff.domain.session.Viewport
import io.github.c4software.gliff.domain.session.laidLike
import io.github.c4software.gliff.domain.session.remoteMode
import io.github.c4software.gliff.domain.session.sharperMirror
import io.github.c4software.gliff.domain.session.zoomStep
import io.github.c4software.gliff.domain.time.Clock
import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientCaps
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.ClipboardMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.GliffException
import io.github.c4software.gliff.proto.OutputInfo
import io.github.c4software.gliff.proto.ProtocolException
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.transport.ChangedHostKeyException
import io.github.c4software.gliff.transport.IncompatibleException
import io.github.c4software.gliff.transport.Session
import io.github.c4software.gliff.transport.SessionHandler
import io.github.c4software.gliff.transport.UnknownHostKeyException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One machine's session, from connecting to closing. It outlives the screen
 * that shows it: a rotation or a window resize must not cut the connection.
 * `scope` is therefore the application's, not a screen's.
 */
class SessionController(
    private val machineId: String,
    private val machines: MachineRepository,
    private val connector: Connector,
    val decoder: VideoDecoder,
    private val scope: CoroutineScope,
    /** Where the connection is made, which blocks. */
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val mirror: FrameSink? = null,
    /** Told of every failure with its cause, for the platform's log. */
    private val onFailure: (Throwable) -> Unit = {},
    /** Put text copied on the remote on this device's clipboard. Called in `scope`. */
    private val clipboardOut: (String) -> Unit = {},
    /** Whether the clipboard is shared at all; asked each time. */
    private val clipboardOn: () -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> get() = mutableState

    val machine: Machine? get() = machines.find(machineId)

    private var job: Job? = null
    private var resizeJob: Job? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var closed = false

    /**
     * The window as last reported, the web viewer's while there is one, the
     * resolution the user imposes if any; then what follows from them, and
     * what the server was last asked for.
     */
    @Volatile
    private var window: Viewport? = null

    @Volatile
    private var viewer: Viewport? = null

    @Volatile
    private var forced: Viewport? = null

    /** How many times larger the picture is pinched here, in steps; see [setMagnified]. */
    @Volatile
    private var magnified = 1f

    /** The mirrored screen, once the server has said which; null for a private one. */
    @Volatile
    private var mirrored: OutputInfo? = null

    @Volatile
    private var wanted: ClientMsg.Resize? = null

    @Volatile
    private var requested: ClientMsg.Resize? = null

    @Volatile
    private var lastKeyframeRequestMs = 0L

    /** Connect, or connect again after a failure. */
    fun start() {
        if (closed) return
        job?.cancel()
        mutableState.value = SessionState.Connecting
        job = scope.launch { mutableState.value = runOnce() }
    }

    /** Trust the key of the pending [SessionState.HostKeyPrompt] and connect. */
    fun acceptHostKey() {
        val prompt = mutableState.value as? SessionState.HostKeyPrompt ?: return
        val machine = machine ?: return
        machines.save(machine.copy(hostKey = prompt.key.fingerprint))
        start()
    }

    /** End the session for good. */
    fun close() {
        if (closed) return
        closed = true
        resizeJob?.cancel()
        // A live session says Bye and ends by itself; anything else is cut.
        session?.close() ?: job?.cancel()
        mutableState.value = SessionState.Closed
    }

    /**
     * The video area changed: `width x height` physical pixels at display
     * scale `scale`. The server is asked to follow, once the size has been
     * still for a moment: a window edge being dragged reports dozens of sizes.
     */
    fun setWindowSize(width: Int, height: Int, scale: Float) {
        // The reference client stays silent under 64 px, and so do we.
        if (width < MIN_WINDOW || height < MIN_WINDOW || closed) return
        // Kept for when the viewer leaves; until then the remote is drawn for it.
        window = Viewport(width, height, scale)
        follow()
    }

    /**
     * A web browser watches the session through the mirror: the remote is
     * sized for its window rather than this device's, which then shows the
     * picture fitted in its own. Null when the viewer is gone.
     */
    fun setViewer(view: Viewport?) {
        if (closed) return
        viewer = view?.takeIf { it.width >= MIN_WINDOW && it.height >= MIN_WINDOW }
        follow()
    }

    /**
     * The user wants the remote at this resolution and scale whatever shows
     * it, laid the way of the window: larger than the screen, it shows more,
     * fitted in it. Null follows the window again.
     */
    fun setForced(mode: Viewport?) {
        if (closed) return
        forced = mode
        follow()
    }

    /**
     * The picture is pinched `zoom` times larger on this screen. A mirrored
     * screen is then streamed larger, so the zoomed picture shows its real
     * pixels rather than enlarged ones. A private screen is not: it would
     * have to change its own size or scale, under its windows.
     */
    fun setMagnified(zoom: Float) {
        if (closed) return
        magnified = zoomStep(zoom)
        follow()
    }

    /** Ask the server for what the window, the viewer and the user now make. */
    private fun follow() {
        val view = viewer ?: window ?: return
        val mode = forced?.let { it.laidLike(view) } ?: view
        val fitting = remoteMode(mode.width, mode.height, mode.scale)
        val screen = mirrored
        // The web viewer sees the whole picture: it sizes the stream for itself.
        val sharper = screen?.takeIf { viewer == null && magnified > 1f }?.let {
            sharperMirror(
                fitting.width, fitting.height, magnified,
                it.width, it.height, decoder.maxWidth, decoder.maxHeight,
            )
        }
        val size = sharper?.let { (w, h) -> ClientMsg.Resize(w, h, fitting.scale) }
            ?: ClientMsg.Resize(fitting.width, fitting.height, fitting.scale)
        if (size == wanted) return
        wanted = size
        resizeLater()
    }

    private fun resizeLater() {
        resizeJob?.cancel()
        resizeJob = scope.launch {
            // The first size goes at once, so the stream starts at the right one.
            if (requested != null) delay(RESIZE_DEBOUNCE_MS)
            sendResize()
        }
    }

    /** Pointer and key events; dropped while there is no session to take them. */
    fun send(msg: ClientMsg) {
        session?.send(msg)
    }

    /**
     * Clipboard work, done one piece at a time in `scope`: the engine is
     * fed from the session's reader thread and from the screen.
     */
    private val clipboardWork = Channel<() -> Unit>(Channel.UNLIMITED)
    private val clipboard = ClipboardSync(
        send = { msg, payload -> session?.send(ClientMsg.Clipboard(msg), payload) },
        onRemoteText = { if (clipboardOn()) clipboardOut(it) },
    )

    init {
        scope.launch { for (work in clipboardWork) work() }
    }

    /** This device's clipboard holds `text` (null: no text). Any thread. */
    fun localClipboard(text: String?) {
        if (clipboardOn()) clipboardWork.trySend { clipboard.localText(text) }
    }

    /** The layout the session types with: the remote's own when it could be read. */
    @Volatile
    var layout: KeyLayout = KeyLayout.US
        private set

    private val browser = BrowserInput({ layout }, ::send)

    /** Events from the web mirror's page, see [BrowserInput]. Any thread. */
    fun browserInput(text: String) {
        val stream = (mutableState.value as? SessionState.Streaming)?.stream ?: return
        val scale = maxOf(stream.scaleMilli, 10) / 1000.0
        browser.apply(text, stream.width / scale, stream.height / scale)
    }

    /** The video surface is back (or new): the decoder needs a keyframe to draw on it. */
    fun requestKeyframe() {
        val now = clock.nowMs()
        if (now - lastKeyframeRequestMs < KEYFRAME_REQUEST_GAP_MS) return
        lastKeyframeRequestMs = now
        session?.send(ClientMsg.RequestKeyframe)
    }

    private fun sendResize() {
        val size = wanted ?: return
        val session = session ?: return
        if (size == requested) return
        requested = size
        session.send(size)
    }

    private suspend fun runOnce(): SessionState {
        val machine = machine ?: return SessionState.Failed(SessionError.MachineRemoved)
        try {
            val transport = withContext(io) { connector.connect(machine) }
            val session = Session(
                transport,
                ClientCaps(
                    codecs = listOf(Codec.H264),
                    maxWidth = decoder.maxWidth,
                    maxHeight = decoder.maxHeight,
                    chroma = if (decoder.fullChroma) {
                        listOf(ChromaMode.Dual420, ChromaMode.Single420)
                    } else {
                        listOf(ChromaMode.Single420)
                    },
                ),
                Handler(),
                io,
                keymap = KeyLayout.parse(transport.keymap).also { layout = it ?: KeyLayout.US }?.text.orEmpty(),
            )
            requested = null
            this.session = session
            // Queued now, the offer of what the clipboard holds leaves behind the handshake.
            clipboardWork.trySend { clipboard.connected() }
            decoder.listener = object : VideoDecoder.Listener {
                override fun onFrameDone(frameId: Long) = session.send(ClientMsg.FrameAck(frameId, clock.nowMs()))
                override fun onNeedKeyframe() = requestKeyframe()
            }
            if (closed) session.close()
            // Queued now, it leaves right behind the handshake's Pong.
            sendResize()
            try {
                session.run()
            } finally {
                this.session = null
            }
            return if (closed) SessionState.Closed else SessionState.Failed(SessionError.ServerClosed)
        } catch (e: UnknownHostKeyException) {
            return SessionState.HostKeyPrompt(e.key, previous = null)
        } catch (e: ChangedHostKeyException) {
            return SessionState.HostKeyPrompt(e.key, previous = e.expected)
        } catch (e: IncompatibleException) {
            return SessionState.Failed(SessionError.Incompatible(e.message.orEmpty()))
        } catch (e: GliffException) {
            if (!closed) onFailure(e)
            return if (closed) SessionState.Closed else SessionState.Failed(SessionError.Connection(e.message ?: e.toString()))
        }
    }

    /** Runs on the session's reader thread. */
    private inner class Handler : SessionHandler {
        private var headless = false
        private var output = ""
        private var coded: Pair<Int, Int>? = null
        private var dual = false

        override fun onHello(ack: ServerMsg.HelloAck) {
            headless = ack.session.headless
            output = ack.session.output
            mirrored = if (headless) null else ack.outputs.firstOrNull { it.name == output } ?: ack.outputs.firstOrNull()
            if (mirrored != null && magnified > 1f) follow()
        }

        override fun onStreamConfig(config: ServerMsg.StreamConfig) {
            val offered = config.chroma == ChromaMode.Single420 ||
                (config.chroma == ChromaMode.Dual420 && decoder.fullChroma)
            if (config.codec != Codec.H264 || !offered) {
                throw ProtocolException("the server chose ${config.codec} ${config.chroma}, which was not offered")
            }
            // Only a new coded size or chroma resets the decoder: a change of
            // pace or of view box must not cost a keyframe. The mirror only
            // gets the main stream, a whole 4:2:0 picture by itself.
            val size = config.width to config.height
            val nowDual = config.chroma == ChromaMode.Dual420
            if (size != coded || nowDual != dual) {
                decoder.configure(config.width, config.height, nowDual)
                if (size != coded) mirror?.configure(config.width, config.height)
                coded = size
                dual = nowDual
            }
            mutableState.value = SessionState.Streaming(
                StreamInfo(
                    config.width, config.height, config.viewWidth, config.viewHeight,
                    config.scaleMilli, config.fpsCap, config.pipeline, headless, output,
                )
            )
        }

        override fun onClipboard(msg: ClipboardMsg, payload: ByteArray?) {
            if (clipboardOn()) clipboardWork.trySend { clipboard.onMessage(msg, payload) }
        }

        override fun onVideoFrame(frame: ServerMsg.VideoFrame, main: ByteArray, aux: ByteArray?) {
            // Without it a decoder holds frames back, waiting for a reordering that never comes.
            val unit = if (frame.keyframe) H264.withoutReordering(main) else main
            val auxUnit = aux?.takeIf { dual }?.let { if (frame.keyframe) H264.withoutReordering(it) else it }
            mirror?.frame(unit, frame.keyframe)
            decoder.decode(frame.frameId, unit, auxUnit, frame.keyframe)
        }
    }

    companion object {
        const val RESIZE_DEBOUNCE_MS = 150L
        const val MIN_WINDOW = 64
        const val KEYFRAME_REQUEST_GAP_MS = 250L
    }
}
