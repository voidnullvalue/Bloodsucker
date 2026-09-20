# Bloodsucker Android

A native Material 3 smart-home client for the MQTT broker at
`tcp://192.168.88.14:1883`. It continuously discovers WLED lights, Govee BLE
sensors, weather, Matter endpoints, gateway switches, and Wake-on-LAN devices.

## Build

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The broker URI can be changed from Settings. The app currently connects while
its process is alive, rebuilds its state from retained messages, and stores its
installation ID, aliases, rooms, and favorites in local preferences.
