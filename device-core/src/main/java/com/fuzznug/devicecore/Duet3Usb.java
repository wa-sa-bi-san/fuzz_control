package com.fuzznug.devicecore;

import android.hardware.usb.*;
import java.io.IOException;
import java.util.Locale;

/** Endpoint zero only: does not claim audio/MIDI interfaces or change configuration. */
public final class Duet3Usb implements AutoCloseable {
    private final UsbDeviceConnection connection;
    private final Duet3Protocol.Transport transport;
    public final Duet3Protocol protocol;
    public final Duet3MixerProtocol mixer;
    public final Duet3InputProtocol input;
    public final Duet3DspProtocol dsp;
    public final Duet3SystemProtocol system;
    public final Duet3Controller controller;
    public static boolean matches(UsbDevice device) {
        String name = device.getProductName();
        String maker = device.getManufacturerName();
        return name != null && maker != null
            && name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals("duet3")
            && maker.toLowerCase(Locale.ROOT).contains("apogee");
    }
    public Duet3Usb(UsbManager manager, UsbDevice device) throws IOException {
        if (!matches(device)) throw new IOException("Only a device identifying as Apogee Duet 3 is supported");
        if (!manager.hasPermission(device)) throw new IOException("Request USB access first");
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Cannot open Duet 3. Reconnect and request access.");
        transport = (type, request, value, index, data) ->
            connection.controlTransfer(type, request, value, index, data, data.length, 500);
        protocol = new Duet3Protocol(transport);
        mixer = new Duet3MixerProtocol(transport);
        input = new Duet3InputProtocol(transport);
        dsp = new Duet3DspProtocol(transport);
        system = new Duet3SystemProtocol(transport);
        controller = new Duet3Controller(input,mixer,dsp);
    }
    public int[] readMeterPacket() throws IOException { return Duet3MeterPacket.read(transport); }
    @Override public void close() { connection.close(); }
}
