package neonsync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** CIE 1931 chromaticity plus a normalized brightness (0..1). */
data class LightColor(val x: Double, val y: Double, val brightness: Double) {
    fun colorDistance(o: LightColor) = abs(x - o.x) + abs(y - o.y)
}

data class XY(val x: Double, val y: Double)

/** Linear-light sRGB. */
data class LinearRgb(val r: Double, val g: Double, val b: Double) {
    operator fun times(f: Double) = LinearRgb(r * f, g * f, b * f)
    operator fun plus(o: LinearRgb) = LinearRgb(r + o.r, g + o.g, b + o.b)
    fun toXy() = ColorMath.linearRgbToXy(r, g, b)

    companion object {
        /** Parses `#rrggbb`; null if malformed. */
        fun parse(hex: String): LinearRgb? {
            val h = hex.trim().removePrefix("#")
            if (h.length != 6) return null
            val v = h.toIntOrNull(16) ?: return null
            return LinearRgb(ColorMath.linear((v shr 16) and 0xff), ColorMath.linear((v shr 8) and 0xff), ColorMath.linear(v and 0xff))
        }
    }
}

object ColorMath {
    /** D65, the reference white of sRGB and the usual target for bias lighting. */
    val WHITE = XY(0.3127, 0.3290)

    /** Philips Hue gamut C (Hue Play, newer colour bulbs). */
    val GAMUT_C = listOf(XY(0.6915, 0.3083), XY(0.17, 0.7), XY(0.1532, 0.0475))

    private val toLinearLut = DoubleArray(256) { srgbToLinear(it / 255.0) }

    fun linear(v8: Int): Double = toLinearLut[v8]

    fun srgbToLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    fun linearToSrgb(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

    /** Linear sRGB → CIE xy. Black maps to the white point. */
    fun linearRgbToXy(r: Double, g: Double, b: Double): XY {
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val sum = x + y + z
        if (sum < 1e-9) return WHITE
        return XY(x / sum, y / sum)
    }

    /** CIE xy (at full brightness) → 8 bit sRGB, used for light color swatches. */
    fun xyToSrgb8(c: XY): Triple<Int, Int, Int> {
        val bigY = 1.0
        val bigX = bigY / c.y * c.x
        val bigZ = bigY / c.y * (1 - c.x - c.y)
        var r = 3.2406 * bigX - 1.5372 * bigY - 0.4986 * bigZ
        var g = -0.9689 * bigX + 1.8758 * bigY + 0.0415 * bigZ
        var b = 0.0557 * bigX - 0.2040 * bigY + 1.0570 * bigZ
        r = max(r, 0.0); g = max(g, 0.0); b = max(b, 0.0)
        val m = max(r, max(g, b)).takeIf { it > 0 } ?: 1.0
        fun enc(v: Double) = (linearToSrgb(v / m) * 255).roundToInt().coerceIn(0, 255)
        return Triple(enc(r), enc(g), enc(b))
    }

    /** Approximate chromaticity of a black body / white light at [kelvin] (Kim et al.), for display. */
    fun kelvinToXy(kelvin: Double): XY {
        val t = kelvin.coerceIn(1667.0, 25000.0)
        val x = if (t <= 4000) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return XY(x, y)
    }

    /** Scales the distance from the white point; values > 1 make colours more vivid. */
    fun saturate(c: XY, factor: Double): XY =
        XY(WHITE.x + (c.x - WHITE.x) * factor, WHITE.y + (c.y - WHITE.y) * factor)

    /** Clamps a chromaticity into the triangle of reproducible colours. */
    fun clampToGamut(p: XY, gamut: List<XY> = GAMUT_C): XY {
        if (insideTriangle(p, gamut[0], gamut[1], gamut[2])) return p
        val candidates = listOf(
            closestOnSegment(p, gamut[0], gamut[1]),
            closestOnSegment(p, gamut[1], gamut[2]),
            closestOnSegment(p, gamut[2], gamut[0]),
        )
        return candidates.minBy { (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) }
    }

    private fun cross(o: XY, a: XY, b: XY) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun insideTriangle(p: XY, a: XY, b: XY, c: XY): Boolean {
        val d1 = cross(a, b, p)
        val d2 = cross(b, c, p)
        val d3 = cross(c, a, p)
        val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
        val hasPos = d1 > 0 || d2 > 0 || d3 > 0
        return !(hasNeg && hasPos)
    }

    private fun closestOnSegment(p: XY, a: XY, b: XY): XY {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return XY(a.x + t * dx, a.y + t * dy)
    }
}

/** Raw zone statistics measured on one frame. */
data class ZoneSample(val chroma: XY, val brightness: Double)

/**
 * Computes the colour of a screen zone from a downscaled BGRx frame.
 * Averaging is done in linear light; saturated pixels are weighted higher so that a small
 * coloured object on a grey background still tints the light.
 */
class ZoneAnalyzer(private val saturatedPixelWeight: Double) {
    fun analyze(frame: Frame, zone: Zone): ZoneSample {
        val x0 = (zone.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = (zone.right * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y0 = (zone.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = (zone.bottom * frame.height).toInt().coerceIn(y0 + 1, frame.height)

        var wr = 0.0; var wg = 0.0; var wb = 0.0; var wSum = 0.0
        var lr = 0.0; var lg = 0.0; var lb = 0.0
        val data = frame.data
        for (y in y0 until y1) {
            var i = y * frame.stride + x0 * 4
            for (x in x0 until x1) {
                val b8 = data[i].toInt() and 0xff
                val g8 = data[i + 1].toInt() and 0xff
                val r8 = data[i + 2].toInt() and 0xff
                i += 4
                val mx = max(r8, max(g8, b8))
                val mn = min(r8, min(g8, b8))
                val sat = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
                val w = 1.0 + saturatedPixelWeight * sat
                val r = ColorMath.linear(r8)
                val g = ColorMath.linear(g8)
                val b = ColorMath.linear(b8)
                wr += r * w; wg += g * w; wb += b * w; wSum += w
                lr += r; lg += g; lb += b
            }
        }
        val n = ((x1 - x0) * (y1 - y0)).toDouble()
        val chroma = ColorMath.linearRgbToXy(wr / wSum, wg / wSum, wb / wSum)
        // Perceived brightness: the strongest channel of the plain average, back in sRGB encoding.
        // Using luminance instead would make pure blue scenes nearly black.
        val brightness = ColorMath.linearToSrgb(max(lr, max(lg, lb)) / n).coerceIn(0.0, 1.0)
        return ZoneSample(chroma, brightness)
    }
}
