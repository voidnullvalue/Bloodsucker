package com.bloodsucker.home

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloodsucker.home.data.HomeRepository
import com.bloodsucker.home.model.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { BloodsuckerTheme { App() } } }
}

class MainViewModel(private val repository: HomeRepository) : androidx.lifecycle.ViewModel() {
    val state = repository.state
    fun setPower(d: SmartDevice, on: Boolean) = repository.setPower(d, on)
    fun setLevel(d: SmartDevice, value: Int) = repository.setLevel(d, value)
    fun wake(d: SmartDevice) = repository.wake(d)
    fun wledApi(d: SmartDevice, prefix: String, value: Int) = repository.wledApi(d, prefix, value)
    fun wledColor(d: SmartDevice, color: String) = repository.setWledColor(d, color)
    fun kasaHsv(d: SmartDevice, hue: Int, saturation: Int, brightness: Int) = repository.setKasaHsv(d, hue, saturation, brightness)
    fun kasaTemperature(d: SmartDevice, kelvin: Int) = repository.setKasaColorTemperature(d, kelvin)
    fun kasaPreset(d: SmartDevice, preset: Int) = repository.setKasaPreset(d, preset)
    fun generic(d: SmartDevice, value: String) = repository.setGeneric(d, value)
    fun favorite(d: SmartDevice) = repository.toggleFavorite(d.key)
    fun alias(d: SmartDevice, name: String, room: String) = repository.setAlias(d.key, name, room)
    fun broker(uri: String) = repository.setBroker(uri)
    fun reconnect() = repository.connect()
}

@Composable private fun model(): MainViewModel {
    val app = LocalContext.current.applicationContext as BloodsuckerApp
    return viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>) = MainViewModel(app.repository) as T
    })
}

private enum class Tab(val title: String, val icon: ImageVector) { HOME("Home", Icons.Outlined.Home), LIGHTS("Lights", Icons.Outlined.Lightbulb), CLIMATE("Climate", Icons.Outlined.Thermostat), DEVICES("Devices", Icons.Outlined.Devices), SETTINGS("Settings", Icons.Outlined.Settings) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun App(vm: MainViewModel = model()) {
    val state by vm.state.collectAsStateWithLifecycle(); var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text(tab.title, fontWeight = FontWeight.Bold); Text(connectionText(state), style = MaterialTheme.typography.labelSmall, color = connectionColor(state.connection)) } }, actions = { if (state.connection != ConnectionState.CONNECTED) IconButton(vm::reconnect) { Icon(Icons.Default.Refresh, "Reconnect") } }) },
        bottomBar = { NavigationBar { Tab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(item.icon, null) }, label = { Text(item.title, maxLines = 1) }) } } }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(state, vm)
                Tab.LIGHTS -> DeviceList(state.devices.filter { it.kind == DeviceKind.WLED || it.kind == DeviceKind.KASA }, "No lights yet", vm)
                Tab.CLIMATE -> ClimateScreen(state, vm)
                Tab.DEVICES -> DeviceList(state.devices.filter { it.kind in setOf(DeviceKind.SWITCH, DeviceKind.MATTER, DeviceKind.WAKE, DeviceKind.CONTROL) }, "No controllable devices yet", vm)
                Tab.SETTINGS -> SettingsScreen(state, vm)
            }
        }
    }
}

@Composable private fun HomeScreen(state: AppState, vm: MainViewModel) {
    val weather = state.devices.firstOrNull { it.kind == DeviceKind.WEATHER }
    val favorites = state.devices.filter { it.favorite }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.WbSunny, null, Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Column { Text(weather?.readings?.firstOrNull { it.label == "Temperature f" }?.value ?: "Home", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(weather?.readings?.firstOrNull { it.label == "Condition" }?.value ?: "Your home at a glance") } }
                    if (weather != null) { Spacer(Modifier.height(14.dp)); ReadingGrid(weather.readings.take(6)) }
                }
            }
        }
        item { SectionTitle("Favorites", if (favorites.isEmpty()) "Tap the star on a device to pin it here" else "Your everyday controls") }
        if (favorites.isEmpty()) item { EmptyPanel(Icons.Outlined.StarOutline, "Nothing pinned yet") } else items(favorites, key = { it.key }) { DeviceCard(it, vm) }
        item { SectionTitle("Recently seen", "Live updates from ${state.devices.size} discovered devices") }
        items(state.devices.sortedByDescending { it.lastSeen }.take(5), key = { "recent:${it.key}" }) { DeviceCard(it, vm) }
    }
}

