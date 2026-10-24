package io.github.c4software.gliff.presentation.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.c4software.gliff.data.video.VideoDecoder
import io.github.c4software.gliff.domain.input.KeyLayout
import io.github.c4software.gliff.domain.input.Keyboard
import io.github.c4software.gliff.domain.input.Keys
import io.github.c4software.gliff.domain.input.Trackpad
import io.github.c4software.gliff.domain.session.Box
import io.github.c4software.gliff.domain.session.Magnifier
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.domain.session.StreamInfo
import io.github.c4software.gliff.domain.session.fit
import io.github.c4software.gliff.domain.session.keyboardZoom
import io.github.c4software.gliff.presentation.theme.Strings
import io.github.c4software.gliff.presentation.theme.failure
import io.github.c4software.gliff.presentation.theme.rememberStrings
import io.github.c4software.gliff.proto.ClientMsg
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Where the platform draws the decoded video. It tells `decoder` when its
 * surface comes and goes, and calls `onReady` each time a surface is there
 * to draw on. `modifier` fills the video area; `picture` is where in it the
 * picture goes, magnified or not (null: nothing to show yet).
 */
@Composable
expect fun VideoSurface(decoder: VideoDecoder, onReady: () -> Unit, picture: Box?, modifier: Modifier)

/** Lay this out as `picture`, which may stick out of the area. Null fills it. */
fun Modifier.placedAt(picture: Box?): Modifier = layout { measurable, constraints ->
    val box = picture ?: Box(0, 0, constraints.maxWidth, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(maxOf(box.width, 0), maxOf(box.height, 0)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(box.x, box.y) }
}

/** A mouse pointer that shows nothing, where the platform has one. */
@Composable
expect fun hiddenPointerIcon(): PointerIcon?

/** Where what the on-screen keyboard (or a plugged one) types goes. */
interface KeySink {
    fun text(text: String)

    /** One press and release of an evdev key. */
    fun key(code: Int)

    /** A physical key going down or up. */
    fun raw(code: Int, pressed: Boolean)
}

/**
 * The platform's on-screen keyboard: up while `shown`, typing into `sink`.
 * It draws nothing itself.
 */
@Composable
expect fun SoftKeyboard(shown: Boolean, sink: KeySink)

/**
 * A machine's remote screen, with whatever stands between the user and it.
 * `turns`: the host can lay the window on its side, which a button then
 * offers; it does so while `onLandscape` last said true.
 * `touchControls`: the floating buttons, for a finger; a host with a mouse
 * and a keyboard leaves them out.
 * `onClose`: where nothing of the system closes a window (no back gesture, no
 * title bar), a button of the screen does.
 */
@Composable
fun SessionRoute(
    viewModel: SessionViewModel,
    onBack: () -> Unit,
    onClose: (() -> Unit)? = null,
    turns: Boolean = false,
    onLandscape: (Boolean) -> Unit = {},
    touchControls: Boolean = true,
    strings: Strings = rememberStrings(),
) {
    val ui by viewModel.uiState.collectAsState()
    val density = LocalDensity.current.density
    SessionContent(
        state = ui.state,
        address = ui.address,
        strings = strings,
        onSize = { w, h, zoom -> viewModel.onArea(w, h, density, zoom) },
        onMagnified = viewModel::onMagnified,
        onAcceptHostKey = viewModel::acceptHostKey,
        onRetry = viewModel::retry,
        onBack = onBack,
        onClose = onClose,
        turns = turns,
        onLandscape = onLandscape,
        touchControls = touchControls,
        directTouch = ui.directTouch,
        layout = viewModel::layout,
        send = viewModel::send,
        keyboard = { shown, sink -> SoftKeyboard(shown, sink) },
    ) { modifier, picture ->
        VideoSurface(viewModel.decoder, viewModel::requestKeyframe, picture, modifier)
    }
}

@Composable
fun SessionContent(
    state: SessionState,
    address: String,
    strings: Strings,
    /**
     * The video area in pixels. `zoom` (at most 1) shrinks the remote's
     * display scale while the keyboard takes part of the window, so that it
     * keeps showing as much as the whole window did.
     */
    onSize: (width: Int, height: Int, zoom: Float) -> Unit,
    /** How many times larger the picture is pinched, once the fingers have been still for a moment. */
    onMagnified: (zoom: Float) -> Unit = {},
    onAcceptHostKey: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    turns: Boolean = false,
    onLandscape: (Boolean) -> Unit = {},
    touchControls: Boolean = true,
    directTouch: Boolean = true,
    layout: () -> KeyLayout = { KeyLayout.US },
    send: (ClientMsg) -> Unit = {},
    keyboard: @Composable (shown: Boolean, sink: KeySink) -> Unit = { _, _ -> },
    keyboardOpen: Boolean = false,
    onClose: (() -> Unit)? = null,
    /** Draws the video: `fill` takes the whole area, `picture` is where the picture goes in it. */
    video: @Composable (fill: Modifier, picture: Box?) -> Unit,
) {
    // The picture keeps its last geometry while the session is elsewhere
    // (reconnecting, resizing), so the last frame stays where it was.
    var stream by remember { mutableStateOf<StreamInfo?>(null) }
    if (state is SessionState.Streaming) stream = state.stream
    val live = state is SessionState.Streaming

    val sender = rememberUpdatedState(send)
    val slop = LocalViewConfiguration.current.touchSlop
    val trackpad = remember(slop) { Trackpad(slop) { sender.value(it) } }
    val layoutNow = rememberUpdatedState(layout)
    val keys = remember { Keyboard({ layoutNow.value() }) { sender.value(it) } }
    var pointer by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // A tap clicks where it lands; off, the screen is a trackpad instead.
    var direct by rememberSaveable(directTouch) { mutableStateOf(directTouch) }
    trackpad.direct = direct
    // The video area with the keyboard away: what the remote is sized for.
    var whole by remember { mutableStateOf(IntSize.Zero) }
    var typing by remember { mutableStateOf(keyboardOpen) }
    // Ctrl, Alt, Super: armed by a tap, spent on the next key.
    var held by remember { mutableStateOf(emptySet<Int>()) }
    val sink = remember {
        object : KeySink {
            override fun text(text: String) {
                keys.type(text, held)
                held = emptySet()
            }

            override fun key(code: Int) {
                keys.tap(code, held)
                held = emptySet()
            }

            override fun raw(code: Int, pressed: Boolean) = keys.raw(code, pressed)
        }
    }

    // Pinched larger on this screen only; the remote keeps its size.
    var magnifier by remember { mutableStateOf(Magnifier()) }
    val fitted = stream?.let { fit(it.viewWidth, it.viewHeight, area.width, area.height) }
    val picture = fitted?.let { magnifier.place(it, area.width, area.height) }
    val placed = rememberUpdatedState(fitted to area)
    val magnifiedNow = rememberUpdatedState(onMagnified)
    LaunchedEffect(magnifier.zoom) {
        delay(MAGNIFIED_SETTLE_MS)
        magnifiedNow.value(magnifier.zoom)
    }
    trackpad.magnified = magnifier.zoom > 1f
    trackpad.onMagnify = { factor, dx, dy, cx, cy ->
        val (box, size) = placed.value
        if (box != null) magnifier = magnifier.moved(box, size.width, size.height, factor, dx, dy, cx, cy)
    }
    stream?.let { s ->
        if (picture != null) {
            val scale = maxOf(s.scaleMilli, 10) / 1000.0
            trackpad.layout(picture.x, picture.y, picture.width, picture.height, s.width / scale, s.height / scale)
        }
    }

    // The keyboard can be sent away by the system (the back gesture): follow it.
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) {
        if (imeUp) imeSeen = true else if (imeSeen) {
            imeSeen = false
            typing = false
        }
    }
    if (!live && typing) typing = false
    // Upright unless asked otherwise, and always for the keyboard, which
    // would cover most of a screen lying down.
    var lyingDown by rememberSaveable { mutableStateOf(false) }
    val landscape = turns && lyingDown && !typing
    val landscapeChanged = rememberUpdatedState(onLandscape)
    LaunchedEffect(landscape) { landscapeChanged.value(landscape) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Clear of the system bars, the cutout, a desktop window's caption
            // bar and the on-screen keyboard: the picture sits above it.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // A magnified picture is larger than its area.
                    .clipToBounds()
                    .onSizeChanged {
                        area = it
                        // Mid-turn: this is not the shape the window is heading for.
                        if (turns && landscape != (it.width > it.height)) return@onSizeChanged
                        if (!typing && !imeUp) whole = it
                        onSize(it.width, it.height, keyboardZoom(it.width, it.height, whole.width, whole.height))
                    },
            ) {
                video(Modifier.matchParentSize(), picture)
                if (live) {
                    val haptics = LocalHapticFeedback.current
                    // The remote draws its own pointer on a private screen: ours would be a second one.
                    val hidden = if (stream?.headless == true) hiddenPointerIcon() else null
                    Box(
                        Modifier.matchParentSize().pointerInput(trackpad) {
                            awaitPointerEventScope {
                                var landed = TimeSource.Monotonic.markNow()
                                while (true) {
                                    val left = Trackpad.LONG_PRESS_MS - landed.elapsedNow().inWholeMilliseconds
                                    val event = if (trackpad.awaitingLongPress) {
                                        withTimeoutOrNull(maxOf(left, 1)) { awaitPointerEvent() }
                                    } else {
                                        awaitPointerEvent()
                                    }
                                    if (event == null) {
                                        if (trackpad.longPress()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        continue
                                    }
                                    for (change in event.changes) {
                                        if (change.type == PointerType.Mouse) {
                                            trackpad.mouseMove(change.position.x, change.position.y)
                                            if (event.type == PointerEventType.Scroll) {
                                                trackpad.mouseScroll(change.scrollDelta.x, change.scrollDelta.y)
                                            }
                                            val buttons = event.buttons
                                            trackpad.mouseButtons(
                                                buttons.isPrimaryPressed, buttons.isSecondaryPressed, buttons.isTertiaryPressed,
                                            )
                                            change.consume()
                                            continue
                                        }
                                        val id = change.id.value
                                        when {
                                            change.changedToDown() -> {
                                                if (!trackpad.awaitingLongPress) landed = TimeSource.Monotonic.markNow()
                                                trackpad.down(id, change.position.x, change.position.y, change.uptimeMillis)
                                            }
                                            change.changedToUp() -> trackpad.up(id, change.uptimeMillis)
                                            change.pressed -> trackpad.move(id, change.position.x, change.position.y)
                                        }
                                        change.consume()
                                    }
                                    pointer = Offset(trackpad.x.toFloat(), trackpad.y.toFloat())
                                }
                            }
                        }
                    )
                    // A private screen's video has the remote's own pointer in it.
                    if (picture != null && stream?.headless == false) {
                        PointerArrow(
                            Modifier.offset {
                                IntOffset(
                                    picture.x + (pointer.x * picture.width).toInt(),
                                    picture.y + (pointer.y * picture.height).toInt(),
                                )
                            }
                        )
                    }
                    if (!typing && touchControls) {
                        Row(
                            Modifier.align(Alignment.BottomEnd).padding(12.dp).alpha(0.7f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (onClose != null) KeyButton(ICON_CLOSE, onClick = onClose)
                            KeyButton(if (direct) ICON_TRACKPAD else ICON_TOUCH) { direct = !direct }
                            if (turns) KeyButton(if (lyingDown) ICON_UPRIGHT else ICON_LYING) { lyingDown = !lyingDown }
                            KeyButton(ICON_KEYBOARD) { typing = true }
                        }
                    }
                }
        when (state) {
            is SessionState.Streaming -> {}
            SessionState.Connecting -> Notice {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(strings.connecting(address))
                }
            }
            is SessionState.Failed -> Notice {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.error.incompatible) strings.incompatibleTitle else strings.failedTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    strings.failure(state.error).takeIf { it.isNotEmpty() }?.let { message ->
                        SelectionContainer {
                            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onBack) { Text(strings.back) }
                        // Another attempt cannot fix a version mismatch.
                        if (!state.error.incompatible) Button(onClick = onRetry) { Text(strings.retry) }
                    }
                }
            }
            SessionState.Closed -> Notice {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.sessionEnded)
                    TextButton(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text(strings.back) }
                }
            }
            is SessionState.HostKeyPrompt -> HostKeyDialog(state, address, strings, onAcceptHostKey, onBack)
        }
            }
            if (live && typing) {
                KeyBar(held, onHold = { held = if (it in held) held - it else held + it }, onKey = sink::key) { typing = false }
            }
        }
        keyboard(live && typing, sink)
    }
}

