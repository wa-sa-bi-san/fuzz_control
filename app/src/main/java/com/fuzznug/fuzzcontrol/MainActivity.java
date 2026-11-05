package com.fuzznug.fuzzcontrol;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.media.*;
import android.media.midi.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.fuzznug.devicecore.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final String permissionAction="com.fuzznug.fuzzcontrol.USB_PERMISSION";
    private UsbDevice selected;
    private boolean active,busy,ready;
    private int generation,tab,recordingSource;
    private int[][] mix=new int[5][4],outputs=new int[2][4];
    private TextView status;
    private LinearLayout content;
    private String report="";
    private final StringBuilder log=new StringBuilder();
    private AudioManager audio;
    private MidiManager midi;
    private interface Operation { void run(Duet3Usb session) throws IOException; }
    private final Runnable inventoryRefresh=()->refreshInventory();
    private final BroadcastReceiver usbReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(permissionAction.equals(intent.getAction())) {
                UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);
                if(selected!=null && manager.hasPermission(selected)) transact("Connect and read",s->{});
                else status.setText("USB access was not granted. Tap Connect to try again.");
            }
            if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                // Android may detach any device on a hub; only invalidate our selected device.
                UsbDevice removed=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if(selected!=null && (removed==null || selected.getDeviceName().equals(removed.getDeviceName()))) disconnect("Duet disconnected. Reconnect, then tap Connect.");
            }
            queueInventory();
        }
    };
    private final AudioDeviceCallback audioCallback=new AudioDeviceCallback(){
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d){queueInventory();}
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d){queueInventory();}
    };
    private final MidiManager.DeviceCallback midiCallback=new MidiManager.DeviceCallback(){
        @Override public void onDeviceAdded(MidiDeviceInfo d){queueInventory();}
        @Override public void onDeviceRemoved(MidiDeviceInfo d){queueInventory();}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=column();root.setBackgroundColor(Color.rgb(14,19,28));root.setPadding(dp(14),dp(12),dp(14),dp(12));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(14)+i.getSystemWindowInsetLeft(),dp(8)+i.getSystemWindowInsetTop(),dp(14)+i.getSystemWindowInsetRight(),dp(8)+i.getSystemWindowInsetBottom());return i;});
        LinearLayout heading=row();TextView title=text("FUZZ CONTROL",24);title.setTextColor(Color.rgb(97,226,183));heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        button(heading,"Connect",this::connect,false);button(heading,"Read",()->transact("Refresh settings",s->{}),false);root.addView(heading);
        status=text("Duet 3 • Connect to read settings. Hardware testing pending.",13);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
        LinearLayout tabs=row();String[] labels={"Mixer","Outputs","Devices"};
        for(int i=0;i<labels.length;i++){final int index=i;Button b=button(tabs,labels[i],()->{tab=index;render();},false);b.setLayoutParams(new LinearLayout.LayoutParams(0,dp(48),1));}
        root.addView(tabs);ScrollView scroll=new ScrollView(this);content=column();scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        render();refreshInventory();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.rgb(225,233,244));v.setPadding(dp(4),dp(6),dp(4),dp(6));return v;}
    private Button button(LinearLayout parent,String label,Runnable action,boolean hardware){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());if(hardware)b.setEnabled(ready&&!busy);parent.addView(b);return b;}
    private void panel(LinearLayout l,int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));l.setBackground(d);l.setPadding(dp(10),dp(10),dp(10),dp(10));}
    private static String db(int wire){int n=Duet3MixerProtocol.decodeDb(wire);return (n>0?"+":"")+n+" dB";}
    private static String pan(int wire){return wire==64?"Center":(wire<64?"L ":"R ")+Math.round(Math.abs(wire-64)*100f/64)+"%";}
    private void render(){
        if(content==null)return;content.removeAllViews();
        if(tab==0)renderMixer();else if(tab==1)renderOutputs();else renderDevices();
    }
    private void renderMixer(){
        content.addView(text("DIRECT MONITOR MIXER",18));
        content.addView(text("Blend instruments with playback. Set an output source to Mixer on the Outputs tab to hear this mix. Swipe sideways for all five strips.",13));
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setFillViewport(false);LinearLayout strips=row();strips.setGravity(Gravity.TOP);scroll.addView(strips);content.addView(scroll);
        for(int i=0;i<5;i++) {
            final int ch=i;LinearLayout card=column();panel(card,i==4?Color.rgb(28,47,51):Color.rgb(26,34,48));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(165),-2);cp.setMargins(0,dp(8),dp(8),dp(8));strips.addView(card,cp);
            TextView name=text(Duet3MixerProtocol.CHANNELS[i],16);name.setTypeface(null,Typeface.BOLD);card.addView(name);
            TextView level=text(ready?db(mix[i][0]):"Not read",22);level.setGravity(Gravity.CENTER);card.addView(level);
            if(i<4){
                TextView p=text(ready?pan(mix[i][1]):"Pan",13);card.addView(p);
                SeekBar panBar=new SeekBar(this);panBar.setMax(128);panBar.setProgress(ready?mix[i][1]:64);panBar.setContentDescription(Duet3MixerProtocol.CHANNELS[i]+" pan");panBar.setEnabled(ready&&!busy);
                panBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                    public void onProgressChanged(SeekBar b,int v,boolean user){if(user)p.setText(pan(v)+" • pending");}
                    public void onStartTrackingTouch(SeekBar b){}
                    public void onStopTrackingTouch(SeekBar b){int value=b.getProgress();transact("Pan "+Duet3MixerProtocol.CHANNELS[ch]+" to "+value,s->s.mixer.write(ch,Duet3MixerProtocol.PAN,value));}
                });card.addView(panBar,new LinearLayout.LayoutParams(-1,dp(48)));
            }else{card.addView(text("Stereo mix bus",13));Space spacer=new Space(this);card.addView(spacer,new LinearLayout.LayoutParams(1,dp(48)));}
            VerticalFader fader=new VerticalFader(this,ready?mix[i][0]:0,v->level.setText(db(v)+" • pending"),v->transact("Level "+Duet3MixerProtocol.CHANNELS[ch]+" to "+db(v),s->s.mixer.write(ch,Duet3MixerProtocol.LEVEL,v)));
            fader.setContentDescription(Duet3MixerProtocol.CHANNELS[i]+" level");fader.setEnabled(ready&&!busy);card.addView(fader,new LinearLayout.LayoutParams(-1,dp(235)));
            button(card,"Unity · 0 dB",()->transact("Unity "+Duet3MixerProtocol.CHANNELS[ch],s->s.mixer.write(ch,Duet3MixerProtocol.LEVEL,48)),true);
            Button mute=button(card,ready&&mix[i][2]==1?"MUTE ON":"Mute",()->transact("Toggle mute "+Duet3MixerProtocol.CHANNELS[ch],s->s.mixer.toggle(ch==4?0:ch,ch==4?Duet3MixerProtocol.MASTER_MUTE:Duet3MixerProtocol.MUTE)),true);mute.setSelected(ready&&mix[i][2]==1);if(mute.isSelected())mute.setTextColor(Color.rgb(194,54,43));
            if(i<4){Button solo=button(card,ready&&mix[i][3]==1?"SOLO ON":"Solo",()->transact("Toggle solo "+Duet3MixerProtocol.CHANNELS[ch],s->s.mixer.toggle(ch,Duet3MixerProtocol.SOLO)),true);if(ready&&mix[i][3]==1)solo.setTextColor(Color.rgb(161,100,0));}
        }
        content.addView(text("Faders show device settings after readback. Live audio meters, stereo-link editing and input/DSP settings are not included in this build.",12));
    }
    private void renderOutputs(){
        content.addView(text("OUTPUTS & ROUTING",18));
        content.addView(text("Choose the source separately for each physical output. Output numbering needs confirmation on your Duet. Changing a source can change what you hear immediately.",13));
        for(int i=0;i<2;i++){
            final int out=i;LinearLayout card=column();panel(card,Color.rgb(26,34,48));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(8));content.addView(card,lp);
            card.addView(text("Output "+(i+1)+(ready?" · -"+outputs[i][0]+" dB":" · not read"),20));
            button(card,"Source: "+(ready?Duet3MixerProtocol.ROUTES[outputs[i][3]]:"unknown"),()->chooseRoute(out),true);
            LinearLayout levels=row();button(levels,"Quieter · -1 dB",()->transact("Output "+(out+1)+" quieter",s->s.protocol.stepLevel(out,-1)),true);button(levels,"Louder · +1 dB",()->transact("Output "+(out+1)+" louder",s->s.protocol.stepLevel(out,1)),true);card.addView(levels);
            LinearLayout switches=row();button(switches,"Dim: "+(ready?(outputs[i][1]==1?"on":"off"):"?"),()->transact("Output dim "+out,s->s.protocol.toggle(out,Duet3Protocol.DIM)),true);button(switches,"Mono: "+(ready?(outputs[i][2]==1?"on":"off"):"?"),()->transact("Output mono "+out,s->s.protocol.toggle(out,Duet3Protocol.MONO)),true);card.addView(switches);
        }
        content.addView(text("RECORDING / LOOPBACK",18));
        button(content,"USB inputs 1–2: "+(ready?(recordingSource==3?"Unmapped source (3)":Duet3MixerProtocol.ROUTES[recordingSource]):"unknown"),this::chooseRecordingSource,true);
        content.addView(text("Analog 1–2 sends the physical inputs to your recording app. Mixer sends this monitor mix, including playback.",13));
    }
    private void chooseRecordingSource(){
        final int[] codes={6,2,0,1,4,5};String[] labels=new String[codes.length];for(int i=0;i<codes.length;i++)labels[i]=Duet3MixerProtocol.ROUTES[codes[i]];
        new AlertDialog.Builder(this).setTitle("USB recording inputs 1–2").setItems(labels,(d,n)->
            new AlertDialog.Builder(this).setTitle("Record " + labels[n] + "?")
                .setMessage("This replaces the signal sent to recording apps. Disable input monitoring in your recording app before choosing Mixer or Playback to avoid feedback.")
                .setPositiveButton("Apply",(dialog,which)->transact("Recording source "+labels[n],s->s.mixer.write(0,Duet3MixerProtocol.RECORDING_SOURCE,codes[n])))
                .setNegativeButton("Cancel",null).show()).setNegativeButton("Cancel",null).show();
    }
    private void chooseRoute(int out){
        final int[] codes={0,1,2,4,5,6};String[] labels=new String[codes.length];for(int i=0;i<codes.length;i++)labels[i]=Duet3MixerProtocol.ROUTES[codes[i]];
        new AlertDialog.Builder(this).setTitle("Output "+(out+1)+" source").setItems(labels,(d,n)->transact("Output "+(out+1)+" source "+labels[n],s->s.mixer.write(out,Duet3MixerProtocol.ROUTE,codes[n]))).setNegativeButton("Cancel",null).show();
    }
    private void renderDevices(){
        content.addView(text("CONNECTION & DIAGNOSTICS",18));
        button(content,"Refresh devices",this::refreshInventory,false);
        button(content,"Capture raw meter packet",()->transact("Read meter packet",session->{
            String packet=Arrays.toString(session.readMeterPacket());
            handler.post(()->{if(!isDestroyed())log.append("Raw meter words (unmapped): ").append(packet).append('\n');});
        }),true);
        button(content,"Share diagnostic report",()->{Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"Fuzz Control 0.3.1 diagnostics").putExtra(Intent.EXTRA_TEXT,report+"\nCONTROL LOG\n"+log);startActivity(Intent.createChooser(send,"Share report"));},false);
        TextView data=text(report,12);data.setTypeface(Typeface.MONOSPACE);data.setTextIsSelectable(true);content.addView(data);
        content.addView(text("Experimental 0.3.1. USB controls are independently implemented. No Apogee software is bundled. No audio recording or network access.",12));
    }
    private void connect(){
        if(busy)return;List<UsbDevice> devices=DeviceInventory.usbDevices(this);devices.removeIf(d->!Duet3Usb.matches(d));
        if(devices.isEmpty()){status.setText("No Apogee Duet 3 detected. Check Devices and share its report if yours is attached.");tab=2;render();return;}
        String[] labels=new String[devices.size()];for(int i=0;i<labels.length;i++)labels[i]="Duet 3 #"+(i+1);
        new AlertDialog.Builder(this).setTitle("Connect Duet 3").setItems(labels,(d,n)->{
            selected=devices.get(n);generation++;ready=false;render();UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);
            if(manager.hasPermission(selected)){transact("Connect and read",s->{});return;}
            PendingIntent pending=PendingIntent.getBroadcast(this,0,new Intent(permissionAction).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE);
            try{manager.requestPermission(selected,pending);status.setText("Grant USB access to read the Duet.");}catch(RuntimeException e){disconnect("USB permission failed. Reconnect and try again.");}
        }).setNegativeButton("Cancel",null).show();
    }
    private void transact(String label,Operation op){
        if(busy)return;if(selected==null){status.setText("Tap Connect first.");return;}
        final UsbDevice device=selected;final int token=generation;busy=true;render();status.setText(label+"…");
        worker.execute(()->{
            int[][] nextMix=null,nextOutputs=new int[2][4];int nextRecording=0;String message;boolean ok=false;
            try(Duet3Usb session=new Duet3Usb((UsbManager)getSystemService(USB_SERVICE),device)){
                op.run(session);nextMix=session.mixer.snapshot();nextRecording=session.mixer.read(0,Duet3MixerProtocol.RECORDING_SOURCE);
                for(int i=0;i<2;i++){nextOutputs[i][0]=session.protocol.read(i,Duet3Protocol.LEVEL);nextOutputs[i][1]=session.protocol.read(i,Duet3Protocol.DIM);nextOutputs[i][2]=session.protocol.read(i,Duet3Protocol.MONO);nextOutputs[i][3]=session.mixer.read(i,Duet3MixerProtocol.ROUTE);}
                message=label+" • device readback received. Hardware/audio validation pending.";ok=true;
            }catch(IOException|RuntimeException e){message=label+" failed: "+e.getMessage()+". Tap Read to recover; a write may already have applied.";}
            final int rec=nextRecording;final int[][] m=nextMix,o=nextOutputs;final String result=message;final boolean success=ok;
            handler.post(()->{busy=false;if(isDestroyed())return;log.append(result).append('\n');if(success)log.append("Mixer ").append(Arrays.deepToString(m)).append("\nOutputs ").append(Arrays.deepToString(o)).append("\nRecording source ").append(rec).append('\n');if(log.length()>20000)log.delete(0,log.length()-20000);
                if(!active||token!=generation)return;ready=success;if(success){mix=m;outputs=o;recordingSource=rec;}status.setText(result);render();});
        });
    }
    private void disconnect(String message){generation++;selected=null;ready=false;status.setText(message);render();}
    private void queueInventory(){handler.removeCallbacks(inventoryRefresh);handler.postDelayed(inventoryRefresh,250);}
    private void refreshInventory(){try{report=DeviceInventory.report(this);}catch(RuntimeException e){report="Inventory unavailable. Reconnect and retry.";}if(tab==2)render();}
    @Override protected void onStart(){super.onStart();active=true;IntentFilter f=new IntentFilter(permissionAction);f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);androidx.core.content.ContextCompat.registerReceiver(this,usbReceiver,f,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);audio=(AudioManager)getSystemService(AUDIO_SERVICE);midi=(MidiManager)getSystemService(MIDI_SERVICE);if(audio!=null)audio.registerAudioDeviceCallback(audioCallback,handler);if(midi!=null)midi.registerDeviceCallback(midiCallback,handler);refreshInventory();render();}
    @Override protected void onStop(){active=false;generation++;ready=false;unregisterReceiver(usbReceiver);if(audio!=null)audio.unregisterAudioDeviceCallback(audioCallback);if(midi!=null)midi.unregisterDeviceCallback(midiCallback);handler.removeCallbacks(inventoryRefresh);super.onStop();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
