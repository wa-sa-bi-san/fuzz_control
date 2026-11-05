# USB transport analysis

Historical checkpoint. Superseded by [the recovered output protocol](duet3-output-protocol.md) and experimental 0.2.0 implementation.
Static inspection of user-provided Control 2 DSP 1.21.55, ApogeeGlue x86_64 slice. No hardware requests sent.

Located vendor-OUT implementation at virtual address 0x10003e3d0 by cross-reference to the sendUSBOutVendorRequest diagnostic. It constructs a request with bmRequestType 0x40. Request is taken from sil; value from r9w; index from r8w; length from cx; data pointer from rdx. It calls the device interface indirectly at vtable offset 0xf0. This is a generic helper, not yet a verified Duet 3 parameter command.

Located Duet3GlueServerDevice destructor at 0x1000b5f90 by cross-reference to the device destruction diagnostic. Its initial vtable address resolves to 0x1002da858. RTTI strings identify Duet3GlueServerDevice handlers for monitor settings, input settings, general setters and FX. Glue has only a small remaining symbol table; these RTTI names do not themselves expose request values.

A full x86_64 direct-call scan found no direct calls to 0x10003e3d0, consistent with virtual/indirect dispatch. Next analysis must resolve that dispatch and the Duet3-specific handler closures, then correlate exact request IDs, data payloads and readbacks. Do not interpret bmRequestType 0x40 as a complete command or expose speculative hardware sliders.

Inspection used LIEF for Mach-O parsing and Capstone for disassembly. Stream disasm_lite rather than accumulating detailed instructions for the entire binary, which has excessive memory overhead. macOS binaries remain outside the repository.

Status: Android app still diagnostic-only. No verified speaker-volume, headphone-volume, input-gain or routing command has been recovered yet. Physical-device validation remains necessary after recovering the commands.