/** The pointer, drawn here because the video does not carry it. */
@Composable
private fun PointerArrow(modifier: Modifier) {
    Canvas(modifier.size(12.dp, 19.dp)) {
        val u = 1.dp.toPx()
        val arrow = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 16f * u)
            lineTo(4f * u, 12.5f * u)
            lineTo(7f * u, 19f * u)
            lineTo(9.5f * u, 18f * u)
            lineTo(6.5f * u, 11.5f * u)
            lineTo(11.5f * u, 11.5f * u)
            close()
        }
        drawPath(arrow, Color.Black)
        drawPath(arrow, Color.White, style = Stroke(width = u))
    }
}

@Composable
private fun KeyButton(
    /** A text, or one of the `ICON_` names for a picture no font can be trusted to have. */
    label: String,
    modifier: Modifier = Modifier,
    armed: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp).widthIn(min = 44.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (armed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            when (label) {
                ICON_KEYBOARD -> KeyboardIcon()
                ICON_TRACKPAD -> PointingIcon(touch = false)
                ICON_TOUCH -> PointingIcon(touch = true)
                ICON_LYING -> ScreenIcon(lying = true)
                ICON_UPRIGHT -> ScreenIcon(lying = false)
                ICON_CLOSE -> CloseIcon()
                else -> Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

private const val ICON_KEYBOARD = "\u0000keyboard"
private const val ICON_LYING = "\u0000lying"
private const val ICON_TRACKPAD = "\u0000trackpad"
private const val ICON_TOUCH = "\u0000touch"

/** The way of pointing the button switches to: a trackpad with its two buttons, or a tap on the screen. */
@Composable
private fun PointingIcon(touch: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp, 20.dp)) {
        val u = 1.dp.toPx()
        val line = Stroke(width = 1.5f * u)
        if (touch) {
            drawCircle(color, 2.6f * u, center)
            drawCircle(color, 7f * u, center, style = line)
        } else {
            drawRoundRect(color, Offset(2f * u, 2f * u), Size(20f * u, 16f * u), CornerRadius(3f * u), line)
            drawLine(color, Offset(2f * u, 13f * u), Offset(22f * u, 13f * u), 1.2f * u)
            drawLine(color, Offset(12f * u, 13f * u), Offset(12f * u, 18f * u), 1.2f * u)
        }
    }
}
private const val ICON_UPRIGHT = "\u0000upright"
private const val ICON_CLOSE = "\u0000close"

@Composable
private fun CloseIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(20.dp, 20.dp)) {
        val u = 1.dp.toPx()
        drawLine(color, Offset(5f * u, 5f * u), Offset(15f * u, 15f * u), 1.5f * u)
        drawLine(color, Offset(15f * u, 5f * u), Offset(5f * u, 15f * u), 1.5f * u)
    }
}

