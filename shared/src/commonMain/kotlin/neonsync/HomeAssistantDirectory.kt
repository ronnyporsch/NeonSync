package neonsync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class HaArea(val id: String, val name: String)

/** What a light actually shows according to Home Assistant. */
data class LightState(
    /** "on", "off", "unavailable", … */
    val state: String,
    /** Colour for display; brightness 0..1, 0 = off. Null when unavailable/unknown. */
    val color: LightColor?,
) {
    companion object {
        /** Parses an HA state object (`{entity_id, state, attributes}`). */
        fun of(stateObject: JsonObject): LightState {
            val state = stateObject.str("state") ?: "unknown"
            val attrs = stateObject["attributes"] as? JsonObject
            val color = when (state) {
                "off" -> LightColor(ColorMath.WHITE.x, ColorMath.WHITE.y, 0.0)
                "on" -> {
                    val xy = (attrs?.get("xy_color") as? JsonArray)?.takeIf { it.size == 2 }
                        ?.let { XY(it[0].jsonPrimitive.content.toDouble(), it[1].jsonPrimitive.content.toDouble()) }
                        ?: attrs?.str("color_temp_kelvin")?.toDoubleOrNull()?.let(ColorMath::kelvinToXy)
                        ?: ColorMath.kelvinToXy(2700.0) // brightness-only lights: warm white
                    val bri = attrs?.str("brightness")?.toDoubleOrNull()?.let { it / 255.0 } ?: 1.0
                    LightColor(xy.x, xy.y, bri.coerceIn(0.01, 1.0))
                }
                else -> null
            }
            return LightState(state, color)
        }

        private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    }
}

data class HaLight(
    val entityId: String,
    val name: String,
    val areaId: String?,
    val state: LightState,
    val colorModes: List<String>,
) {
    val supportsColor get() = colorModes.any { it in COLOR_MODES }
    val available get() = state.state != "unavailable"

    companion object {
        private val COLOR_MODES = setOf("xy", "hs", "rgb", "rgbw", "rgbww")
    }
}

/** All lights of a Home Assistant instance with their areas. */
data class LightDirectory(val areas: List<HaArea>, val lights: List<HaLight>) {
    val areaOf: Map<String, String?> by lazy { lights.associate { it.entityId to it.areaId } }
    val byId: Map<String, HaLight> by lazy { lights.associateBy { it.entityId } }

    /**
     * Lights that react: selected individually or through an area, and able to show colours.
     * Before the list is loaded, individually selected lights are taken as they are.
     */
    fun active(setup: Setup): Set<String> =
        if (lights.isEmpty()) setup.selectedLights else setup.activeLights(areaOf).filter { byId[it]?.supportsColor == true }.toSet()

    companion object {
        val EMPTY = LightDirectory(emptyList(), emptyList())

        private val log = logger("LightDirectory")

        /**
         * Reads lights from the state machine, areas from the registries. An entity's own area wins
         * over the area of its device, like in the HA UI. Registry access needs an admin user; without
         * it all lights end up unassigned.
         */
        suspend fun load(ha: HomeAssistantClient): LightDirectory {
            val states = ha.getStates().filter { it.str("entity_id")?.startsWith("light.") == true }
            val (areas, entityArea) = try {
                val areas = ha.list("config/area_registry/list").map { HaArea(it.str("area_id")!!, it.str("name") ?: it.str("area_id")!!) }
                val deviceArea = ha.list("config/device_registry/list").associate { it.str("id") to it.str("area_id") }
                val entities = ha.list("config/entity_registry/list")
                val entityArea = entities.associate { e ->
                    e.str("entity_id") to (e.str("area_id") ?: deviceArea[e.str("device_id")])
                }
                areas to entityArea
            } catch (e: Exception) {
                log.warn("Could not read area registry: {}", e.message)
                emptyList<HaArea>() to emptyMap()
            }
            val knownAreas = areas.map { it.id }.toSet()
            val lights = states.map { s ->
                val attrs = s["attributes"] as? JsonObject
                val id = s.str("entity_id")!!
                HaLight(
                    entityId = id,
                    name = attrs?.str("friendly_name") ?: id,
                    areaId = entityArea[id]?.takeIf { it in knownAreas },
                    state = LightState.of(s),
                    colorModes = (attrs?.get("supported_color_modes") as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
            }.sortedBy { it.name.lowercase() }
            return LightDirectory(areas.sortedBy { it.name.lowercase() }, lights)
        }

        private suspend fun HomeAssistantClient.list(type: String): List<JsonObject> =
            (request(buildJsonObject { put("type", type) }) as? JsonArray)?.map { it.jsonObject } ?: emptyList()

        private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    }
}
