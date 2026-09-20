# Bloodsucker Android MQTT App — implementation handoff

## Goal

Build a polished, consumer-friendly Android app that connects directly to the
existing MQTT broker, automatically discovers devices and sensors from topics,
and exposes the same useful controls and state currently implemented by the
MiFi web dashboard.

This document is intended to be sufficient context for a fresh development
session. The existing web implementation remains the behavioral reference at:

- Source: `/home/void/mifi/mqtt-gateway/main.c`
- Supporting notes: `/home/void/mifi/mqttsetup.md`
- Gateway README: `/home/void/mifi/mqtt-gateway/README.md`
- Live web UI/API: `http://192.168.88.14:8080/`

## Product direction

This should feel like a consumer smart-home application, not an MQTT browser.
Do not show raw topic paths, cluster IDs, MAC addresses, JSON, or numeric WLED
effect IDs in the normal UI. Those can live in an optional diagnostics screen.

Recommended primary navigation:

1. **Home** — favorite controls plus current indoor/outdoor conditions.
2. **Lights** — automatically discovered WLED lights with rich controls.
3. **Climate** — Govee sensors, weather, forecasts, and Matter sensor states.
4. **Devices** — switches, purifier, Wake-on-LAN, and other discovered controls.
5. **Settings** — broker configuration, aliases, ordering, favorites, and
   diagnostics.

Use a modern Material 3 visual language, large touch targets, meaningful icons,
dark/light themes, clear offline/stale-state treatment, and optimistic controls
that reconcile with the next MQTT state update. Temperature defaults to
Fahrenheit and other measurements to US/imperial units.

## Network and broker

| Setting | Current value |
| --- | --- |
| Broker host | `192.168.88.14` |
| Plain MQTT | TCP `1883` |
| TLS MQTT | TCP `8883` |
| Authentication | Anonymous is currently allowed |
| Scope | Local Wi-Fi/LAN only; cellular-facing traffic is blocked |
| Discovery subscription | `#` |

The first version can use `tcp://192.168.88.14:1883`. Make the broker URI a
setting rather than compiling it into the data layer. Use a unique persistent
client ID such as `bloodsucker-android-<installation UUID>`.

Recommended connection behavior:

- MQTT 3.1.1 or MQTT 5 is acceptable; do not rely on MQTT 5-only features.
- Enable automatic reconnect with exponential backoff and jitter.
- Subscribe at QoS 0 initially; existing devices primarily use QoS 0.
- Keep a local last-known-state cache, but clearly mark stale/offline values.
- Process retained messages as the initial snapshot, then merge live updates.
- Resubscribe after reconnect.
- Never publish exploratory messages to unknown topics.
- Ignore `$SYS/#` for product discovery.
- Treat `gateway/#` as normalized gateway data/control, not as raw-device
  discovery input, to prevent duplicate cards.

The broker is anonymous today, which is convenient but not appropriate for an
untrusted network. Keep credentials and TLS support in the app architecture so
the broker can be hardened later without redesigning the app. Never ship SSH
credentials or device-management credentials in the APK.

## Core discovery model

Subscribe broadly and maintain an in-memory topic registry:

```text
TopicRecord(
  topic,
  rawPayload,
  parsedPayload,
  firstSeen,
  lastSeen,
  retained,
  source,
  deviceKey,
  capability
)
```

Discovery is continuous. A new matching topic should add a card without an app
update or restart. A disappeared device should remain visible as offline/stale
rather than immediately vanishing. Persist user aliases, favorites, room
assignments, hidden devices, and card ordering by stable `deviceKey`.

Suggested processing pipeline:

```text
MQTT message
  -> secret/system-topic filter
  -> topic-family recognizers
  -> payload parser/decoder
  -> stable device aggregation and deduplication
  -> domain model/state store
  -> reactive Compose UI
```

Recognizer precedence should be specific to general:

1. WLED
2. Matter
3. Govee BLE
4. Weather
5. Wake-on-LAN
6. Normalized gateway controls
7. Generic state/command convention
8. Generic read-only sensor topics
9. Diagnostics-only unknown topics

Do not make a topic writable merely because its payload resembles a switch.
Only the explicit contracts below authorize publishing.

## WLED discovery, state, metadata, and controls

Discover one light for each topic:

```text
wled/<six-hex-device-id>/status
```

The status value is normally `online`. Group all `wled/<id>/...` topics under
the same stable device key. Existing useful state topics include:

```text
wled/<id>/status     availability
wled/<id>/g          brightness, 0..255
wled/<id>/c          color, usually #RRGGBB or #WWRRGGBB
wled/<id>/v          XML-like state containing fields such as ds, fx, fp, ps,
                     sx, and ix
```

Controls are native MQTT publishes:

