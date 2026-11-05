# Fuzz Control

Open-source standalone Android controller for Apogee Duet 3, with a reusable device library for later integration into FuzzNug Ultimate. Unofficial and not affiliated with Apogee.

## Version 0.2.0: experimental output controls

- USB reads and writes for two hardware output levels, Dim and Mono, recovered from the Mac application's transport and named handlers.
- 1 dB level steps; reads actual current state before changing it and verifies the response afterward.
- USB/audio/MIDI inventory, device permission requests, hotplug updates and shareable diagnostics.
- Direct USB and hubs use the same device enumeration. Hardware compatibility, output labels and audio coexistence still need testing on the owner's phone and Duet.

**This is not yet an Apogee Control 2 replacement.** The mixer, input gain/configuration, routing, meters, presets and DSP are not implemented. No physical device tests have been performed. A passing build or USB response is not proof that audio changes correctly.

## Install and test

Open **Actions → Build Android APK → latest successful run → Artifacts → fuzz-control-apk** in your phone's browser. Download and unzip, then open the APK. This is a debug-signed development APK; CI generates a new debug key, so you may need to uninstall a previous build before installing.

1. Connect Duet directly to the phone. Tap **Request USB access** and choose it.
2. Tap **Connect Duet 3 controls**. Initial connection only reads settings.
3. With quiet audio, compare the displayed levels with the Duet. Tap **Quieter · -1 dB** on one output and check the physical change.
4. Confirm which numbered output is speakers and which is headphones. Check Dim and Mono, then restore them.
5. Share the diagnostic report. Repeat through your powered hub with MIDI devices attached and check that audio continues.

If no matching Duet appears, share the report rather than trying another device. The current matcher requires Duet 3 / Apogee USB name descriptors. USB inventory cannot establish hub power, cabling or topology. No Mac or PC is required for the Android app's control path.

## Development

Java 17, SDK 35, AGP 8.9.1, Gradle 8.11.1. The workflow installs Gradle; no wrapper yet.

```sh
gradle :device-core:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

`device-core` contains inventory, endpoint-zero transport and an independently testable protocol. `app` contains the standalone UI. Protocol tests cover USB packet fields, short/failed transfers, invalid responses, readback mismatches, bounds, fresh reads and distinct toggles. See [protocol evidence](docs/duet3-output-protocol.md).

No network permission, analytics, serial collection, audio recording, firmware flashing or audio/MIDI interface claiming. Repository contains no proprietary Apogee binaries/assets, private FuzzNug code, account credentials or signing keys. MIT licensed.
