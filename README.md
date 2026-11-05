# Fuzz Control

Standalone open-source Android controller for **Apogee Duet 3**, with reusable `device-core` code for future FuzzNug integration. Unofficial and not affiliated with Apogee.

## Version 0.5.0

- **Mixer:** Analog 1/2, Playback 1–2/3–4, master faders, pan, mute/solo, hardware stereo linking and live meters.
- **Inputs:** Mic, Instrument, +4 dBu and -10 dBV line modes; preamp gain; offset-preserving gain grouping; 48 V phantom; Soft Limit; polarity.
- **Hardware ECS FX:** all 17 parameters, including three-band EQ, high-pass, compressor, wet mix, drive, output level, section/global bypass and gain-reduction meters. Stereo FX uses the shared bank when linked.
- **Outputs:** independent source routing, level, mute, Dim, Mono, mute-all and recording-source/loopback selection.
- **Presets:** named mixer/ECS presets, JSON import/export, Flat FX starting point and A/B comparison. These are app-owned presets, not manufacturer factory presets.
- **System:** controller/meter LED brightness, peak/overload hold, clear meters and USB host volume target.
- **Devices:** USB/audio/MIDI inventory, permission handling, hotplug and shareable diagnostics. Direct cables and hubs use the same USB enumeration.

**Hardware validation is pending.** These controls are independently implemented from static analysis of the owner's Mac installer. Unit tests and emulator checks do not establish that every firmware version behaves identically. Physical signal mapping, discrete label direction, grouping/stereo behavior and audio/MIDI coexistence must be confirmed on the phone and Duet. Unavailable reads are shown as unavailable; no controls or meter data are simulated.

## Install

Open **Actions → Build Android APK → latest successful run → Artifacts → fuzz-control-apk** in the phone's browser. Extract the ZIP and install the APK. CI currently produces debug-signed development builds. A new build may require uninstalling the previous one; export saved presets before uninstalling.

Connect Duet, tap **Connect**, choose it and grant USB access. Connection only reads settings. Use the horizontally scrollable tabs for Mixer, Inputs, FX, Outputs, Presets, System and Devices. Sliders send on release, and numeric entry is available for gain and DSP parameters. Settings are reread after edits; failures stop changes until **Read** recovers the connection. Partial multi-command failures may leave some settings applied.

Start physical validation with quiet monitoring. Compare displayed input/output settings with the device, confirm output numbering, then test mixer, stereo/grouping, FX and recording routing. For phantom power, enable only with compatible connected equipment. For loopback, disable the recording app's input monitoring before selecting Mixer/Playback to prevent a feedback loop. Repeat through the intended powered hub with MIDI devices and audio active; share the Devices report if a read or operation fails.

## Platform boundaries

- Android/your recording app owns sample rate and audio/MIDI streams. Fuzz Control never claims those interfaces and is not a DAW or MIDI router.
- No firmware flashing, license activation, manufacturer preset redistribution, or bundled proprietary code/artwork.
- Mixer/ECS presets do not change preamp type/gain/phantom or physical output routing. They are scoped to the named section.
- Manufacturer desktop-only features and exact UI artwork are not reproduced. This is an original mobile controller, not the Mac app packaged in an APK.
- No network permission, analytics, audio recording or serial collection.

## Development

Java 17, SDK 35, AGP 8.9.1, Gradle 8.11.1. CI installs Gradle and SDK packages.

```sh
gradle :device-core:testDebugUnitTest :app:assembleDebug :app:lintDebug
gradle :app:connectedDebugAndroidTest
```

The APK workflow builds/tests/lints; the screen workflow runs an Android 35 emulator and uploads portrait/landscape screenshots and instrumented test reports. The screenshot fixture lives only in `androidTest` and never connects to hardware.

Protocol evidence: [outputs](docs/duet3-output-protocol.md), [mixer/meters](docs/duet3-mixer-protocol.md), [inputs, stereo, DSP and system](docs/duet3-input-dsp-protocol.md). Earlier investigation documents describe historical stages, not the current feature set. MIT licensed.
