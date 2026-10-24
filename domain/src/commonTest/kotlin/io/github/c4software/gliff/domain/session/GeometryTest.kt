package io.github.c4software.gliff.domain.session

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {
    private fun stream(width: Int, height: Int, view: Pair<Int, Int> = width to height, scaleMilli: Int = 1000) =
        StreamInfo(width, height, view.first, view.second, scaleMilli, 60, null, true, "o")

    @Test
    fun aViewOfTheWindowsSizeFillsIt() {
        assertEquals(Box(0, 0, 1280, 720), fit(1280, 720, 1280, 720))
    }

    @Test
    fun aWiderWindowLetterboxesLeftAndRight() {
        assertEquals(Box(160, 0, 960, 720), fit(1440, 1080, 1280, 720))
    }

    @Test
    fun aTallerWindowLetterboxesTopAndBottom() {
        // A 16:9 screen mirrored on a phone held upright.
        assertEquals(Box(0, 896, 1080, 607), fit(1920, 1080, 1080, 2400))
    }

    @Test
    fun aSmallViewIsEnlargedToTheWindow() {
        assertEquals(Box(0, 0, 2560, 1440), fit(1280, 720, 2560, 1440))
    }

    @Test
    fun degenerateSizesDoNotDivideByZero() {
        assertEquals(Box(0, 0, 100, 50), fit(0, 0, 100, 50))
        assertEquals(Box(0, 0, 0, 0), fit(1920, 1080, 0, 0))
    }

    @Test
    fun pointsMapToTheRemotesLogicalSpace() {
        // 2560x1440 stream at scale 2 shown letterboxed in a 1280x1000 window.
        val s = stream(2560, 1440, scaleMilli = 2000)
        val picture = fit(s.viewWidth, s.viewHeight, 1280, 1000)
        assertEquals(Box(0, 140, 1280, 720), picture)
        assertEquals(0.0 to 0.0, toRemote(0f, 140f, picture, s))
        assertEquals(640.0 to 360.0, toRemote(640f, 500f, picture, s))
        assertEquals(1280.0 to 720.0, toRemote(1280f, 860f, picture, s))
        // In the letterbox: clamped to the picture's edge.
        assertEquals(640.0 to 0.0, toRemote(640f, 10f, picture, s))
        assertEquals(640.0 to 720.0, toRemote(640f, 999f, picture, s))
    }

    @Test
    fun aReducedStreamMapsThroughItsEffectiveScale() {
        // The server halved the resolution: 960x540 stretched to a 1920x1080
        // view, and scale_milli folds the downscale in (1.0 * 960 / 1920).
        val s = stream(960, 540, view = 1920 to 1080, scaleMilli = 500)
        val picture = fit(s.viewWidth, s.viewHeight, 1920, 1080)
        assertEquals(1920.0 to 1080.0, toRemote(1920f, 1080f, picture, s))
        assertEquals(960.0 to 540.0, toRemote(960f, 540f, picture, s))
    }

    /** Hyprland's rule: a multiple of 1/120 that divides both sides into whole logical pixels. */
    private fun assertTakenAsAsked(mode: RemoteMode) {
        val steps = mode.scale * 120
        assertEquals(steps.toInt().toFloat(), steps, "scale ${mode.scale} is not a multiple of 1/120")
        assertEquals(0, mode.width * 120 % steps.toInt(), "$mode: width")
        assertEquals(0, mode.height * 120 % steps.toInt(), "$mode: height")
        assertEquals(0, mode.width % 2 + mode.height % 2, "$mode: odd size")
    }

    @Test
    fun aModeIsOneTheCompositorKeeps() {
        // 1.8 on 1080x1046 is what Hyprland turned into 2.
        assertEquals(RemoteMode(1080, 1044, 1.8f), remoteMode(1080, 1046, 1.8f))
        for ((w, h, scale) in listOf(
            Triple(1080, 2238, 2.625f), Triple(1080, 1046, 1.227f), Triple(1080, 2174, 2.625f),
            Triple(2410, 1000, 2.625f), Triple(1400, 900, 1f), Triple(1919, 1079, 1.5f), Triple(700, 500, 3.5f),
            Triple(1080, 2238, 0.5f), Triple(1080, 2238, 0.75f),
        )) {
            val mode = remoteMode(w, h, scale)
            assertTakenAsAsked(mode)
            assertTrue(mode.width <= w && mode.height <= h, "$mode grows past ${w}x$h")
            assertTrue(w - mode.width < 40 && h - mode.height < 40, "$mode wastes too much of ${w}x$h")
            assertTrue(abs(mode.scale - scale) / scale < 0.07f, "$mode strays from $scale")
        }
        // A whole scale needs nothing but an even size.
        assertEquals(RemoteMode(1400, 900, 1f), remoteMode(1400, 900, 1f))
        assertEquals(RemoteMode(1920, 1080, 2f), remoteMode(1920, 1081, 2f))
    }

    @Test
    fun aMagnifiedPictureKeepsWhatIsUnderTheFingers() {
        val fitted = Box(0, 100, 1000, 500)
        assertEquals(fitted, Magnifier().place(fitted, 1000, 700))
        // Twice as large around the point a quarter in from the left, a fifth down.
        val twice = Magnifier().moved(fitted, 1000, 700, 2f, 0f, 0f, 250f, 200f)
        val box = twice.place(fitted, 1000, 700)
        assertEquals(2000 to 1000, box.width to box.height)
        // That point of the picture is still at 250, 200 on the screen.
        assertEquals(250f, box.x + 0.25f * box.width, 1f)
        assertEquals(200f, box.y + 0.2f * box.height, 1f)
        // Dragging moves it, but never past its own edge.
        val dragged = twice.moved(fitted, 1000, 700, 1f, 100f, 0f, 500f, 350f).place(fitted, 1000, 700)
        assertEquals(box.x + 100, dragged.x)
        val far = twice.moved(fitted, 1000, 700, 1f, 5000f, 5000f, 500f, 350f).place(fitted, 1000, 700)
        assertEquals(0 to 0, far.x to far.y)
        // Never smaller than fitted, never beyond the limit, and centred again at 1.
        assertEquals(Magnifier(), twice.moved(fitted, 1000, 700, 0.1f, 30f, 30f, 10f, 10f))
        assertEquals(Magnifier.MAX_ZOOM, twice.moved(fitted, 1000, 700, 100f, 0f, 0f, 500f, 350f).zoom)
        // A smaller area (the keyboard came up) pulls the picture back over it.
        val squeezed = far.let { twice.moved(fitted, 1000, 700, 1f, 5000f, 5000f, 500f, 350f) }.place(Box(0, 0, 600, 300), 600, 300)
        assertTrue(squeezed.x <= 0 && squeezed.x + squeezed.width >= 600, "$squeezed")
    }

    @Test
    fun theKeyboardShrinksTheRemoteScale() {
        assertEquals(1f, keyboardZoom(1080, 2238, 1080, 2238))
        // Under half the height left, drawn that much smaller: as many lines as before.
        assertEquals(0.467f, keyboardZoom(1080, 1046, 1080, 2238), 0.001f)
        assertEquals(0.4f, keyboardZoom(1080, 300, 1080, 2238))
        // Lying down without a keyboard, upright with one: the width is what has to fit.
        assertEquals(0.483f, keyboardZoom(1080, 1046, 2238, 1080), 0.001f)
        assertEquals(1f, keyboardZoom(2238, 2500, 1080, 2238))
        assertEquals(1f, keyboardZoom(1080, 1046, 0, 0))
    }

    @Test
    fun theAreaIsReportedAtTheDensityShrunkUnderTheKeyboard() {
        assertEquals(2.625f, areaScale(2.625f, 1f, keyboardZoom = true, forced = false))
        assertEquals(1.3125f, areaScale(2.625f, 0.5f, keyboardZoom = true, forced = false))
        // The option off, the keyboard changes nothing of the scale.
        assertEquals(2.625f, areaScale(2.625f, 0.5f, keyboardZoom = false, forced = false))
    }

    @Test
    fun anImposedResolutionIsNotReportedUnderTheKeyboard() {
        assertNull(areaScale(2.625f, 0.5f, keyboardZoom = true, forced = true))
        assertEquals(2.625f, areaScale(2.625f, 1f, keyboardZoom = true, forced = true))
    }

    @Test
    fun sharperMirrorGrowsTheFittedScreenUpToItsOwnSize() {
        // A 2560x1440 screen in an upright phone: 1080x607 fitted, 2160x1215 at twice.
        assertEquals(2160 to 1214, sharperMirror(1080, 2200, 2f, 2560, 1440, 4096, 4096))
        assertEquals(2560 to 1440, sharperMirror(1080, 2200, 4f, 2560, 1440, 4096, 4096))
        assertEquals(1920 to 1080, sharperMirror(1080, 2200, 4f, 2560, 1440, 1920, 1920))
        // No zoom, or a screen no larger than the window: nothing to gain.
        assertEquals(null, sharperMirror(1080, 2200, 1f, 2560, 1440, 4096, 4096))
        assertEquals(null, sharperMirror(1080, 2200, 3f, 1080, 608, 4096, 4096))
    }

    @Test
    fun zoomStepRoundsUpToHalves() {
        assertEquals(1f, zoomStep(0.8f))
        assertEquals(1f, zoomStep(1f))
        assertEquals(1.5f, zoomStep(1.1f))
        assertEquals(2.5f, zoomStep(2.4f))
        assertEquals(4f, zoomStep(9f))
    }
}
