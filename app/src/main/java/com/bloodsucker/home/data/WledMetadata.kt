package com.bloodsucker.home.data

import com.bloodsucker.home.model.NamedValue
import com.bloodsucker.home.model.WledControls
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WledMetadata(val name: String = "", val controls: WledControls = WledControls())

object WledMetadataClient {
    fun fetch(id: String, gatewayBase: String = "http://192.168.88.14:8080"): WledMetadata {
        val info = jsonObject(get("$gatewayBase/api/wled-meta?id=$id&kind=info"))
        val effects = namedArray(get("$gatewayBase/api/wled-meta?id=$id&kind=effects"), skipReserved = true)
        val palettes = namedArray(get("$gatewayBase/api/wled-meta?id=$id&kind=palettes"))
        val presets = namedPresets(get("$gatewayBase/api/wled-meta?id=$id&kind=presets"))
        return WledMetadata(info?.optString("name").orEmpty(), WledControls(effects, palettes, presets))
    }

    private fun get(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000; connection.readTimeout = 5_000
        connection.useCaches = true
        try { if (connection.responseCode !in 200..299) null else connection.inputStream.bufferedReader().use { it.readText() } }
        finally { connection.disconnect() }
    }.getOrNull()

    private fun jsonObject(raw: String?) = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun namedArray(raw: String?, skipReserved: Boolean = false): List<NamedValue> {
        val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return buildList { for (i in 0 until array.length()) { val name = array.optString(i).trim(); if (name.isNotEmpty() && (!skipReserved || (name != "RSVD" && name != "-"))) add(NamedValue(i, name)) } }
    }
    private fun namedPresets(raw: String?): List<NamedValue> {
        val obj = jsonObject(raw) ?: return emptyList()
        return obj.keys().asSequence().mapNotNull { key ->
            val id = key.toIntOrNull()?.takeIf { it in 1..250 } ?: return@mapNotNull null
            val preset = obj.optJSONObject(key) ?: return@mapNotNull null
            NamedValue(id, preset.optString("n").ifBlank { "Preset $id" })
        }.sortedBy { it.value }.toList()
    }
}
