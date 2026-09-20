package com.bloodsucker.home.discovery

import com.bloodsucker.home.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

data class DiscoveryResult(val device: SmartDevice? = null, val forecast: Forecast? = null, val recognized: Boolean = false)

object TopicDiscovery {
    private val secret = Regex("(^|/)(password|passwd|secret|token|apikey|api_key|credential|auth)(/|$)", RegexOption.IGNORE_CASE)
    private val wled = Regex("^wled/([0-9a-fA-F]{6})/(status|g|c|v)$")
    private val matter = Regex("^matter/(\\d+)/(\\d+)/(\\d+)/(\\d+)$")
    private val govee = Regex("^ble/(?:[^/]+/)?([^/]+?)(?:/(?:advertisement|state))?$")
    private val wol = Regex("^wol/([0-9a-fA-F]{12})/$")
    private val gatewaySwitch = Regex("^gateway/device/(switch-[1-4])/switch/state$")
    private val forecast = Regex("^weather/wttr/forecast/(\\d+)/(date|max_temp_f|min_temp_f|sunrise|sunset)$")
    private val metrics = setOf("temperature", "temp", "humidity", "pressure", "battery", "voltage", "current", "power", "energy", "illuminance", "co2", "pm25", "moisture", "occupancy", "motion")
    private val matterHiddenClusters = setOf(3, 29, 31, 40, 48, 49, 51, 52, 53, 54, 60, 62)

    fun parse(topic: String, bytes: ByteArray, now: Long = System.currentTimeMillis()): DiscoveryResult {
        if (topic.startsWith("\$SYS/") || secret.containsMatchIn(topic) || bytes.size > 64 * 1024) return DiscoveryResult()
        val payload = bytes.toString(Charsets.UTF_8).trim()
        wled.matchEntire(topic)?.let { m ->
            val id = m.groupValues[1].uppercase()
            val leaf = m.groupValues[2]
            val readings = mutableListOf<Reading>()
            var online = true; var level: Int? = null; var color: String? = null; var name = "WLED $id"
            when (leaf) {
                "status" -> online = payload.equals("online", true)
                "g" -> level = payload.toIntOrNull()?.coerceIn(0, 255)
                "c" -> color = payload.takeIf { it.matches(Regex("#[0-9a-fA-F]{6,8}")) }
                "v" -> {
                    Regex("<ds>(.*?)</ds>", RegexOption.IGNORE_CASE).find(payload)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { name = it }
                    for ((tag, label) in listOf("fx" to "Effect", "fp" to "Palette", "ps" to "Preset", "sx" to "Speed", "ix" to "Intensity"))
                        Regex("<$tag>(.*?)</$tag>", RegexOption.IGNORE_CASE).find(payload)?.groupValues?.get(1)?.let { readings += Reading(label, it) }
                }
            }
            return DiscoveryResult(SmartDevice("wled:$id", DeviceKind.WLED, name, online = online, lastSeen = now, readings = readings, level = level, color = color), recognized = true)
        }
        gatewaySwitch.matchEntire(topic)?.let { m ->
            val id = m.groupValues[1]
            val on = payload.equals("on", true) || payload == "1"
            return DiscoveryResult(SmartDevice("gateway:$id", DeviceKind.SWITCH, "Meross switch ${id.substringAfter('-')}", lastSeen = now, power = on), recognized = true)
        }
        wol.matchEntire(topic)?.let { m ->
            val mac = m.groupValues[1].uppercase()
            return DiscoveryResult(SmartDevice("wol:$mac", DeviceKind.WAKE, "Wake ${mac.takeLast(4)}", lastSeen = now, detail = "Ready to wake"), recognized = true)
        }
        forecast.matchEntire(topic)?.let { m ->
            val i = m.groupValues[1].toInt(); val leaf = m.groupValues[2]
            val f = when (leaf) { "date" -> Forecast(i, date = payload); "max_temp_f" -> Forecast(i, high = payload); "min_temp_f" -> Forecast(i, low = payload); "sunrise" -> Forecast(i, sunrise = payload); else -> Forecast(i, sunset = payload) }
            return DiscoveryResult(forecast = f, recognized = true)
        }
        if (topic.startsWith("weather/wttr/current/")) {
            val metric = topic.substringAfterLast('/'); val label = friendly(metric)
            val value = weatherValue(metric, payload)
            return DiscoveryResult(SmartDevice("weather:wttr", DeviceKind.WEATHER, "Weather", lastSeen = now, readings = listOf(Reading(label, value))), recognized = true)
        }
        matter.matchEntire(topic)?.let { m ->
            val node = m.groupValues[1].toInt(); val endpoint = m.groupValues[2].toInt(); val cluster = m.groupValues[3].toInt(); val attr = m.groupValues[4].toInt()
            if (endpoint == 0 || cluster in matterHiddenClusters || attr >= 65528) return DiscoveryResult(recognized = true)
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return DiscoveryResult(recognized = true)
            val raw = obj.opt("value") ?: return DiscoveryResult(recognized = true)
            val label = obj.optString("attribute").takeIf { it.isNotBlank() } ?: matterLabel(cluster, attr)
            val value = matterValue(cluster, attr, raw)
            return DiscoveryResult(SmartDevice("matter:$node:$endpoint", DeviceKind.MATTER, if (node == 1 && endpoint == 1) "Air purifier" else "Matter device $node", lastSeen = now, readings = listOf(Reading(label, value)), power = if (cluster == 6 && attr == 0) asBoolean(raw) else null, level = if (cluster == 514 && attr in setOf(2, 3)) raw.toString().toIntOrNull() else null), recognized = true)
        }
        if (topic.startsWith("ble/")) parseGovee(topic, payload, now)?.let { return DiscoveryResult(it, recognized = true) }
        val leaf = topic.substringAfterLast('/').lowercase()
        if (leaf in metrics && '/' in topic && !topic.startsWith("gateway/")) {
            val prefix = topic.substringBeforeLast('/'); var value = payload
            if (leaf == "temperature") payload.toDoubleOrNull()?.let { value = "%.1f°F".format(Locale.US, it * 9 / 5 + 32) }
            return DiscoveryResult(SmartDevice("sensor:$prefix", DeviceKind.SENSOR, prefix.substringAfterLast('/').replaceFirstChar(Char::uppercase), lastSeen = now, readings = listOf(Reading(friendly(leaf), value))), recognized = true)
        }
        return DiscoveryResult()
    }

