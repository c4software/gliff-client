package io.github.c4software.gliff.data.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * H.264 through `MediaCodec`, into buffers a [VideoRenderer] draws: one codec
 * for a 4:2:0 stream, two for Dual420, whose halves are paired by frame and
 * recombined into 4:4:4 on the GPU. Decoding to buffers rather than to a
 * surface is what gives the renderer the raw planes that recombining needs,
 * and lets it choose how the picture is magnified.
 *
 * The session feeds it from its reader thread whether or not there is a
 * renderer: with none (the app is in the background, the view is not laid
 * out yet) frames are dropped and acked. A renderer that appears starts the
 * codecs and asks for a keyframe, since a decoder can only start on one.
 *
 * A frame's id rides as its presentation timestamp, so the output side knows
 * which frame it got, and acks it once every codec it went to gave it back.
 */
class MediaCodecDecoder : VideoDecoder {
    override val maxWidth: Int
    override val maxHeight: Int
    override val fullChroma: Boolean

    @Volatile
    override var listener: VideoDecoder.Listener? = null

    /** Guards the codecs' lifecycle; the frame path only reads `lanes`. */
    private val lock = Any()
    private var renderer: VideoRenderer? = null
    private var size: Pair<Int, Int>? = null
    private var dual = false

    /** The main codec, then the auxiliary one in Dual420. */
    @Volatile
    private var lanes: List<Lane> = emptyList()

    /** Frames inside the codecs, with how many codecs still owe them. */
    private val pending = ConcurrentHashMap<Long, Int>()

    /** Dual420 halves waiting for their other half, by frame. Under itself. */
    private val halves = HashMap<Long, Pair<Int, YuvImage>>()

    private val free = ConcurrentLinkedQueue<YuvImage>()

    @Volatile
    private var waitingForKeyframe = true

    /** Waiting for the next main picture, to make a thumbnail of it. */
    private val snapshot = AtomicReference<((Thumbnail) -> Unit)?>()

