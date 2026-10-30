package neonsync

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlacementTest {
    private fun assertNear(expected: Double, actual: Double, eps: Double = 0.02) =
        assertTrue(abs(expected - actual) < eps, "expected $expected but was $actual")

    @Test
    fun `bar behind the left edge samples the left strip`() {
        val z = Placement.zoneFor(Vec3(-0.6, 0.0, -0.1))
        assertNear(0.0, z.left)
        assertTrue(z.right < 0.4, "right=${z.right}")
        assertNear(0.0, z.top)
        assertNear(1.0, z.bottom)
    }

    @Test
    fun `light above the screen samples the top`() {
        val z = Placement.zoneFor(Vec3(0.0, 0.5, -0.1))
        assertNear(0.0, z.top)
        assertTrue(z.bottom < 0.4)
        assertNear(0.0, z.left)
        assertNear(1.0, z.right)
    }

    @Test
    fun `far light samples the whole screen`() {
        val z = Placement.zoneFor(Vec3(-2.5, 1.5, 3.0))
        assertEquals(Zone(0.0, 0.0, 1.0, 1.0), z)
    }

    @Test
    fun `left and right lights get their screen edge`() {
        assertEquals(-0.6, Placement.defaultPosition("Hue Play left", emptyList()).x)
        assertEquals(0.6, Placement.defaultPosition("Hue Play right", emptyList()).x)
        val other = Placement.defaultPosition("Desk lamp", emptyList())
        assertTrue(abs(other.x) < 0.5, "edges stay free for left/right lights: $other")
        val taken = listOf(Placement.defaultPosition("Hue Play left", emptyList()))
        assertTrue(Placement.defaultPosition("Another left", taken) != taken[0])
    }

    @Test
    fun `pan follows x`() {
        assertEquals(-1.0, Placement.panFor(Vec3(-2.0, 0.0, 0.0)))
        assertEquals(0.0, Placement.panFor(Vec3(0.0, 1.0, 1.0)))
    }

    @Test
    fun `active lights combine areas and single lights`() {
        val setup = Setup(selectedAreas = setOf("living"), selectedLights = setOf("light.desk"))
        val areas = mapOf("light.a" to "living", "light.b" to "living", "light.c" to "kitchen", "light.desk" to null)
        assertEquals(setOf("light.a", "light.b", "light.desk"), setup.activeLights(areas))
    }
}
