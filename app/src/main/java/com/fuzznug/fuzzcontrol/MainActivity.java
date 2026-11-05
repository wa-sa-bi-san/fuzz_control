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
    private boolean active,busy,ready,meterBusy,meterFailed;
    private final List<MeterDisplay> meterDisplays=new ArrayList<>();
    private final Runnable meterTick=()->pollMeters();
    private final class MeterDisplay {
        final int[] words;final boolean reduction;final TextView label;final ProgressBar[] bars;
        MeterDisplay(LinearLayout card,int[] words){this(card,words,false);}
        MeterDisplay(LinearLayout card,int[] words,boolean reduction){
            this.reduction=reduction;this.words=words;label=text("Meters: waiting",11);card.addView(label);bars=new ProgressBar[words.length];
            for(int i=0;i<words.length;i++){ProgressBar bar=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(600);bar.setContentDescription("Signal level "+(i+1));card.addView(bar,new LinearLayout.LayoutParams(-1,dp(8)));bars[i]=bar;}
            meterDisplays.add(this);
        }
        void update(int[] packet){StringBuilder text=new StringBuilder();for(int i=0;i<words.length;i++){
            int word=packet[words[i]];double db=reduction?word/100.0:Duet3MeterPacket.dbfs(word);boolean clip=!reduction&&Duet3MeterPacket.clipped(word);
            if(i>0)text.append(" / ");text.append(Double.isInfinite(db)?"−∞":String.format(Locale.ROOT,"%.1f",db));if(clip)text.append(" CLIP");
            bars[i].setProgress((int)Math.round(Math.max(0,Math.min(60,reduction?db:db+60))*10));
            bars[i].setProgressTintList(android.content.res.ColorStateList.valueOf(clip?Color.rgb(245,94,88):Color.rgb(97,226,183)));
        }label.setText(text.append(reduction?" dB reduction":" dBFS").toString());}
        void unavailable(){label.setText("Meters unavailable");for(ProgressBar bar:bars)bar.setProgress(0);}
    }

    private int generation,tab,recordingSource,stereo,fxChannel;
    private int[][] inputs;
    private float[][] effects;
    private int[] systemSettings;
    private String inputError="Connect to read inputs",dspError="Connect to read DSP",systemError="Connect to read system settings";
    private final float[][][] comparisons=new float[2][2][];
    private PresetStore presets;
    private int[][] mix=new int[5][4],outputs=new int[2][5];
    private TextView status;
    private LinearLayout content;
    private final List<Button> tabButtons=new ArrayList<>();
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
        super.onCreate(state);presets=new PresetStore(this);
        LinearLayout root=column();root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff101b29,0xff080d16,0xff121326}));root.setPadding(dp(14),dp(12),dp(14),dp(12));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(14)+i.getSystemWindowInsetLeft(),dp(8)+i.getSystemWindowInsetTop(),dp(14)+i.getSystemWindowInsetRight(),dp(8)+i.getSystemWindowInsetBottom());return i;});
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout heading=row(),brand=column();TextView title=text("FUZZ CONTROL",24);title.setTextColor(0xff82ffe0);title.setTypeface(Typeface.create("sans-serif-black",Typeface.NORMAL));title.setLetterSpacing(0.10f);title.setShadowLayer(dp(12),0,0,0x9961e2b7);brand.addView(title);
        TextView subtitle=text("DUET 3  /  MOBILE CONTROL",11);subtitle.setLetterSpacing(0.18f);subtitle.setTextColor(0xff8199b3);subtitle.setPadding(dp(4),0,dp(4),dp(8));brand.addView(subtitle);
        heading.addView(brand,new LinearLayout.LayoutParams(0,-2,1));root.addView(heading);
        LinearLayout connection=landscape?heading:row();
        for(int c=0;c<2;c++){Button control=c==0?button(connection,"Connect",this::connect,false):button(connection,"Read",()->transact("Refresh settings",s->{}),false);LinearLayout.LayoutParams lp=landscape?new LinearLayout.LayoutParams(dp(112),dp(48)):new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(3),dp(4),dp(3),dp(6));control.setLayoutParams(lp);}
        if(!landscape)root.addView(connection);
        status=text("Duet 3 • Connect to read settings. Hardware testing pending.",13);status.setTextColor(0xff91a8bd);status.setTextSize(12);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
        LinearLayout tabs=row();String[] labels={"Mixer","Inputs","FX","Outputs","Presets","System","Devices"};
        for(int i=0;i<labels.length;i++){final int index=i;Button b=button(tabs,labels[i],()->{tab=index;render();},false);LinearLayout.LayoutParams navLp=new LinearLayout.LayoutParams(dp(88),dp(44));navLp.setMargins(0,dp(6),dp(6),dp(8));b.setLayoutParams(navLp);tabButtons.add(b);}
        HorizontalScrollView navigation=new HorizontalScrollView(this);navigation.setHorizontalScrollBarEnabled(false);navigation.addView(tabs);root.addView(navigation);ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setPadding(0,dp(8),0,dp(12));content=column();scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        render();refreshInventory();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private GradientDrawable surface(int top,int bottom,int border,int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});d.setCornerRadius(dp(radius));d.setStroke(dp(1),border);return d;
    }
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(size>=18?0xffedf7ff:0xffb2c3d5);v.setFontFeatureSettings("tnum");v.setPadding(dp(4),dp(7),dp(4),dp(7));if(size>=16)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));if(size==18){v.setTextSize(14);v.setLetterSpacing(0.10f);v.setTextColor(0xff7ce8d2);}return v;}
    private void buttonStyle(Button b,boolean accent){
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},surface(0xff18212e,0xff141b27,0xff263243,13));
        states.addState(new int[]{android.R.attr.state_pressed},surface(0xff285c62,0xff203f52,0xff82ffe0,13));
        states.addState(new int[]{android.R.attr.state_selected},surface(0xff24574f,0xff153b40,0xff63e5cb,13));
        states.addState(new int[]{},surface(accent?0xff22544f:0xff253245,accent?0xff16383d:0xff192433,accent?0xff4bbda9:0xff3a4a62,13));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x4067f5d0),states,null));
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff63758a,accent?0xffa3ffe8:0xffdfebfa}));
        b.setElevation(dp(accent?4:2));b.setPadding(dp(12),dp(8),dp(12),dp(8));
    }
    private Button button(LinearLayout parent,String label,Runnable action,boolean hardware){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinHeight(dp(48));b.setMinimumWidth(0);b.setOnClickListener(v->action.run());if(hardware)b.setEnabled(ready&&!busy);boolean accent=label.equals("Connect")||label.startsWith("Save ")||label.startsWith("Recall ");buttonStyle(b,accent);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(parent.getOrientation()==LinearLayout.VERTICAL?-1:-2,-2);lp.setMargins(dp(3),dp(5),dp(3),dp(5));parent.addView(b,lp);return b;}
    private void panel(LinearLayout l,int color){boolean master=color==Color.rgb(28,47,51);l.setBackground(surface(master?0xff203f43:0xff202d40,master?0xff11282f:0xff141e2e,master?0xff438e85:0xff34475e,20));l.setElevation(dp(5));l.setPadding(dp(12),dp(12),dp(12),dp(14));}
    private static String db(int wire){int n=Duet3MixerProtocol.decodeDb(wire);return (n>0?"+":"")+n+" dB";}
    private static String pan(int wire){return wire==64?"Center":(wire<64?"L ":"R ")+Math.round(Math.abs(wire-64)*100f/64)+"%";}
    private void render(){
        if(content==null)return;for(int i=0;i<tabButtons.size();i++){Button b=tabButtons.get(i);b.setSelected(i==tab);b.setTextColor(i==tab?0xffa3ffe8:0xff92a8c2);}content.removeAllViews();meterDisplays.clear();
        switch(tab){case 0:renderMixer();break;case 1:renderInputs();break;case 2:renderFx();break;case 3:renderOutputs();break;case 4:renderPresets();break;case 5:renderSystem();break;default:renderDevices();}
        if(meterFailed)for(MeterDisplay display:meterDisplays)display.unavailable();
    }
    private void renderMixer(){
        content.addView(text("DIRECT MONITOR MIXER",18));
        content.addView(text("Blend instruments with playback. Set an output source to Mixer on the Outputs tab to hear this mix. Swipe sideways for all five strips.",13));
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setFillViewport(false);LinearLayout strips=row();strips.setGravity(Gravity.TOP);scroll.addView(strips);content.addView(scroll);
        for(int i=0;i<5;i++) {
            final int ch=i;LinearLayout card=column();panel(card,i==4?Color.rgb(28,47,51):Color.rgb(26,34,48));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(165),-2);cp.setMargins(0,dp(8),dp(8),dp(8));strips.addView(card,cp);
            TextView name=text(Duet3MixerProtocol.CHANNELS[i],16);name.setTypeface(null,Typeface.BOLD);card.addView(name);
            new MeterDisplay(card,Duet3MeterPacket.MIXER_WORDS[i]);
            TextView level=text(ready?db(mix[i][0]):"Not read",22);level.setGravity(Gravity.CENTER);card.addView(level);
            if(i<4){
                TextView p=text(ready?pan(mix[i][1]):"Pan",13);card.addView(p);
                SeekBar panBar=new SeekBar(this);panBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff859aff));panBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xffb8c5ff));panBar.setMax(128);panBar.setProgress(ready?mix[i][1]:64);panBar.setContentDescription(Duet3MixerProtocol.CHANNELS[i]+" pan");panBar.setEnabled(ready&&!busy&&!(ch<2&&stereo==1));
                panBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                    public void onProgressChanged(SeekBar b,int v,boolean user){if(user)p.setText(pan(v)+" • pending");}
                    public void onStartTrackingTouch(SeekBar b){}
                    public void onStopTrackingTouch(SeekBar b){int value=b.getProgress();transact("Pan "+Duet3MixerProtocol.CHANNELS[ch]+" to "+value,s->s.controller.setMixer(ch,Duet3MixerProtocol.PAN,value));}
                });card.addView(panBar,new LinearLayout.LayoutParams(-1,dp(48)));
            }else{card.addView(text("Stereo mix bus",13));Space spacer=new Space(this);card.addView(spacer,new LinearLayout.LayoutParams(1,dp(48)));}
            VerticalFader fader=new VerticalFader(this,ready?mix[i][0]:0,v->level.setText(db(v)+" • pending"),v->transact("Level "+Duet3MixerProtocol.CHANNELS[ch]+" to "+db(v),s->s.controller.setMixer(ch,Duet3MixerProtocol.LEVEL,v)));
            fader.setContentDescription(Duet3MixerProtocol.CHANNELS[i]+" level");fader.setEnabled(ready&&!busy);card.addView(fader,new LinearLayout.LayoutParams(-1,dp(235)));
            button(card,"Unity · 0 dB",()->transact("Unity "+Duet3MixerProtocol.CHANNELS[ch],s->s.controller.setMixer(ch,Duet3MixerProtocol.LEVEL,48)),true);
            Button mute=button(card,ready&&mix[i][2]==1?"MUTE ON":"Mute",()->transact("Toggle mute "+Duet3MixerProtocol.CHANNELS[ch],s->{if(ch==4)s.mixer.toggle(0,Duet3MixerProtocol.MASTER_MUTE);else s.controller.toggleMixer(ch,Duet3MixerProtocol.MUTE);}),true);mute.setSelected(ready&&mix[i][2]==1);if(mute.isSelected())mute.setTextColor(0xffffa69d);
            if(i<4){Button solo=button(card,ready&&mix[i][3]==1?"SOLO ON":"Solo",()->transact("Toggle solo "+Duet3MixerProtocol.CHANNELS[ch],s->s.controller.toggleMixer(ch,Duet3MixerProtocol.SOLO)),true);if(ready&&mix[i][3]==1)solo.setTextColor(0xffffd487);}
        }
        button(content,"Analog stereo link: "+(ready?(stereo==1?"on":"off"):"not read"),this::chooseStereo,true);
        content.addView(text("Linked analog inputs share level, mute/solo and stereo FX. Their mixer pans are left/right. Input gain grouping preserves existing offsets.",12));
    }
    private interface NumberAction { void apply(float value); }
    private void slider(LinearLayout parent,String name,float value,float min,float max,float step,boolean enabled,NumberAction action){
        TextView caption=text(name+": "+String.format(Locale.ROOT,"%.2f",value),15);parent.addView(caption);
        SeekBar bar=new SeekBar(this);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff63e5cb));bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff9affdf));bar.setMax(Math.round((max-min)/step));bar.setProgress(Math.round((value-min)/step));bar.setEnabled(enabled&&!busy);bar.setContentDescription(name);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int n,boolean user){if(user)caption.setText(name+": "+String.format(Locale.ROOT,"%.2f",min+n*step)+" • pending");}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){action.apply(min+b.getProgress()*step);}
        });parent.addView(bar,new LinearLayout.LayoutParams(-1,dp(48)));
        Button exact=button(parent,"Enter value",()->{EditText field=new EditText(this);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);field.setText(String.valueOf(value));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(name+" ("+min+" to "+max+")").setView(field).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
            dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{float n=Float.parseFloat(field.getText().toString());if(!Float.isFinite(n)||n<min||n>max)throw new NumberFormatException();n=min+Math.round((n-min)/step)*step;action.apply(Math.max(min,Math.min(max,n)));dialog.dismiss();}catch(NumberFormatException e){field.setError("Enter a number from "+min+" to "+max);}}));dialog.show();
        },false);exact.setEnabled(enabled&&!busy);
    }
    private LinearLayout card(String title){LinearLayout c=column();panel(c,Color.rgb(26,34,48));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(8));content.addView(c,lp);c.addView(text(title,20));return c;}
    private void chooseStereo(){boolean on=stereo!=1;new AlertDialog.Builder(this).setTitle(on?"Link Analog 1–2?":"Unlink Analog 1–2?")
        .setMessage(on?"Use Analog 1 mixer level, mute/solo and FX for the stereo pair. Pan becomes full left/right. Eligible preamp gains are grouped with their existing offset.":"Restore independent mixer controls and center both pans. The two FX banks become independent again.")
        .setPositiveButton("Apply",(d,n)->transact("Stereo link "+on,s->s.controller.setStereo(on))).setNegativeButton("Cancel",null).show();}
    private void renderInputs(){
        content.addView(text("ANALOG INPUTS",18));if(inputs==null||!ready){content.addView(text(inputError.isEmpty()?"Tap Read to refresh inputs.":inputError,14));return;}
        for(int c=0;c<2;c++){final int ch=c;int[] values=inputs[c];LinearLayout box=card("Analog "+(c+1));new MeterDisplay(box,new int[]{4+c});
            button(box,"Input: "+Duet3InputProtocol.TYPES[values[0]],()->new AlertDialog.Builder(this).setTitle("Analog "+(ch+1)+" input type").setItems(Duet3InputProtocol.TYPES,(d,n)->new AlertDialog.Builder(this).setTitle("Use "+Duet3InputProtocol.TYPES[n]+"?").setMessage("Changing input type switches 48 V phantom power off first.").setPositiveButton("Apply",(a,b)->transact("Input type",s->s.input.write(ch,Duet3InputProtocol.TYPE,n))).setNegativeButton("Cancel",null).show()).show(),true);
            if(values[0]>=2){int field=values[0]==2?1:2;slider(box,"Preamp gain (dB)",values[field],0,values[0]==2?65:45,1,true,v->transact("Input gain",s->s.input.setGain(ch,Math.round(v))));}
            if(values[0]==2)button(box,"48 V phantom: "+(values[3]==1?"on":"off"),()->{
                int value=1-values[3];if(value==0){transact("Phantom off",s->s.input.write(ch,Duet3InputProtocol.PHANTOM,0));return;}
                new AlertDialog.Builder(this).setTitle("Enable 48 V on Analog "+(ch+1)+"?").setMessage("Enable only if the connected microphone or DI accepts phantom power. Lower monitoring volume before switching.").setPositiveButton("Enable 48 V",(d,n)->transact("Phantom on",s->s.input.write(ch,Duet3InputProtocol.PHANTOM,1))).setNegativeButton("Cancel",null).show();},true);
            button(box,"Soft Limit: "+(values[4]==1?"on":"off"),()->transact("Soft Limit",s->s.input.write(ch,Duet3InputProtocol.SOFT_LIMIT,1-s.input.read(ch,Duet3InputProtocol.SOFT_LIMIT))),true);
            button(box,"Polarity: "+(values[5]==1?"inverted":"normal"),()->transact("Polarity",s->s.input.write(ch,Duet3InputProtocol.POLARITY,1-s.input.read(ch,Duet3InputProtocol.POLARITY))),true);
            button(box,"ECS FX: "+(values[7]==0?"on":"bypassed"),()->transact("FX bypass",s->s.controller.setFxBypass(ch,1-s.input.read(ch,Duet3InputProtocol.FX_BYPASS))),true);
        }
        button(content,"Group preamp gains: "+(inputs[0][6]==1&&inputs[1][6]==1?"on":"off"),()->transact("Preamp gain grouping",s->s.input.setGroup(!(s.input.read(0,Duet3InputProtocol.GROUP)==1&&s.input.read(1,Duet3InputProtocol.GROUP)==1))),true);
        button(content,"Stereo link: "+(stereo==1?"on":"off"),this::chooseStereo,true);
    }
    private void renderFx(){
        content.addView(text("HARDWARE ECS CHANNEL STRIP",18));
        LinearLayout select=row();for(int c=0;c<2;c++){final int ch=c;button(select,"Analog "+(c+1),()->{fxChannel=ch;render();},false);}content.addView(select);
        if(effects==null||!ready){content.addView(text(dspError.isEmpty()?"Tap Read to refresh DSP.":dspError,14));return;}
        final int ch=fxChannel;content.addView(text(stereo==1?"Stereo FX • Analog 1–2":"Analog "+(ch+1)+" FX",18));
        if(inputs!=null)button(content,"ECS processing: "+(inputs[ch][7]==0?"on":"bypassed"),()->transact("FX bypass",x->x.controller.setFxBypass(ch,1-x.input.read(ch,Duet3InputProtocol.FX_BYPASS))),true);
        final float[] values=effects[ch];
        LinearLayout reductionCard=card("Gain reduction");new MeterDisplay(reductionCard,new int[]{6+(stereo==1?0:ch)},true);
        LinearLayout switches=card("Signal path");String[] toggles={"EQ bypass","Compressor bypass","High-pass to sidechain","Mid peak wide bandwidth","EQ before compressor"};
        for(int p=0;p<5;p++){final int parameter=p;button(switches,toggles[p]+": "+(values[p]==1?"on":"off"),()->transact(toggles[parameter],x->{int channel=x.controller.dspChannel(ch);float old=x.dsp.read(channel)[parameter];x.dsp.write(channel,parameter,1-old);}),true);}
        button(switches,"Ratio: "+Duet3DspProtocol.format(5,values[5]),()->new AlertDialog.Builder(this).setTitle("Compression ratio").setItems(new String[]{"3:1","5:1","10:1"},(d,n)->transact("Compressor ratio",x->x.controller.setDsp(ch,5,n))).show(),true);
        LinearLayout eq=card("Equalizer"),comp=card("Compression"),output=card("Drive & output");
        int[] order={7,11,8,12,9,13,10,14,15,16,6};
        for(int p:order){final int parameter=p;boolean hz=p==7||p==11||p==12||p==13;LinearLayout parent=(p==14||p==15)?comp:(p==16||p==6)?output:eq;
            slider(parent,Duet3DspProtocol.NAMES[p]+" • "+Duet3DspProtocol.format(p,values[p]),values[p],Duet3DspProtocol.MIN[p],Duet3DspProtocol.MAX[p],hz?1:p==15?0.01f:0.1f,true,v->transact(Duet3DspProtocol.NAMES[parameter],x->x.controller.setDsp(ch,parameter,v)));
        }
        LinearLayout ab=card("Compare A / B");for(int slot=0;slot<2;slot++){final int index=slot;String name=slot==0?"A":"B";
            button(ab,"Capture "+name,()->transact("Capture DSP "+name,x->{float[] captured=x.dsp.read(x.controller.dspChannel(ch));handler.post(()->comparisons[ch][index]=captured);}),true);
            Button recall=button(ab,"Recall "+name,()->{float[] saved=comparisons[ch][index];if(saved!=null)transact("Recall DSP "+name,x->x.controller.applyDsp(ch,saved));},true);recall.setEnabled(ready&&!busy&&comparisons[ch][slot]!=null);
        }
        content.addView(text("Effects run inside the Duet. Presets are available on the Presets tab. Hardware DSP changes affect the recorded signal as well as monitoring.",13));
    }
    private void renderSystem(){
        content.addView(text("DUET SETTINGS",18));
        content.addView(text("Sample rate is negotiated by Android and your audio app. Choose it in FuzzNug or your recording app.",14));
        if(!ready||systemSettings==null){content.addView(text(systemError.isEmpty()?"Tap Read to refresh system settings.":systemError,14));return;}
        for(int i=0;i<2;i++){final int index=i==0?1:0;slider(content,i==0?"Controller ring brightness (%)":"Device meter brightness (%)",systemSettings[i],0,100,10,true,v->transact("LED brightness",s->s.system.write(Duet3SystemProtocol.BRIGHTNESS,index,Math.round(v))));}
        for(int i=3;i<5;i++){final int request=i==3?Duet3SystemProtocol.PEAK_HOLD:Duet3SystemProtocol.OVER_HOLD;String title=i==3?"Peak hold":"Overload hold";String[] labels={"Off","2 seconds","Infinite"};button(content,title+": "+labels[systemSettings[i]],()->new AlertDialog.Builder(this).setTitle(title).setItems(labels,(d,n)->transact(title,s->s.system.write(request,0,n))).show(),true);}
        button(content,"Clear peak / overload indicators",()->transact("Clear meters",s->s.system.clearMeters()),true);
        button(content,"USB volume target: Output "+(systemSettings[2]+1),()->new AlertDialog.Builder(this).setTitle("USB host volume target").setItems(new String[]{"Output 1","Output 2"},(d,n)->transact("USB volume target",s->s.system.write(Duet3SystemProtocol.OS_VOLUME,0,n))).show(),true);
    }
    private void renderOutputs(){
        content.addView(text("OUTPUTS & ROUTING",18));
        content.addView(text("Choose the source separately for each physical output. Output numbering needs confirmation on your Duet. Changing a source can change what you hear immediately.",13));
        for(int i=0;i<2;i++){
            final int out=i;LinearLayout card=column();panel(card,Color.rgb(26,34,48));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(8));content.addView(card,lp);
            card.addView(text("Output "+(i+1)+(ready?" · "+(-outputs[i][0])+" dB":" · not read"),20));
            new MeterDisplay(card,Duet3MeterPacket.OUTPUT_WORDS[i]);
            if(ready&&outputs[i][0]<=64)slider(card,"Output level (dB)",-outputs[i][0],-64,0,1,true,v->transact("Output level",s->{if(s.protocol.read(out,Duet3Protocol.LEVEL)>64)throw new IOException("Output is below the adjustable range; reread device");s.protocol.writeAndVerify(out,Duet3Protocol.LEVEL,-Math.round(v));}));
            button(card,"Source: "+(ready?Duet3MixerProtocol.ROUTES[outputs[i][3]]:"unknown"),()->chooseRoute(out),true);
            LinearLayout levels=column();button(levels,"Quieter · -1 dB",()->transact("Output "+(out+1)+" quieter",s->s.protocol.stepLevel(out,-1)),true);button(levels,"Louder · +1 dB",()->transact("Output "+(out+1)+" louder",s->s.protocol.stepLevel(out,1)),true);card.addView(levels);
            button(card,"Mute: "+(ready?(outputs[i][4]==1?"on":"off"):"?"),()->transact("Output mute "+out,s->s.protocol.toggle(out,Duet3Protocol.MUTE)),true);
            LinearLayout switches=row();button(switches,"Dim: "+(ready?(outputs[i][1]==1?"on":"off"):"?"),()->transact("Output dim "+out,s->s.protocol.toggle(out,Duet3Protocol.DIM)),true);button(switches,"Mono: "+(ready?(outputs[i][2]==1?"on":"off"):"?"),()->transact("Output mono "+out,s->s.protocol.toggle(out,Duet3Protocol.MONO)),true);card.addView(switches);
        }
        button(content,"Mute both outputs",()->transact("Mute all outputs",s->{s.protocol.writeAndVerify(0,Duet3Protocol.MUTE,1);s.protocol.writeAndVerify(1,Duet3Protocol.MUTE,1);}),true);
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
    private void renderPresets(){
        content.addView(text("YOUR PRESETS",18));content.addView(text("Save mixer settings or all 17 ECS parameters. Imported files only add saved presets; nothing is applied to the Duet until you choose Recall. These are Fuzz Control presets, not Apogee factory preset files.",13));
        button(content,"Save mixer",()->savePreset("mixer",0),true);
        button(content,"Save Analog 1 FX",()->savePreset("dsp",0),true).setEnabled(ready&&!busy&&effects!=null);
        button(content,"Save Analog 2 FX",()->savePreset("dsp",1),true).setEnabled(ready&&!busy&&effects!=null);
        button(content,"Flat FX preset",()->recallPreset(new PresetStore.Preset("Flat / bypassed sections","dsp",Duet3DspProtocol.FLAT)),true).setEnabled(ready&&!busy&&effects!=null);
        button(content,"Export presets",()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"fuzz-control-presets.json"),101),false);
        button(content,"Import presets",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),102),false);
        List<PresetStore.Preset> all=presets.list();if(all.isEmpty())content.addView(text("No saved presets yet.",14));
        for(PresetStore.Preset p:all){LinearLayout box=card(p.name+" · "+p.kind);button(box,"Recall",()->recallPreset(p),true).setEnabled(ready&&!busy&&(!p.kind.equals("dsp")||effects!=null));
            button(box,"Delete",()->new AlertDialog.Builder(this).setTitle("Delete "+p.name+"?").setPositiveButton("Delete",(d,n)->{try{presets.delete(p.name);render();}catch(Exception e){status.setText(e.getMessage());}}).setNegativeButton("Cancel",null).show(),false);}
    }
    private void savePreset(String kind,int ch){
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint(kind.equals("mixer")?"My monitor mix":"My instrument FX");name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Save "+kind+" preset").setMessage("A matching name replaces the saved preset.").setView(name).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a name");return;}dialog.dismiss();
            transact("Save "+title,session->{float[] values;
                if(kind.equals("dsp"))values=session.dsp.read(session.controller.dspChannel(ch));
                else{int[][] snapshot=session.mixer.snapshot();values=new float[20];for(int i=0;i<5;i++)for(int j=0;j<4;j++)values[i*4+j]=snapshot[i][j];}
                try{presets.save(new PresetStore.Preset(title,kind,values));}catch(org.json.JSONException e){throw new IOException("Could not save preset",e);}
            });}));dialog.show();
    }
    private void recallPreset(PresetStore.Preset p){
        if(p.kind.equals("dsp")){new AlertDialog.Builder(this).setTitle("Recall "+p.name).setItems(stereo==1?new String[]{"Stereo Analog 1–2"}:new String[]{"Analog 1","Analog 2"},(d,ch)->new AlertDialog.Builder(this).setTitle("Apply FX preset?").setMessage("This changes the hardware effects and the processed recording signal.").setPositiveButton("Recall",(a,b)->transact("Recall "+p.name,s->s.controller.applyDsp(ch,p.data))).setNegativeButton("Cancel",null).show()).show();return;}
        new AlertDialog.Builder(this).setTitle("Recall mixer: "+p.name+"?").setMessage("Replaces mixer faders, pan, mute and solo. If inputs are stereo-linked, Analog 1 controls both channels and their pans remain left/right. Output routing and preamps are unchanged.")
            .setPositiveButton("Recall",(d,n)->transact("Recall "+p.name,s->{
                boolean linked=s.controller.linked();s.mixer.write(0,Duet3MixerProtocol.MASTER_MUTE,1);
                for(int c=0;c<4;c++){if(linked&&c==1)continue;s.controller.setMixer(c,Duet3MixerProtocol.LEVEL,(int)p.data[c*4]);if(!(linked&&c==0))s.controller.setMixer(c,Duet3MixerProtocol.PAN,(int)p.data[c*4+1]);s.controller.setMixer(c,Duet3MixerProtocol.MUTE,(int)p.data[c*4+2]);s.controller.setMixer(c,Duet3MixerProtocol.SOLO,(int)p.data[c*4+3]);}
                s.mixer.write(4,Duet3MixerProtocol.LEVEL,(int)p.data[16]);s.mixer.write(0,Duet3MixerProtocol.MASTER_MUTE,(int)p.data[18]);
            })).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();
        if(request!=101&&request!=102)return;
        worker.execute(()->{String message;try{
            if(request==101){byte[] bytes=presets.exportText().getBytes(java.nio.charset.StandardCharsets.UTF_8);try(java.io.OutputStream stream=getContentResolver().openOutputStream(uri,"wt")){if(stream==null)throw new IOException("Cannot open destination");stream.write(bytes);}message="Presets exported.";}
            else{java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.io.InputStream stream=getContentResolver().openInputStream(uri)){if(stream==null)throw new IOException("Cannot open preset file");byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1){if(bytes.size()+n>65536)throw new IOException("Preset file exceeds 64 KB");bytes.write(buffer,0,n);}}
                String contents=bytes.toString("UTF-8");List<PresetStore.Preset> imported=PresetStore.decode(contents);handler.post(()->{if(isDestroyed())return;new AlertDialog.Builder(this).setTitle("Import "+imported.size()+" presets?").setMessage("Matching names will replace saved presets. Device settings are not changed.").setPositiveButton("Import",(d,n)->{try{presets.importText(contents);status.setText("Presets imported. Tap Read to reconnect controls.");render();}catch(Exception e){status.setText("Import failed: "+e.getMessage());}}).setNegativeButton("Cancel",null).show();});return;}
        }catch(Exception e){message="Preset file operation failed: "+e.getMessage();}final String outcome=message;handler.post(()->{if(!isDestroyed()){status.setText(outcome);render();}});});
    }
    private void renderDevices(){
        content.addView(text("CONNECTION & DIAGNOSTICS",18));
        button(content,"Refresh devices",this::refreshInventory,false);
        button(content,"Capture raw meter packet",()->transact("Read meter packet",session->{
            String packet=Arrays.toString(session.readMeterPacket());
            handler.post(()->{if(!isDestroyed())log.append("Raw meter words (unmapped): ").append(packet).append('\n');});
        }),true);
        button(content,"Share diagnostic report",()->{Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"Fuzz Control 0.5.1 diagnostics").putExtra(Intent.EXTRA_TEXT,report+"\nCONTROL LOG\n"+log);startActivity(Intent.createChooser(send,"Share report"));},false);
        TextView data=text(report,12);data.setTypeface(Typeface.MONOSPACE);data.setTextIsSelectable(true);content.addView(data);
        content.addView(text("Experimental 0.5.1. USB controls are independently implemented. No Apogee software is bundled. No audio recording or network access.",12));
    }
    private void connect(){
        if(busy)return;List<UsbDevice> devices=DeviceInventory.usbDevices(this);devices.removeIf(d->!Duet3Usb.matches(d));
        if(devices.isEmpty()){status.setText("No Apogee Duet 3 detected. Check Devices and share its report if yours is attached.");tab=6;render();return;}
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
            int[][] nextMix=null,nextOutputs=new int[2][5];int nextRecording=0,nextStereo=0;int[][] nextInputs=null;float[][] nextEffects=null;int[] nextSystem=null;String inError="",fxError="",sysError="";String message;boolean ok=false;
            try(Duet3Usb session=new Duet3Usb((UsbManager)getSystemService(USB_SERVICE),device)){
                op.run(session);nextStereo=session.input.read(0,Duet3InputProtocol.STEREO);nextMix=session.mixer.snapshot();nextRecording=session.mixer.read(0,Duet3MixerProtocol.RECORDING_SOURCE);
                for(int i=0;i<2;i++){nextOutputs[i][0]=session.protocol.read(i,Duet3Protocol.LEVEL);nextOutputs[i][1]=session.protocol.read(i,Duet3Protocol.DIM);nextOutputs[i][2]=session.protocol.read(i,Duet3Protocol.MONO);nextOutputs[i][3]=session.mixer.read(i,Duet3MixerProtocol.ROUTE);nextOutputs[i][4]=session.protocol.read(i,Duet3Protocol.MUTE);}
                try{nextInputs=session.input.snapshot();}catch(IOException e){inError=e.getMessage();}
                try{float[] left=session.dsp.read(0);nextEffects=new float[][]{left,nextStereo==1?left.clone():session.dsp.read(1)};}catch(IOException e){fxError=e.getMessage();}
                try{nextSystem=session.system.snapshot();}catch(IOException e){sysError=e.getMessage();}
                message=label+" • device readback received. Settings refreshed.";ok=true;
            }catch(IOException|RuntimeException e){message=label+" failed: "+e.getMessage()+". Tap Read to recover; a write may already have applied.";}
            final int rec=nextRecording,link=nextStereo;final int[][] in=nextInputs;final float[][] fx=nextEffects;final int[] sys=nextSystem;final String ie=inError,fe=fxError,se=sysError;final int[][] m=nextMix,o=nextOutputs;final String result=message;final boolean success=ok;
            handler.post(()->{busy=false;if(isDestroyed())return;log.append(result).append('\n');if(success)log.append("Mixer ").append(Arrays.deepToString(m)).append("\nOutputs ").append(Arrays.deepToString(o)).append("\nRecording source ").append(rec).append("\nInputs ").append(Arrays.deepToString(in)).append("\nDSP ").append(Arrays.deepToString(fx)).append("\nSystem ").append(Arrays.toString(sys)).append("\nOptional reads: ").append(ie).append(" / ").append(fe).append(" / ").append(se).append('\n');if(log.length()>20000)log.delete(0,log.length()-20000);
                if(!active||token!=generation)return;ready=success;if(success){mix=m;outputs=o;recordingSource=rec;stereo=link;inputs=in;effects=fx;systemSettings=sys;inputError=ie;dspError=fe;systemError=se;meterFailed=false;}status.setText(result);render();scheduleMeters();});
        });
    }
    private void scheduleMeters(){handler.removeCallbacks(meterTick);if(active&&ready&&!meterFailed)handler.postDelayed(meterTick,200);}
    private void pollMeters(){
        if(!active||!ready||selected==null||meterFailed)return;
        if(busy||meterBusy||tab>=4){scheduleMeters();return;}
        meterBusy=true;final UsbDevice device=selected;final int token=generation;
        worker.execute(()->{
            int[] packet=null;String error=null;
            try(Duet3Usb session=new Duet3Usb((UsbManager)getSystemService(USB_SERVICE),device)){packet=session.readMeterPacket();}
            catch(IOException|RuntimeException e){error=e.getMessage();}
            final int[] data=packet;final String failure=error;
            handler.post(()->{meterBusy=false;if(isDestroyed()||!active||token!=generation)return;
                if(failure!=null){meterFailed=true;log.append("Meter polling stopped: ").append(failure).append('\n');for(MeterDisplay meter:meterDisplays)meter.unavailable();}
                else {for(MeterDisplay meter:meterDisplays)meter.update(data);scheduleMeters();}
            });
        });
    }
    private void disconnect(String message){generation++;selected=null;ready=false;handler.removeCallbacks(meterTick);status.setText(message);render();}
    private void queueInventory(){handler.removeCallbacks(inventoryRefresh);handler.postDelayed(inventoryRefresh,250);}
    private void refreshInventory(){try{report=DeviceInventory.report(this);}catch(RuntimeException e){report="Inventory unavailable. Reconnect and retry.";}if(tab==6)render();}
    @Override protected void onStart(){super.onStart();active=true;IntentFilter f=new IntentFilter(permissionAction);f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);androidx.core.content.ContextCompat.registerReceiver(this,usbReceiver,f,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);audio=(AudioManager)getSystemService(AUDIO_SERVICE);midi=(MidiManager)getSystemService(MIDI_SERVICE);if(audio!=null)audio.registerAudioDeviceCallback(audioCallback,handler);if(midi!=null)midi.registerDeviceCallback(midiCallback,handler);refreshInventory();render();}
    @Override protected void onStop(){active=false;generation++;ready=false;unregisterReceiver(usbReceiver);if(audio!=null)audio.unregisterAudioDeviceCallback(audioCallback);if(midi!=null)midi.unregisterDeviceCallback(midiCallback);handler.removeCallbacks(inventoryRefresh);handler.removeCallbacks(meterTick);super.onStop();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
