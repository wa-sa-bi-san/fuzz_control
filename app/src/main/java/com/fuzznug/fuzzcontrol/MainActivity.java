package com.fuzznug.fuzzcontrol;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.media.*;
import android.media.midi.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.fuzznug.devicecore.DeviceInventory;
import java.util.List;

public final class MainActivity extends Activity {
    private TextView reportView;
    private String report = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String permissionAction = "com.fuzznug.fuzzcontrol.USB_PERMISSION";
    private final Runnable refreshTask = () -> refresh();
    private AudioManager audio;
    private MidiManager midi;
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (permissionAction.equals(intent.getAction())) {
                Toast.makeText(c, intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    ? "USB access granted" : "USB access was not granted", Toast.LENGTH_SHORT).show();
            }
            queueRefresh();
        }
    };
    private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { queueRefresh(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { queueRefresh(); }
    };
    private final MidiManager.DeviceCallback midiCallback = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo device) { queueRefresh(); }
        @Override public void onDeviceRemoved(MidiDeviceInfo device) { queueRefresh(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(18, 22, 30));
        layout.setPadding(dp(20), dp(20), dp(20), dp(12));
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = text("FUZZ CONTROL", 26);
        title.setTextColor(Color.rgb(142, 235, 196));
        layout.addView(title);
        layout.addView(text("Duet 3 · USB & MIDI diagnostics", 16));
        layout.addView(text("Connect directly or through powered hubs. This version detects devices; Apogee settings controls are still under investigation.", 14));
        addButton(layout, "Refresh devices", () -> refresh());
        addButton(layout, "Request USB access…", () -> chooseUsb());
        addButton(layout, "Share diagnostic report", () -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Fuzz Control diagnostic report");
            send.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(send, "Share report"));
        });
        ScrollView scroll = new ScrollView(this);
        reportView = text("", 13);
        reportView.setTypeface(android.graphics.Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        scroll.addView(reportView);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        refresh();
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(Color.rgb(228, 233, 240)); t.setPadding(0, dp(6), 0, dp(8)); return t;
    }
    private void addButton(LinearLayout parent, String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    }
    private void queueRefresh() { handler.removeCallbacks(refreshTask); handler.postDelayed(refreshTask, 250); }
    private void refresh() {
        try { report = DeviceInventory.report(this); }
        catch (RuntimeException e) { report = "Unable to read inventory: " + e.getClass().getSimpleName() + "\nReconnect devices and refresh."; }
        reportView.setText(report);
    }
    private void chooseUsb() {
        List<UsbDevice> devices = DeviceInventory.usbDevices(this);
        if (devices.isEmpty()) { Toast.makeText(this, "No USB devices detected", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            UsbDevice d = devices.get(i);
            names[i] = String.valueOf(d.getProductName()) + String.format(" (%04x:%04x) #%d", d.getVendorId(), d.getProductId(), i + 1);
        }
        new AlertDialog.Builder(this).setTitle("Choose a device").setItems(names, (dialog, index) -> {
            UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
            UsbDevice device = devices.get(index);
            if (usb.hasPermission(device)) { Toast.makeText(this, "USB access already granted", Toast.LENGTH_SHORT).show(); return; }
            PendingIntent pending = PendingIntent.getBroadcast(this, 0,
                new Intent(permissionAction).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
            try { usb.requestPermission(device, pending); }
            catch (RuntimeException e) { Toast.makeText(this, "Device unavailable; reconnect and try again", Toast.LENGTH_LONG).show(); }
        }).setNegativeButton("Cancel", null).show();
    }
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        androidx.core.content.ContextCompat.registerReceiver(this, usbReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        midi = (MidiManager) getSystemService(MIDI_SERVICE);
        if (audio != null) audio.registerAudioDeviceCallback(audioCallback, handler);
        if (midi != null) midi.registerDeviceCallback(midiCallback, handler);
        refresh();
    }
    @Override protected void onStop() {
        unregisterReceiver(usbReceiver);
        if (audio != null) audio.unregisterAudioDeviceCallback(audioCallback);
        if (midi != null) midi.unregisterDeviceCallback(midiCallback);
        handler.removeCallbacks(refreshTask);
        super.onStop();
    }
}
