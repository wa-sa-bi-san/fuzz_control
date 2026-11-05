package com.fuzznug.devicecore;

import java.io.IOException;

/** Independently recovered Control 2 1.21.55 protocol. Hardware validation pending. */
public final class Duet3Protocol {
    public static final int LEVEL = 0x33, DIM = 0x40, MONO = 0x46, MUTE = 0x35;
    public interface Transport {
        int transfer(int type, int request, int value, int index, byte[] data);
    }
    private final Transport transport;
    public Duet3Protocol(Transport transport) { this.transport = transport; }
    private static void validate(int output, int request) {
        if (output < 0 || output > 1) throw new IllegalArgumentException("Invalid output");
        if (request != LEVEL && request != DIM && request != MONO && request != MUTE)
            throw new IllegalArgumentException("Unsupported command");
    }
    public int read(int output, int request) throws IOException {
        validate(output, request);
        byte[] data = new byte[1];
        int count = transport.transfer(0xc0, request, 0, output, data);
        if (count != 1) throw new IOException("USB read failed (" + count + ")");
        int value = data[0] & 255;
        if ((request == LEVEL && value > 127) || (request != LEVEL && value > 1))
            throw new IOException("Unexpected device response: " + value);
        return value;
    }
    public int writeAndVerify(int output, int request, int value) throws IOException {
        validate(output, request);
        // First experimental release limits output level to -64..0 dB.
        if (value < 0 || value > (request == LEVEL ? 64 : 1))
            throw new IllegalArgumentException("Value outside supported range");
        int count = transport.transfer(0x40, request, 0, output, new byte[]{(byte)value});
        if (count != 1) throw new IOException("USB write failed (" + count + "); reread device");
        int actual = read(output, request);
        if (actual != value) throw new IOException("Readback mismatch: requested " + value + ", received " + actual);
        return actual;
    }
    public int stepLevel(int output, int dbChange) throws IOException {
        if (dbChange != -1 && dbChange != 1) throw new IllegalArgumentException("Use 1 dB steps");
        int attenuation = read(output, LEVEL);
        if (attenuation > 64) throw new IOException("Level below experimental range. Adjust on Duet first.");
        int next = Math.max(0, Math.min(64, attenuation - dbChange));
        return next == attenuation ? attenuation : writeAndVerify(output, LEVEL, next);
    }
    public int toggle(int output, int request) throws IOException {
        if (request != DIM && request != MONO && request != MUTE) throw new IllegalArgumentException("Not a toggle");
        return writeAndVerify(output, request, 1 - read(output, request));
    }
}
