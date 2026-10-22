package io.github.c4software.gliff.presentation.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.c4software.gliff.data.video.VideoDecoder
import io.github.c4software.gliff.domain.session.Box

/** No decoder on the desktop target, which only renders the screens in tests. */
@Composable
actual fun VideoSurface(decoder: VideoDecoder, onReady: () -> Unit, picture: Box?, modifier: Modifier) {
    Box(modifier.placedAt(picture).background(Color(0xFF20242C)))
}