    private fun parseGovee(topic: String, payload: String, now: Long): SmartDevice? {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val rawMac = obj.optString("mac", govee.matchEntire(topic)?.groupValues?.getOrNull(1).orEmpty())
        val mac = rawMac.filter(Char::isLetterOrDigit).uppercase().takeIf { it.length == 12 } ?: return null
        val name = obj.optString("name", obj.optString("alias", "Govee ${mac.takeLast(4)}"))
        val isGovee = name.contains("govee", true) || name.startsWith("GV", true) || payload.contains("EC88", true) || payload.contains("60552")
        if (!isGovee) return null
        val model = Regex("H\\d{4}", RegexOption.IGNORE_CASE).find(name)?.value?.uppercase()
        val bytes = manufacturerBytes(obj, if (model == "H5179") listOf("1", "0x0001") else listOf("60552", "0xEC88"))
        val readings = mutableListOf<Reading>()
        if (bytes != null && model in setOf("H5075", "H5179")) {
            val offset = if (model == "H5179") 2 else 1
            if (bytes.size > offset + 3) {
                var n = (bytes[offset].toInt() and 255 shl 16) or (bytes[offset + 1].toInt() and 255 shl 8) or (bytes[offset + 2].toInt() and 255)
                val negative = n and 0x800000 != 0; n = n and 0x7fffff
                var c = (n / 1000) / 10.0; if (negative) c = -c
                readings += Reading("Temperature", "%.1f°F".format(Locale.US, c * 9 / 5 + 32))
                readings += Reading("Humidity", "%.1f%%".format(Locale.US, (n % 1000) / 10.0))
                readings += Reading("Battery", "${bytes[offset + 3].toInt() and 0x7f}%")
            }
        }
        obj.opt("rssi")?.let { readings += Reading("Signal", "$it dBm") }
        return SmartDevice("govee:$mac", DeviceKind.GOVEE, name.ifBlank { "Govee ${mac.takeLast(4)}" }, lastSeen = now, readings = readings, supported = model in setOf("H5075", "H5179"), detail = if (readings.isEmpty()) "Unsupported sensor" else model.orEmpty())
    }

    private fun manufacturerBytes(obj: JSONObject, keys: List<String>): ByteArray? {
        val containers = listOf("manufacturerData", "manufacturer_data", "manufacturerdata").mapNotNull { obj.optJSONObject(it) }
        val v = containers.firstNotNullOfOrNull { c -> keys.firstNotNullOfOrNull { k -> if (c.has(k)) c.opt(k) else null } } ?: keys.firstNotNullOfOrNull { k -> if (obj.has(k)) obj.opt(k) else null }
        return when (v) {
            is JSONArray -> ByteArray(v.length()) { v.optInt(it).toByte() }
            is String -> v.replace(Regex("[^0-9A-Fa-f]"), "").takeIf { it.length % 2 == 0 }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            else -> null
        }
    }
    private fun asBoolean(v: Any) = v == true || v.toString() == "1" || v.toString().equals("true", true)
    private fun matterValue(cluster: Int, attr: Int, v: Any): String = when {
        cluster == 6 && attr == 0 -> if (asBoolean(v)) "On" else "Off"
        cluster == 91 && attr == 0 -> listOf("Unknown", "Good", "Fair", "Moderate", "Poor", "Very poor", "Extremely poor").getOrElse(v.toString().toIntOrNull() ?: 0) { "Unknown" }
        cluster == 1026 && attr == 0 -> "%.1f°F".format(Locale.US, (v.toString().toDoubleOrNull() ?: 0.0) / 100 * 9 / 5 + 32)
        cluster == 1029 && attr == 0 -> "%.1f%%".format(Locale.US, (v.toString().toDoubleOrNull() ?: 0.0) / 100)
        cluster == 113 || cluster == 514 -> if (v is Number) "${v.toDouble().roundToInt()}%" else v.toString()
        else -> v.toString()
    }
    private fun matterLabel(c: Int, a: Int) = when (c) { 6 -> "Power"; 91 -> "Air quality"; 113 -> if (a == 0) "Filter condition" else "Filter"; 514 -> "Fan"; 1026 -> "Temperature"; 1029 -> "Humidity"; else -> "Status" }
    private fun weatherValue(metric: String, value: String) = when { metric.contains("temperature") || metric.contains("feels_like") -> "$value°F"; metric == "humidity" || metric == "cloud_cover" -> "$value%"; metric == "wind_mph" -> "$value mph"; metric == "pressure_in" -> "$value inHg"; metric == "visibility_miles" -> "$value mi"; else -> value }
    private fun friendly(s: String) = s.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
