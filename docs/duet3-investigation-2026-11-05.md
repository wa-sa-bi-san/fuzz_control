# Duet 3 investigation, 2026-11-05

## Status
The existing Android APK is a device inventory tool. No mixer, gain, routing, DSP or phantom power commands have been implemented or verified. This investigation does not establish full Control 2 parity.

## Inspected input
User-provided ApogeeControl2-DSP-macOS.zip, SHA-256 36a1c97e98f32755b3e8a43c79e0c90c53e1b04977c8dc750149c772e981bd18.
ZIP contains Apogee Control 2 DSP_1_21_55.dmg, which contains a flat XAR installer.
Inspected offline without executing installer or Mac binaries. No proprietary binaries or extracted assets committed.

## Observations
- Installer components include Control2.pkg, ApogeeMessenger.pkg, ApogeeGlue.pkg, firmware, and unrelated plugin/license packages.
- Control 2 and Messenger contain Duet 3-specific symbols, including Duet3SideEffects, Duet3FixedOutputSideEffect, PreModelDuet3, and Duet3::resetBlacklist.
- Control 2/Messenger contain state identifiers for mixer channels, phantom power and USB connections. These are identifiers, not a documented wire protocol.
- ApogeeGlue contains USBDeviceMacOS / USBDeviceManager diagnostic strings, sendUSBInVendorRequest / sendUSBOutVendorRequest / readEndpoint / writeEndpoint strings, and a Duet3TX string.
- Those transport strings demonstrate code worth tracing, but do not prove which exact transfer type each Duet 3 control uses.
- Firmware includes a Duet3.bin and separate version file; firmware modification/flashing is not part of this work.
- Mixer/UI elements and transport are separate investigation targets. Recreating faders alone would not deliver working controls.

## Next engineering gates
1. Trace Duet 3 dispatch and transport routines in Glue; identify device matching, request format, endpoints, state addresses, payloads and value encoding.
2. Correlate one reversible parameter (such as output level) with its readback and write path.
3. If static analysis leaves ambiguity, obtain narrowly scoped hardware observations/captures on the owner's machine. Do not assume all macOS releases permit generic USB capture.
4. Implement the smallest verified control in device-core and test on Android with direct USB, then through hubs, while audio continues.
5. Expand to mixer, independent speaker/headphone routing, input configuration, meters, presets and eventually DSP controls as evidence supports each.
6. Never label a control connected/working solely because its UI moves. Never transplant old Duet or ONE model request values into Duet 3 without evidence.

## Reproducible extraction
Use 7-Zip's nested DMG/HFS+ reader to extract the exact installer package path, then extract the flat XAR component Payloads. Inspected Payloads are gzip-compressed CPIO archives. Binary strings and C++ symbols can be inspected offline. Some binaries retain useful symbols; Glue's targeted transport symbols were not recovered by the initial string scan, so disassembly/reference tracing remains necessary.
