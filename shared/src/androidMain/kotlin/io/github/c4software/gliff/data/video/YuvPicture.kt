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
