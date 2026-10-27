package io.github.c4software.gliff.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isCaptionBarVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.c4software.gliff.data.clipboard.AndroidClipboard
import io.github.c4software.gliff.presentation.machines.MachinesRoute
import io.github.c4software.gliff.presentation.session.SessionRoute
import io.github.c4software.gliff.presentation.session.SessionTabs
import io.github.c4software.gliff.presentation.session.SessionTabsViewModel
import io.github.c4software.gliff.presentation.session.SessionViewModel
import io.github.c4software.gliff.presentation.theme.GliffTheme
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

/**
 * The remote screens, one tab per machine, over the list in the same task:
 * on a desktop, one window. Its + shows the list in the window again. The manifest keeps this activity alive across size, rotation and
 * display changes; the sessions themselves belong to the application, so even
 * a recreated activity finds them running.
 */
class SessionActivity : ComponentActivity(), KoinComponent {
    private val clipboard: AndroidClipboard by inject()

    /** Kept with the activity across its recreations; cleared, ending every session, when it finishes. */
    private val tabs: SessionTabsViewModel by viewModels {
        viewModelFactory { initializer { get<SessionTabsViewModel>() } }
    }

    /** The session of the tab on screen, null between two. */
    private var shown: SessionViewModel? = null

    /** The window's position is ours to choose: a phone's whole screen, not a window among others. */
    private var turns by mutableStateOf(false)
    private var landscape = false

    /**
     * The screen is blacked out, a browser showing the session in its place.
     * `touches` counts the fingers put down, each one lighting it for a while.
     */
    private var dark by mutableStateOf(false)
    private var touches by mutableIntStateOf(0)
    private var waking = false

    /** No mouse and keyboard together: the screen keeps its floating buttons. */
    private var touchControls by mutableStateOf(true)
    private val inputDevices = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateTouchControls()
        override fun onInputDeviceRemoved(deviceId: Int) = updateTouchControls()
        override fun onInputDeviceChanged(deviceId: Int) = updateTouchControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        blendHeaderBar()
        // Recreated, the tabs are still there: the intent was opened already.
        if (tabs.isEmpty) open(intent)
        if (tabs.isEmpty) {
            finish()
            return
        }
        applyImmersion()
        applyOrientation()
        setContent {
            SessionTabs(
                tabs,
                onEmpty = ::finish,
                machines = { onOpen ->
                    // Back gives the tab its screen again.
                    BackHandler { tabs.showList(false) }
                    GliffTheme {
                        MachinesRoute(onOpen, desktop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
                    }
                },
            ) { viewModel, onClose, tabBar ->
                DisposableEffect(viewModel) {
                    shown = viewModel
                    viewModel.onShown()
                    title = viewModel.uiState.value.address.ifEmpty { null } ?: title
                    // This session has not been told what the clipboard holds.
                    clipboardSeen = -1L
                    offerClipboard()
                    onDispose { if (shown === viewModel) shown = null }
                }
                Tab(viewModel, onClose, tabBar)
            }
        }
    }

