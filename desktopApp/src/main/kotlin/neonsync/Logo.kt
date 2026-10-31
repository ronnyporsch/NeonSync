package neonsync

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import org.jetbrains.skia.svg.SVGLengthUnit

/** The app logo (`neonsync.svg` on the classpath), rendered with Skia for the tray and the window icon. */
object Logo {
    val svg: ByteArray by lazy {
        Logo::class.java.getResourceAsStream("/neonsync.svg")?.use { it.readBytes() } ?: error("neonsync.svg missing from resources")
    }

    /** Renders a square bitmap of [size] pixels. */
    fun render(size: Int): Bitmap {
        val dom = SVGDOM(Data.makeFromBytes(svg))
        dom.root?.width = SVGLength(size.toFloat(), SVGLengthUnit.PX)
        dom.root?.height = SVGLength(size.toFloat(), SVGLengthUnit.PX)
        val surface = Surface.makeRaster(ImageInfo.makeN32Premul(size, size))
        dom.render(surface.canvas)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
        surface.makeImageSnapshot().readPixels(bitmap)
        return bitmap
    }

    /** ARGB32 in network byte order, non-premultiplied, as StatusNotifierItem expects. */
    fun argb(size: Int): ByteArray {
        val bgra = render(size).readPixels() ?: error("could not read logo pixels")
        val out = ByteArray(bgra.size)
        for (i in 0 until size * size) {
            val o = i * 4
            out[o] = bgra[o + 3] // A
            out[o + 1] = bgra[o + 2] // R
            out[o + 2] = bgra[o + 1] // G
            out[o + 3] = bgra[o] // B
        }
        return out
    }
}