    init {
        // What the device's AVC decoders accept, within what H.264 encoders produce.
        var width = 1920
        var height = 1080
        var instances = 1
        runCatching {
            var first = true
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder || MIME !in info.supportedTypes) continue
                val caps = info.getCapabilitiesForType(MIME)
                val video = caps.videoCapabilities ?: continue
                width = maxOf(width, video.supportedWidths.upper)
                height = maxOf(height, video.supportedHeights.upper)
                // The one createDecoderByType picks: Dual420 runs two of it.
                if (first) instances = caps.maxSupportedInstances
                first = false
            }
        }
        // A phone held upright wants the long side vertical: offer a square.
        val side = minOf(maxOf(width, height), 4096)
        maxWidth = side
        maxHeight = side
        fullChroma = instances >= 2
    }

    /** The view's renderer is ready to draw, or (null) its surface went away. Any thread. */
    fun setRenderer(next: VideoRenderer?) {
        synchronized(lock) {
            if (next === renderer) return
            renderer = next
            restart()
        }
        if (next != null) listener?.onNeedKeyframe()
    }

    override fun configure(width: Int, height: Int, dual: Boolean) {
        synchronized(lock) {
            size = width to height
            this.dual = dual
            // The server opens a new coded size or chroma with a keyframe by itself.
            restart()
        }
    }

    override fun decode(frameId: Long, data: ByteArray, aux: ByteArray?, keyframe: Boolean) {
        val lanes = lanes
        if (lanes.isEmpty() || (waitingForKeyframe && !keyframe)) {
            listener?.onFrameDone(frameId)
            return
        }
        // Without an auxiliary codec the main stream still makes a picture.
        val parts = if (lanes.size == 2 && aux != null) listOf(data, aux) else listOf(data)
        pending[frameId] = parts.size
        var failed = false
        // An auxiliary codec that misses a frame cannot decode the ones after it.
        if (lanes.size == 2 && aux == null && !keyframe) lanes[1].miss()
        parts.forEachIndexed { i, part ->
            if (!lanes[i].queue(frameId, part, keyframe)) {
                failed = true
                done(frameId)
            }
        }
        if (failed) lost() else if (keyframe) waitingForKeyframe = false
    }

    override fun close() {
        synchronized(lock) {
            renderer = null
            size = null
            restart()
        }
    }

    override fun snapshot(onPicture: (Thumbnail) -> Unit) = snapshot.set(onPicture)

    /** One codec gave `frameId` back, or never will: ack it once none owes it. */
    private fun done(frameId: Long) {
        var last = false
        pending.computeIfPresent(frameId) { _, n ->
            last = n <= 1
            if (last) null else n - 1
        }
        if (last) listener?.onFrameDone(frameId)
    }

    /** A codec lost a frame: nothing goes on until a keyframe. Any thread. */
    private fun lost() {
        waitingForKeyframe = true
        listener?.onNeedKeyframe()
    }

    /** Replace a codec that failed, unless it has been replaced already. */
    private fun recover(failed: Lane) {
        synchronized(lock) {
            if (failed in lanes) restart()
        }
        listener?.onNeedKeyframe()
    }

    /** Release the codecs and, when there is something to draw on, make new ones. */
    private fun restart() {
        val old = lanes
        lanes = emptyList()
        old.forEach { it.release() }
        waitingForKeyframe = true
        // Frames the old codecs swallowed will never come out.
        val owed = pending.keys.toList()
        owed.forEach { pending.remove(it) }
        owed.forEach { listener?.onFrameDone(it) }
        synchronized(halves) {
            halves.values.forEach { free.add(it.second) }
            halves.clear()
        }

        val renderer = renderer ?: return
        val (width, height) = size ?: return
        val main = Lane(0, dual, renderer, width, height)
        if (!main.start()) return
        // Two codecs at once is more than some devices give: then the main stream alone.
        val aux = if (dual) Lane(1, true, renderer, width, height).takeIf { it.start() } else null
        if (dual && aux == null) main.paired = false
        lanes = listOfNotNull(main, aux)
    }

    /** A picture came out of lane `index`: show it once whole. Either lane's thread. */
    private fun arrived(index: Int, paired: Boolean, renderer: VideoRenderer, frameId: Long, image: YuvImage) {
        // Before the picture is passed on, which may recycle it any moment.
        if (index == 0) snapshot.getAndSet(null)?.let { onPicture -> image.thumbnail(THUMBNAIL_WIDTH)?.let(onPicture) }
        if (!paired) {
            renderer.show(YuvPicture(image, null, free::add))
            return
        }
        val whole = synchronized(halves) {
            val other = halves.remove(frameId)
            if (other == null || other.first == index) {
                other?.let { free.add(it.second) }
                halves[frameId] = index to image
                return
            }
            // Halves of older frames lost their other half: they will never be shown.
            val stale = halves.keys.filter { it < frameId }
            stale.forEach { id -> halves.remove(id)?.let { free.add(it.second) } }
            if (index == 0) image to other.second else other.second to image
        }
        renderer.show(YuvPicture(whole.first, whole.second, free::add))
    }

    /** An access unit waiting for an input buffer. */
    private class Access(val frameId: Long, val data: ByteArray, val keyframe: Boolean)

    /**
     * One codec, on a thread of its own: in Dual420 the two copy their
     * pictures out at the same time. Frames wait here for the codec to offer
     * an input buffer rather than block the reader. A codec that misses one
     * cannot decode those after it: it then takes nothing until a keyframe,
     * which is what keeps a picture made from missing references (grey,
     * smeared) off the screen.
     */
    private inner class Lane(
        private val lane: Int,
        /** Whether its pictures wait for the other lane's; cleared when that lane could not start. */
        @Volatile var paired: Boolean,
        private val renderer: VideoRenderer,
        private val width: Int,
        private val height: Int,
    ) {
        private val thread = HandlerThread("gliff-decoder-$lane").apply { start() }

        @Volatile
        private var codec: MediaCodec? = null

        /** Input buffers the codec has offered, and frames waiting for one. Under `this`. */
        private val inputs = ArrayDeque<Int>()
        private val waiting = ArrayDeque<Access>()

        /** A frame was lost: only a keyframe is taken. Under `this`. */
        private var broken = false

        /** Held while a picture is copied out, so the codec is not stopped under it. */
        private val output = Any()

        fun start(): Boolean = try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                // A keyframe of a busy screen is far above the default guess.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height * 3 / 2, 1 shl 20))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val created = MediaCodec.createDecoderByType(MIME)
            created.setCallback(Callback(), Handler(thread.looper))
            // No csd: SPS and PPS arrive in front of every keyframe.
            created.configure(format, null, null, 0)
            codec = created
            created.start()
            Log.i(TAG, "decoder ${created.name} at ${width}x$height${if (lane == 1) ", auxiliary" else ""}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "cannot start a decoder at ${width}x$height", e)
            release()
            false
        }

        fun release() {
            val old = synchronized(output) { codec.also { codec = null } }
            old?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            val dropped = synchronized(this) {
                inputs.clear()
                waiting.toList().also { waiting.clear() }
            }
            dropped.forEach { done(it.frameId) }
            thread.quitSafely()
        }

        /** A frame went past it: only a keyframe is taken now. */
        fun miss() {
            synchronized(this) { broken = true }
        }

        /** Hand `data` to the codec, now or once it has room; false when it was dropped. */
        fun queue(frameId: Long, data: ByteArray, keyframe: Boolean): Boolean {
            val dropped = ArrayList<Long>()
            var stuck = false
            val taken = synchronized(this) {
                when {
                    codec == null -> false
                    keyframe -> {
                        // What still waits is older than a picture that does without it.
                        waiting.forEach { dropped.add(it.frameId) }
                        waiting.clear()
                        broken = false
                        waiting.add(Access(frameId, data, true))
                        true
                    }
                    broken -> false
                    waiting.size >= MAX_WAITING -> {
                        // Even a keyframe could not get in: the codec is stuck, not just busy.
                        stuck = waiting.first().keyframe
                        waiting.forEach { dropped.add(it.frameId) }
                        waiting.clear()
                        broken = true
                        Log.w(TAG, "dropping frames up to $frameId: the decoder is ${if (stuck) "stuck" else "behind"}")
                        false
                    }
                    else -> {
                        waiting.add(Access(frameId, data, false))
                        true
                    }
                }
            }
            dropped.forEach(::done)
            if (stuck) recover(this) else if (taken) feed()
            return taken
        }

        /** Put waiting frames in the buffers the codec offered, in order. */
        private fun feed() {
            val dropped = ArrayList<Long>()
            var failed = false
            synchronized(this) {
                val codec = codec ?: return
                while (inputs.isNotEmpty() && waiting.isNotEmpty() && !failed) {
                    val unit = waiting.removeFirst()
                    val index = inputs.removeFirst()
                    try {
                        val buffer = codec.getInputBuffer(index)
                        if (buffer == null || buffer.capacity() < unit.data.size) {
                            Log.w(TAG, "dropping frame ${unit.frameId}: too large")
                            inputs.addFirst(index)
                            dropped.add(unit.frameId)
                            waiting.forEach { dropped.add(it.frameId) }
                            waiting.clear()
                            broken = true
                            break
                        }
                        buffer.clear()
                        buffer.put(unit.data)
                        val flags = if (unit.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        codec.queueInputBuffer(index, 0, unit.data.size, unit.frameId, flags)
                    } catch (e: IllegalStateException) {
                        // Also MediaCodec.CodecException; or the codec was released under us.
                        Log.w(TAG, "decode failed", e)
                        dropped.add(unit.frameId)
                        failed = true
                    }
                }
            }
            dropped.forEach(::done)
            if (failed) {
                recover(this)
            } else if (dropped.isNotEmpty()) {
                lost()
            }
        }

        private inner class Callback : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                synchronized(this@Lane) {
                    if (this@Lane.codec !== codec) return
                    inputs.add(index)
                }
                feed()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val frameId = info.presentationTimeUs
                var picture: YuvImage? = null
                synchronized(output) {
                    if (this@Lane.codec !== codec) return@synchronized
                    try {
                        if (info.size > 0) {
                            codec.getOutputImage(index)?.use { image ->
                                val w = minOf(width, image.width) and 1.inv()
                                val h = minOf(height, image.height) and 1.inv()
                                picture = (free.poll() ?: YuvImage()).apply { copy(image, w, h) }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: IllegalStateException) {
                        picture?.let(free::add)
                        picture = null
                    }
                }
                picture?.let { arrived(lane, paired, renderer, frameId, it) }
                done(frameId)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(TAG, "decoder error", e)
                recover(this@Lane)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }
    }

    private companion object {
        const val TAG = "gliff"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /** About a fifth of a second at 60 fps: past that the codec is behind for good. */
        const val MAX_WAITING = 12

        /** Wide enough for a tile of the machines' list on a large screen. */
        const val THUMBNAIL_WIDTH = 480
    }
}
