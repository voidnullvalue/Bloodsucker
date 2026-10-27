package com.bloodsucker.home.data

import android.content.Context
import android.content.SharedPreferences
import com.bloodsucker.home.discovery.TopicDiscovery
import com.bloodsucker.home.model.*
import com.bloodsucker.home.mqtt.MqttClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal interface HomePreferences {
    fun getString(key: String, defaultValue: String?): String?
    fun getStringSet(key: String, defaultValue: Set<String>): Set<String>
    fun putString(key: String, value: String)
    fun putStringSet(key: String, values: Set<String>)
}

private class AndroidHomePreferences(private val prefs: SharedPreferences) : HomePreferences {
    override fun getString(key: String, defaultValue: String?) = prefs.getString(key, defaultValue)
    override fun getStringSet(key: String, defaultValue: Set<String>) = prefs.getStringSet(key, defaultValue)?.toSet() ?: defaultValue
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun putStringSet(key: String, values: Set<String>) { prefs.edit().putStringSet(key, values).apply() }
}

internal interface HomeTransport {
    fun setListener(onConnection: (Boolean, String?) -> Unit, onMessage: (String, ByteArray, Boolean) -> Unit)
    fun connect(uriText: String, clientId: String)
    fun disconnect()
    fun publish(topic: String, payload: String): Boolean
    /** Legacy transports complete synchronously; the MQTT transport overrides this with an IO write flush. */
    fun publishWithCompletion(topic: String, payload: String, onComplete: (Boolean) -> Unit): Boolean {
        val accepted = publish(topic, payload)
        if (accepted) onComplete(true)
        return accepted
    }
}

private class MqttHomeTransport(scope: CoroutineScope) : HomeTransport {
    private var onConnection: ((Boolean, String?) -> Unit)? = null
    private var onMessage: ((String, ByteArray, Boolean) -> Unit)? = null
    private val client = MqttClient(scope, { connected, error -> onConnection?.invoke(connected, error) }, { topic, bytes, retained -> onMessage?.invoke(topic, bytes, retained) })

    override fun setListener(onConnection: (Boolean, String?) -> Unit, onMessage: (String, ByteArray, Boolean) -> Unit) {
        this.onConnection = onConnection; this.onMessage = onMessage
    }
    override fun connect(uriText: String, clientId: String) = client.connect(uriText, clientId)
    override fun disconnect() = client.disconnect()
    override fun publish(topic: String, payload: String) = client.publish(topic, payload)
    override fun publishWithCompletion(topic: String, payload: String, onComplete: (Boolean) -> Unit) =
        client.publishWithCompletion(topic, payload, onComplete)
}