@Composable private fun ClimateScreen(state: AppState, vm: MainViewModel) {
    val devices = state.devices.filter { it.kind in setOf(DeviceKind.GOVEE, DeviceKind.WEATHER, DeviceKind.SENSOR) || (it.kind == DeviceKind.MATTER && it.readings.any { r -> r.label in setOf("Temperature", "Humidity", "Air quality") }) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.forecasts.isNotEmpty()) { item { SectionTitle("Forecast", "Automatically discovered") }; item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.forecasts.take(3).forEach { f -> ElevatedCard(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text(f.date.ifBlank { "Day ${f.index + 1}" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("${f.high}° / ${f.low}°", style = MaterialTheme.typography.titleMedium); if (f.sunrise.isNotBlank()) Text("↑ ${f.sunrise}", style = MaterialTheme.typography.labelSmall) } } } } }
        }
        if (devices.isEmpty()) item { EmptyPanel(Icons.Outlined.Thermostat, "Waiting for climate data") }
        items(devices, key = { it.key }) { DeviceCard(it, vm) }
    }
}

@Composable private fun DeviceList(devices: List<SmartDevice>, empty: String, vm: MainViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (devices.isEmpty()) item { EmptyPanel(Icons.Outlined.SensorsOff, empty) }
        items(devices, key = { it.key }) { DeviceCard(it, vm) }
    }
}

@Composable private fun DeviceCard(device: SmartDevice, vm: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf(false) }
    ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = if (!device.online) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f) else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = kindColor(device.kind).copy(alpha = .14f), modifier = Modifier.size(48.dp)) { Box(contentAlignment = Alignment.Center) { Icon(kindIcon(device.kind), null, tint = kindColor(device.kind)) } }
                    Spacer(Modifier.width(12.dp)); Column { Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(listOf(device.room, freshness(device)).filter(String::isNotBlank).joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton({ vm.favorite(device) }) { Icon(if (device.favorite) Icons.Default.Star else Icons.Outlined.StarOutline, "Favorite", tint = if (device.favorite) MaterialTheme.colorScheme.tertiary else LocalContentColor.current) }
                if (device.kind !in setOf(DeviceKind.MATTER, DeviceKind.CONTROL)) device.power?.let { checked ->
                    Switch(checked = checked, onCheckedChange = { vm.setPower(device, it) })
                }
            }
            if (device.readings.isNotEmpty()) { Spacer(Modifier.height(12.dp)); ReadingGrid(device.readings.take(6)) }
            if (device.kind == DeviceKind.MATTER && device.power != null) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        if (BuildConfig.DEBUG) Log.d("BloodsuckerInput", "purifier control clicked")
                        vm.setPower(device, !device.power)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PowerSettingsNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (device.power) "Turn purifier off" else "Turn purifier on")
                }
            }
            if (device.kind == DeviceKind.CONTROL) {
                Spacer(Modifier.height(12.dp))
                GenericControlPanel(device, vm)
            }
            if (!device.supported) { Spacer(Modifier.height(10.dp)); Text("Identified, but this model's measurements aren't decoded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    if (device.kind == DeviceKind.WLED) WledPanel(device, vm)
                    else if (device.kind == DeviceKind.KASA) KasaPanel(device, vm)
                    else device.level?.let { current ->
                        Text("Fan speed", style = MaterialTheme.typography.labelLarge)
                        var slider by remember(current) { mutableFloatStateOf(current.toFloat()) }
                        Slider(slider, { slider = it }, onValueChangeFinished = { vm.setLevel(device, slider.toInt()) }, valueRange = 0f..100f)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (device.kind == DeviceKind.WAKE) Button({ vm.wake(device) }) { Icon(Icons.Default.PowerSettingsNew, null); Spacer(Modifier.width(6.dp)); Text("Wake") }
                        TextButton({ edit = true }) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(4.dp)); Text("Name & room") }
                    }
                }
            }
        }
    }
    if (edit) EditDialog(device, { edit = false }) { name, room -> vm.alias(device, name, room); edit = false }
}

