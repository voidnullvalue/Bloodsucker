package com.bloodsucker.home.data

import com.bloodsucker.home.model.SmartDevice
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Explicit contract for the single ESPHome monochromatic/dimmable light we
 * support. Nothing here infers another writable topic or another device from
 * light-shaped payloads.
 */
object EsphomeLight {
    const val KEY = "esphome:geo-lamp"
    const val NAME = "Geo Lamp"
    const val STATE_TOPIC = "geo-lamp/light/state"
    const val COMMAND_TOPIC = "geo-lamp/light/command"
    const val AVAILABILITY_TOPIC = "geo-lamp/status"

    val topics: Set<String> = setOf(STATE_TOPIC, COMMAND_TOPIC, AVAILABILITY_TOPIC)
    val effects: List<String> = listOf("None", "Campfire", "Candle", "Turbulent Flame")

    fun isGeoDevice(device: SmartDevice) = device.key == KEY && device.kind == com.bloodsucker.home.model.DeviceKind.ESPHOME

    /** Parses only the documented partial JSON/plain ON/OFF state payload. */
    fun parseState(payload: String): EsphomeLightValues? {
        val raw = payload.trim()
        if (raw.isEmpty()) return null
        when (raw.uppercase()) {
            "ON" -> return EsphomeLightValues(power = true)
            "OFF" -> return EsphomeLightValues(power = false)
        }
        if (!raw.startsWith("{")) return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        var changed = false
        var power: Boolean? = null
        var brightness: Int? = null
        var effect: String? = null

        if (obj.has("state")) {
            val state = obj.opt("state")
            if (state is String) when (state.trim().uppercase()) {
                "ON" -> { power = true; changed = true }
                "OFF" -> { power = false; changed = true }
            }
        }
        if (obj.has("brightness")) {
            val value = obj.opt("brightness")
            // Contract: a JSON integer only. Strings, booleans, fractions, and
            // values outside 0..255 are invalid and preserve the prior value.
            val parsed = if (value is Int) value
                else if (value is Long) value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                else null
            if (parsed != null && parsed in 0..255) { brightness = parsed; changed = true }
        }
        if (obj.has("effect")) {
            val value = obj.opt("effect")
            if (value is String) {
                val candidate = value.trim()
                if (candidate in effects) { effect = candidate; changed = true }
            }
        }
        return if (changed) EsphomeLightValues(power, brightness, effect) else null
    }

    /** Returns null for malformed/unknown availability payloads so state is preserved. */
    fun parseAvailability(payload: String): Boolean? = when (payload.trim().lowercase()) {
        "online" -> true
        "offline" -> false
        else -> null
    }

    fun powerPayload(on: Boolean): String = """{"state":"${if (on) "ON" else "OFF"}"}"""

    fun brightnessPayload(brightness: Int): String? = brightness.takeIf { it in 0..255 }
        ?.let { """{"state":"ON","brightness":$it}""" }

    fun effectPayload(effect: String): String? = effect.takeIf { it in effects }
        ?.let { """{"state":"ON","effect":"$it"}""" }

    fun percentToBrightness(percent: Int): Int = (percent.coerceIn(0, 100) * 255 / 100.0).roundToInt()

    fun brightnessToPercent(brightness: Int): Int = (brightness.coerceIn(0, 255) * 100 / 255.0).roundToInt()

    fun displayLabel(effect: String?): String = when (effect) {
        null -> "Not reported"
        "None" -> "Steady illumination"
        else -> effect
    }

    fun isFlameEffect(effect: String?): Boolean = effect != null && effect != "None" && effect in effects
}

data class EsphomeLightValues(
    val power: Boolean? = null,
    val brightness: Int? = null,
    val effect: String? = null
)
