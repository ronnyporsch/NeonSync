package neonsync

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

actual fun logger(name: String): Logger {
    val log = LoggerFactory.getLogger(name)
    return object : Logger {
        override fun debug(message: String, vararg args: Any?) = log.debug(message, *args)
        override fun info(message: String, vararg args: Any?) = log.info(message, *args)
        override fun warn(message: String, vararg args: Any?) = log.warn(message, *args)
        override fun error(message: String, vararg args: Any?) = log.error(message, *args)
    }
}

private class ConcurrentHashMapImpl<K : Any, V : Any> : ConcurrentHashMap<K, V>(), ConcurrentMap<K, V>

@Suppress("UNCHECKED_CAST")
actual fun <K, V> concurrentMapOf(): ConcurrentMap<K, V> = ConcurrentHashMapImpl<Any, Any>() as ConcurrentMap<K, V>

/**
 * A file on disk. Writes atomically (the UI saves often, e.g. while dragging lights) and, if
 * [private], readable by the owner only.
 */
class PathTextFile(val path: Path, private val private: Boolean = true) : TextFile {
    override val location get() = path.toString()

    override fun readText(): String? = if (path.exists()) path.readText() else null

    override fun writeText(text: String) {
        path.parent.createDirectories()
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        tmp.writeText(text)
        if (private) Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
