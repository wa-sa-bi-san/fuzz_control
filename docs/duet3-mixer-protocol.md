# Duet 3 mixer: independently recovered protocol

Version 0.3.0 implements hardware mixer levels, pan, mute, solo, master level/mute and independent output source selection. Static evidence is from the same user-provided Control 2 DSP 1.21.55 installer as the output investigation. None of the proprietary binaries or disassembly dumps are committed. All addresses below are virtual addresses in the x86_64 slice.

Transport: request type 0xc0 for read, 0x40 for write, wValue 0, length 1, wIndex as below. Both directions use the same bRequest. All writes require a successful preflight read and exact matching readback. No retries, no initialization writes, no audio interface claims.

| Control | Request | Index | Payload | Setter | Read path |
| --- | --- | --- | --- | --- | --- |
| Channel level | 0x4c | 0..3 | integer dB + 48, range 0..54 | 0x1000b07e0 / 0x1000b0c00 | 0x1000b3dfb / 0x1000b435d |
| Channel pan | 0x4d | 0..3 | 0..128; 64 center | 0x1000b08d0 / 0x1000b0d00 | 0x1000b4162 / 0x1000b46dd |
| Channel mute | 0x4f | 0..3 | boolean | 0x1000b0a60 / 0x1000b0de0 | 0x1000b3efb / 0x1000b4467 |
| Channel solo | 0x4e | 0..3 | boolean | 0x1000b0b30 / 0x1000b0eb0 | 0x1000b3fc8 / 0x1000b4539 |
| Master level | 0x4c | 4 | integer dB + 48 | 0x1000b0f70 | 0x1000b4817 |
| Master mute | 0xe2 | 0 | boolean | 0x1000b1060 | 0x1000b4915 |
| Physical output source | 0x53 | 0..1 | enum below | 0x1000c20c0 | 0x1000b32e1 |

Read state is published under mixers/mixer0/channels. Indexes 0 and 1 are the analog channels; playback loop explicitly adds 2 and addresses channel2/channel3 (0x1000b42f8..0x1000b4346). Master is a separate node at 0x100380668.

Named property attribution: read 0x4c publishes `volume` at 0x1000b3e7d; 0x4f publishes `mute` at 0x1000b3f4b; 0x4e publishes `solo` at 0x1000b4018; 0x4d publishes `pan` at 0x1000b41da. Property identifiers were resolved through their static initializers. Pan readback multiplies the unsigned byte by 1/128 (constant 0x1002afe68); setters multiply normalized pan by 128. Level setters clamp -48..+6 and add 48; reads subtract 48. The master mute read publishes `mute` at 0x1000b4966.

The documented channel layout, independently corroborating labels, is Analog IN 1, Analog IN 2, Playback 1-2, Playback 3-4, Mixer Master in Apogee's guide:
https://knowledge.apogeedigital.com/hubfs/Users%20Guides%20and%20Documentation%20PDFs/Duet3UsersGuide.pdf?hsLang=en

## Output routing attribution

Duet3 monitor lambda2 translates shared RouteSource integer IDs into USB bytes. These shared IDs are independently named by the retained ApogeeMessenger symbols and initializers, rather than inferred from enumeration order.

| USB byte | Shared ID | Meaning | Messenger initializer |
| --- | --- | --- | --- |
| 0 | 0x18000000 | Playback1_2 | 0x10020f76a |
| 1 | 0x18000002 | Playback3_4 | 0x10020f7a9 |
| 2 | 0x28000000 | Mixer1 | 0x10020f5e2 |
| 3 | 0x40000000 | Follow | 0x10020f5a3 |
| 4 | 0x30000000 | HWIn_1 | 0x10020ffe4 |
| 5 | 0x30000001 | HWIn_2 | 0x100210023 |
| 6 | 0x38000000 | HWIn_1_2 | 0x10020fdc2 |

Follow is displayed if read, but not selectable because dependency behavior between physical outputs has not been established. All other six routes are selectable independently. Changing a route is explicit, never automatic on connection. Output speaker/headphone numbering still requires hardware confirmation.

## Known gaps, not simulated

- No live audio meters. Meter sample processing exists at 0x1000b0080, but the request/endpoint acquisition path has not yet been traced. Never animate fabricated level meters.
- Analog stereo-link editing is not included. Request 0xdb is read at 0x1000b1ffe and written at 0x1000b09df; its effects include analog input state as well as mixer `isStereo`. Paired parameter synchronization needs further tracing before adding a switch.
- Loopback / Assign to SW Inputs is not included. Request 0xdc is read at 0x1000b1efa; source enum and setter require correlation.
- Input preamp configuration, phantom power, DSP, presets and fixed-output mode remain outside this build.
- Existing hardware stereo-link state is preserved. Linked behavior must be tested; independent UI channel controls do not promise to override hardware linkage.

## Validation

Unit tests use an injected USB transport: full snapshot read coverage, playback/master indexing, request direction, value conversion, pan bounds, separate mute/solo commands, per-output routing, malformed and short reads, readback mismatch and rejection of unsupported combinations. These tests do not prove physical device compatibility.

The Android UI has horizontally scrollable channel strips, release-to-send vertical faders, pan sliders, mute/solo state and a separate routing tab. Values are refreshed from the device after each completed operation and via Read. There is no periodic polling yet. Controls disable while busy or after failure, and reconnect/resume require fresh reads. Snapshots from a previous connection/lifecycle generation are discarded. A failed post-write read is reported as possibly applied, not rolled back.
