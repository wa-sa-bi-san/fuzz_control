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
import com.fuzznug.devicecore.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.util.List;

public final class MainActivity extends Activity {
    private TextView reportView, controlStatus;
    private LinearLayout controls;
    private final ExecutorService usbWorker = Executors.newSingleThreadExecutor();
    private boolean busy, active;
    private UsbDevice selected;
    private final StringBuilder controlLog = new StringBuilder();
    private final int[][] values = new int[2][3];
    private boolean ready;
    private interface Operation { void run(Duet3Protocol protocol) throws IOException; }

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
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                selected = null; ready = false; renderControls();
                controlStatus.setText("USB device detached. Reconnect and read outputs again.");
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
        layout.addView(text("Duet 3 · Experimental output controls", 16));
        layout.addView(text("Recovered from Control 2. Hardware validation pending. Full mixer, inputs, routing and DSP are not implemented yet.", 14));
        ScrollView page = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        page.addView(body);
        layout.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        addButton(body, "Connect Duet 3 controls", () -> connectControls());
        controlStatus = text("Connect your Duet 3, grant USB access, then read its outputs.", 14);
        body.addView(controlStatus);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        body.addView(controls);
        addButton(body, "Read outputs", () -> transact(p -> {}));
        addButton(body, "Refresh devices", () -> refresh());
        addButton(body, "Request USB access…", () -> chooseUsb());
        addButton(body, "Share diagnostic report", () -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Fuzz Control diagnostic report");
            send.putExtra(Intent.EXTRA_TEXT, report + "\nCONTROL LOG\n" + controlLog);
            startActivity(Intent.createChooser(send, "Share report"));
        });
        reportView = text("", 13);
        reportView.setTypeface(android.graphics.Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        body.addView(reportView);
        setContentView(layout);
        refresh();
    }
    private void connectControls() {
        List<UsbDevice> devices = DeviceInventory.usbDevices(this);
        devices.removeIf(d -> !Duet3Usb.matches(d));
        if (devices.isEmpty()) {
            controlStatus.setText("No device identifying as Apogee Duet 3 found. Share the diagnostic report if yours is connected.");
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = "Duet 3 #" + (i + 1);
        new AlertDialog.Builder(this).setTitle("Choose Duet 3").setItems(names, (d, i) -> {
            if (busy) return;
            selected = devices.get(i); ready = false; renderControls();
            transact(p -> {});
        }).setNegativeButton("Cancel", null).show();
    }
    private void renderControls() {
        if (controls == null) return;
        controls.removeAllViews();
        for (int output = 0; output < 2; output++) {
            final int index = output;
            controls.addView(text("Output " + (output + 1) + " · " + (ready ? "-" + values[output][0] + " dB" : "not read"), 20));
            controls.addView(text("Device output index " + output + ". Speaker/headphone labels await hardware confirmation.", 12));
            addButton(controls, "Quieter · -1 dB", () -> transact(p -> p.stepLevel(index, -1)));
            addButton(controls, "Louder · +1 dB", () -> transact(p -> p.stepLevel(index, 1)));
            addButton(controls, "Dim: " + (ready ? (values[output][1] == 1 ? "on" : "off") : "unknown"),
                () -> transact(p -> p.toggle(index, Duet3Protocol.DIM)));
            addButton(controls, "Mono: " + (ready ? (values[output][2] == 1 ? "on" : "off") : "unknown"),
                () -> transact(p -> p.toggle(index, Duet3Protocol.MONO)));
        }
        for (int i = 0; i < controls.getChildCount(); i++)
            if (controls.getChildAt(i) instanceof Button) controls.getChildAt(i).setEnabled(ready && !busy);
    }
    private void transact(Operation operation) {
        if (busy) return;
        if (selected == null) { controlStatus.setText("Connect Duet 3 controls first."); return; }
        final UsbDevice device = selected;
        busy = true; renderControls();
        controlStatus.setText("Reading device…");
        usbWorker.execute(() -> {
            String result;
            int[][] snapshot = new int[2][3];
            boolean success = false;
            try (Duet3Usb session = new Duet3Usb((UsbManager)getSystemService(USB_SERVICE), device)) {
                operation.run(session.protocol);
                for (int i = 0; i < 2; i++) {
                    snapshot[i][0] = session.protocol.read(i, Duet3Protocol.LEVEL);
                    snapshot[i][1] = session.protocol.read(i, Duet3Protocol.DIM);
                    snapshot[i][2] = session.protocol.read(i, Duet3Protocol.MONO);
                }
                result = "Device readback: output 1 -" + snapshot[0][0] + " dB, output 2 -" + snapshot[1][0] + " dB. Dim=" + snapshot[0][1] + "/" + snapshot[1][1] + ", Mono=" + snapshot[0][2] + "/" + snapshot[1][2] + ". Verify the change on the Duet. No audio test performed.";
                success = true;
            } catch (IOException | RuntimeException e) {
                result = "Control stopped: " + e.getMessage() + ". Read outputs before trying again.";
            }
            final String message = result;
            final boolean ok = success;
            handler.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                controlLog.append(message).append("\n");
                if (controlLog.length() > 12000) controlLog.delete(0, controlLog.length() - 12000);
                if (!active || selected != device) return;
                ready = ok;
                if (ok) for (int i = 0; i < 2; i++) System.arraycopy(snapshot[i], 0, values[i], 0, 3);
                controlStatus.setText(message);
                renderControls();
            });
        });
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
        active = true;
        renderControls();
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
        active = false; ready = false; selected = null;
        unregisterReceiver(usbReceiver);
        if (audio != null) audio.unregisterAudioDeviceCallback(audioCallback);
        if (midi != null) midi.unregisterDeviceCallback(midiCallback);
        handler.removeCallbacks(refreshTask);
        super.onStop();
    }
    @Override protected void onDestroy() { usbWorker.shutdown(); super.onDestroy(); }
}
