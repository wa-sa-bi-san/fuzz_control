# Duet 3 input, ECS and system protocol

Implementation 0.5.0. Independently derived from the owner's Control 2 DSP 1.21.55 macOS installer. Addresses refer to the Glue x86_64 slice unless otherwise noted. No binaries, extracted code, artwork or manufacturer presets are included in this repository. Static analysis supports the implementation; physical USB/audio validation remains pending.

## Input controls

One byte, vendor IN 0xc0 / OUT 0x40, wValue 0, wIndex input 0 or 1. Exact transfer length and matching readback are required. No automatic retries.

| Function | Request | Wire value | Read attribution | Setter |
| --- | --- | --- | --- | --- |
| Reference/type | 0x16 | 0 line +4 dBu; 1 line -10 dBV; 2 microphone; 3 instrument | 0x1000b20d0, analogRefLevel at 0x1000b212e | 0x1000c08f0 |
| Mic gain | 0x34 | 0..65 dB | 0x1000b2186, micSettings/PreModelDuet3/gain | 0x1000c0710 |
| Instrument gain | 0x3e | 0..45 dB | 0x1000b227b, instSettings/PreModelDuet3/gain | 0x1000c0390 |
| Phantom | 0x15 | boolean | 0x1000b240d, phantom | 0x1000c05b9 |
| Soft Limit | 0x11 | boolean | 0x1000b237a, softLimit | 0x1000c0e79 |
| Polarity invert | 0x13 | boolean | 0x1000b24bd, polarityInvert | 0x1000c0bd9 |
| Gain group membership | 0x44 | boolean | 0x1000b254d, group | 0x1000b1120 |
| ECS bypass | 0x05 | 0 processing, 1 bypassed | 0x1000b2656, FX/printFx/bypassed | 0x1000c11be, 0x1000c139e |
| Analog stereo link | 0xdb | boolean; index 0 only | 0x1000b1ffe | 0x1000b09b0, 0x1000c1520 |

Type conversion: read jump table at 0x1002c64f4 maps wire 1,2,3 to shared enum 3,1,4; default wire 0 maps enum 2. Setter at 0x1000c0937 maps shared enums back with packed constant 0x301000200. It sends phantom OFF before changing reference type. The Android implementation does the same and only offers phantom enable for Mic, with an explicit user confirmation.

PreModelDuet3 gain range selection at 0x100138778 branches on shared reference enum 1 (mic) and 4 (instrument), selecting constants 0x1002af730 (0..65) and 0x1002af750 (0..45). Line types have no gain control.

Gain grouping preserves the starting offset, including different Mic/Inst ranges. Android preflights both gains before writing, rejects a change exceeding either range, then rereads the peer before deciding whether a second write is needed. This avoids double-applying offsets on firmware that already mirrors changes. A partial USB failure stops immediately; it is not reported as a successful group operation.

## Stereo coordination

The same 0xdb state is published for analog mixer stereo and FX stereo. Messenger MixerSideEffects::propertyChanged at 0x10003b7b0 synchronizes paired level/mute/solo, assigns pans left/right on linking and center on unlinking, and groups eligible non-line preamps. Android uses Analog 1 as the source on linking, copies its mixer level/mute/solo to Analog 2, and explicitly mirrors subsequent edits. Individual pan controls are disabled while linked. Preamp offsets are preserved. Unlinking reveals the independent hardware FX banks; it does not promise to copy the stereo FX bank into the dormant second bank.

Original DSP read logic at 0x1000b2712 skips the second bank when 0xdb is set; Android likewise displays and edits bank 0 for both sides of a stereo pair. FX bypass is applied to both inputs while linked.

## ECS parameter transfer

Read: IN 0xc0, request 0xda, wIndex channel, wValue 0, **68 bytes**. The buffer is 17 little-endian IEEE-754 floats in physical units. The full-bank read occurs at 0x1000b27f3..0x1000b2809, followed by float access at 0x1000b29c2.