| Action | Topic | Payload |
| --- | --- | --- |
| Power on/off | `wled/<id>` | `ON` or `OFF` |
| Brightness | `wled/<id>` | integer `0..255` |
| RGB color | `wled/<id>/col` | `#RRGGBB` |
| Effect | `wled/<id>/api` | `FX=<0..255>` |
| Palette | `wled/<id>/api` | `FP=<0..255>` |
| Speed | `wled/<id>/api` | `SX=<0..255>` |
| Intensity | `wled/<id>/api` | `IX=<0..255>` |
| Preset | `wled/<id>/api` | `PL=<1..250>` |
| Transition | `wled/<id>/api` | `TT=<0..65000>` milliseconds |

Effect, palette, and preset IDs are not consumer-facing names. Resolve their
catalogs in this order:

1. Read WLED's IP and friendly name from gateway metadata:
   `GET http://192.168.88.14:8080/api/wled-meta?id=<id>&kind=info`.
2. Fetch current catalogs directly from the WLED device if reachable:
   `/json/eff`, `/json/pal`, and `/presets.json`.
3. Fall back to the gateway cache:
   `/api/wled-meta?id=<id>&kind=effects|palettes|presets`.

The display name priority is user alias, WLED metadata `name`, the `ds` field
from `/v`, then `WLED <id>`. Cache catalogs locally and refresh occasionally,
not during every state update.

## Govee BLE sensor discovery

BLE advertisements arrive under several redundant topic forms, including:

```text
ble/<MAC-with-underscores>/advertisement
ble/<MAC-with-underscores>/state
ble/<scanner-name>/<MAC-with-colons>
```

Parse JSON and recognize Govee records when the name/alias begins with `GV`,
contains `Govee`, advertises service UUID `EC88`, or contains Govee manufacturer
data. Deduplicate by normalized uppercase MAC address, not topic. Prefer the
freshest decodable record. Multiple scanners often report the same physical
sensor.

Currently supported thermometer formats:

### H5075

Manufacturer bytes are found at numeric key `60552` or hex key `0xEC88`.
Values may be a JSON byte array or an even-length hexadecimal string. Starting
at byte offset 1, read a 24-bit big-endian packed measurement:

```text
n = (b[1] << 16) | (b[2] << 8) | b[3]
negative = (n & 0x800000) != 0
n = n & 0x7fffff
celsius = trunc(n / 1000) / 10
if negative: celsius = -celsius
humidityPercent = (n % 1000) / 10
batteryPercent = b[4] & 0x7f
fahrenheit = celsius * 9 / 5 + 32
```

### H5179

Manufacturer bytes are found at key `0x0001` or `1`. Decode the same packed
format starting at byte offset 2; battery is the following byte.

Other Govee models should still be discovered and displayed as an unsupported
sensor with name, signal strength, and last-seen age. Never apply a guessed
decoder and show plausible-looking but false temperature data.

Current live discovery found more Govee devices than the original hard-coded
pair, demonstrating why MAC-address lists must not be used.

## Weather

Weather is published under `weather/wttr/...`. Build a current-conditions card
from:

```text
weather/wttr/current/temperature_f
weather/wttr/current/feels_like_f
weather/wttr/current/humidity
weather/wttr/current/condition
weather/wttr/current/wind_mph
weather/wttr/current/wind_direction
weather/wttr/current/pressure_in
weather/wttr/current/precip_in
weather/wttr/current/visibility_miles
weather/wttr/current/cloud_cover
weather/wttr/current/uv_index
weather/wttr/current/observation_time
```

Auto-discover forecast indices rather than assuming a fixed count:

```text
weather/wttr/forecast/<index>/date
weather/wttr/forecast/<index>/max_temp_f
weather/wttr/forecast/<index>/min_temp_f
weather/wttr/forecast/<index>/avg_temp_f
weather/wttr/forecast/<index>/sunrise
weather/wttr/forecast/<index>/sunset
weather/wttr/forecast/<index>/moon_phase
weather/wttr/forecast/<index>/moon_illumination
```

Prefer `_f`, `mph`, `pressure_in`, `precip_in`, and `visibility_miles`; suppress
their metric duplicates in the consumer UI.

Other weather/sensor publishers may use a simpler prefix such as:

```text
lab/weather/temperature
lab/weather/humidity
```

Group such scalar readings by all topic segments except the final metric name.
The current generic `temperature` value is Celsius and must be converted to
Fahrenheit. In a durable implementation, allow a per-source unit override so
an ambiguous future publisher cannot be silently misconverted.

## Matter discovery and presentation

Canonical Matter attribute state topics are:

```text
matter/<node>/<endpoint>/<cluster>/<attribute>
```

