package neonsync

import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigStoreTest {
    private val home = Files.createTempDirectory("neonsync-test")

    @Test
    fun `first start creates a private default config`() {
        val path = home.resolve("neonsync/config.json")

        val config = ConfigStore(PathTextFile(path)).loadOrCreate()

        assertEquals(Config(), config)
        assertTrue(path.exists())
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
    }

    @Test
    fun `update keeps values edited on disk`() {
        val path = home.resolve("update-test.json")
        val store = ConfigStore(PathTextFile(path))
        store.save(Config())
        // User edits a tuning value by hand while the app runs
        path.writeText(path.readText().replace("\"updatesPerSecond\": 5.0", "\"updatesPerSecond\": 8.0"))

        store.update { it.copy(setup = it.setup.copy(selectedLights = setOf("light.x"))) }

        val result = json.decodeFromString<Config>(path.readText())
        assertEquals(8.0, result.color.updatesPerSecond)
        assertEquals(setOf("light.x"), result.setup.selectedLights)
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
    }

    @Test
    fun `light states are parsed for display`() {
        fun parse(s: String) = LightState.of(json.parseToJsonElement(s).jsonObject)

        val off = parse("""{"entity_id":"light.a","state":"off","attributes":{}}""")
        assertEquals(0.0, off.color!!.brightness)

        val xy = parse("""{"entity_id":"light.a","state":"on","attributes":{"xy_color":[0.6,0.3],"brightness":51}}""")
        assertEquals(0.6, xy.color!!.x)
        assertEquals(0.2, xy.color!!.brightness, 1e-9)

        val ct = parse("""{"entity_id":"light.a","state":"on","attributes":{"color_temp_kelvin":2700,"brightness":255}}""")
        assertTrue(ct.color!!.x > 0.44, "2700 K is warm: ${ct.color}")

        assertNull(parse("""{"entity_id":"light.a","state":"unavailable","attributes":{}}""").color)
    }

    @Test
    fun `lights react to all bands unless restricted`() {
        val setup = Setup(lightBands = mapOf("light.a" to setOf(Band.BASS), "light.b" to emptySet()))
        assertEquals(setOf(Band.BASS), setup.bandsOf("light.a"))
        assertEquals(Band.ALL, setup.bandsOf("light.b"))
        assertEquals(Band.ALL, setup.bandsOf("light.c"))
        val roundTrip = json.decodeFromString<Setup>(json.encodeToString(setup))
        assertEquals(setup, roundTrip)
    }

    @Test
    fun `palette uses the configured band colours`() {
        val p = AudioPalette.of(Setup(audioBassColor = "#0000ff"))
        assertNull(p.fixed)
        assertEquals(LinearRgb(0.0, 0.0, 1.0), p.bass)
        val fixed = AudioPalette.of(Setup(audioColorMode = AudioColorMode.FIXED, audioFixedColor = "#ff0000"))
        assertNotNull(fixed.fixed)
        assertTrue(fixed.fixed!!.x > 0.6)
    }
}
