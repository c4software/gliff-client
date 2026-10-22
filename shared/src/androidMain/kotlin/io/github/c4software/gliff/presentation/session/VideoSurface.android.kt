package io.github.c4software.gliff.presentation.session

import android.opengl.GLSurfaceView
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.c4software.gliff.data.video.MediaCodecDecoder
import io.github.c4software.gliff.data.video.VideoDecoder
import io.github.c4software.gliff.data.video.VideoRenderer
import io.github.c4software.gliff.domain.session.Box

/**
 * A `GLSurfaceView` over the whole video area, its [VideoRenderer] drawing
 * the picture where `picture` says: magnifying is a matter for the renderer,
 * so the surface never grows past the screen. It keeps showing the last
 * frame while the codecs are being replaced.
 */
@Composable
actual fun VideoSurface(decoder: VideoDecoder, onReady: () -> Unit, picture: Box?, modifier: Modifier) {
    val ready = rememberUpdatedState(onReady)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                val renderer = VideoRenderer(this)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                preserveEGLContextOnPause = true
                tag = renderer
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        (decoder as? MediaCodecDecoder)?.setRenderer(renderer)
                        ready.value()
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        (decoder as? MediaCodecDecoder)?.setRenderer(null)
                    }
                })
            }
        },
        update = { (it.tag as VideoRenderer).place(picture) },
    )
}
