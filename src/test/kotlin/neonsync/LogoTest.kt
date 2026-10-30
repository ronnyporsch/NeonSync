package neonsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogoTest {
    @Test
    fun `logo renders to tray pixmaps`() {
        val size = 32
        val argb = Logo.argb(size)
        assertEquals(size * size * 4, argb.size)
        // Corners are transparent, the ring is opaque
        assertEquals(0, argb[0].toInt() and 0xff)
        val opaque = (0 until size * size).count { (argb[it * 4].toInt() and 0xff) > 200 }
        assertTrue(opaque > size * size / 10, "too few opaque pixels: $opaque")
    }
}
