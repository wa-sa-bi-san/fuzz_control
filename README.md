# Fuzz Control
Standalone Android diagnostics for an Apogee Duet 3 and USB/MIDI devices, intended for later private integration into FuzzNug Ultimate.

## Version 0.1.0
- Lists USB interfaces/endpoints, MIDI 1.0 devices, and Android audio inputs/outputs.
- Refreshes when devices connect/disconnect, including devices behind hubs.
- Explicit per-device USB permission request.
- Shareable plain-text report. No network permission, analytics, serial-number collection, audio recording, interface claiming, or vendor command writes.
- Does **not** yet change Duet gain, routing, monitoring, phantom power, or DSP. Listing channels does not prove simultaneous multichannel audio support.

## Install
Open this repository's **Actions → Build Android APK → latest successful run → Artifacts → fuzz-control-apk** in your phone's browser. Download and unzip the artifact, then open the APK and allow installation from that browser/file manager if Android asks.
This is a debug-signed development APK. CI uses a generated debug signing key; a future build may require uninstalling the previous app before installing. Stable private signing will be configured later without committing private keys.

## First hardware test
1. Connect the Duet directly to the phone. Open Fuzz Control and share its report.
2. Repeat through a powered hub with Duet and a MIDI controller attached.
3. Repeat with the second hub. Unplug/reconnect each peripheral and verify the list refreshes.
4. While your usual audio app plays through the Duet, open Fuzz Control and refresh. Confirm playback remains stable.
USB inventory cannot reliably show hub topology, power budget, or cable health. No computer needs to remain connected for this Android diagnostic app.

## Development
Java 17, Android SDK 35, Android Gradle Plugin 8.9.1, Gradle 8.11.1.
Open the project in Android Studio, or run:
```sh
gradle :app:assembleDebug :app:lintDebug
```
The GitHub workflow installs Gradle; a wrapper is not yet included.

## Architecture and next step
`device-core` is an Android library with read-only inventory, independent of the standalone UI in `app`. It can later be imported into FuzzNug.
Next: capture/inspect the Duet 3's actual control protocol using owned hardware and Apogee Control 2, document commands, then prove readback and a reversible control change while audio runs. Do not reuse another Apogee model's command values without verification. Firmware updates and DSP recreation are outside this first version.

Unofficial project; not affiliated with Apogee. Hardware behavior and UI have not yet been tested on a physical phone.
