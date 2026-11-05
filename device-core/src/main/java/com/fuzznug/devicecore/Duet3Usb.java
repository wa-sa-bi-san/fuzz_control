package com.fuzznug.devicecore;

import android.hardware.usb.*;
import java.io.IOException;
import java.util.Locale;

/** Endpoint zero only: does not claim audio/MIDI interfaces or change configuration. */
public final class Duet3Usb implements AutoCloseable {
    private final UsbDeviceConnection connection;
    public final Duet3Protocol protocol;
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
        protocol = new Duet3Protocol((type, request, value, index, data) ->
            connection.controlTransfer(type, request, value, index, data, data.length, 500));
    }
    @Override public void close() { connection.close(); }
}