@Composable private fun GenericControlPanel(device: SmartDevice, vm: MainViewModel) {
    when {
        device.controlType == "switch" || device.controlType == "scroll-power" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ vm.generic(device, "on") }, Modifier.weight(1f)) { Text("On") }
                FilledTonalButton({ vm.generic(device, "off") }, Modifier.weight(1f)) { Text("Off") }
            }
        }
        device.controlType == "light" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ vm.generic(device, "on") }, Modifier.weight(1f)) { Text("On") }
                FilledTonalButton({ vm.generic(device, "off") }, Modifier.weight(1f)) { Text("Off") }
            }
            var brightness by remember(device.level) { mutableFloatStateOf((device.level ?: 255).toFloat()) }
            Text("Brightness ${brightness.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(brightness, { brightness = it }, onValueChangeFinished = { vm.generic(device, brightness.toInt().toString()) }, valueRange = 0f..255f)
        }
        device.controlType.contains("brightness") || device.controlType.contains("speed") -> {
            val max = if (device.controlType.startsWith("flex-")) 100f else 255f
            var value by remember(device.numericValue) { mutableFloatStateOf((device.numericValue ?: 0.0).toFloat().coerceIn(0f, max)) }
            Text("Value ${value.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(value, { value = it }, onValueChangeFinished = { vm.generic(device, value.toInt().toString()) }, valueRange = 0f..max)
        }
        device.controlType == "number" -> {
            var value by remember(device.numericValue) { mutableStateOf(device.numericValue?.toString() ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c in ".-" } }, label = { Text("Value") }, singleLine = true, modifier = Modifier.weight(1f))
                Button({ value.toDoubleOrNull()?.let { vm.generic(device, value) } }) { Text("Apply") }
            }
        }
        device.controlType.contains("color") -> {
            var value by remember(device.color) { mutableStateOf(device.color ?: "#FFFFFF") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it.take(7) }, label = { Text("RGB color") }, singleLine = true, modifier = Modifier.weight(1f))
                Button({ if (value.matches(Regex("#[0-9a-fA-F]{6}"))) vm.generic(device, value) }) { Text("Apply") }
            }
        }
        device.controlType.contains("text") -> {
            var value by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it.take(240) }, label = { Text("Text to display") }, modifier = Modifier.weight(1f))
                Button({ if (value.isNotBlank()) vm.generic(device, value) }) { Text("Send") }
            }
        }
        else -> Text("Control type: ${device.controlType}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun WledPanel(device: SmartDevice, vm: MainViewModel) {
    Text("Power", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton({ vm.setPower(device, true) }) { Text("On") }
        FilledTonalButton({ vm.setPower(device, false) }) { Text("Off") }
    }
    Spacer(Modifier.height(8.dp))
    val brightness = device.level ?: 255
    var level by remember(brightness) { mutableFloatStateOf(brightness.toFloat()) }
    Text("Brightness ${level.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(level, { level = it }, onValueChangeFinished = { vm.setLevel(device, level.toInt()) }, valueRange = 0f..255f)
    var color by remember(device.color) { mutableStateOf(device.color?.let { "#${it.takeLast(6)}" } ?: "#FFFFFF") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(color, { color = it.take(7) }, label = { Text("RGB color") }, singleLine = true, modifier = Modifier.weight(1f))
        Button({ vm.wledColor(device, color) }) { Text("Apply") }
    }
    val controls = device.wledControls
    if (controls == null) Text("Loading effect names…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        val currentEffect = readingInt(device, "Effect")
        val currentPalette = readingInt(device, "Palette")
        val currentPreset = readingInt(device, "Preset")
        PrettyPicker("Effect", controls.effects, currentEffect) { vm.wledApi(device, "FX", it) }
        PrettyPicker("Palette", controls.palettes, currentPalette) { vm.wledApi(device, "FP", it) }
        PrettyPicker("Preset", controls.presets, currentPreset) { vm.wledApi(device, "PL", it) }
    }
    WledSlider("Speed", readingInt(device, "Speed") ?: 128) { vm.wledApi(device, "SX", it) }
    WledSlider("Intensity", readingInt(device, "Intensity") ?: 128) { vm.wledApi(device, "IX", it) }
    var transition by remember { mutableStateOf("0") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(transition, { transition = it.filter(Char::isDigit).take(5) }, label = { Text("Transition ms") }, singleLine = true, modifier = Modifier.weight(1f))
        Button({ transition.toIntOrNull()?.let { vm.wledApi(device, "TT", it) } }) { Text("Apply") }
    }
}

@Composable private fun KasaPanel(device: SmartDevice, vm: MainViewModel) {
    Text("Power", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton({ vm.setPower(device, true) }) { Text("On") }
        FilledTonalButton({ vm.setPower(device, false) }) { Text("Off") }
    }
    Spacer(Modifier.height(8.dp))
    var brightness by remember(device.level) { mutableFloatStateOf((device.level ?: 100).toFloat()) }
    Text("Brightness ${brightness.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(brightness, { brightness = it }, onValueChangeFinished = { vm.setLevel(device, brightness.toInt()) }, valueRange = 0f..100f)
    var temperature by remember(device.colorTemperatureKelvin) { mutableFloatStateOf((device.colorTemperatureKelvin ?: 2700).toFloat()) }
    Text("Color temperature ${temperature.toInt()} K", style = MaterialTheme.typography.labelLarge)
    Slider(temperature, { temperature = it }, onValueChangeFinished = { vm.kasaTemperature(device, temperature.toInt()) }, valueRange = 2500f..6500f)
    var hue by remember(device.hue) { mutableFloatStateOf((device.hue ?: 0).toFloat()) }
    var saturation by remember(device.saturation) { mutableFloatStateOf((device.saturation ?: 0).toFloat()) }
    var hsvBrightness by remember(device.level) { mutableFloatStateOf((device.level ?: 100).toFloat()) }
    Text("Hue ${hue.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(hue, { hue = it }, valueRange = 0f..360f)
    Text("Saturation ${saturation.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(saturation, { saturation = it }, valueRange = 0f..100f)
    Text("HSV brightness ${hsvBrightness.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(hsvBrightness, { hsvBrightness = it }, valueRange = 0f..100f)
    FilledTonalButton({ vm.kasaHsv(device, hue.toInt(), saturation.toInt(), hsvBrightness.toInt()) }, modifier = Modifier.fillMaxWidth()) { Text("Apply color") }
    Spacer(Modifier.height(8.dp))
    Text("Light presets", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..2).forEach { preset -> OutlinedButton({ vm.kasaPreset(device, preset) }, Modifier.weight(1f)) { Text("Light preset $preset") } }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (3..4).forEach { preset -> OutlinedButton({ vm.kasaPreset(device, preset) }, Modifier.weight(1f)) { Text("Light preset $preset") } }
    }
}

@Composable private fun WledSlider(label: String, initial: Int, send: (Int) -> Unit) {
    var value by remember(initial) { mutableFloatStateOf(initial.toFloat()) }
    Text("$label ${value.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(value, { value = it }, onValueChangeFinished = { send(value.toInt()) }, valueRange = 0f..255f)
}

@Composable private fun PrettyPicker(label: String, options: List<NamedValue>, current: Int?, send: (Int) -> Unit) {
    if (options.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.value == current }
    Box {
        OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${selected?.name ?: "Choose…"}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { open = false; send(item.value) }) }
        }
    }
}

private fun readingInt(device: SmartDevice, label: String) = device.readings.firstOrNull { it.label == label }?.value?.toIntOrNull()

@Composable private fun ReadingGrid(readings: List<Reading>) { readings.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { r -> Column(Modifier.weight(1f).padding(vertical = 4.dp)) { Text(r.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(r.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } } } }

@Composable private fun SettingsScreen(state: AppState, vm: MainViewModel) {
    var broker by remember(state.brokerUri) { mutableStateOf(state.brokerUri) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("Broker", "Local network connection") }
        item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(broker, { broker = it }, label = { Text("MQTT URI") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Button({ vm.broker(broker) }, enabled = broker != state.brokerUri) { Text("Save & reconnect") }; Text("Anonymous connection • QoS 0 • retained snapshot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { SectionTitle("Diagnostics", "Safe connection details") }
        item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { DiagnosticRow("Status", connectionText(state)); DiagnosticRow("Devices", state.devices.size.toString()); DiagnosticRow("Recognized messages", state.recognizedTopics.toString()); DiagnosticRow("Ignored messages", state.ignoredTopics.toString()); state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } } } }
        item { Text("Bloodsucker ${BuildConfig.VERSION_NAME} • Payloads and raw topics stay out of the consumer interface.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun EditDialog(d: SmartDevice, dismiss: () -> Unit, save: (String, String) -> Unit) { var name by remember { mutableStateOf(d.name) }; var room by remember { mutableStateOf(d.room) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Personalize device") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true); OutlinedTextField(room, { room = it }, label = { Text("Room") }, singleLine = true) } }, confirmButton = { TextButton({ save(name, room) }) { Text("Save") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } }) }
