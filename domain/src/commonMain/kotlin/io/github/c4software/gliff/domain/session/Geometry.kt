package io.github.c4software.gliff.domain.session

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A rectangle in pixels. */
data class Box(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Where a `viewWidth x viewHeight` picture goes in a `width x height` window:
 * as large as fits, centred, aspect kept. The stream is stretched to it, so a
 * reduced-resolution stream never shrinks on screen.
 */
fun fit(viewWidth: Int, viewHeight: Int, width: Int, height: Int): Box {
    if (viewWidth <= 0 || viewHeight <= 0 || width <= 0 || height <= 0) return Box(0, 0, maxOf(width, 0), maxOf(height, 0))
    // Compare the aspects without dividing: the window is wider when w/h > vw/vh.
    val wider = width.toLong() * viewHeight > viewWidth.toLong() * height
    val w = if (wider) (height.toLong() * viewWidth / viewHeight).toInt() else width
    val h = if (wider) height else (width.toLong() * viewHeight / viewWidth).toInt()
    return Box((width - w) / 2, (height - h) / 2, w, h)
}

/**
 * Map a point of the window to the remote's logical space, which is what
 * pointer messages carry: undo the fit, scale to stream pixels, then divide by
 * the stream scale. Points outside the picture clamp to its edge.
 */
fun toRemote(x: Float, y: Float, picture: Box, stream: StreamInfo): Pair<Double, Double> {
    if (picture.width <= 0 || picture.height <= 0) return 0.0 to 0.0
    val px = ((x - picture.x).toDouble() * stream.width / picture.width).coerceIn(0.0, stream.width.toDouble())
    val py = ((y - picture.y).toDouble() * stream.height / picture.height).coerceIn(0.0, stream.height.toDouble())
    val scale = maxOf(stream.scaleMilli, 10) / 1000.0
    return px / scale to py / scale
}

/** A size and display scale to ask the server for. */
data class RemoteMode(val width: Int, val height: Int, val scale: Float)

/**
 * The mode closest to a `width x height` window at `scale` that the remote
 * compositor takes as asked.
 *
 * Hyprland only keeps a scale that is a multiple of 1/120 and divides both
 * sides into whole logical pixels; anything else it replaces with one that
 * does, which can be far off (1.8 on 1080x1046 becomes 2). So the scale is
 * picked among the nearby multiples of 1/120 and the size trimmed by a few
 * pixels to fit it, whichever loses the least of the window.
 */
fun remoteMode(width: Int, height: Int, scale: Float): RemoteMode {
    val wanted = (scale.coerceIn(0.5f, 4f) * SCALE_STEPS).roundToInt()
    var best = RemoteMode(width and 1.inv(), height and 1.inv(), wanted / SCALE_STEPS.toFloat())
    var bestCost = Long.MAX_VALUE
    val reach = maxOf(wanted * SCALE_REACH_PERCENT / 100, 1)
    for (steps in maxOf(wanted - reach, SCALE_STEPS / 2)..minOf(wanted + reach, SCALE_STEPS * 4)) {
        // steps/120 = p/q in lowest terms: a side of p*m pixels is q*m logical ones.
        val p = steps / gcd(steps, SCALE_STEPS)
        // The server rounds sizes down to even.
        val unit = if (p % 2 == 0) p else p * 2
        val w = width / unit * unit
        val h = height / unit * unit
        if (w <= 0 || h <= 0) continue
        // Lost pixels first, then the distance to the scale asked for.
        val lost = width.toLong() * height - w.toLong() * h
        val cost = lost * 1000 + abs(steps - wanted)
        if (cost < bestCost) {
            bestCost = cost
            best = RemoteMode(w, h, steps / SCALE_STEPS.toFloat())
        }
    }
    return best
}

/**
 * What to ask a mirrored screen of `outputWidth x outputHeight` for, so that
 * a picture pinched `zoom` times larger in a `width x height` window gets the
 * pixels it shows: the screen fitted in the window, that many times larger,
 * up to the screen's own size and to what the decoder takes (`maxWidth x
 * maxHeight`). Null when that is no larger than the window alone gives. The
 * mirror's output does not change: the server only encodes it larger.
 */
fun sharperMirror(
    width: Int,
    height: Int,
    zoom: Float,
    outputWidth: Int,
    outputHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
): Pair<Int, Int>? {
    val shown = fit(outputWidth, outputHeight, width, height)
    if (shown.width <= 0 || shown.height <= 0) return null
    val factor = minOf(
        zoom,
        outputWidth.toFloat() / shown.width,
        maxWidth.toFloat() / shown.width,
        maxHeight.toFloat() / shown.height,
    )
    if (factor < MIN_SHARPER) return null
    // The height from the screen's own aspect: the fitted box lost a fraction of a pixel.
    val w = (shown.width * factor).roundToInt().coerceAtMost(minOf(outputWidth, maxWidth))
    val h = (w.toLong() * outputHeight / outputWidth).toInt().coerceAtMost(maxHeight)
    return (w and 1.inv()) to (h and 1.inv())
}

/** A pinch rounded up to the next half: each step costs a keyframe, so the stream does not follow every finger move. */
fun zoomStep(zoom: Float): Float = (ceil(zoom * 2) / 2).coerceIn(1f, Magnifier.MAX_ZOOM)

/** Less than this more pixels is not worth a keyframe. */
private const val MIN_SHARPER = 1.2f

/** This resolution turned the way of `view`: upright for an upright window. */
fun Viewport.laidLike(view: Viewport): Viewport {
    val long = maxOf(width, height)
    val short = minOf(width, height)
    return if (view.height > view.width) copy(width = short, height = long) else copy(width = long, height = short)
}

private const val SCALE_STEPS = 120

/** How far from the wanted scale a better-fitting one may be. */
private const val SCALE_REACH_PERCENT = 6

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/**
 * How the picture is magnified on this screen, the remote knowing nothing of
 * it: `zoom` times its fitted size, moved by `panX, panY` pixels from centred.
 */
data class Magnifier(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    /** Where the fitted picture `fitted` goes in a `width x height` area. */
    fun place(fitted: Box, width: Int, height: Int): Box {
        val held = hold(fitted, width, height)
        val w = (fitted.width * held.zoom).roundToInt()
        val h = (fitted.height * held.zoom).roundToInt()
        return Box(
            (fitted.x + (fitted.width - w) / 2f + held.panX).roundToInt(),
            (fitted.y + (fitted.height - h) / 2f + held.panY).roundToInt(),
            w, h,
        )
    }

    /**
     * Two fingers moved: the picture grows by `factor` around the point
     * `cx, cy` between them, which itself moved by `dx, dy`. What was under
     * the fingers stays under them.
     */
    fun moved(fitted: Box, width: Int, height: Int, factor: Float, dx: Float, dy: Float, cx: Float, cy: Float): Magnifier {
        if (fitted.width <= 0 || fitted.height <= 0) return this
        val now = hold(fitted, width, height)
        val next = (now.zoom * factor).coerceIn(1f, MAX_ZOOM)
        // The picture's corner, before and after: the point under the fingers keeps its place in the picture.
        val left = fitted.x + fitted.width * (1 - now.zoom) / 2 + now.panX
        val top = fitted.y + fitted.height * (1 - now.zoom) / 2 + now.panY
        val newLeft = cx + dx - (cx - left) / now.zoom * next
        val newTop = cy + dy - (cy - top) / now.zoom * next
        return Magnifier(
            next,
            newLeft - (fitted.x + fitted.width * (1 - next) / 2),
            newTop - (fitted.y + fitted.height * (1 - next) / 2),
        ).hold(fitted, width, height)
    }

    /** Keep the picture over the area: no pan past its edges, none at all when it fits. */
    private fun hold(fitted: Box, width: Int, height: Int): Magnifier {
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        val slackX = maxOf(fitted.width * z - width, 0f) / 2
        val slackY = maxOf(fitted.height * z - height, 0f) / 2
        return Magnifier(
            z,
            if (slackX > 0) panX.coerceIn(-slackX, slackX) else 0f,
            if (slackY > 0) panY.coerceIn(-slackY, slackY) else 0f,
        )
    }

    companion object {
        const val MAX_ZOOM = 4f
    }
}

/**
 * How much smaller the remote draws while the keyboard is up: small enough
 * that the area left (`width x height`) shows no less of the remote, in width
 * or in height, than the whole window (`wholeWidth x wholeHeight`) did. A
 * terminal keeps its lines and its columns, whether the keyboard took half
 * the height or the window stood up to make room.
 */
fun keyboardZoom(width: Int, height: Int, wholeWidth: Int, wholeHeight: Int): Float {
    if (wholeWidth <= 0 || wholeHeight <= 0 || width <= 0 || height <= 0) return 1f
    val fit = minOf(width.toFloat() / wholeWidth, height.toFloat() / wholeHeight)
    return fit.coerceIn(MIN_KEYBOARD_ZOOM, 1f)
}

private const val MIN_KEYBOARD_ZOOM = 0.4f

/**
 * The display scale the remote is asked for, for a video area at `density`
 * shrunk by `zoom` under the keyboard; null when the area is not to be
 * reported at all. An imposed resolution stays as it is under the keyboard,
 * and laid as the whole window was.
 */
fun areaScale(density: Float, zoom: Float, keyboardZoom: Boolean, forced: Boolean): Float? = when {
    forced && zoom < 1f -> null
    keyboardZoom -> density * zoom
    else -> density
}
