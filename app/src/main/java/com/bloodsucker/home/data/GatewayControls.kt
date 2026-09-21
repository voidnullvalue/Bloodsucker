package com.bloodsucker.home.data

import com.bloodsucker.home.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

object GatewayControls {
    private const val BASE = "http://192.168.88.14:8080"

    fun fetch(): List<SmartDevice> = runCatching {
        val c = URI("$BASE/api/topics").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 3_000; c.readTimeout = 5_000
        c.inputStream.bufferedReader().use { parse(it.readText()) }
    }.getOrDefault(emptyList())

    internal fun parse(json: String): List<SmartDevice> {
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (!item.optBoolean("writable")) continue
                val topic = item.optString("topic"); val type = item.optString("control")
                if (type == "wled") {
                    val id = topic.split('/').getOrNull(1)?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) } ?: continue
                    add(SmartDevice(
                        key = "wled:${id.uppercase()}", kind = DeviceKind.WLED,
                        name = item.optString("name").ifBlank { "WLED ${id.uppercase()}" },
                        online = item.optString("value").equals("online", true),
                        lastSeen = item.optLong("last_seen") * 1000
                    ))
                    continue
                }
                if (type.isBlank() || type in setOf("percent", "trigger") || item.optString("id").startsWith("switch-")) continue
                val raw = item.optString("value")
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                val scalar = obj?.opt("value")?.toString() ?: raw
                val power = when (type) {
                    "switch", "scroll-power" -> scalar.equals("on", true) || scalar == "1"
                    "light" -> obj?.optString("state")?.equals("on", true)
                    else -> null
                }
                val numeric = when {
                    type == "light" -> obj?.optDouble("brightness")?.takeUnless(Double::isNaN)
                    type == "number" || type.contains("brightness") || type.contains("speed") -> scalar.toDoubleOrNull()
                    else -> null
                }
                val color = obj?.optJSONObject("color")?.let { c -> if (c.has("r")) "#%02X%02X%02X".format(c.optInt("r"), c.optInt("g"), c.optInt("b")) else null }
                    ?: scalar.takeIf { type.contains("color") && it.matches(Regex("#[0-9a-fA-F]{6}")) }
                add(SmartDevice(
                    key = "control:$topic", kind = DeviceKind.CONTROL,
                    name = item.optString("name").ifBlank { pretty(topic) }, lastSeen = item.optLong("last_seen") * 1000,
                    readings = listOf(Reading("Value", if (type == "light") obj?.optString("state", raw) ?: raw else scalar)),
                    power = power, level = numeric?.toInt(), color = color, controlType = type,
                    stateTopic = topic, numericValue = numeric
                ))
            }
        }
    }

    fun send(topic: String, value: String): Boolean = runCatching {
        val q = "topic=${URLEncoder.encode(topic, "UTF-8")}&value=${URLEncoder.encode(value, "UTF-8")}"
        val c = URI("$BASE/api/control?$q").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 3_000; c.readTimeout = 5_000
        c.inputStream.bufferedReader().use { JSONObject(it.readText()).optBoolean("ok") }
    }.getOrDefault(false)

    private fun pretty(topic: String) = topic.trim('/').split('/').filterNot { it in setOf("state", "switch", "light", "number") }
        .joinToString(" · ") { it.replace('_', ' ').replaceFirstChar(Char::uppercase) }
}
