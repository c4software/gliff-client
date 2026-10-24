package io.github.c4software.gliff.data.session

import io.github.c4software.gliff.data.local.MemoryStore
import io.github.c4software.gliff.data.local.StoredMachineRepository
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.session.HostKeyInfo
import io.github.c4software.gliff.domain.session.SessionError
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.domain.session.Viewport
import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.VideoPipeline
import io.github.c4software.gliff.transport.ChangedHostKeyException
import io.github.c4software.gliff.transport.UnknownHostKeyException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The controller against a scripted server, over an in-memory connection. */
class SessionControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryStore()
    private val machines = StoredMachineRepository(store)
    private val machine = Machine(user = "val", host = "box").also { machines.save(it) }
    private val decoder = FakeDecoder()
    private val peers = LinkedBlockingQueue<Peer>()
    private val serverKey = HostKeyInfo("Ed25519", "SHA256:server")

    /** Trust on first use, as the real connector does it. */
    private val connector = Connector { m ->
        when (m.hostKey) {
            null -> throw UnknownHostKeyException(serverKey)
            serverKey.fingerprint -> Peer().also { peers.add(it) }.transport
            else -> throw ChangedHostKeyException(m.hostKey!!, serverKey)
        }
    }

    private val controller = SessionController(machine.id, machines, connector, decoder, scope, Dispatchers.IO, { 4242 })

    private fun config(width: Int = 1920, height: Int = 1080, fps: Int = 60) = ServerMsg.StreamConfig(
        Codec.H264, ChromaMode.Single420, VideoPipeline.Gpu, width, height, 1000, ByteArray(0), null, width, height, fps,
    )

    private fun peer(): Peer = peers.poll(5, TimeUnit.SECONDS) ?: error("no connection within 5 s")

    private inline fun <reified T : SessionState> awaitState(): T = runBlocking {
        withTimeout(5000) { controller.state.first { it is T } as T }
    }

    private fun trusted() = machines.save(machine.copy(hostKey = serverKey.fingerprint))

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    @Test
    fun aFirstConnectionAsksBeforeTrustingTheHostKey() {
        controller.start()
        val prompt = awaitState<SessionState.HostKeyPrompt>()
        assertEquals(serverKey, prompt.key)
        assertNull(prompt.previous)
        assertNull(machines.find(machine.id)!!.hostKey)

        controller.acceptHostKey()
        val hello = peer().handshake(config())
        assertEquals("SHA256:server", machines.find(machine.id)!!.hostKey)
        // Stored, so the next launch does not ask again.
        assertEquals("SHA256:server", StoredMachineRepository(store).find(machine.id)!!.hostKey)
        assertEquals(listOf(ChromaMode.Single420), hello.caps.chroma)
        assertEquals(4096 to 4096, hello.caps.maxWidth to hello.caps.maxHeight)
        val streaming = awaitState<SessionState.Streaming>()
        assertEquals(1920 to 1080, streaming.stream.width to streaming.stream.height)
        assertTrue(streaming.stream.headless)
        assertEquals("HEADLESS-2", streaming.stream.output)
    }

    @Test
    fun aChangedHostKeyShowsTheOneTrustedBefore() {
        machines.save(machine.copy(hostKey = "SHA256:old"))
        controller.start()
        val prompt = awaitState<SessionState.HostKeyPrompt>()
        assertEquals("SHA256:old", prompt.previous)
        assertEquals(serverKey, prompt.key)
        // Nothing is replaced until the user says so.
        assertEquals("SHA256:old", machines.find(machine.id)!!.hostKey)
    }

    @Test
    fun framesReachTheDecoderAndAreAcked() {
        trusted()
        controller.start()
        val p = peer()
        p.handshake(config())
        p.writer.write(ServerMsg.VideoFrame(7, 0, true, emptyList(), 1000, 0), ByteArray(1000))
        p.writer.write(ServerMsg.VideoFrame(8, 0, false, emptyList(), 10, 0), ByteArray(10))
        assertEquals(Triple(7L, 1000, true), decoder.decoded.poll(5, TimeUnit.SECONDS))
        assertEquals(Triple(8L, 10, false), decoder.decoded.poll(5, TimeUnit.SECONDS))
        assertEquals(ClientMsg.FrameAck(7, 4242), p.expect())
        assertEquals(ClientMsg.FrameAck(8, 4242), p.expect())
    }

    @Test
    fun theWindowSizeGoesRightBehindTheHandshake() {
        trusted()
        // The view is laid out before the connection is up.
        controller.setWindowSize(1080, 2200, 2.5f)
        controller.start()
        val p = peer()
        p.handshake(config())
        assertEquals(ClientMsg.Resize(1080, 2200, 2.5f), p.expect())
    }

    @Test
    fun theStreamBeforeTheWindowSizeIsNotDecoded() {
        trusted()
        controller.setWindowSize(1080, 2200, 2.5f)
        controller.start()
        val p = peer()
        // Streamed before the server read the size: acked, not decoded.
        p.handshake(config(2560, 1440))
        assertEquals(ClientMsg.Resize(1080, 2200, 2.5f), p.expect())
        p.writer.write(ServerMsg.VideoFrame(0, 0, true, emptyList(), 100, 0), ByteArray(100))
        assertEquals(ClientMsg.FrameAck(0, 4242), p.expect())
        p.writer.write(config(1080, 608))
        p.writer.write(ServerMsg.VideoFrame(1, 0, true, emptyList(), 100, 0), ByteArray(100))
        assertEquals(Triple(1L, 100, true), decoder.decoded.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf(1080 to 608), decoder.configured.toList())
    }

    @Test
    fun theFirstStreamIsTakenWhenNoOtherComes() {
        trusted()
        controller.setWindowSize(1920, 1080, 1f)
        controller.start()
        val p = peer()
        p.handshake(config())
        assertEquals(ClientMsg.Resize(1920, 1080, 1f), p.expect())
        // The server kept its size and said nothing more: a keyframe is asked for, and decoded.
        assertEquals(ClientMsg.RequestKeyframe, p.expect())
        p.writer.write(ServerMsg.VideoFrame(5, 0, true, emptyList(), 100, 0), ByteArray(100))
        assertEquals(Triple(5L, 100, true), decoder.decoded.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf(1920 to 1080), decoder.configured.toList())
        awaitState<SessionState.Streaming>()
    }

    @Test
    fun aDraggedWindowEdgeSendsOnlyWhereItStops() {
        trusted()
        controller.setWindowSize(1000, 800, 1f)
        controller.start()
        val p = peer()
        p.handshake(config())
        assertEquals(ClientMsg.Resize(1000, 800, 1f), p.expect())
        p.writer.write(config(1000, 800))
        for (w in 1001..1040) {
            controller.setWindowSize(w, 800, 1f)
            Thread.sleep(5)
        }
        assertEquals(ClientMsg.Resize(1040, 800, 1f), p.expect())
        // The same size again, and one too small to be a window, say nothing.
        controller.setWindowSize(1040, 800, 1f)
        controller.setWindowSize(40, 30, 1f)
        Thread.sleep(SessionController.RESIZE_DEBOUNCE_MS * 2)
        controller.requestKeyframe()
        assertEquals(ClientMsg.RequestKeyframe, p.expect())
    }

    @Test
    fun theRemoteIsSizedForTheWebViewerWhileThereIsOne() {
        trusted()
        controller.setWindowSize(1080, 2200, 2.5f)
        controller.start()
        val p = peer()
        p.handshake(config())
        assertEquals(ClientMsg.Resize(1080, 2200, 2.5f), p.expect())
        p.writer.write(config(1080, 2200))
        controller.setViewer(Viewport(2560, 1440, 1f))
        assertEquals(ClientMsg.Resize(2560, 1440, 1f), p.expect())
        // This device's window is remembered, not asked for: the keyboard came up.
        controller.setWindowSize(1080, 1200, 2.5f)
        controller.setViewer(Viewport(2560, 1440, 1f))
        // The page was zoomed out: the same pixels at a smaller scale.
        controller.setViewer(Viewport(2560, 1440, 0.5f))
        assertEquals(ClientMsg.Resize(2560, 1440, 0.5f), p.expect())
        controller.setViewer(null)
        assertEquals(ClientMsg.Resize(1080, 1200, 2.5f), p.expect())
    }

    @Test
    fun anImposedResolutionHoldsWhateverShowsIt() {
        trusted()
        controller.setWindowSize(1080, 2200, 2.5f)
        controller.setForced(Viewport(2560, 1440, 1f))
        controller.start()
        val p = peer()
        p.handshake(config())
        // Upright, as the window is.
        assertEquals(ClientMsg.Resize(1440, 2560, 1f), p.expect())
        p.writer.write(config(1440, 2560))
        controller.setWindowSize(2200, 1080, 2.5f)
        assertEquals(ClientMsg.Resize(2560, 1440, 1f), p.expect())
        // A browser of another size changes nothing, unless it stands the other way.
        controller.setViewer(Viewport(1920, 1080, 2f))
        controller.setViewer(Viewport(800, 1200, 2f))
        assertEquals(ClientMsg.Resize(1440, 2560, 1f), p.expect())
        controller.setViewer(null)
        assertEquals(ClientMsg.Resize(2560, 1440, 1f), p.expect())
        controller.setForced(null)
        assertEquals(ClientMsg.Resize(2200, 1080, 2.5f), p.expect())
    }

    @Test
    fun onlyANewCodedSizeResetsTheDecoder() {
        trusted()
        controller.start()
        val p = peer()
        p.handshake(config())
        awaitState<SessionState.Streaming>()
        p.writer.write(config(fps = 30))
        p.writer.write(config(1280, 720))
        p.writer.write(ServerMsg.VideoFrame(1, 0, true, emptyList(), 1, 0), ByteArray(1))
        decoder.decoded.poll(5, TimeUnit.SECONDS)
        assertEquals(listOf(1920 to 1080, 1280 to 720), decoder.configured.toList())
        val stream = (controller.state.value as SessionState.Streaming).stream
        assertEquals(Triple(1280, 720, 60), Triple(stream.viewWidth, stream.viewHeight, stream.fpsCap))
    }

    @Test
    fun keyframeRequestsAreSpacedOut() {
        var now = 10_000L
        val c = SessionController(machine.id, machines, connector, decoder, scope, Dispatchers.IO, { now })
        trusted()
        c.start()
        val p = peer()
        p.handshake(config())
        repeat(5) { c.requestKeyframe() }
        now += SessionController.KEYFRAME_REQUEST_GAP_MS
        c.requestKeyframe()
        c.close()
        assertEquals(
            listOf(ClientMsg.RequestKeyframe, ClientMsg.RequestKeyframe, ClientMsg.Bye),
            listOf(p.expect(), p.expect(), p.expect()),
        )
    }

    @Test
    fun anIncompatibleServerIsNotWorthRetrying() {
        trusted()
        controller.start()
        val p = peer()
        p.expect()
        p.writer.write(ServerMsg.Error(1, "Client version is too old"))
        val failed = awaitState<SessionState.Failed>()
        assertEquals(SessionError.Incompatible("Client version is too old"), failed.error)
    }

    @Test
    fun aDroppedConnectionCanBeRetried() {
        trusted()
        controller.start()
        val first = peer()
        first.handshake(config())
        awaitState<SessionState.Streaming>()
        first.toClient.close()
        val failed = awaitState<SessionState.Failed>()
        assertTrue(!failed.error.incompatible)

        controller.setWindowSize(800, 600, 1f)
        controller.start()
        val second = peer()
        second.handshake(config())
        // A new server knows nothing of the old one's size.
        assertEquals(ClientMsg.Resize(800, 600, 1f), second.expect())
        second.writer.write(config(800, 600))
        awaitState<SessionState.Streaming>()
    }

    @Test
    fun closingSaysByeAndStaysClosed() {
        trusted()
        controller.start()
        val p = peer()
        p.handshake(config())
        awaitState<SessionState.Streaming>()
        controller.close()
        assertEquals(ClientMsg.Bye, p.expect())
        assertEquals(SessionState.Closed, controller.state.value)
        controller.start()
        Thread.sleep(100)
        assertEquals(SessionState.Closed, controller.state.value)
        assertNull(peers.poll())
    }
}
