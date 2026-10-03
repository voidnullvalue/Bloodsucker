package com.bloodsucker.home.model

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class DeviceKind { WLED, KASA, GOVEE, MATTER, SWITCH, WEATHER, SENSOR, WAKE, CONTROL }

data class Reading(val label: String, val value: String)
data class NamedValue(val value: Int, val name: String)
data class WledControls(
    val effects: List<NamedValue> = emptyList(),
    val palettes: List<NamedValue> = emptyList(),
    val presets: List<NamedValue> = emptyList()
)

data class SmartDevice(
    val key: String,
    val kind: DeviceKind,
    val name: String,
    val room: String = "",
    val online: Boolean = true,
    val lastSeen: Long = System.currentTimeMillis(),
    val readings: List<Reading> = emptyList(),
    val power: Boolean? = null,
    val level: Int? = null,
    val color: String? = null,
    val hue: Int? = null,
    val saturation: Int? = null,
    val colorTemperatureKelvin: Int? = null,
    val favorite: Boolean = false,
    val supported: Boolean = true,
    val detail: String = "",
    val wledControls: WledControls? = null,
    val controlType: String = "",
    val stateTopic: String = "",
    val numericValue: Double? = null
)

data class Forecast(val index: Int, val date: String = "", val high: String = "", val low: String = "", val sunrise: String = "", val sunset: String = "")

data class AppState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val brokerUri: String = "tcp://192.168.88.14:1883",
    val devices: List<SmartDevice> = emptyList(),
    val forecasts: List<Forecast> = emptyList(),
    val recognizedTopics: Int = 0,
    val ignoredTopics: Int = 0,
    val lastError: String? = null
)
