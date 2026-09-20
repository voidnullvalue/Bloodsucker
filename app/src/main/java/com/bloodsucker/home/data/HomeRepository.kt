package com.bloodsucker.home.data

import android.content.Context
import com.bloodsucker.home.discovery.TopicDiscovery
import com.bloodsucker.home.model.*
import com.bloodsucker.home.mqtt.MqttClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

class HomeRepository(context: Context) {
    private val prefs = context.getSharedPreferences("bloodsucker", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(AppState(brokerUri = prefs.getString("broker", DEFAULT_BROKER) ?: DEFAULT_BROKER))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val clientId = prefs.getString("clientId", null) ?: "bloodsucker-android-${UUID.randomUUID()}".also { prefs.edit().putString("clientId", it).apply() }
    private val client = MqttClient(scope, ::connectionChanged, ::messageReceived)
    private val metadataLoading = mutableSetOf<String>()

    init { connect() }
    fun connect() { mutable.update { it.copy(connection = ConnectionState.CONNECTING, lastError = null) }; client.connect(state.value.brokerUri, clientId) }
    fun disconnect() = client.disconnect()
    fun setBroker(value: String) { val clean = value.trim(); if (!clean.startsWith("tcp://") && !clean.startsWith("ssl://") && !clean.startsWith("tls://")) return; prefs.edit().putString("broker", clean).apply(); mutable.update { it.copy(brokerUri = clean) }; connect() }
    fun toggleFavorite(key: String) { val set = prefs.getStringSet("favorites", emptySet())!!.toMutableSet(); if (!set.add(key)) set.remove(key); prefs.edit().putStringSet("favorites", set).apply(); mutable.update { s -> s.copy(devices = s.devices.map { if (it.key == key) it.copy(favorite = key in set) else it }) } }
    fun setAlias(key: String, alias: String, room: String) { prefs.edit().putString("alias:$key", alias.trim()).putString("room:$key", room.trim()).apply(); mutable.update { s -> s.copy(devices = s.devices.map { if (it.key == key) it.copy(name = alias.ifBlank { it.name }, room = room.trim()) else it }) } }

    fun setPower(device: SmartDevice, on: Boolean) = when (device.kind) {
        DeviceKind.WLED -> publishValidated("wled/${device.key.substringAfter(':')}", if (on) "ON" else "OFF")
        DeviceKind.SWITCH -> publishValidated("gateway/device/${device.key.substringAfter(':')}/switch/set", if (on) "on" else "off")
        DeviceKind.MATTER -> matterCommand(device, if (on) 1 else 0)
        else -> false
    }
    fun setLevel(device: SmartDevice, level: Int): Boolean {
        return when (device.kind) {
            DeviceKind.WLED -> publishValidated("wled/${device.key.substringAfter(':')}", level.coerceIn(0, 255).toString())
            DeviceKind.MATTER -> {
                val (node, endpoint) = device.key.removePrefix("matter:").split(':').map(String::toInt)
                if (node != 1 || endpoint != 1 || level !in 0..100) false else publishValidated("matter/rpc/request", JSONObject().put("id", "android-${UUID.randomUUID()}").put("operation", "write").put("node_id", node).put("endpoint", endpoint).put("cluster", 514).put("attribute", 2).put("value", level).toString())
            }
            else -> false
        }
    }
    fun wake(device: SmartDevice) = if (device.kind == DeviceKind.WAKE) publishValidated("wol/${device.key.substringAfter(':')}/", "wake") else false
    fun setWledColor(device: SmartDevice, color: String): Boolean {
        val normalized = color.trim().uppercase().let { if (it.startsWith('#')) it else "#$it" }
        return if (device.kind == DeviceKind.WLED && normalized.matches(Regex("#[0-9A-F]{6}"))) publishValidated("wled/${device.key.substringAfter(':')}/col", normalized) else false
    }
    fun wledApi(device: SmartDevice, prefix: String, value: Int): Boolean {
        if (device.kind != DeviceKind.WLED || prefix !in setOf("FX", "FP", "SX", "IX", "PL", "TT")) return false
        val range = if (prefix == "PL") 1..250 else if (prefix == "TT") 0..65000 else 0..255
        return value.takeIf { it in range }?.let { publishValidated("wled/${device.key.substringAfter(':')}/api", "$prefix=$it") } ?: false
    }
    private fun matterCommand(device: SmartDevice, command: Int): Boolean {
        val parts = device.key.removePrefix("matter:").split(':').mapNotNull(String::toIntOrNull); if (parts != listOf(1, 1)) return false
        return publishValidated("matter/rpc/request", JSONObject().put("id", "android-${UUID.randomUUID()}").put("operation", "command").put("node_id", 1).put("endpoint", 1).put("cluster", 6).put("command", command).toString())
    }
    private fun publishValidated(topic: String, payload: String): Boolean {
        if (state.value.connection != ConnectionState.CONNECTED || payload.length > 4096) return false
        return runCatching { client.publish(topic, payload) }.getOrDefault(false)
    }
    private fun connectionChanged(connected: Boolean, error: String?) { mutable.update { it.copy(connection = if (connected) ConnectionState.CONNECTED else if (error == null) ConnectionState.CONNECTING else ConnectionState.DISCONNECTED, lastError = error) } }
    private fun messageReceived(topic: String, bytes: ByteArray, retained: Boolean) {
        val result = TopicDiscovery.parse(topic, bytes)
        mutable.update { current ->
            if (!result.recognized) return@update current.copy(ignoredTopics = current.ignoredTopics + 1)
            var devices = current.devices
            result.device?.let { incoming ->
                val old = devices.firstOrNull { it.key == incoming.key }
                val alias = prefs.getString("alias:${incoming.key}", null)
                val room = prefs.getString("room:${incoming.key}", "") ?: ""
                val fav = incoming.key in (prefs.getStringSet("favorites", emptySet()) ?: emptySet())
                val merged = merge(old, incoming).copy(name = alias?.takeIf(String::isNotBlank) ?: merge(old, incoming).name, room = room, favorite = fav)
                devices = devices.filterNot { it.key == incoming.key } + merged
                if (incoming.kind == DeviceKind.WLED) loadWledMetadata(incoming.key.substringAfter(':'))
            }
            var forecasts = current.forecasts
            result.forecast?.let { f -> val old = forecasts.firstOrNull { it.index == f.index }; val merged = Forecast(f.index, f.date.ifBlank { old?.date.orEmpty() }, f.high.ifBlank { old?.high.orEmpty() }, f.low.ifBlank { old?.low.orEmpty() }, f.sunrise.ifBlank { old?.sunrise.orEmpty() }, f.sunset.ifBlank { old?.sunset.orEmpty() }); forecasts = (forecasts.filterNot { it.index == f.index } + merged).sortedBy { it.index } }
            current.copy(devices = devices.sortedWith(compareByDescending<SmartDevice> { it.favorite }.thenBy { it.name }), forecasts = forecasts, recognizedTopics = current.recognizedTopics + 1)
        }
    }
    private fun loadWledMetadata(id: String) {
        synchronized(metadataLoading) { if (!metadataLoading.add(id)) return }
        scope.launch(Dispatchers.IO) {
            val meta = WledMetadataClient.fetch(id.lowercase())
            mutable.update { state -> state.copy(devices = state.devices.map { d ->
                if (d.key != "wled:${id.uppercase()}") d else d.copy(
                    name = prefs.getString("alias:${d.key}", null)?.takeIf(String::isNotBlank) ?: meta.name.ifBlank { d.name },
                    wledControls = meta.controls.takeIf { it.effects.isNotEmpty() || it.palettes.isNotEmpty() || it.presets.isNotEmpty() }
                )
            }) }
            synchronized(metadataLoading) { metadataLoading.remove(id) }
        }
    }
    private fun merge(old: SmartDevice?, new: SmartDevice): SmartDevice = if (old == null) new else new.copy(name = if (new.name.startsWith("WLED ") && !old.name.startsWith("WLED ")) old.name else new.name, readings = (old.readings.associateBy { it.label } + new.readings.associateBy { it.label }).values.toList(), power = new.power ?: old.power, level = new.level ?: old.level, color = new.color ?: old.color, online = if (new.kind == DeviceKind.WLED && new.detail.isNotBlank()) old.online else new.online, wledControls = old.wledControls)
    companion object { const val DEFAULT_BROKER = "tcp://192.168.88.14:1883" }
}
