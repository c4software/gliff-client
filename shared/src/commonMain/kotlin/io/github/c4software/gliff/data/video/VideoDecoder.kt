package io.github.c4software.gliff.data.video

/**
 * The platform's H.264 decoder, drawing on the session's video surface. One
 * 4:2:0 stream, or two (Dual420: a main stream and an auxiliary one carrying
 * the chroma the main one drops) recombined into 4:4:4. Annex B, parameter
 * sets in front of every keyframe.
 */
interface VideoDecoder {
    /** The largest picture the device decodes; advertised to the server. */
    val maxWidth: Int
    val maxHeight: Int

    /** Whether it takes Dual420; advertised to the server. */
    val fullChroma: Boolean get() = false

    var listener: Listener?

    /** The coded size or the chroma changed: start over, the next frame is a keyframe. */
    fun configure(width: Int, height: Int, dual: Boolean)

    /**
     * Take one access unit, with its auxiliary half in Dual420. Never blocks
     * for long, and always ends in [Listener.onFrameDone], shown or not: the
     * server waits for it.
     */
    fun decode(frameId: Long, data: ByteArray, aux: ByteArray?, keyframe: Boolean)

    fun close()

    /**
     * Hand the next picture decoded, shrunk to a thumbnail, to `onPicture`,
     * on whatever thread decodes. A decoder that cannot never calls it.
     */
    fun snapshot(onPicture: (Thumbnail) -> Unit) {}

    interface Listener {
        /** The frame left the decoder, or was dropped. */
        fun onFrameDone(frameId: Long)

        /** The decoder cannot go on from the frames it has. */
        fun onNeedKeyframe()
    }
}

/** Makes the decoder of a new session; the session closes it. */
fun interface VideoDecoderFactory {
    fun create(): VideoDecoder
}

/** Somewhere else the session's video goes besides the screen: the mirror for web browsers. */
interface FrameSink {
    /** The coded size of the frames that follow. */
    fun configure(width: Int, height: Int)

    /** One Annex B access unit, in decoding order. Must not block. */
    fun frame(data: ByteArray, keyframe: Boolean)
}

/** A small picture of a remote screen: `width x height` pixels, 0xAARRGGBB, row by row. */
class Thumbnail(val width: Int, val height: Int, val argb: IntArray)
