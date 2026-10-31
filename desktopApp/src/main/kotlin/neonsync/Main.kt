package neonsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import neonsync.ui.runUi
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import kotlin.system.exitProcess

private const val USAGE = """Usage: neonsync [command]

Commands:
  run           Start NeonSync with window and tray icon (default)
                --tray: start in the tray only (used for autostart)
  list-lights   List light entities known to Home Assistant
  test-lights   Flash the selected lights red, green and blue one after another
  help          Show this help
"""

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "run"
    try {
        when (command) {
            "run" -> run(showWindow = "--tray" !in args)
            "list-lights" -> withHomeAssistant { ha -> listLights(ha) }
            "test-lights" -> withHomeAssistant { ha -> testLights(ha) }
            "help", "-h", "--help" -> print(USAGE)
            else -> {
                System.err.print(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: ConfigException) {
        System.err.println(e.message)
        exitProcess(1)
    }
}

private fun run(showWindow: Boolean) {
    val bus = DBusConnectionBuilder.forSessionBus().build()
    if (!SingleInstance.acquire(bus, focusRunning = showWindow)) {
        println(if (showWindow) "NeonSync is already running, focusing its window." else "NeonSync is already running.")
        bus.disconnect()
        return
    }
    setWindowClass("neonsync")
    val config = AppPaths.configStore.loadOrCreate()
    val platform = Platform(
        configFile = PathTextFile(AppPaths.configFile),
        screen = PortalScreenSource(bus, config.capture),
        audio = PipeWireAudio(config.audio),
        openConfigFile = { ProcessBuilder("xdg-open", AppPaths.configFile.toString()).start() },
    )
    val app = NeonSyncApp(config, platform)
    val shell = DesktopShell(app, bus)
    SingleInstance.export(bus) { shell.showWindow() }
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking { app.shutdown() }
        shell.stop()
        bus.disconnect()
    })
    app.start()
    shell.start(showWindow = showWindow || app.needsSetup)
    // Compose needs to own this thread; returns only if the UI terminates unexpectedly
    runUi(app, shell)
    shell.quit()
}

/**
 * AWT derives the X11 WM_CLASS from the main class ("neonsync-MainKt"). KDE uses it to match the
 * window to neonsync.desktop (taskbar icon and name), so set it to the desktop file name.
 */
private fun setWindowClass(name: String) {
    try {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, name)
    } catch (_: Exception) {
        // Not X11/XWayland or not opened (see --add-opens in build.gradle.kts); only cosmetic
    }
}

private fun withHomeAssistant(block: suspend (HomeAssistantClient) -> Unit) {
    val config = AppPaths.configStore.load()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ha = HomeAssistantClient(config.homeAssistant, scope)
    try {
        runBlocking {
            ha.start()
            try {
                ha.awaitConnected()
            } catch (_: Exception) {
                System.err.println("Could not connect to Home Assistant at ${config.homeAssistant.url}")
                exitProcess(1)
            }
            block(ha)
        }
    } finally {
        ha.close()
        scope.cancel()
    }
}

private suspend fun listLights(ha: HomeAssistantClient) {
    val lights = ha.getStates().filter { it["entity_id"]?.jsonPrimitive?.content?.startsWith("light.") == true }
    if (lights.isEmpty()) {
        println("No light entities found.")
        return
    }
    println("%-45s %-12s %-30s %s".format("ENTITY", "STATE", "NAME", "COLOR MODES"))
    for (l in lights.sortedBy { it["entity_id"]!!.jsonPrimitive.content }) {
        val attrs = l["attributes"]?.jsonObject
        val modes = attrs?.get("supported_color_modes")?.let { runCatching { it.jsonArray.joinToString(",") { m -> m.jsonPrimitive.content } }.getOrNull() } ?: ""
        println(
            "%-45s %-12s %-30s %s".format(
                l["entity_id"]!!.jsonPrimitive.content,
                l["state"]?.jsonPrimitive?.content,
                attrs?.get("friendly_name")?.jsonPrimitive?.content ?: "",
                modes,
            ),
        )
    }
}

private suspend fun testLights(ha: HomeAssistantClient) {
    val setup = AppPaths.configStore.loadOrCreate().setup
    val directory = LightDirectory.load(ha)
    val colors = listOf("red" to listOf(0.6915, 0.3083), "green" to listOf(0.17, 0.7), "blue" to listOf(0.1532, 0.0475))
    val ids = setup.activeLights(directory.areaOf).sorted()
    if (ids.isEmpty()) println("No lights selected. Select some in the Lights tab of the app.")
    for (id in ids) {
        println("$id (position ${setup.placements[id]})")
        for ((name, xy) in colors) {
            println("  $name")
            ha.callService(
                "light", "turn_on", id,
                buildJsonObject {
                    putJsonArray("xy_color") { xy.forEach { add(JsonPrimitive(it)) } }
                    put("brightness", 200)
                    put("transition", 0)
                },
            )
            delay(1000)
        }
    }
    println("Done.")
}