/** A phone, in the position the button turns the screen to. */
@Composable
private fun ScreenIcon(lying: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp, 20.dp)) {
        val u = 1.dp.toPx()
        val long = 22f * u
        val short = 13f * u
        val w = if (lying) long else short
        val h = if (lying) short else long
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(3f * u), Stroke(width = 1.5f * u))
        // The side the charging port is on.
        if (lying) {
            drawRect(color, Offset(left + w - 3.6f * u, top + h / 2 - 2f * u), Size(1.4f * u, 4f * u))
        } else {
            drawRect(color, Offset(left + w / 2 - 2f * u, top + h - 3.6f * u), Size(4f * u, 1.4f * u))
        }
    }
}

@Composable
private fun KeyboardIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp, 16.dp)) {
        val u = 1.dp.toPx()
        drawRoundRect(color, style = Stroke(width = 1.5f * u), cornerRadius = CornerRadius(3f * u))
        for (row in 0..1) for (column in 0..4) {
            drawRect(color, Offset((4f + 3.6f * column) * u, (4f + 3.4f * row) * u), Size(1.6f * u, 1.6f * u))
        }
        drawRect(color, Offset(7.5f * u, 10.8f * u), Size(9f * u, 1.6f * u))
    }
}

/** The keys a phone keyboard lacks, in a strip right above it. */
@Composable
private fun KeyBar(held: Set<Int>, onHold: (Int) -> Unit, onKey: (Int) -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyButton(ICON_KEYBOARD, onClick = onClose)
        KeyButton("Esc") { onKey(Keys.ESC) }
        KeyButton("Tab") { onKey(Keys.TAB) }
        for ((label, code) in MODIFIERS) KeyButton(label, armed = code in held) { onHold(code) }
        for ((label, code) in EXTRA_KEYS) KeyButton(label) { onKey(code) }
    }
}

