# MeshWars Scanner

Android MeshCore and Meshtastic scanner for WDGWARS.

## Features

- MeshCore Companion BLE connection and self-information decoding
- Meshtastic BLE connection and NodeInfo protobuf decoding
- Heltec V3 detection over BLE and USB host attach notifications
- WDGWARS-compatible validation and HMAC uploads
- Offline capture history and JSON/CSV export
- Pager-style live radar UI
- User-triggered OTA updates from GitHub Releases

## Build

```bash
./gradlew assembleDebug
```

The APK is copied to `MeshWarsScanner.apk` after a build.

## OTA releases

The app checks:

`https://api.github.com/repos/sinXne0/MeshWarsScanner/releases/latest`

Each release should attach an asset named `MeshWarsScanner.apk`. In the app, tap
`UPDATE` in the top console to check and install a newer release.

Android will show the normal package-install confirmation. The user must allow
this app to install unknown apps the first time.
