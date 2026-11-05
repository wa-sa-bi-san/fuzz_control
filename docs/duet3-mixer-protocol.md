# Duet 3 mixer: independently recovered protocol

**0.5.0 update:** Stereo linking, input controls, hardware ECS DSP, presets and system controls are now implemented. See [extended evidence](duet3-input-dsp-protocol.md). The gaps below describe the historical 0.4.0 build. Physical validation is still pending.

Version 0.4.0 implements hardware mixer levels, pan, mute, solo, master level/mute independent output source selection and recording-source / loopback selection. Static evidence is from the same user-provided Control 2 DSP 1.21.55 installer as the output investigation. None of the proprietary binaries or disassembly dumps are committed. All addresses below are virtual addresses in the x86_64 slice.

Transport: request type 0xc0 for read, 0x40 for write, wValue 0, length 1, wIndex as below. Both directions use the same bRequest. All writes require a successful preflight read and exact matching readback. No retries, no initialization writes, no audio interface claims.

| Control | Request | Index | Payload | Setter | Read path |
| --- | --- | --- | --- | --- | --- |
| Channel level | 0x4c | 0..3 | integer dB + 48, range 0..54 | 0x1000b07e0 / 0x1000b0c00 | 0x1000b3dfb / 0x1000b435d |
| Channel pan | 0x4d | 0..3 | 0..128; 64 center | 0x1000b08d0 / 0x1000b0d00 | 0x1000b4162 / 0x1000b46dd |
| Channel mute | 0x4f | 0..3 | boolean | 0x1000b0a60 / 0x1000b0de0 | 0x1000b3efb / 0x1000b4467 |
| Channel solo | 0x4e | 0..3 | boolean | 0x1000b0b30 / 0x1000b0eb0 | 0x1000b3fc8 / 0x1000b4539 |
| Master level | 0x4c | 4 | integer dB + 48 | 0x1000b0f70 | 0x1000b4817 |
| Master mute | 0xe2 | 0 | boolean | 0x1000b1060 | 0x1000b4915 |
| Recording source | 0xdc | 0 | same source IDs, excluding Follow | 0x1000c0160 | 0x1000b1efa |
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

- Live meters are implemented using IN 0x55 at up to 5 Hz. Physical device validation remains pending; acquisition/mapping evidence is documented below.
- Analog stereo-link editing is not included. Request 0xdb is read at 0x1000b1ffe and written at 0x1000b09df; its effects include analog input state as well as mixer `isStereo`. Paired parameter synchronization needs further tracing before adding a switch.
- Recording source is implemented through 0xdc. The setter registered at 0x1000b02ce..0x1000b0330 targets `swin/swin_1_2/routeSource`. Its closure at 0x1000c0160 maps the same shared source enums (0,1,2,4,5,6) and sends 0xdc with index/value 0. Readback publishes the same property at 0x1000b1f32..0x1000b1f99. The UI provides source selection for USB recording inputs 1–2; it does not claim arbitrary loopback destination channels.
- Input preamp configuration, phantom power, DSP, presets and fixed-output mode remain outside this build.
- Existing hardware stereo-link state is preserved. Linked behavior must be tested; independent UI channel controls do not promise to override hardware linkage.

## Validation

Unit tests use an injected USB transport: full snapshot read coverage, playback/master indexing, request direction, value conversion, pan bounds, separate mute/solo commands, per-output routing, malformed and short reads, readback mismatch and rejection of unsupported combinations. These tests do not prove physical device compatibility.

The Android UI has horizontally scrollable channel strips, release-to-send vertical faders, pan sliders, mute/solo state and a separate routing tab. Values are refreshed from the device after each completed operation and via Read. Live meter polling is separate from settings reads, stops on error/background/disconnect, and does not queue unbounded work. Controls disable while busy or after failure, and reconnect/resume require fresh reads. Snapshots from a previous connection/lifecycle generation are discarded. A failed post-write read is reported as possibly applied, not rolled back.

## Read-only meter capture for hardware correlation

Devices → Capture raw meter packet performs exactly one IN request 0x55 and records the 14 unsigned big-endian words in the shareable report. It does not label these values as input/playback/master meters or clear overload flags. Exact 28-byte length is required. Tests cover byte order, high-bit preservation and truncated transfers. Capture during silence, then with only Analog 1, only Analog 2 and individual playback pairs active to establish the remaining physical mapping. This diagnostic facility is available alongside the live meter display.


## Meter mapping and decoding (0.4.0)

Glue acquisition at 0x1000b5c30 sends vendor IN 0x55, index/value 0, length 28, and swaps every 16-bit word from big endian. Conversion at 0x1000b5e60 extracts overload bit 15 and computes 20*log10((word & 0x7fff)/32768). The call at 0x10028528c resolves through stub pointer 0x1002d3a20 to `_log10`. Words 6 and 7 instead divide by 100 at 0x1000b01e2..0x1000b0233 and are gain reduction, not audio level.

The Control 2 `DeviceInfo::metersForType` function is at 0x1001c7230. Its ordinary-device branch arranges groups as speaker meters, headphone meters, additional hardware outputs, analog inputs, digital inputs, FX reduction, playback, and mixer master. `DeviceInfo::updateFeatures` computes offsets at 0x1001c2d7d..0x1001c2dba from the hardware model counts. Speaker and headphone counts derive from hwout monitorType at 0x1001c29bb..0x1001c2c15. Combining the Duet's one speaker pair, one headphone pair, two analog inputs, no additional/digital inputs, two reduction values, four playback channels and one stereo mixer gives this packet mapping:

| Words (zero based) | Signal |
| --- | --- |
| 0, 1 | Main / speaker output L, R |
| 2, 3 | Headphone output L, R |
| 4, 5 | Analog input 1, 2 |
| 6, 7 | Input FX gain reduction, excluded from dBFS bars |
| 8, 9 | Playback 1–2 |
| 10, 11 | Playback 3–4 |
| 12, 13 | Mixer master L, R |

This mapping is a static-analysis inference, pending physical signal correlation. UI bars are actual decoded device values, never animation or random samples. Zero amplitude displays negative infinity. A failed/truncated meter read clears bars and labels them unavailable without disabling independently working mixer controls. Read reconnects polling. Settings reads/writes and meter reads share one worker so USB work is serialized. There is no automatic overload-reset write.