    /** One tab's session on screen; `onClose` closes the tab, ending its session. */
    @Composable
    private fun Tab(viewModel: SessionViewModel, onClose: () -> Unit, tabBar: @Composable () -> Unit) {
        // Back leaves this session alone; the last one leaving closes the window.
        BackHandler(onBack = onClose)
        val ui by viewModel.uiState.collectAsState()
        LaunchedEffect(ui.keepAwake) {
            val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (ui.keepAwake) window.addFlags(flag) else window.clearFlags(flag)
        }
        val darkens = ui.darkens
        LaunchedEffect(darkens, touches) {
            dark = false
            if (darkens) {
                delay(DARK_AFTER_MS)
                dark = true
            }
        }
        LaunchedEffect(dark) {
            // As dim as the panel goes; on an OLED a black picture then lights nothing.
            window.attributes = window.attributes.apply {
                screenBrightness = if (dark) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
        GliffTheme {
            SessionRoute(
                viewModel,
                onBack = onClose,
                turns = turns,
                onLandscape = {
                    landscape = it
                    applyOrientation()
                },
                touchControls = touchControls,
                tabs = tabBar,
            )
            HeaderTitle(ui.address)
            if (dark) Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    /** A machine opened from the list or a shortcut: its tab comes forward, made if need be. */
    private fun open(intent: Intent) {
        intent.data?.lastPathSegment?.let(tabs::open)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // The touch that lights the screen was not aimed at anything on it.
            waking = dark
            touches++
        }
        return if (waking) true else super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // The web mirror shows the session whose window was used last.
        shown?.onShown()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyImmersion()
        applyOrientation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            leaveDesktopWindow()
            applyImmersion()
            offerClipboard()
        }
    }

    /** Asked once, when the window first shows: after that the user decides, through its header. */
    private var fullScreenAsked = false

    /**
     * Opened in a desktop window with the option on, the session asks for the
     * whole display; its bars then hide as on a phone. The header gives the
     * window back.
     */
    private fun leaveDesktopWindow() {
        if (fullScreenAsked || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        fullScreenAsked = true
        if (shown?.uiState?.value?.desktopFullScreen == true && inDesktopWindow) {
            requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, null)
        }
    }

    /** Timestamp of the clipboard content the session was last told of. */
    private var clipboardSeen = -1L
    private val clipboardChanged = ClipboardManager.OnPrimaryClipChangedListener { offerClipboard() }

    /**
     * Tell the session what the clipboard holds, when it changed. Only the
     * focused window may read it, hence here and not in the background.
     */
    private fun offerClipboard() {
        val viewModel = shown ?: return
        if (!hasWindowFocus() || !viewModel.sharesClipboard()) return
        val content = clipboard.read(clipboardSeen) ?: return
        clipboardSeen = content.timestamp
        viewModel.localClipboard(content.text)
    }

    override fun onStart() {
        super.onStart()
        clipboard.onChange(clipboardChanged)
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(inputDevices, null)
        updateTouchControls()
    }

    override fun onStop() {
        clipboard.removeOnChange(clipboardChanged)
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputDevices)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A keyboard plugged in or out is a configuration change, kept by configChanges.
        updateTouchControls()
    }

    private fun updateTouchControls() {
        touchControls = !hasMouseAndKeyboard()
    }

    /**
     * A phone holds the remote screen upright until its button lays it down,
     * and stands it back up for the keyboard, which would cover most of a
     * lying screen. A free-form or split window, and a tablet or a desktop
     * display, keep the shape the user gave them.
     */
    private fun applyOrientation() {
        turns = !isInMultiWindowMode &&
            resources.configuration.smallestScreenWidthDp < LARGE_SCREEN_DP
        requestedOrientation = when {
            !turns -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * Full screen hides the system bars; a free-form or split window keeps
     * them, and its caption bar, like any desktop window.
     */
    private fun applyImmersion() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isInMultiWindowMode) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** From this width up Android itself stops honouring orientation requests. */
        private const val LARGE_SCREEN_DP = 600

        /** How long the screen stays lit after a touch while a browser watches. */
        private const val DARK_AFTER_MS = 5000L

        /** Into the sessions' window: opening a machine again brings its tab back. */
        fun intent(context: Context, machineId: String): Intent =
            Intent(context, SessionActivity::class.java)
                .setData(Uri.Builder().scheme("gliff").authority("session").appendPath(machineId).build())
    }
}

/** The machine's name in the header bar of a desktop window, which shows through over the app's black. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderTitle(title: String) {
    if (!WindowInsets.isCaptionBarVisible || title.isEmpty()) return
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.captionBar), contentAlignment = Alignment.Center) {
        Text(
            title,
            // Clear of the app's chip on the left and the window's buttons on the right.
            Modifier.padding(horizontal = 160.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