@Composable private fun SectionTitle(title: String, subtitle: String) { Column { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun EmptyPanel(icon: ImageVector, text: String) { Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), RoundedCornerShape(20.dp)).padding(32.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(10.dp)); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun DiagnosticRow(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.SemiBold) } }
private fun connectionText(s: AppState) = when (s.connection) { ConnectionState.CONNECTED -> "Connected to ${s.brokerUri.removePrefix("tcp://").removePrefix("ssl://")}"; ConnectionState.CONNECTING -> "Connecting…"; ConnectionState.DISCONNECTED -> "Offline" }
private fun connectionColor(s: ConnectionState) = when (s) { ConnectionState.CONNECTED -> Color(0xFF238636); ConnectionState.CONNECTING -> Color(0xFFE09F00); ConnectionState.DISCONNECTED -> Color(0xFFC53D3D) }
private fun freshness(d: SmartDevice): String { val mins = (System.currentTimeMillis() - d.lastSeen) / 60_000; return when { !d.online -> "Offline"; mins < 1 -> "Just now"; mins < 60 -> "$mins min ago"; else -> "Stale" } }
private fun kindIcon(k: DeviceKind) = when (k) { DeviceKind.WLED, DeviceKind.KASA -> Icons.Outlined.Lightbulb; DeviceKind.GOVEE -> Icons.Outlined.Thermostat; DeviceKind.MATTER -> Icons.Outlined.Air; DeviceKind.SWITCH -> Icons.Outlined.ToggleOn; DeviceKind.WEATHER -> Icons.Outlined.WbSunny; DeviceKind.SENSOR -> Icons.Outlined.Sensors; DeviceKind.WAKE -> Icons.Outlined.Computer; DeviceKind.CONTROL -> Icons.Outlined.Tune }
private fun kindColor(k: DeviceKind) = when (k) { DeviceKind.WLED -> Color(0xFFFF8A34); DeviceKind.KASA -> Color(0xFFFF8A34); DeviceKind.GOVEE -> Color(0xFF0D9488); DeviceKind.MATTER -> Color(0xFF7C3AED); DeviceKind.SWITCH -> Color(0xFF2563EB); DeviceKind.WEATHER -> Color(0xFFE9A700); DeviceKind.SENSOR -> Color(0xFF0891B2); DeviceKind.WAKE -> Color(0xFF475569); DeviceKind.CONTROL -> Color(0xFFDB2777) }

@Composable private fun BloodsuckerTheme(content: @Composable () -> Unit) { val dark = androidx.compose.foundation.isSystemInDarkTheme(); MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFFFB77B), secondary = Color(0xFF6DD6CA)) else lightColorScheme(primary = Color(0xFF8B3F00), secondary = Color(0xFF006B62), background = Color(0xFFFFF8F4)), typography = Typography(), content = content) }
