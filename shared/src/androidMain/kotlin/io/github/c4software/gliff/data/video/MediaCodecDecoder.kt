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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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

    private val thread = HandlerThread("gliff-decoder").apply { start() }

    /** Every codec calls back on it, so pairing needs no lock. */
    private val handler = Handler(thread.looper)

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

    /** Dual420 halves waiting for their other half, by frame. The handler thread only. */
    private val halves = HashMap<Long, Pair<Int, YuvImage>>()

    private val free = ConcurrentLinkedQueue<YuvImage>()

    @Volatile
    private var waitingForKeyframe = true

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
        parts.forEachIndexed { i, part ->
            if (!lanes[i].queue(frameId, part, keyframe)) {
                failed = true
                done(frameId)
            }
        }
        if (failed) {
            waitingForKeyframe = true
            listener?.onNeedKeyframe()
        } else {
            waitingForKeyframe = false
        }
    }

    override fun close() {
        synchronized(lock) {
            renderer = null
            size = null
            restart()
        }
        thread.quitSafely()
    }

    /** One codec gave `frameId` back, or never will: ack it once none owes it. */
    private fun done(frameId: Long) {
        var last = false
        pending.computeIfPresent(frameId) { _, n ->
            last = n <= 1
            if (last) null else n - 1
        }
        if (last) listener?.onFrameDone(frameId)
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
        handler.post {
            halves.values.forEach { free.add(it.second) }
            halves.clear()
        }

        val renderer = renderer ?: return
        val (width, height) = size ?: return
        val main = Lane(0, renderer, width, height)
        if (!main.start()) return
        // Two codecs at once is more than some devices give: then the main stream alone.
        val aux = if (dual) Lane(1, renderer, width, height).takeIf { it.start() } else null
        lanes = listOfNotNull(main, aux)
    }

    /** A picture came out of lane `index`: show it once whole. The handler thread. */
    private fun arrived(index: Int, renderer: VideoRenderer, frameId: Long, image: YuvImage) {
        if (lanes.size < 2) {
            renderer.show(YuvPicture(image, null, free::add))
            return
        }
        val other = halves.remove(frameId)
        if (other == null || other.first == index) {
            other?.let { free.add(it.second) }
            halves[frameId] = index to image
            return
        }
        val (main, aux) = if (index == 0) image to other.second else other.second to image
        renderer.show(YuvPicture(main, aux, free::add))
        // Halves of older frames lost their other half: they will never be shown.
        val stale = halves.keys.filter { it < frameId }
        stale.forEach { id -> halves.remove(id)?.let { free.add(it.second) } }
    }

    private inner class Lane(
        private val lane: Int,
        private val renderer: VideoRenderer,
        private val width: Int,
        private val height: Int,
    ) {
        @Volatile
        private var codec: MediaCodec? = null

        /** Input buffers the codec has offered. */
        private val inputs = LinkedBlockingQueue<Int>()

        fun start(): Boolean = try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                // A keyframe of a busy screen is far above the default guess.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height * 3 / 2, 1 shl 20))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val created = MediaCodec.createDecoderByType(MIME)
            created.setCallback(Callback(), handler)
            // No csd: SPS and PPS arrive in front of every keyframe.
            created.configure(format, null, null, 0)
            created.start()
            codec = created
            Log.i(TAG, "decoder ${created.name} at ${width}x$height${if (lane == 1) ", auxiliary" else ""}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "cannot start a decoder at ${width}x$height", e)
            release()
            false
        }

        fun release() {
            codec?.let { old ->
                codec = null
                runCatching { old.stop() }
                runCatching { old.release() }
            }
            inputs.clear()
        }

        /** Hand `data` to the codec; false when it could not take it. */
        fun queue(frameId: Long, data: ByteArray, keyframe: Boolean): Boolean {
            val codec = codec ?: return false
            return try {
                // No free input buffer for this long means the codec is stuck.
                val index = inputs.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS)
                val buffer = index?.let { codec.getInputBuffer(it) }
                if (index == null || buffer == null || buffer.capacity() < data.size) {
                    Log.w(TAG, "dropping frame $frameId: ${if (index == null) "no input buffer" else "too large"}")
                    index?.let { inputs.add(it) }
                    return false
                }
                buffer.clear()
                buffer.put(data)
                codec.queueInputBuffer(index, 0, data.size, frameId, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                true
            } catch (e: IllegalStateException) {
                // Also MediaCodec.CodecException; or the codec was released under us.
                Log.w(TAG, "decode failed", e)
                recover(this)
                false
            }
        }

        private inner class Callback : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (this@Lane.codec === codec) inputs.add(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val frameId = info.presentationTimeUs
                var picture: YuvImage? = null
                try {
                    if (this@Lane.codec === codec && info.size > 0) {
                        codec.getOutputImage(index)?.use { image ->
                            val w = minOf(width, image.width) and 1.inv()
                            val h = minOf(height, image.height) and 1.inv()
                            picture = (free.poll() ?: YuvImage()).apply { copy(image, w, h) }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                }
                picture?.let { arrived(lane, renderer, frameId, it) }
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
        const val INPUT_WAIT_MS = 400L
    }
}
