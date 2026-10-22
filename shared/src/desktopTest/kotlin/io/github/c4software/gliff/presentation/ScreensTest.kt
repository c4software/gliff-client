package io.github.c4software.gliff.presentation

import androidx.compose.foundation.background
import io.github.c4software.gliff.presentation.session.placedAt
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.session.HostKeyInfo
import io.github.c4software.gliff.domain.session.SessionError
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.domain.session.StreamInfo
import io.github.c4software.gliff.domain.settings.Settings
import io.github.c4software.gliff.presentation.machines.KeyDialog
import io.github.c4software.gliff.presentation.machines.MachineDialog
import io.github.c4software.gliff.presentation.machines.MachinesContent
import io.github.c4software.gliff.presentation.session.SessionContent
import io.github.c4software.gliff.presentation.settings.SettingsDialog
import io.github.c4software.gliff.presentation.theme.English
import io.github.c4software.gliff.presentation.theme.French
import io.github.c4software.gliff.presentation.theme.GliffTheme
import io.github.c4software.gliff.presentation.theme.Strings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the shared screens off-screen into `build/screenshots`, to look at
 * them without a device. It fails only when a screen cannot be composed.
 */
class ScreensTest {
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String, width: Int, height: Int, density: Float = 2f, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(density)) { GliffTheme(content) }
        try {
            // A second frame lets dialogs and measured layouts settle.
            scene.render(0)
            val image = scene.render(500_000_000)
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(png)
            assertTrue(png.size > 1000, name)
        } finally {
            scene.close()
        }
    }

    private val machines = listOf(
        Machine(id = "1", user = "val", host = "atelier", hostKey = "SHA256:f7uPhSCFfqXC5dJVBcw9wvDYZTabtmqGkV0eKHKZKEU"),
        Machine(id = "2", user = "val", host = "tour.tail1234.ts.net", port = 2222, headless = false, output = "DP-1"),
        Machine(id = "3", user = "valentin", host = "192.168.1.20", headless = false),
    )

    @Composable
    private fun Machines(list: List<Machine>, strings: Strings) =
        MachinesContent(list, strings, onOpen = {}, onAdd = {}, onEdit = {}, onDelete = {}, onShowKey = {})

    @Composable
    private fun Session(state: SessionState, strings: Strings = French, typing: Boolean = false) = SessionContent(
        state, "val@atelier", strings, onSize = { _, _, _ -> }, onAcceptHostKey = {}, onRetry = {}, onBack = {},
        keyboardOpen = typing,
        turns = true,
    ) { modifier, picture -> Box(modifier.placedAt(picture).background(Color(0xFF2B4A7A))) }

    private fun stream(width: Int, height: Int) = StreamInfo(width, height, width, height, 2000, 60, null, true, "HEADLESS-2")

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun aTouchOnTheSessionReachesTheRemote() {
        val sent = java.util.concurrent.CopyOnWriteArrayList<io.github.c4software.gliff.proto.ClientMsg>()
        val scene = ImageComposeScene(820, 1700, Density(2f)) {
            GliffTheme {
                SessionContent(
                    SessionState.Streaming(stream(820, 1700)), "val@atelier", French,
                    onSize = { _, _, _ -> }, onAcceptHostKey = {}, onRetry = {}, onBack = {},
                    send = { sent.add(it) },
                ) { modifier, picture -> Box(modifier.placedAt(picture).background(Color(0xFF2B4A7A))) }
            }
        }
        try {
            scene.render(0)
            scene.render(100_000_000)
            val at = androidx.compose.ui.geometry.Offset(205f, 425f)
            val touch = androidx.compose.ui.input.pointer.PointerType.Touch
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at, type = touch, timeMillis = 1000)
            scene.render(150_000_000)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at, type = touch, timeMillis = 1050)
            scene.render(200_000_000)
            // A quarter of the way across and down a 410x850 logical screen, then a left click.
            assertEquals(
                listOf(
                    io.github.c4software.gliff.proto.ClientMsg.PointerMotion(102.5, 212.5),
                    io.github.c4software.gliff.proto.ClientMsg.PointerButton(0x110, true),
                    io.github.c4software.gliff.proto.ClientMsg.PointerButton(0x110, false),
                ),
                sent.toList(),
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun machines() {
        shot("machines-phone-fr", 820, 1700) { Machines(machines, French) }
        shot("machines-empty-en", 820, 1700) { Machines(emptyList(), English) }
        shot("machines-window", 1400, 900, density = 1f) { Machines(machines, English) }
    }

    @Test
    fun dialogs() {
        shot("machine-dialog-fr", 820, 1700) {
            Machines(machines, French)
            MachineDialog(machines[1], French, onDismiss = {}, onSave = {})
        }
        shot("options-fr", 820, 1700) {
            Machines(machines, French)
            SettingsDialog(
                Settings(forcedWidth = 1920, forcedHeight = 1080, httpMirror = true, mirrorToken = "k7f3q2mxp9"),
                listOf("http://192.168.1.42:8765/k7f3q2mxp9/", "http://100.101.102.103:8765/k7f3q2mxp9/"),
                French, onChange = {}, onDismiss = {},
            )
        }
        shot("key-dialog-fr", 820, 1700) {
            Machines(machines, French)
            KeyDialog("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOr1mB7oJ3cQyq0x9s4d4mU7mQp1nq3s8c0G0wqk7YtZ gliff@Pixel-9", French) {}
        }
    }

    @Test
    fun session() {
        shot("session-connecting", 820, 1700) { Session(SessionState.Connecting) }
        shot("session-host-key", 820, 1700) {
            Session(SessionState.HostKeyPrompt(HostKeyInfo("Ed25519", "SHA256:f7uPhSCFfqXC5dJVBcw9wvDYZTabtmqGkV0eKHKZKEU"), null))
        }
        shot("session-host-key-changed", 820, 1700) {
            Session(
                SessionState.HostKeyPrompt(
                    HostKeyInfo("Ed25519", "SHA256:f7uPhSCFfqXC5dJVBcw9wvDYZTabtmqGkV0eKHKZKEU"),
                    "SHA256:0ldK3yQ2m1cV8xTn5pLr7wZb9dFh3jGs6aUe4iOy2Mk",
                )
            )
        }
        shot("session-failed", 820, 1700) {
            Session(SessionState.Failed(SessionError.Connection("bash: line 1: gliff-server: command not found")))
        }
        shot("session-incompatible", 820, 1700) {
            Session(SessionState.Failed(SessionError.Incompatible("Client version is too old")), English)
        }
        // A 16:9 mirror on an upright phone, and in a wide desktop window.
        shot("session-mirror-portrait", 820, 1700) { Session(SessionState.Streaming(stream(1920, 1080))) }
        shot("session-typing", 820, 1700) { Session(SessionState.Streaming(stream(820, 1600)), typing = true) }
        shot("session-mirror-window", 1400, 700, density = 1f) { Session(SessionState.Streaming(stream(1920, 1200))) }
    }
}