Payloads are JSON objects whose scalar state is normally in `value`, with
human-readable `cluster` and `attribute` names when supplied. Group by node and
endpoint. Product name is available at:

```text
matter/<node>/0/40/3
```

Only promote operational endpoint state into the consumer UI. Exclude global
attributes `65528` and above and infrastructure/metadata clusters such as:

```text
3, 29, 31, 40, 48, 49, 51, 52, 53, 54, 60, 62
```

Raw excluded topics may remain visible in diagnostics.

Useful formatting rules:

- Cluster `6`, attribute `0`: OnOff boolean -> `On`/`Off`.
- Cluster `91`, attribute `0`: AirQuality enum:
  `0 Unknown`, `1 Good`, `2 Fair`, `3 Moderate`, `4 Poor`,
  `5 Very poor`, `6 Extremely poor`.
- Cluster `113`: filter state; `Condition` is percent,
  `InPlaceIndicator` is boolean.
- Cluster `514`: fan state; attributes containing `Percent` are percentages.
- Cluster `1026`, attribute `0`: temperature is hundredths of °C; convert to °F.
- Cluster `1029`, attribute `0`: relative humidity is hundredths of a percent.

The current air purifier is Govee H7126, node `1`, endpoint `1`. It exposes
power, fan mode/percentage, categorical air quality, and HEPA filter state. It
does **not** currently expose numeric particulate concentration, so never infer
PM2.5 from the air-quality enum.

### Matter purifier controls

Publish JSON to `matter/rpc/request`.

Power command (`command` is `1` for on, `0` for off):

```json
{
  "id": "android-<unique-request-id>",
  "operation": "command",
  "node_id": 1,
  "endpoint": 1,
  "cluster": 6,
  "command": 1
}
```

Fan percentage, `0..100`:

```json
{
  "id": "android-<unique-request-id>",
  "operation": "write",
  "node_id": 1,
  "endpoint": 1,
  "cluster": 514,
  "attribute": 2,
  "value": 50
}
```

For the initial release, keep these writes explicitly constrained to the known
node/endpoint/cluster/attribute contract. Do not turn arbitrary discovered
Matter attributes into editable fields.

## Meross/local switches through the gateway

The MiFi gateway owns the vendor-specific Meross control mechanism. The
Android app should not reproduce Meross signing or publish to raw appliance
topics.

Four verified switches are exposed as:

```text
gateway/device/switch-1/switch/set
gateway/device/switch-2/switch/set
gateway/device/switch-3/switch/set
gateway/device/switch-4/switch/set
```

Publish lowercase `on` or `off`. Corresponding state topics use:

```text
gateway/device/switch-<1..4>/switch/state
```

Give these user-editable names and room assignments. The gateway web UI calls
them “Meross switch 1” through “Meross switch 4” until renamed.

## Generic convention-based controls

The existing gateway recognizes:

```text
<device>/<switch|light|number>/<entity>/state
```

and maps it to:

```text
<device>/<switch|light|number>/<entity>/command
```

The Android app may implement the same recognizer:

- `switch`: publish `ON` or `OFF`.
- `light`: publish `ON`/`OFF`; brightness support is device-dependent. The web
  gateway accepts brightness `0..255` and wraps it as JSON for its HTTP API,
  but a direct-MQTT app should only send the payload expected by the actual
  topic family.
- `number`: publish a finite numeric value, preferably with configured bounds.

Because generic direct-MQTT payload conventions can vary, start switches and
numbers as read-only unless their command contract is known or confirmed in
settings. The safer alternative is to send controls through the web gateway’s
validated endpoint:

```text
GET /api/control?topic=<URL-encoded-state-topic>&value=<value>
```

## Wake-on-LAN

Discover topics matching exactly:

```text
wol/<12-hex-digit-MAC>/
```

Expose a one-shot **Wake** action. Publish lowercase `wake` to the same topic.
Do not render it as an on/off switch because no persistent power state exists.

## Generic read-only sensors

Promote scalar topics whose final segment clearly represents a measurement,
including temperature/temp, humidity, pressure, battery, voltage, current,
power, energy, illuminance/light level, CO2, particulate matter, air quality,
wind, rain/precipitation, UV index, moisture, occupancy, or motion.

Group readings by their topic prefix rather than creating one card per metric.
Apply user-friendly labels and units. Keep unknown or structured payloads in
diagnostics until a decoder exists.

## Naming, identity, and deduplication

Stable identity rules:

- WLED: device ID from `wled/<id>/...`.
- Govee BLE: normalized MAC address, independent of scanner/topic.
- Matter: node plus endpoint; use Basic Information ProductName for display.
- Weather: fixed provider/source prefix.
- Convention devices: topic prefix through entity name.
- Gateway switches: gateway ID (`switch-1`, etc.).

