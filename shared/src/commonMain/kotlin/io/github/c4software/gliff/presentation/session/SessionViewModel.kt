package io.github.c4software.gliff.presentation.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.c4software.gliff.data.mirror.WebMirror
import io.github.c4software.gliff.data.session.Sessions
import io.github.c4software.gliff.data.video.VideoDecoder
import io.github.c4software.gliff.domain.input.KeyLayout
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.domain.session.areaScale
import io.github.c4software.gliff.domain.settings.Settings
import io.github.c4software.gliff.domain.settings.SettingsRepository
import io.github.c4software.gliff.presentation.UiStateSharing
import io.github.c4software.gliff.proto.ClientMsg
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class SessionUiState(
    val state: SessionState,
    val address: String,
    /** A tap clicks where it lands; off, the screen starts as a trackpad. */
    val directTouch: Boolean,
    val keepAwake: Boolean,
    /** Opened from a desktop window, the session is to fill the display. */
    val desktopFullScreen: Boolean = false,
    /** A browser watches the web mirror, and this screen is to go dark meanwhile. */
    val darkens: Boolean,
)

/**
 * One machine's session, as its window shows it. The session itself belongs
 * to [Sessions] and survives this: it ends when the window is gone for good,
 * which is when this is cleared, or when the user leaves it.
 */
class SessionViewModel(
    private val machineId: String,
    private val sessions: Sessions,
    private val settings: SettingsRepository,
    mirror: WebMirror?,
) : ViewModel() {
    private val controller = sessions.session(machineId)
    private var ended = false

    /** The session's state alone, for what the platform does around the window. */
    val sessionState: StateFlow<SessionState> get() = controller.state

    val decoder: VideoDecoder get() = controller.decoder

    val uiState: StateFlow<SessionUiState> =
        combine(controller.state, settings.settings, mirror?.watched ?: flowOf(false), ::uiState)
            .stateIn(viewModelScope, UiStateSharing, uiState(controller.state.value, settings.settings.value, false))

    private fun uiState(state: SessionState, options: Settings, watched: Boolean) = SessionUiState(
        state = state,
        address = controller.machine?.address.orEmpty(),
        directTouch = options.directTouch,
        keepAwake = options.keepAwake,
        desktopFullScreen = options.desktopFullScreen,
        darkens = options.httpMirror && options.mirrorDark && watched,
    )

    /** The window is the one in front: the web mirror shows it. */
    fun onShown() = sessions.front(machineId)

    /** The video area is `width x height` pixels at `density`, shrunk by `zoom` under the keyboard. */
    fun onArea(width: Int, height: Int, density: Float, zoom: Float) {
        val options = settings.settings.value
        val forced = options.forcedFor(controller.machine) != null
        areaScale(density, zoom, options.keyboardZoom, forced)?.let { controller.setWindowSize(width, height, it) }
    }

    /** The picture is pinched `zoom` times larger, and has been still for a moment. */
    fun onMagnified(zoom: Float) = controller.setMagnified(zoom)

    fun acceptHostKey() = controller.acceptHostKey()

    fun retry() = controller.start()

    fun send(msg: ClientMsg) = controller.send(msg)

    fun layout(): KeyLayout = controller.layout

    fun requestKeyframe() = controller.requestKeyframe()

    /** Whether this device's clipboard is to be read for the remote at all. */
    fun sharesClipboard(): Boolean = settings.settings.value.clipboard

    /** This device's clipboard holds `text` (null: no text). */
    fun localClipboard(text: String?) = controller.localClipboard(text)

    /** The user left the session: it ends now, not when the window goes. */
    fun end() {
        if (ended) return
        ended = true
        sessions.endSession(machineId)
    }

    override fun onCleared() = end()
}
