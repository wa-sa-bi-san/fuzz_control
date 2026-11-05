# Experimental Duet 3 output protocol

Recovered by static inspection of the user-provided Control 2 DSP 1.21.55 installer. This is independent implementation work; no Apogee binaries, assets or code are redistributed. Physical Android/Duet validation is still pending. These findings supersede the earlier investigation's statement that no command had been recovered.

## Transport evidence

ApogeeGlue x86_64 virtual addresses, not file offsets:

- USB transport vtable 0x1002d8dd8: slot +0x10 -> 0x10003e230 (IN), +0x18 -> 0x10003e3d0 (OUT).
- IN constructs bmRequestType 0xc0; OUT constructs 0x40. bRequest=argument 2; payload=argument 3; length=argument 4; wIndex=argument 5; wValue=argument 6.
- Duet3GlueServerDevice holds this transport at +0x440. Output requests below use one payload byte and wValue=0.
- Android maps these to UsbDeviceConnection.controlTransfer on endpoint zero, with a 500 ms timeout, without claiming streaming interfaces. Documentation: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection

## Recovered operations

| Property | Request | Payload | Index | Setter evidence |
| --- | --- | --- | --- | --- |
| hwout gain | 0x33 | unsigned attenuation; dB = -byte | 0 or 1 | monitor lambda3 at 0x1000c1ee0 |
| dim | 0x40 | 0 off, 1 on | 0 or 1 | lambda4 at 0x1000c1d30 |
| sum (mono) | 0x46 | 0 off, 1 on | 0 or 1 | lambda5 at 0x1000c1b80 |

Both reads and writes use the same request ID, differentiated by request type. Gain readback at 0x1000b2f46..0x1000b2f8a reads one unsigned byte, negates it and stores float dB. Dim readback starts 0x1000b311d. Sum/mono readback uses request 0x46 at 0x1000b320b..0x1000b3238 and publishes to property 0x100380260 at 0x1000b3294.

Property attribution comes from registration, not guesses based on request numbers:

- Registration function 0x1000be610 is called with index 0 / identifier hwout0 at 0x1000b029e and index 1 / hwout1 at 0x1000b02b2.
- Lambda3 vtable 0x1002e1bc0 registered at 0x1000be88c with property global 0x100380110. Its initializer at 0x100267e46 resolves to the string `gain`.
- Lambda4 vtable 0x1002e1c40 registered with 0x100380258. Initializer at 0x10026840a resolves to `dim`.
- Lambda5 vtable 0x1002e1cc0 registered with 0x100380260. Initializer at 0x10026842e resolves to `sum`.
- Output identifiers resolve at 0x10026888a and 0x1002688ae to hwout0 and hwout1. UI deliberately retains output numbers until the owner confirms speaker/headphone association on hardware.

## First implementation limits

Version 0.2.0 restricts level changes to 1 dB steps within -64..0 dB. This is an application restriction, not a claim about the full hardware range. It reads the current level before every step, requires exactly one transferred byte, and verifies each write with a readback. No automatic retries of writes. Invalid response, transfer failure or mismatch disables further editing until a fresh read. Toggle controls also read current state before changing it.

Device selection currently requires product descriptor normalized to `duet3` and manufacturer containing `apogee`. This is conservative descriptor matching, not verified numeric VID/PID matching. If the hardware's descriptors differ or are absent, report them and refine the matcher; do not silently accept every USB audio interface.

No initialization, firmware update, phantom power, audio interface claiming, routing, mixer, DSP or fixed-output-mode command is sent. A successful USB readback is not proof of audible behavior or compatibility with every firmware. Direct USB, hubs, reconnects, audio coexistence and physical output association need owner testing.

## Next hardware check

1. Start with quiet audio. Grant USB access, choose Connect Duet 3 controls, then Read outputs.
2. Compare displayed output levels to the Duet's knob display.
3. Tap Quieter once on output 1; confirm precisely which physical output changes and that the device display agrees. Repeat on output 2.
4. Check Dim and Mono, restoring them afterward. Share the diagnostic report containing the control log.
5. Repeat through the powered hub with MIDI devices attached while audio plays.

Stop if output readback fails. Do not infer success from an enabled button alone. Full mixer parity remains a separate implementation milestone.
