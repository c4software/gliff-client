package io.github.c4software.gliff.presentation

import androidx.compose.foundation.background
import io.github.c4software.gliff.presentation.session.placedAt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.domain.session.StreamInfo
import io.github.c4software.gliff.domain.settings.Settings
import io.github.c4software.gliff.presentation.machines.MachinesContent
import io.github.c4software.gliff.presentation.session.SessionContent
import io.github.c4software.gliff.presentation.settings.SettingsDialog
import io.github.c4software.gliff.presentation.theme.English
import io.github.c4software.gliff.presentation.theme.French
import io.github.c4software.gliff.presentation.theme.GliffTheme
import io.github.c4software.gliff.presentation.theme.Strings
import java.io.File
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume

/**
 * The Play listing's screenshots, for a phone, a tablet and a desktop, when
 * asked for:
 *
 *     GLIFF_STORE=1 ./gradlew :shared:desktopTest --tests '*StoreShots*'
 *
 * The screens are the app's own, composed off-screen at each device's size. The
 * remote desktop in the session shots is drawn here: there is no video
 * without a machine to connect to. `store/README.md` says so.
 */
class StoreShots {
    /** A device the listing shows: its directory under `store/screenshots`, its pixels and density. */
    private class Form(val dir: String, val width: Int, val height: Int, val density: Float) {
        /** Wide enough to hold the remote desktop as it is, not upright. */
        val wide get() = width > height
    }

    private fun shot(form: Form, locale: String, name: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(form.width, form.height, Density(form.density)) { GliffTheme(content) }
        try {
            scene.render(0)
            val png = scene.render(500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("../store/screenshots/${form.dir}", locale).apply { mkdirs() }.resolve("$name.png").writeBytes(png)
        } finally {
            scene.close()
        }
    }

    private val machines = listOf(
        Machine(id = "1", user = "val", host = "workstation", hostKey = "SHA256:f7uPhSCFfqXC5dJVBcw9wvDYZTabtmqGkV0eKHKZKEU"),
        Machine(id = "2", user = "val", host = "laptop.tail1234.ts.net", headless = false, output = "DP-1", hostKey = "SHA256:0ldK3yQ2m1cV8xTn5pLr7wZb9dFh3jGs6aUe4iOy2Mk"),
        Machine(id = "3", user = "val", host = "192.168.1.20", port = 2222, headless = false),
    )

    @Composable
    private fun Machines(strings: Strings) =
        MachinesContent(machines, strings, onOpen = {}, onAdd = {}, onEdit = {}, onDelete = {}, onShowKey = {})

    @Composable
    private fun Session(form: Form, strings: Strings, typing: Boolean = false) = SessionContent(
        SessionState.Streaming(StreamInfo(form.width, form.height, form.width, form.height, 2000, 60, null, true, "HEADLESS-2")),
        "val@workstation", strings,
        onSize = { _, _, _ -> }, onAcceptHostKey = {}, onRetry = {}, onBack = {},
        keyboardOpen = typing,
        turns = !form.wide,
    ) { modifier, picture -> Desktop(modifier.placedAt(picture), wide = form.wide) }

    @Test
    fun screenshots() {
        Assume.assumeTrue("set GLIFF_STORE=1 to write the store screenshots", System.getenv("GLIFF_STORE") == "1")
        for (form in FORMS) for ((locale, strings, names) in listOf(
            Triple("en-US", English, listOf("01-session", "02-keys", "03-machines", "04-options")),
            Triple("fr-FR", French, listOf("01-session", "02-touches", "03-machines", "04-options")),
        )) {
            shot(form, locale, names[0]) { Session(form, strings) }
            shot(form, locale, names[1]) { Session(form, strings, typing = true) }
            shot(form, locale, names[2]) { Machines(strings) }
            shot(form, locale, names[3]) {
                Machines(strings)
                SettingsDialog(
                    Settings(httpMirror = true, mirrorToken = "k7f3q2mxp9"),
                    listOf("http://192.168.1.42:8765/k7f3q2mxp9/"),
                    strings, desktop = form.dir == "desktop", onChange = {}, onDismiss = {},
                )
            }
        }
    }

    /** The pictures `store/demo-server/make-sample.sh` encodes into the demonstration server's video. */
    @Test
    fun demoFrames() {
        Assume.assumeTrue("set GLIFF_STORE=1 to write the demo frames", System.getenv("GLIFF_STORE") == "1")
        val frames = File("build/demo-frames").apply { deleteRecursively(); mkdirs() }
        for (step in 0 until DEMO_STEPS) {
            val scene = ImageComposeScene(1280, 720, Density(1.5f)) { GliffTheme { Desktop(Modifier.fillMaxSize(), wide = true, step = step) } }
            try {
                scene.render(0)
                val png = scene.render(500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(frames, "%03d.png".format(step)).writeBytes(png)
            } finally {
                scene.close()
            }
        }
    }

    private companion object {
        val FORMS = listOf(
            // A 1080 x 2400 phone at 420 dpi.
            Form("phone", 1080, 2400, 2.625f),
            // A 10-inch tablet, 1280 x 800 dp, lying down.
            Form("tablet", 2560, 1600, 2f),
            // A desktop or Chromebook display, 1280 x 720 dp.
            Form("desktop", 1920, 1080, 1.5f),
        )
    }
}

private val Base = Color(0xFF1A1B26)
private val Dim = Color(0xFF565F89)
private val Ink = Color(0xFFC0CAF5)
private val Blue = Color(0xFF7AA2F7)
private val Green = Color(0xFF9ECE6A)
private val Purple = Color(0xFFBB9AF7)
private val Orange = Color(0xFFFF9E64)

/** How many pictures the demonstration loop has; the terminal's lines come one per step. */
private const val DEMO_STEPS = 16

/**
 * A tiled desktop, standing in for the video: a bar, an editor, a terminal.
 * `step` is how far the terminal has got, for the demonstration loop.
 */
@Composable
private fun Desktop(modifier: Modifier, wide: Boolean = false, step: Int = DEMO_STEPS) {
    Column(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF24283B), Color(0xFF15161E)))).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (n in 1..4) Mono(n.toString(), if (n == 2) Blue else Dim)
            }
            Mono("14:32", Ink)
            Mono("wifi  87%", Dim)
        }
        if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Editor(Modifier.weight(1f))
                Terminal(Modifier.weight(1f), step)
            }
        } else {
            Editor(Modifier.weight(1.25f))
            Terminal(Modifier.weight(1f), step)
        }
    }
}

