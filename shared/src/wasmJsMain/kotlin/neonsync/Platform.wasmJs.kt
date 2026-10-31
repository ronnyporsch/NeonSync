package neonsync

actual fun logger(name: String): Logger = object : Logger {
    override fun debug(message: String, vararg args: Any?) {}
    override fun info(message: String, vararg args: Any?) = println(format("INFO", message, args))
    override fun warn(message: String, vararg args: Any?) = println(format("WARN", message, args))
    override fun error(message: String, vararg args: Any?) = println(format("ERROR", message, args))

    private fun format(level: String, message: String, args: Array<out Any?>): String {
        val error = args.lastOrNull() as? Throwable
        val text = "$level $name - ${formatLogMessage(message, args)}"
        return if (error != null) text + "\n" + error.stackTraceToString() else text
    }
}

// The browser runs everything on one thread
private class SingleThreadedMap<K, V>(private val map: MutableMap<K, V> = HashMap()) : MutableMap<K, V> by map, ConcurrentMap<K, V> {
    override fun putIfAbsent(key: K, value: V): V? = get(key) ?: null.also { put(key, value) }
}

actual fun <K, V> concurrentMapOf(): ConcurrentMap<K, V> = SingleThreadedMap()

/** Stored in the browser's local storage, which only this site can read. */
class LocalStorageTextFile(private val key: String) : TextFile {
    override val location get() = "browser local storage ($key)"
    override fun readText(): String? = localStorageGet(key)
    override fun writeText(text: String) = localStorageSet(key, text)
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun localStorageGet(key: String): String? = js("localStorage.getItem(key)")
@OptIn(ExperimentalWasmJsInterop::class)
private fun localStorageSet(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