class HomeRepository internal constructor(
    private val prefs: HomePreferences,
    transportFactory: (CoroutineScope) -> HomeTransport,
    private val gatewayFetch: () -> List<SmartDevice>,
    private val wledFetch: (String) -> WledMetadata,
    autoStart: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        AndroidHomePreferences(context.applicationContext.getSharedPreferences("bloodsucker", Context.MODE_PRIVATE)),
        { scope -> MqttHomeTransport(scope) },
        { GatewayControls.fetch() },
        { id -> WledMetadataClient.fetch(id) },
        autoStart = true
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(AppState(brokerUri = prefs.getString("broker", DEFAULT_BROKER) ?: DEFAULT_BROKER))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val clientId = prefs.getString("clientId", null) ?: "bloodsucker-android-${UUID.randomUUID()}".also { prefs.putString("clientId", it) }
    private val transport = transportFactory(scope).also { it.setListener(::connectionChanged, ::messageReceived) }
    private val metadataLoading = mutableSetOf<String>()
    private val geoRevision = AtomicLong(0)
    private val connectionEpoch = AtomicLong(0)
    @Volatile private var geoOfflineEpoch = -1L
    // Serializes the Geo optimistic command guard/commit with authoritative MQTT
    // merges, offline-epoch bookkeeping and connection transitions. Reentrant so
    // synchronous fake/legacy completions can run inside the enqueue call.
    private val stateLock = Any()
    @Volatile internal var geoCompletionCheckHook: (() -> Unit)? = null

    init {
        if (autoStart) {
            connect()
            scope.launch(Dispatchers.IO) { while (isActive) { loadGatewayControls(); delay(30_000) } }
        }
    }
    fun connect() { mutable.update { it.copy(connection = ConnectionState.CONNECTING, lastError = null) }; transport.connect(state.value.brokerUri, clientId) }
    fun disconnect() = transport.disconnect()
    fun setBroker(value: String) { val clean = value.trim(); if (!clean.startsWith("tcp://") && !clean.startsWith("ssl://") && !clean.startsWith("tls://")) return; prefs.putString("broker", clean); mutable.update { it.copy(brokerUri = clean) }; connect() }
    fun toggleFavorite(key: String) { val set = prefs.getStringSet("favorites", emptySet()).toMutableSet(); if (!set.add(key)) set.remove(key); prefs.putStringSet("favorites", set); mutable.update { s -> s.copy(devices = s.devices.map { if (it.key == key) it.copy(favorite = key in set) else it }) } }
    fun setAlias(key: String, alias: String, room: String) { prefs.putString("alias:$key", alias.trim()); prefs.putString("room:$key", room.trim()); mutable.update { s -> s.copy(devices = s.devices.map { if (it.key == key) it.copy(name = alias.ifBlank { it.name }, room = room.trim()) else it }) } }

    fun setPower(device: SmartDevice, on: Boolean): Boolean {
        if (device.kind == DeviceKind.ESPHOME) {
            return if (EsphomeLight.isGeoDevice(device)) {
                publishGeoCommand(EsphomeLight.powerPayload(on)) { it.copy(power = on) }
            } else false
        }
        val sent = when (device.kind) {
        DeviceKind.WLED -> publishValidated(wledTopic(device), if (on) "ON" else "OFF")
        DeviceKind.KASA -> publishValidated(kasaMqttTopic(device.key) + "/set", if (on) "ON" else "OFF")
        DeviceKind.SWITCH -> publishValidated("gateway/device/${device.key.substringAfter(':')}/switch/set", if (on) "on" else "off")
        DeviceKind.MATTER -> matterCommand(device, if (on) 1 else 0)
        DeviceKind.CONTROL -> sendGatewayControl(device, if (on) "on" else "off")
        else -> false
        }
        mutable.update { current ->
            if (sent) current.copy(
                devices = current.devices.map { if (it.key == device.key) it.copy(power = on) else it },
                lastError = null
            ) else current.copy(lastError = "Control was not sent. Check the broker connection and try again.")
        }
        return sent
    }
    fun setLevel(device: SmartDevice, level: Int): Boolean {
        val normalized = level.coerceIn(0, 255)
        if (device.kind == DeviceKind.ESPHOME) {
            if (!EsphomeLight.isGeoDevice(device)) return false
            val payload = EsphomeLight.brightnessPayload(level) ?: return false
            return publishGeoCommand(payload) { it.copy(level = normalized, power = true) }
        }
        return when (device.kind) {
            DeviceKind.WLED -> publishValidated(wledTopic(device), normalized.toString())
            DeviceKind.KASA -> kasaBrightnessPayload(level)?.let { publishValidated(kasaMqttTopic(device.key) + "/feature/brightness/set", it) } ?: false
            DeviceKind.MATTER -> {
                val (node, endpoint) = device.key.removePrefix("matter:").split(':').map(String::toInt)
                if (node != 1 || endpoint != 1 || level !in 0..100) false else publishValidated("matter/rpc/request", JSONObject().put("id", "android-${UUID.randomUUID()}").put("operation", "write").put("node_id", node).put("endpoint", endpoint).put("cluster", 514).put("attribute", 2).put("value", level).toString())
            }
            DeviceKind.CONTROL -> sendGatewayControl(device, level.toString())
            else -> false
        }
    }
    fun setEsphomePercent(device: SmartDevice, percent: Int): Boolean =
        if (EsphomeLight.isGeoDevice(device)) setLevel(device, EsphomeLight.percentToBrightness(percent)) else false
    fun setEsphomeEffect(device: SmartDevice, effect: String): Boolean {
        if (!EsphomeLight.isGeoDevice(device)) return false
        val payload = EsphomeLight.effectPayload(effect) ?: return false
        return publishGeoCommand(payload) { it.copy(effect = effect, power = true) }
    }
    fun wake(device: SmartDevice) = if (device.kind == DeviceKind.WAKE) publishValidated("wol/${device.key.substringAfter(':')}/", "wake") else false
    fun setWledColor(device: SmartDevice, color: String): Boolean {
        val normalized = color.trim().uppercase().let { if (it.startsWith('#')) it else "#$it" }
        return if (device.kind == DeviceKind.WLED && normalized.matches(Regex("#[0-9A-F]{6}"))) publishValidated("${wledTopic(device)}/col", normalized) else false
    }
    fun setKasaHsv(device: SmartDevice, hue: Int, saturation: Int, brightness: Int): Boolean =
        kasaHsvPayload(hue, saturation, brightness)?.let { payload ->
            if (device.kind == DeviceKind.KASA) publishValidated(kasaMqttTopic(device.key) + "/feature/hsv/set", payload) else false
        } ?: false
    fun setKasaColorTemperature(device: SmartDevice, kelvin: Int): Boolean =
        kasaColorTemperaturePayload(kelvin)?.let { payload -> if (device.kind == DeviceKind.KASA) publishValidated(kasaMqttTopic(device.key) + "/feature/color_temperature/set", payload) else false } ?: false
    fun setKasaPreset(device: SmartDevice, preset: Int): Boolean =
        kasaPresetPayload(preset)?.let { payload -> if (device.kind == DeviceKind.KASA) publishValidated(kasaMqttTopic(device.key) + "/feature/light_preset/set", payload) else false } ?: false
    fun wledApi(device: SmartDevice, prefix: String, value: Int): Boolean {
        if (device.kind != DeviceKind.WLED || prefix !in setOf("FX", "FP", "SX", "IX", "PL", "TT")) return false
        val range = if (prefix == "PL") 1..250 else if (prefix == "TT") 0..65000 else 0..255
        return value.takeIf { it in range }?.let { publishValidated("${wledTopic(device)}/api", "$prefix=$it") } ?: false
    }
    fun setGeneric(device: SmartDevice, value: String): Boolean =
        if (device.kind == DeviceKind.CONTROL && device.stateTopic !in EsphomeLight.topics) sendGatewayControl(device, value) else false

    private fun sendGatewayControl(device: SmartDevice, value: String): Boolean {
        if (device.stateTopic.isBlank() || device.stateTopic in EsphomeLight.topics) return false
        scope.launch(Dispatchers.IO) {
            val sent = GatewayControls.send(device.stateTopic, value)
            mutable.update { it.copy(lastError = if (sent) null else "Gateway rejected ${device.name} control") }
            if (sent) loadGatewayControls()
        }
        return true
    }
    internal fun loadGatewayControls() {
        val controls = gatewayFetch().filterNot { it.isGeoGatewayDuplicate() }
        if (controls.isEmpty()) return
        mutable.update { current ->
            val keys = controls.mapTo(mutableSetOf()) { it.key }
            val merged = controls.map { incoming ->
                val old = current.devices.firstOrNull { it.key == incoming.key }
                if (incoming.kind == DeviceKind.WLED) merge(old, incoming, retained = false) else incoming
            }
            current.copy(devices = (current.devices.filterNot { it.key in keys } + merged).distinctBy { it.key }.sortedDevices())
        }
        controls.filter { it.kind == DeviceKind.WLED }.forEach { loadWledMetadata(it.key.substringAfter(':')) }
    }
    private fun SmartDevice.isGeoGatewayDuplicate(): Boolean =
        key == EsphomeLight.KEY || kind == DeviceKind.ESPHOME || stateTopic in EsphomeLight.topics ||
            key.removePrefix("control:") in EsphomeLight.topics
    private fun wledTopic(device: SmartDevice) = wledMqttTopic(device.key)
    private fun matterCommand(device: SmartDevice, command: Int): Boolean {
        val parts = device.key.removePrefix("matter:").split(':').mapNotNull(String::toIntOrNull); if (parts != listOf(1, 1)) return false
        return publishValidated("matter/rpc/request", JSONObject().put("id", "android-${UUID.randomUUID()}").put("operation", "command").put("node_id", 1).put("endpoint", 1).put("cluster", 6).put("command", command).toString())
    }
    private fun publishValidated(topic: String, payload: String): Boolean {
        if (state.value.connection != ConnectionState.CONNECTED || payload.length > 4096) return false
        return runCatching { transport.publish(topic, payload) }.getOrDefault(false)
    }
    private fun publishGeoCommand(payload: String, update: (SmartDevice) -> SmartDevice): Boolean {
        synchronized(stateLock) {
            if (state.value.connection != ConnectionState.CONNECTED || payload.length > 4096) return false
            val current = state.value.devices.firstOrNull { it.key == EsphomeLight.KEY }
            if (current == null || !current.online) {
                mutable.update { it.copy(lastError = "Control was not sent while the Geo Lamp is offline.") }
                return false
            }
            val revision = geoRevision.get()
            val epoch = connectionEpoch.get()
            val accepted = runCatching {
                transport.publishWithCompletion(EsphomeLight.COMMAND_TOPIC, payload) { completed ->
                    synchronized(stateLock) {
                        if (!completed) {
                            mutable.update { it.copy(lastError = "Geo command could not be written to the broker.") }
                        } else if (connectionEpoch.get() == epoch && geoRevision.get() == revision && state.value.connection == ConnectionState.CONNECTED) {
                            // Guard + commit are atomic with authoritative merges, so a state
                            // message that landed after the socket flush can never be
                            // overwritten by this delayed optimistic update.
                            geoCompletionCheckHook?.invoke()
                            mutable.update { currentState ->
                                currentState.copy(devices = currentState.devices.map {
                                    if (it.key == EsphomeLight.KEY) update(it) else it
                                }, lastError = null)
                            }
                        }
                    }
                }
            }.getOrDefault(false)
            if (!accepted) mutable.update { it.copy(lastError = "Control was not sent. Check the broker connection and try again.") }
            return accepted
        }
    }
    private fun connectionChanged(connected: Boolean, error: String?) {
        synchronized(stateLock) {
            if (connected) connectionEpoch.incrementAndGet()
            mutable.update { it.copy(connection = if (connected) ConnectionState.CONNECTED else if (error == null) ConnectionState.CONNECTING else ConnectionState.DISCONNECTED, lastError = error) }
        }
    }
    private fun messageReceived(topic: String, bytes: ByteArray, retained: Boolean) {
        val now = clock()
        val result = TopicDiscovery.parse(topic, bytes, now)
        synchronized(stateLock) {
            result.device?.takeIf { it.kind == DeviceKind.ESPHOME }?.let { incoming ->
                geoRevision.incrementAndGet()
                if (incoming.explicitAvailability) {
                    if (incoming.online) {
                        // A retained online snapshot is authoritative for a new subscription,
                        // but not for an offline observation in the current session.
                        if (!retained || geoOfflineEpoch != connectionEpoch.get()) geoOfflineEpoch = -1L
                    } else {
                        geoOfflineEpoch = connectionEpoch.get()
                    }
                }
            }
            mutable.update { current ->
                if (!result.recognized) return@update current.copy(ignoredTopics = current.ignoredTopics + 1)
                var devices = current.devices
                result.device?.let { incoming ->
                    val old = devices.firstOrNull { it.key == incoming.key }
                    val alias = prefs.getString("alias:${incoming.key}", null)
                    val room = prefs.getString("room:${incoming.key}", "") ?: ""
                    val fav = incoming.key in prefs.getStringSet("favorites", emptySet())
                    val merged = merge(old, incoming, retained)
                    val personalized = merged.copy(name = alias?.takeIf(String::isNotBlank) ?: merged.name, room = room, favorite = fav)
                    devices = devices.filterNot { it.key == incoming.key } + personalized
                    if (incoming.kind == DeviceKind.WLED) loadWledMetadata(incoming.key.substringAfter(':'))
                }
                var forecasts = current.forecasts
                result.forecast?.let { f -> val old = forecasts.firstOrNull { it.index == f.index }; val merged = Forecast(f.index, f.date.ifBlank { old?.date.orEmpty() }, f.high.ifBlank { old?.high.orEmpty() }, f.low.ifBlank { old?.low.orEmpty() }, f.sunrise.ifBlank { old?.sunrise.orEmpty() }, f.sunset.ifBlank { old?.sunset.orEmpty() }); forecasts = (forecasts.filterNot { it.index == f.index } + merged).sortedBy { it.index } }
                current.copy(devices = devices.sortedDevices(), forecasts = forecasts, recognizedTopics = current.recognizedTopics + 1)
            }
        }
    }
    private fun loadWledMetadata(id: String) {
        synchronized(metadataLoading) { if (!metadataLoading.add(id)) return }
        scope.launch(Dispatchers.IO) {
            val meta = wledFetch(id.lowercase())
            mutable.update { state -> state.copy(devices = state.devices.map { d ->
                if (d.key != "wled:${id.uppercase()}") d else d.copy(
                    name = prefs.getString("alias:${d.key}", null)?.takeIf(String::isNotBlank) ?: meta.name.ifBlank { d.name },
                    wledControls = meta.controls.takeIf { it.effects.isNotEmpty() || it.palettes.isNotEmpty() || it.presets.isNotEmpty() }
                )
            }) }
            synchronized(metadataLoading) { metadataLoading.remove(id) }
        }
    }
    private fun merge(old: SmartDevice?, new: SmartDevice, retained: Boolean): SmartDevice = if (old == null) {
        // Retained snapshots are not live observations. Zero means the live
        // timestamp is unknown instead of falsely reporting "Just now".
        if (new.kind == DeviceKind.ESPHOME && retained) new.copy(lastSeen = 0L) else new
    } else {
        val inheritedName = if ((new.name.startsWith("WLED ") || new.name.startsWith("Kasa KL125 ")) && !old.name.startsWith("WLED ") && !old.name.startsWith("Kasa KL125 ")) old.name else new.name
        if (new.kind == DeviceKind.ESPHOME) {
            val ignoreRetainedOnline = new.explicitAvailability && retained && new.online && geoOfflineEpoch == connectionEpoch.get()
            new.copy(
                name = inheritedName,
                readings = (old.readings.associateBy { it.label } + new.readings.associateBy { it.label }).values.toList(),
                power = new.power ?: old.power, level = new.level ?: old.level, effect = new.effect ?: old.effect,
                online = if (new.explicitAvailability && !ignoreRetainedOnline) new.online else old.online,
                explicitAvailability = old.explicitAvailability || new.explicitAvailability,
                lastSeen = if (retained) old.lastSeen else new.lastSeen
            )
        } else new.copy(
            name = inheritedName,
            readings = (old.readings.associateBy { it.label } + new.readings.associateBy { it.label }).values.toList(),
            power = new.power ?: old.power, level = new.level ?: old.level, color = new.color ?: old.color,
            hue = new.hue ?: old.hue, saturation = new.saturation ?: old.saturation, colorTemperatureKelvin = new.colorTemperatureKelvin ?: old.colorTemperatureKelvin,
            effect = new.effect ?: old.effect,
            online = if ((new.kind == DeviceKind.WLED && new.detail.isNotBlank()) || (new.kind == DeviceKind.KASA && new.power == null && new.level == null && new.hue == null && new.saturation == null && new.colorTemperatureKelvin == null)) old.online else new.online,
            wledControls = old.wledControls
        )
    }
    private fun List<SmartDevice>.sortedDevices() = sortedWith(compareByDescending<SmartDevice> { it.favorite }.thenBy { it.name })
    companion object { const val DEFAULT_BROKER = "tcp://192.168.88.14:1883" }
}

internal fun wledMqttTopic(deviceKey: String) = "wled/${deviceKey.substringAfter(':').lowercase()}"
internal fun kasaMqttTopic(deviceKey: String) = "kasa/${deviceKey.removePrefix("kasa:").lowercase()}"
internal fun kasaBrightnessPayload(brightness: Int): String? = brightness.takeIf { it in 0..100 }?.toString()
internal fun kasaHsvPayload(hue: Int, saturation: Int, brightness: Int): String? =
    if (hue in 0..360 && saturation in 0..100 && brightness in 0..100) org.json.JSONArray().put(hue).put(saturation).put(brightness).toString() else null
internal fun kasaColorTemperaturePayload(kelvin: Int): String? = kelvin.takeIf { it in 2500..6500 }?.toString()
internal fun kasaPresetPayload(preset: Int): String? = if (preset in 1..4) "Light preset $preset" else null
