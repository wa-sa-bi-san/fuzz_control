# Fuzz Control

Open-source standalone Android controller for Apogee Duet 3, with a reusable device library for later integration into FuzzNug Ultimate. Unofficial and not affiliated with Apogee.

## Version 0.4.0: experimental hardware mixer and output routing

- Five mixer strips: Analog 1, Analog 2, Playback 1–2, Playback 3–4 and Mix master.
- Live device meters and overload indicators, polled at up to 5 Hz while the Mixer or Outputs tab is active.
- Mixer faders (-48..+6 dB), pan, channel mute/solo and master mute, with hardware readback.
- Recording-source selection for USB inputs 1–2, including the mixer for loopback.
- Independent output routing to Mixer, playback pairs or analog sources.
- Two physical output levels, Dim and Mono, recovered from the Mac application's transport and named handlers.
- 1 dB level steps; reads actual current state before changing it and verifies the response afterward.
- USB/audio/MIDI inventory, device permission requests, hotplug updates and shareable diagnostics.
- Direct USB and hubs use the same device enumeration. Hardware compatibility, output labels and audio coexistence still need testing on the owner's phone and Duet.

**This is not yet an Apogee Control 2 replacement.** Analog stereo-link editing, input gain/configuration, presets and DSP are not implemented. No physical device tests have been performed. A passing build or USB response is not proof that audio changes correctly.

## Install and test

Open **Actions → Build Android APK → latest successful run → Artifacts → fuzz-control-apk** in your phone's browser. Download and unzip, then open the APK. This is a debug-signed development APK; CI generates a new debug key, so you may need to uninstall a previous build before installing.

1. Connect Duet directly to the phone. Tap **Connect**, choose it and grant USB access.
2. Initial connection only reads settings. The Mixer tab shows the actual device values.
3. With quiet audio, compare the displayed levels with the Duet. On the Outputs tab, tap **Quieter · -1 dB** on one output and check the physical change.
4. Confirm which numbered output is speakers and which is headphones. Check Dim and Mono, then restore them.
5. Set the desired output source to Mixer, then check each channel fader, pan, mute/solo and master at quiet levels.
6. Share the diagnostic report from Devices. Repeat through your powered hub with MIDI devices attached and check that audio continues.

If no matching Duet appears, share the report rather than trying another device. The current matcher requires Duet 3 / Apogee USB name descriptors. USB inventory cannot establish hub power, cabling or topology. No Mac or PC is required for the Android app's control path.

## Development

Java 17, SDK 35, AGP 8.9.1, Gradle 8.11.1. The workflow installs Gradle; no wrapper yet.

```sh
gradle :device-core:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

`device-core` contains inventory, endpoint-zero transport and an independently testable protocol. `app` contains the standalone UI. Protocol tests cover USB packet fields, short/failed transfers, invalid responses, readback mismatches, bounds, fresh reads and distinct toggles. See [output evidence](docs/duet3-output-protocol.md) and [mixer/routing evidence](docs/duet3-mixer-protocol.md).

No network permission, analytics, serial collection, audio recording, firmware flashing or audio/MIDI interface claiming. Repository contains no proprietary Apogee binaries/assets, private FuzzNug code, account credentials or signing keys. MIT licensed.