Write: OUT 0x40, request 0xda, **wIndex channel, wValue parameter ID**, 4-byte little-endian float. Setter 0x1000c0fe0 calls normalization conversion 0x1000ae7d0 before sending physical units. Generic methods 0x100096870/0x1000968a0 forward to transport without payload byte swapping. Android works directly in physical units and requires matching bank readback after each write.

The descriptor table constructed at 0x100274695..0x10027517c establishes IDs and ranges. Named identifier initializers are at 0x10026b99f onward. All 17 controls are implemented; firmware DSP executes the effect, not the phone.

| ID | Parameter | Physical range |
| --- | --- | --- |
| 0 | EQ bypass | 0/1 |
| 1 | Compressor bypass | 0/1 |
| 2 | High-pass to sidechain | 0/1 |
| 3 | Mid peak bandwidth | 0/1 |
| 4 | EQ before compressor | 0/1 |
| 5 | Ratio selector | 0/1/2, shown as 3:1 / 5:1 / 10:1 |
| 6 | Output level | -20..20 dB |
| 7 | High-pass frequency | 20..300 Hz |
| 8 | Low shelf gain | -15..15 dB |
| 9 | Mid peak gain | -15..15 dB |
| 10 | High shelf gain | -15..15 dB |
| 11 | Low shelf frequency | 35..300 Hz |
| 12 | Mid peak frequency | 250..8000 Hz |
| 13 | High shelf frequency | 4000..16000 Hz |
| 14 | Compressor threshold | -50..0 dB |
| 15 | Wet mix | 0..1 |
| 16 | Drive | 0..10 |

Discrete labels are independently corroborated in the ECS Print plugin x86_64 slice: callback 0x47710 selects Sharp for false and Broad for true; the ordered string table at 0x157d250 contains 3:1, 5:1, 10:1. These establish bandwidth and ratio labels without claiming measured audio behavior.

## Output/system additions

Output mute is request 0x35, index 0/1, boolean; read 0x1000b304f, named mute publication follows, setter 0x1000c1a2f. It is separate from mixer channel mute.

Brightness: request 4, index 1 controller ring / 0 meter LEDs, percent byte. Setter 0x1000bfa50 multiplies UI 0..10 by 10. Read 0x1000b1dd0/0x1000b1dfc scales by 0.1. Android offers 10% steps.

Peak hold: 0x18, overload hold: 0x17, index/value 0, 0/1/2 enum. Setters 0x1000b1980/0x1000b1a30 and read publication at 0x1000b3766..0x1000b39d4 agree. Host volume target is 0x14, index/value 0, output enum 0/1, read 0x1000b2e26 and setter 0x1000bfdfc. Android host behavior still depends on its audio stack.

Clear overload uses OUT 0x19, one byte 1, index/value 0, matching 0x1000b5ce6. It is explicit, never sent automatically by the meter poller. Meter words 6/7 are displayed as unsigned value/100 dB gain reduction.

Sample rate remains owned by Android/the audio application; this controller does not seize audio interfaces or override an active stream. No firmware update or manufacturer plugin activation is attempted.

## Presets and tests

Mixer presets save 20 strip fields; ECS presets save all 17 parameters. Import/export uses Android's document picker and an app-specific versioned JSON format. Imports validate the entire file (64 KB, 50 presets, parameter bounds, finite values, discrete steps, duplicate names) before merging. Import never applies settings. Phantom power and preamp type/gain are deliberately not part of mixer/FX presets. A/B compares captured hardware DSP settings.

Unit tests cover exact DSP fields/endianness, truncated reads, nonfinite values, failed readback, phantom/type ordering, grouped offsets with/without firmware mirroring, grouped range checks before writing, linked mixer/FX targeting, output mute isolation and system bounds. Instrumented emulator tests render every screen in both orientations and check preset round trips/invalid-import atomicity. Neither emulation nor a passing build establishes physical USB/audio compatibility.
