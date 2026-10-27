# Bloodsucker Android

A native Material 3 smart-home client for the MQTT broker at
`tcp://192.168.88.14:1883`. It continuously discovers WLED lights, locally
bridged TP-Link Kasa/KL125 lights, Govee BLE sensors, weather, Matter endpoints,
gateway switches, Wake-on-LAN devices, and the explicit ESPHome Geo Lamp.

## Development model

Bloodsucker is developed using a **cathedral model**: the public repository is
source and release distribution, not a collaborative development forum.
Development happens directly on `main` under the maintainer's control.

External pull requests, issues, feature requests, and support requests are not
accepted. Fork the project freely under the GPLv3 and adapt it to your own
installation.

## Build

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The broker URI can be changed from Settings. The app currently connects while
its process is alive, rebuilds its state from retained messages, and stores its
installation ID, aliases, rooms, and favorites in local preferences.

## Geo Lamp ESPHome contract

Geo Lamp support is intentionally an explicit allowlist; it never infers a
writable light from arbitrary JSON or from another light-shaped topic.

| Purpose | Topic | Payload |
| --- | --- | --- |
| Identity | `esphome:geo-lamp` | Display name `Geo Lamp`, native ESPHome light |
| State | `geo-lamp/light/state` | `ON`, `OFF`, or partial JSON |
| Command | `geo-lamp/light/command` | Minimal JSON only |
| Availability | `geo-lamp/status` | `online` or `offline` |

State JSON accepts only `state` (`ON`/`OFF`), `brightness` (JSON integer
`0..255`; strings, booleans, fractions, and out-of-range values are rejected),
and `effect` (`None`, `Campfire`, `Candle`, `Turbulent Flame`). Missing,
malformed, or invalid fields preserve the previous value, so a partial update
never invents power or brightness. Availability is applied independently from
state ordering; a retained state message never changes availability or
refreshes its last-seen time. The first retained snapshot has an unknown live
timestamp rather than falsely reporting "Just now".

An explicit offline observation wins over later state updates and stale
retained `online` replay for the current MQTT connection/session. After a new
connection or subscription, the authoritative retained availability snapshot
may recover the lamp to online. Controls require both a live connection and the
latest repository availability, not a stale device snapshot from the UI.

Commands are the smallest valid JSON object for the action:

- Power: `{"state":"ON"}` or `{"state":"OFF"}`
- Brightness: `{"state":"ON","brightness":0..255}` (no effect field)
- Effect: `{"state":"ON","effect":"<allowlisted effect>"}` (no brightness field)

The UI exposes a percentage slider (`0..100`) that rounds to `0..255` and only
publishes when the drag finishes. `Steady illumination` maps to the `None`
effect. Geo commands are queued for a background IO write, and optimistic state
is applied only after that write and local socket flush complete successfully.
With QoS 0 this local flush is the available success boundary; it does not mean
the broker acknowledged the message. A delayed completion is ignored if newer
authoritative MQTT state arrived or the connection changed, and write/flush
failures do not optimistically update the UI. Controls are disabled while the
lamp is offline or the broker is disconnected.

Gateway snapshots that duplicate any exact Geo Lamp topic are excluded, so the
gateway cannot shadow the native device or expose a generic write path for it.