Display-name priority should be:

1. User alias stored in the app.
2. Device-provided friendly name.
3. Model plus short identifier.
4. Sanitized topic-derived fallback.

Aliases and room assignments belong in local app storage initially. Do not
depend on `/api/override`, because its generated topic IDs are an implementation
detail of the web gateway and may not match the Android app’s stable IDs.

## Freshness and availability

Every displayed value needs a last-update timestamp. Suggested UX:

- Fresh: normal color.
- Delayed: subtle “Updated N minutes ago” label.
- Stale: dim card and show a stale badge.
- Explicit `offline` status: show offline immediately.
- Never replace the last valid sensor reading with zero just because a payload
  is temporarily absent or undecodable.

Thresholds should be source-aware. BLE sensors may naturally update less often
than continuously connected WLED and Matter devices.

## Security and payload safety

- Never surface or persist payloads from topics containing terms such as
  `password`, `passwd`, `secret`, `token`, `apikey`, `api_key`, `credential`,
  or `auth`.
- Do not provide a generic arbitrary-topic publish box in the consumer app.
- Validate numeric bounds and enum choices before publishing.
- Do not log complete MQTT payloads in production builds.
- Store future broker passwords using Android Keystore-backed encrypted storage.
- Treat all MQTT payloads as untrusted input; bound sizes and catch JSON/parser
  failures without crashing the stream processor.

## Recommended Android architecture

Suggested stack:

- Kotlin
- Jetpack Compose and Material 3
- A maintained MQTT client that supports Android lifecycle/reconnect cleanly
- Coroutines and `StateFlow`
- Room for aliases, favorites, ordering, cached state, and broker profiles
- Repository/domain/UI separation
- Foreground service only if continuous background monitoring is genuinely
  required; otherwise connect while the app is foregrounded and rely on the
  retained snapshot after reconnect

Suggested modules/packages:

```text
mqtt/          connection, subscriptions, publish validation
discovery/     recognizers, topic registry, deduplication
devices/       domain models and capabilities
decoders/      Govee, Matter, weather, WLED parsers
data/          Room entities, aliases, settings, last-known state
ui/home/       overview and favorites
ui/lights/     WLED controls
ui/climate/    sensors, weather, Matter readings
ui/devices/    switches, purifier, Wake-on-LAN
ui/settings/   broker, names, rooms, diagnostics
```

Keep recognizers and decoders as pure Kotlin wherever possible so they can be
unit-tested using captured topic/payload fixtures without Android instrumentation.

## Minimum test matrix

1. Cold start with retained messages builds the same devices as live updates.
2. Reconnect does not duplicate cards or reset aliases.
3. The same Govee MAC reported by three scanners becomes one sensor.
4. H5075 and H5179 fixtures decode expected °F, RH, and battery values.
5. Unknown Govee models never display fabricated environmental measurements.
6. WLED IDs auto-appear and named effect/palette/preset catalogs survive a
   temporary HTTP metadata failure.
7. Matter infrastructure clusters do not clutter the main UI.
8. A newly appearing Matter measurement endpoint creates a card automatically.
9. Weather uses imperial topics and does not show duplicate Celsius/km/h cards.
10. Publishing is impossible for an unknown topic or out-of-range value.
11. Broker loss shows offline/stale state and reconnect restores live state.
12. Malformed, oversized, binary, and secret-looking payloads do not crash or
    leak into logs/UI.

## Current live reference snapshot

At the time this handoff was written, the web implementation was deployed and
showed:

- Six discovered Govee devices; supported H5075/H5179 models decode readings,
  while unsupported models remain identified without fake values.
- Current weather and three forecast cards.
- `lab/weather` temperature and humidity.
- Govee H7126 Matter endpoint state and purifier controls.
- Thirteen auto-discovered WLED devices with aliases and named catalogs for
  most lights.
- Four normalized local/Meross switch controls and Wake-on-LAN discovery.

The topic ecosystem is intentionally dynamic. Treat this snapshot as test data,
not as a hard-coded inventory.

## First implementation milestone

Deliver an installable debug APK that:

1. Connects to a configurable broker, defaulting to
   `tcp://192.168.88.14:1883`.
2. Persists a stable installation/client ID and reconnects reliably.
3. Discovers and deduplicates WLED, Govee, weather, Matter, gateway switches,
   and Wake-on-LAN devices.
4. Presents polished Home, Lights, Climate, and Devices screens.
5. Supports validated WLED, purifier, gateway-switch, and Wake controls.
6. Resolves pretty WLED names/effects/palettes/presets.
7. Stores aliases, rooms, favorites, and ordering locally.
8. Includes decoder/discovery unit tests and a diagnostics screen showing
   connection status and recognized versus ignored topic counts.

