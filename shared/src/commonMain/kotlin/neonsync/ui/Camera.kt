package neonsync.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import neonsync.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** A projected point: position on the canvas and distance along the view direction. */
data class Projected(val pos: Offset, val depth: Double)

/** Orbit camera around [target] with a simple pinhole projection. */
data class Camera(
    val yaw: Double = 0.55,
    val pitch: Double = 0.42,
    val distance: Double = 4.6,
    val target: Vec3 = Vec3(0.0, -0.25, 0.5),
) {
    val eye: Vec3 get() = target + Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance
    private val forward: Vec3 get() = (target - eye).normalized()
    private val right: Vec3 get() = (forward cross Vec3(0.0, 1.0, 0.0)).normalized()
    private val up: Vec3 get() = right cross forward

    private fun focal(size: Size) = min(size.width, size.height) * 1.15

    fun project(p: Vec3, size: Size): Projected? {
        val v = p - eye
        val z = v dot forward
        if (z < 0.1) return null
        val f = focal(size)
        return Projected(Offset((size.width / 2 + (v dot right) / z * f).toFloat(), (size.height / 2 - (v dot up) / z * f).toFloat()), z)
    }

    /** Ray from the eye through a canvas point. */
    fun ray(point: Offset, size: Size): Pair<Vec3, Vec3> {
        val f = focal(size)
        val dx = (point.x - size.width / 2) / f
        val dy = -(point.y - size.height / 2) / f
        return eye to (forward + right * dx + up * dy).normalized()
    }

    /** Intersection of a canvas ray with a plane, or null if (nearly) parallel or behind the camera. */
    fun intersect(point: Offset, size: Size, planePoint: Vec3, planeNormal: Vec3): Vec3? {
        val (o, d) = ray(point, size)
        val denom = d dot planeNormal
        if (abs(denom) < 1e-6) return null
        val t = ((planePoint - o) dot planeNormal) / denom
        return if (t > 0) o + d * t else null
    }

    /** Normal of a vertical plane facing the camera, for height dragging. */
    val verticalPlaneNormal: Vec3 get() = Vec3(forward.x, 0.0, forward.z).normalized()

    fun orbit(dx: Float, dy: Float) = copy(
        yaw = yaw - dx * 0.008,
        pitch = (pitch + dy * 0.008).coerceIn(0.05, 1.45),
    )

    fun zoom(steps: Float) = copy(distance = (distance * 1.1.pow(steps.toDouble())).coerceIn(2.5, 16.0))
}
