# GAMR App

Android companion app for the GAMR MAT. It connects directly to the MAT over Bluetooth Low Energy (BLE) to view device information and change supported settings. The MAT remains the HID input device; this app is its configuration and diagnostics companion.

## What it does

- Scans for nearby `GAMR-…` devices (and the legacy `Poorna_GAMR` name).
- Connects using BLE and shows the device ID, firmware version, battery level, active MAT mode, and touch-sensitivity value.
- Selects the four firmware modes: **GAMR**, **Rhythm**, **Balance**, and **Custom**.
- Changes and stores touch sensitivity (50–4095; lower values are more sensitive).
- Changes the inactive auto-shutdown period (5–60 minutes).
- Edits the nine grouped zones in Custom mode, including directions, diagonals, face buttons, and L/R buttons and sticks.
- Shows a live developer 5×5 MAT touch grid supplied by the firmware debug service.

## Requirements

- Android 8.0 (API 26) or newer.
- Bluetooth Low Energy enabled on the phone.
- A GAMR MAT running firmware that provides the GAMR control and debug BLE services.
- Android 12+ requires the **Nearby devices** Bluetooth permission. Android 11 and earlier requires location permission for BLE scanning.

## Use

1. Turn on Bluetooth and power the GAMR MAT.
2. Open the app and tap **Scan**.
3. Select the discovered `GAMR-…` MAT and connect.
4. Use the connected-MAT card to change mode, touch sensitivity, auto shutdown, or Custom mode actions.
5. Tap **Disconnect** when finished.

Settings are written to the MAT and persist there; they are not merely app-side preferences.

## Modes

| Mode | Purpose |
| --- | --- |
| GAMR | Default gamepad layout. |
| Rhythm | Uses the outer eight MAT zones for cardinal and diagonal rhythm-game input. |
| Balance | Uses the inner 3×3 region as a directional control area; the centre is idle. |
| Custom | Lets you configure the app’s nine grouped MAT zones independently. |

The exact HID representation (keyboard or gamepad) is selected by the firmware/profile. The app configures MAT behaviour over its control BLE service; it does not replace the MAT’s HID connection to the host device.

## Build and install

Open `gamr-app` in Android Studio, allow Gradle to sync, connect an Android device, then run the `app` configuration. The project uses Kotlin, Jetpack Compose, Android Gradle Plugin 9.3.2, compile SDK 37, and has a minimum SDK of 26.

To create a debug APK from a terminal on Windows:

```powershell
.\gradlew.bat assembleDebug
```

The resulting APK is at `app\build\outputs\apk\debug\app-debug.apk`.

## BLE contract

The app reads standard Device Information (`0x180A`) and Battery (`0x180F`) services, and uses these GAMR services:

| Service | Characteristic | Use |
| --- | --- | --- |
| `0xFFF5` | `0xFFF6` | Read and write mode, touch threshold, Custom mapping, and shutdown period. |
| `0xFFF7` | `0xFFF9` | Receive live 5×5 MAT-frame debug notifications. |

The control commands currently used are: `0x02` touch threshold, `0x03` MAT mode, `0x04` set Custom-zone action, `0x05` reset Custom actions, and `0x06` auto-shutdown period.

## Project layout

```text
app/src/main/java/com/gamr/app/
├── MainActivity.kt          # Compose UI and permission flow
├── ble/GamrBleScanner.kt    # GAMR BLE discovery
└── ble/GamrBleClient.kt     # GATT reads, writes, and MAT-frame notifications
```

## Current limitation

The app only sees the configuration/debug GATT services. It does not record or display the operating system’s HID keyboard/gamepad events; use the firmware input-recorder page for that testing.