private val MODIFIERS = listOf("Ctrl" to Keys.LEFT_CTRL, "Alt" to Keys.LEFT_ALT, "Super" to Keys.LEFT_META)

private val EXTRA_KEYS = listOf(
    "\u2190" to Keys.LEFT, "\u2193" to Keys.DOWN, "\u2191" to Keys.UP, "\u2192" to Keys.RIGHT,
    "Home" to Keys.HOME, "End" to Keys.END, "PgUp" to Keys.PAGE_UP, "PgDn" to Keys.PAGE_DOWN, "Del" to Keys.DELETE,
)

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Notice(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 440.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Box(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun HostKeyDialog(
    prompt: SessionState.HostKeyPrompt,
    address: String,
    strings: Strings,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    val changed = prompt.previous != null
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (changed) strings.hostKeyChangedTitle else strings.hostKeyTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (changed) strings.hostKeyChangedExplain(address) else strings.hostKeyExplain(address))
                SelectionContainer {
                    Text(
                        "${prompt.key.algorithm}\n${prompt.key.fingerprint}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                prompt.previous?.let {
                    Text(
                        "${strings.previousKey}\n$it",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(
                    if (changed) strings.trustNewKey else strings.trust,
                    color = if (changed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(strings.cancel) } },
    )
}

/** How long a pinch holds still before the stream is asked to follow it. */
private const val MAGNIFIED_SETTLE_MS = 400L
