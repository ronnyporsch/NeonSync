package neonsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogoTest {
    @Test
    fun `logo renders`() {
        val size = 32
        val bgra = Logo.render(size).readPixels() ?: error("could not read logo pixels")
        assertEquals(size * size * 4, bgra.size)
        // Corners are transparent, the ring is opaque
        assertEquals(0, bgra[3].toInt() and 0xff)
        val opaque = (0 until size * size).count { (bgra[it * 4 + 3].toInt() and 0xff) > 200 }
        assertTrue(opaque > size * size / 10, "too few opaque pixels: $opaque")
    }
}
