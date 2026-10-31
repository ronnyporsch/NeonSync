package neonsync

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMathTest {
    private fun assertNear(expected: Double, actual: Double, eps: Double = 0.002) =
        assertTrue(abs(expected - actual) < eps, "expected $expected but was $actual")

    @Test
    fun `white maps to D65`() {
        val xy = ColorMath.linearRgbToXy(1.0, 1.0, 1.0)
        assertNear(0.3127, xy.x)
        assertNear(0.3290, xy.y)
    }

    @Test
    fun `sRGB primaries map to their chromaticities`() {
        ColorMath.linearRgbToXy(1.0, 0.0, 0.0).let { assertNear(0.64, it.x); assertNear(0.33, it.y) }
        ColorMath.linearRgbToXy(0.0, 1.0, 0.0).let { assertNear(0.30, it.x); assertNear(0.60, it.y) }
        ColorMath.linearRgbToXy(0.0, 0.0, 1.0).let { assertNear(0.15, it.x); assertNear(0.06, it.y) }
    }

    @Test
    fun `points inside the gamut are unchanged`() {
        assertEquals(ColorMath.WHITE, ColorMath.clampToGamut(ColorMath.WHITE))
    }

    @Test
    fun `points outside the gamut land on its edge`() {
        // Far beyond the red corner
        val p = ColorMath.clampToGamut(XY(0.9, 0.3))
        assertNear(0.6915, p.x, 0.02)
        assertNear(0.3083, p.y, 0.02)
    }

    @Test
    fun `xy round trip to sRGB`() {
        assertEquals(Triple(255, 0, 0), ColorMath.xyToSrgb8(ColorMath.linearRgbToXy(1.0, 0.0, 0.0)).let { (r, g, b) -> Triple(r, g / 10, b / 10) })
        val (r, g, b) = ColorMath.xyToSrgb8(ColorMath.WHITE)
        assertTrue(r > 250 && g > 250 && b > 250)
    }

    @Test
    fun `zone analysis of a split frame`() {
        // Left half red, right half blue, BGRx
        val w = 8
        val h = 2
        val data = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            if (x < w / 2) data[i + 2] = 0xff.toByte() else data[i] = 0xff.toByte()
        }
        val frame = Frame(w, h, w * 4, data)
        val analyzer = ZoneAnalyzer(3.0)
        val left = analyzer.analyze(frame, Zone(0.0, 0.0, 0.5, 1.0))
        val right = analyzer.analyze(frame, Zone(0.5, 0.0, 1.0, 1.0))
        assertNear(0.64, left.chroma.x)
        assertNear(0.15, right.chroma.x)
        assertNear(1.0, left.brightness)
    }

    @Test
    fun `black frame has zero brightness`() {
        val frame = Frame(4, 4, 16, ByteArray(64))
        val s = ZoneAnalyzer(3.0).analyze(frame, Zone(0.0, 0.0, 1.0, 1.0))
        assertEquals(0.0, s.brightness)
        assertEquals(ColorMath.WHITE, s.chroma)
    }
}
