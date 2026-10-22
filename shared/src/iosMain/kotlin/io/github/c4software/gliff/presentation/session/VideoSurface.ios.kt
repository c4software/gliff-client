package io.github.c4software.gliff.presentation.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.github.c4software.gliff.data.video.VideoDecoder
import io.github.c4software.gliff.data.video.VideoToolboxDecoder
import io.github.c4software.gliff.domain.session.Box
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/**
 * A view carrying the decoder's display layer. The layer stretches each
 * picture to its bounds, so the layout alone decides the picture's size, and
 * it keeps showing the last picture while the decoder starts over. Touches go
 * through it to the screen drawn above.
 */
@OptIn(ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun VideoSurface(decoder: VideoDecoder, onReady: () -> Unit, picture: Box?, modifier: Modifier) {
    val layer = (decoder as? VideoToolboxDecoder)?.layer
    val ready = rememberUpdatedState(onReady)
    UIKitView(
        factory = { LayerView(layer) },
        modifier = modifier.placedAt(picture),
        properties = UIKitInteropProperties(interactionMode = null),
    )
    // The layer is there from the start: what is missing is a keyframe to draw on it.
    LaunchedEffect(layer) { ready.value() }
}

/** Keeps `video` as large as itself. */
@OptIn(ExperimentalForeignApi::class)
private class LayerView(private val video: CALayer?) : UIView(frame = CGRectZero.readValue()) {
    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        // A layer has one parent: this takes it from the view of a screen that was replaced.
        video?.let { layer.addSublayer(it) }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // Followed at once: an animated resize would lag behind the layout.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        video?.frame = bounds
        CATransaction.commit()
    }
}
