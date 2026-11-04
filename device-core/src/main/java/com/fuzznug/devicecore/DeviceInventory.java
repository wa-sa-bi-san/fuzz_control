package com.fuzznug.devicecore;

import android.content.Context;
import android.hardware.usb.*;
import android.media.*;
import android.media.midi.*;
import android.os.Build;
import java.util.*;

/** Read-only inventory. Never claims an interface, starts audio or sends vendor commands. */
public final class DeviceInventory {
    private DeviceInventory() {}
    public static List<UsbDevice> usbDevices(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbDevice> devices = new ArrayList<>();
        if (manager != null) devices.addAll(manager.getDeviceList().values());
        devices.sort(Comparator.comparing(UsbDevice::getDeviceName));
        return devices;
    }
    public static String report(Context context) {
        StringBuilder out = new StringBuilder("FUZZ CONTROL 0.1.0 — DEVICE REPORT\n");
        out.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
            .append(") / ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        out.append("Inventory only. Hardware controls are not implemented.\n");
        out.append("No serial numbers collected. Review names before sharing.\n\nUSB DEVICES\n");
        UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbDevice> devices = usbDevices(context);
        out.append("Count: ").append(devices.size()).append("\n");
        for (UsbDevice d : devices) {
            out.append("\n").append(d.getProductName()).append(" / ").append(d.getManufacturerName())
                .append(String.format(Locale.ROOT, "\nVID:PID %04x:%04x", d.getVendorId(), d.getProductId()))
                .append("\nPermission: ").append(usb != null && usb.hasPermission(d))
                .append("\nInterfaces: ").append(d.getInterfaceCount()).append("\n");
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface intf = d.getInterface(i);
                out.append("  Interface ").append(intf.getId()).append(" alt ").append(intf.getAlternateSetting())
                    .append(" class/subclass/protocol ").append(intf.getInterfaceClass()).append("/")
                    .append(intf.getInterfaceSubclass()).append("/").append(intf.getInterfaceProtocol()).append("\n");
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    out.append("    Endpoint ").append(ep.getAddress()).append(ep.getDirection() == UsbConstants.USB_DIR_IN ? " IN" : " OUT")
                        .append(" type ").append(ep.getType()).append(" packet ").append(ep.getMaxPacketSize())
                        .append(" interval ").append(ep.getInterval()).append("\n");
                }
            }
        }
        out.append("\nAUDIO DEVICES VISIBLE TO ANDROID\n");
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_ALL)) {
            out.append(d.getProductName()).append(" / type ").append(d.getType())
                .append(d.isSource() ? " INPUT" : "").append(d.isSink() ? " OUTPUT" : "")
                .append("\n  Channels ").append(Arrays.toString(d.getChannelCounts()))
                .append(" / sample rates ").append(Arrays.toString(d.getSampleRates())).append("\n");
        }
        out.append("\nMIDI DEVICES VISIBLE TO ANDROID\n");
        MidiManager midi = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
        if (midi == null) out.append("MIDI service unavailable\n");
        else {
            MidiDeviceInfo[] midiDevices = midi.getDevices();
            out.append("MIDI 1.0 count: ").append(midiDevices.length).append("\n");
            for (MidiDeviceInfo d : midiDevices) {
                out.append(d.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME, "Unnamed"))
                    .append(" / ID ").append(d.getId()).append(" / type ").append(d.getType())
                    .append(" / input ports ").append(d.getInputPortCount())
                    .append(" / output ports ").append(d.getOutputPortCount()).append("\n");
            }
        }
        out.append("\nHub topology and available power cannot be determined reliably from this inventory.\n");
        return out.toString();
    }
}
