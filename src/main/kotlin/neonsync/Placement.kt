package neonsync

import kotlin.math.abs

/**
 * Derives what a light shows from where it stands in the room.
 *
 * A light close to the screen samples the part of the screen it is next to (a bar behind the left
 * edge gets the left edge). The further away a light is, the larger its zone gets, until a light
 * across the room samples the whole screen.
 */
object Placement {
    /** Half size of the screen in room units (16:9, width 1). */
    const val SCREEN_HALF_W = 0.5
    const val SCREEN_HALF_H = 0.28125

    /** Zone half-size for a light right at a screen edge (= 30 % strip). */
    private const val EDGE_HALF = 0.15

    /** Distance from the screen at which a light samples the whole screen. */
    private const val FULL_SCREEN_DISTANCE = 2.5

    fun zoneFor(p: Vec3): Zone {
        val u = (p.x / SCREEN_HALF_W).coerceIn(-1.0, 1.0)
        val v = (p.y / SCREEN_HALF_H).coerceIn(-1.0, 1.0)
        // How far the light is from the screen surface region, 0 = at the screen, 1 = far away
        val outside = Vec3(
            (abs(p.x) - SCREEN_HALF_W).coerceAtLeast(0.0),
            (abs(p.y) - SCREEN_HALF_H).coerceAtLeast(0.0),
            p.z,
        ).length
        val far = (outside / FULL_SCREEN_DISTANCE).coerceIn(0.0, 1.0)

        fun axis(c: Double): Pair<Double, Double> {
            val nearHalf = 0.5 + (EDGE_HALF - 0.5) * abs(c)
            val half = nearHalf + (0.5 - nearHalf) * far
            val center = 0.5 + c * (0.5 - half)
            return (center - half).coerceIn(0.0, 1.0) to (center + half).coerceIn(0.0, 1.0)
        }
        val (left, right) = axis(u)
        // Screen y grows downwards
        val (top, bottom) = axis(-v)
        return Zone(left, top, maxOf(right, left + 0.01), maxOf(bottom, top + 0.01))
    }

    /** Stereo position: -1 = left channel only, 1 = right channel only. */
    fun panFor(p: Vec3): Double = (p.x / 1.5).coerceIn(-1.0, 1.0)

    /** Spot for a newly selected light: behind the screen edges first, then around the room. */
    fun defaultPosition(index: Int): Vec3 = DEFAULTS.getOrElse(index) {
        val angle = index * 2.4
        Vec3(2.0 * kotlin.math.sin(angle), 0.5, 1.0 - 2.0 * kotlin.math.cos(angle)).clampToRoom()
    }

    /**
     * Spot for a new light that doesn't collide with [taken]. Names containing "left"/"right"
     * (like "Hue Play left") go behind that screen edge.
     */
    fun defaultPosition(name: String, taken: Collection<Vec3>): Vec3 {
        fun free(p: Vec3) = taken.none { (it - p).length < 0.1 }
        val n = name.lowercase()
        val hinted = when {
            "left" in n || "links" in n -> DEFAULTS[0]
            "right" in n || "rechts" in n -> DEFAULTS[1]
            else -> null
        }
        if (hinted != null && free(hinted)) return hinted
        // Edges are reserved for lights named left/right as long as there are other spots
        val candidates = (2 until 64).map(::defaultPosition) + DEFAULTS.take(2)
        return candidates.firstOrNull(::free) ?: defaultPosition(taken.size)
    }

    private val DEFAULTS = listOf(
        Vec3(-0.6, 0.0, -0.15),
        Vec3(0.6, 0.0, -0.15),
        Vec3(0.0, 0.4, -0.15),
        Vec3(0.0, -0.4, -0.15),
        Vec3(-1.5, 0.8, -0.5),
        Vec3(1.5, 0.8, -0.5),
    )
}