@Composable
private fun Editor(modifier: Modifier) {
    Window(modifier, "relay.kt") {
        Code(1, Purple to "fun ", Blue to "relay", Ink to "(frames: Flow<Frame>) {")
        Code(2, Ink to "    frames")
        Code(3, Ink to "        .", Blue to "filter", Ink to " { it.keyframe || ready }")
        Code(4, Ink to "        .", Blue to "onEach", Ink to " { ready = ", Orange to "true", Ink to " }")
        Code(5, Ink to "        .", Blue to "collect", Ink to " { sink.", Blue to "write", Ink to "(it) }")
        Code(6, Ink to "}")
        Code(7)
        Code(8, Dim to "// One fragment per access unit.")
        Code(9, Purple to "class ", Blue to "Sink", Ink to "(", Purple to "val ", Ink to "out: Output) {")
        Code(10, Purple to "    private var ", Ink to "sequence = ", Orange to "0")
        Code(11)
        Code(12, Purple to "    fun ", Blue to "write", Ink to "(frame: Frame) {")
        Code(13, Ink to "        out.", Blue to "send", Ink to "(sequence++, frame.data)")
        Code(14, Ink to "    }")
        Code(15, Ink to "}")
    }
}

@Composable
private fun Terminal(modifier: Modifier, step: Int) {
    val prompt = arrayOf(Green to "~/projects/relay ", Blue to "main ", Ink to "❯ ")
    // Each entry appears at its step; a prompt waits with a cursor, blinking, until the next one.
    val lines: List<Array<Pair<Color, String>>> = listOf(
        arrayOf(*prompt, Ink to "./gradlew test"),
        arrayOf(Dim to "> Task :compileKotlin"),
        arrayOf(Dim to "> Task :test"),
        arrayOf(),
        arrayOf(Ink to "RelayTest > starts on a keyframe ", Green to "PASSED"),
        arrayOf(Ink to "RelayTest > numbers its fragments ", Green to "PASSED"),
        arrayOf(Ink to "SinkTest > keeps the order ", Green to "PASSED"),
        arrayOf(),
        arrayOf(Green to "BUILD SUCCESSFUL", Ink to " in 4s"),
        arrayOf(*prompt, Ink to "git status --short"),
        arrayOf(Orange to " M ", Ink to "src/relay.kt"),
    )
    Window(modifier, "~/projects/relay") {
        val shown = lines.take((step - 1).coerceAtLeast(0))
        for (line in shown) Line(*line)
        if (shown.size == lines.size || step <= 1) Line(*prompt, Ink to if (step % 2 == 0) "▌" else " ")
    }
}

@Composable
private fun Window(modifier: Modifier, title: String, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Base)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF16161E)).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.padding(top = 3.dp).size(8.dp).clip(CircleShape).background(Blue))
            Mono(title, Dim)
        }
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
    }
}

@Composable
private fun Mono(text: String, color: Color, weight: FontWeight = FontWeight.Normal) =
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = weight, maxLines = 1)

@Composable
private fun Line(vararg parts: Pair<Color, String>) {
    Row {
        if (parts.isEmpty()) Mono(" ", Ink)
        for ((color, text) in parts) Mono(text, color)
    }
}

@Composable
private fun Code(number: Int, vararg parts: Pair<Color, String>) {
    Row {
        Mono(number.toString().padStart(2) + "  ", Dim)
        for ((color, text) in parts) Mono(text, color)
        Spacer(Modifier.size(1.dp))
    }
}
