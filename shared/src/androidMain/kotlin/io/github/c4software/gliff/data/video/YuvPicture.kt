package io.github.c4software.gliff.data.video

import android.media.Image
import java.nio.ByteBuffer

/**
 * One plane of a decoded picture, copied out of the codec's buffer so the
 * codec gets it back at once. Rows keep the codec's stride; the buffer is
 * padded to whole rows, which is what the GPU reads.
 */
class YuvPlane {
    var data: ByteBuffer = ByteBuffer.allocateDirect(0)
        private set
    var rowStride = 0
        private set

    /** 1 for a plane of its own, 2 for one interleaved with the other chroma plane. */
    var pixelStride = 1
        private set

    fun copy(from: Image.Plane, rows: Int) {
        val need = from.rowStride * rows
        if (data.capacity() < need) data = ByteBuffer.allocateDirect(need)
        val src = from.buffer.duplicate()
        src.limit(minOf(src.limit(), src.position() + need))
        data.clear()
        data.put(src)
        data.clear()
        rowStride = from.rowStride
        pixelStride = from.pixelStride
    }
}

/** One decoded 4:2:0 picture: luma `width x height`, chroma half that each way. */
class YuvImage {
    var width = 0
        private set
    var height = 0
        private set
    val y = YuvPlane()
    val u = YuvPlane()
    val v = YuvPlane()

    /**
     * This picture shrunk to at most `maxWidth` pixels across, one sample per
     * thumbnail pixel: BT.709, limited range, as the renderer converts it.
     */
    fun thumbnail(maxWidth: Int): Thumbnail? {
        if (width <= 0 || height <= 0) return null
        val step = maxOf(1, (width + maxWidth - 1) / maxWidth)
        val w = width / step
        val h = height / step
        val argb = IntArray(w * h)
        for (row in 0 until h) {
            val sy = row * step
            for (col in 0 until w) {
                val sx = col * step
                val l = (y.data.get(sy * y.rowStride + sx).toInt() and 0xff) - 16
                val c = (sy / 2) * u.rowStride + (sx / 2) * u.pixelStride
                val cb = (u.data.get(c).toInt() and 0xff) - 128
                val cr = (v.data.get((sy / 2) * v.rowStride + (sx / 2) * v.pixelStride).toInt() and 0xff) - 128
                val r = (1.1644f * l + 1.7927f * cr).toInt().coerceIn(0, 255)
                val g = (1.1644f * l - 0.2132f * cb - 0.5329f * cr).toInt().coerceIn(0, 255)
                val b = (1.1644f * l + 2.1124f * cb).toInt().coerceIn(0, 255)
                argb[row * w + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Thumbnail(w, h, argb)
    }

    fun copy(image: Image, width: Int, height: Int) {
        this.width = width
        this.height = height
        val planes = image.planes
        y.copy(planes[0], height)
        u.copy(planes[1], height / 2)
        v.copy(planes[2], height / 2)
    }
}

/**
 * What one frame shows: the main picture, and in Dual420 the auxiliary one
 * holding the chroma the main one dropped. Whoever holds it last recycles it.
 */
class YuvPicture(val main: YuvImage, val aux: YuvImage?, private val recycler: (YuvImage) -> Unit) {
    fun recycle() {
        recycler(main)
        aux?.let(recycler)
    }
}
