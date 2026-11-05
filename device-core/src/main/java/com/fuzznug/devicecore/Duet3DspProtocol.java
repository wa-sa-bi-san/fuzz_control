package com.fuzznug.devicecore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** Hardware ECS parameter values, not a software audio effect. No proprietary DSP code. */
public final class Duet3DspProtocol {
    public static final int REQUEST=0xda, COUNT=17;
    public static final String[] NAMES={"EQ bypass","Compressor bypass","High-pass to sidechain","Mid bandwidth","EQ before compressor","Ratio","Output level","High-pass frequency","Low shelf gain","Mid peak gain","High shelf gain","Low shelf frequency","Mid peak frequency","High shelf frequency","Threshold","Wet mix","Drive"};
    public static final float[] MIN={0,0,0,0,0,0,-20,20,-15,-15,-15,35,250,4000,-50,0,0};
    public static final float[] MAX={1,1,1,1,1,2,20,300,15,15,15,300,8000,16000,0,1,10};
    public static final float[] FLAT={1,1,0,0,1,0,0,20,0,0,0,100,1000,8000,0,1,0};
    private final Duet3Protocol.Transport usb;
    public Duet3DspProtocol(Duet3Protocol.Transport usb){this.usb=usb;}
    public static void validate(int p,float value){
        if(p<0||p>=COUNT||!Float.isFinite(value)||value<MIN[p]||value>MAX[p]||(p<=5&&value!=Math.rint(value)))throw new IllegalArgumentException("Invalid DSP parameter "+p);
    }
    private static void channel(int ch){if(ch<0||ch>1)throw new IllegalArgumentException("Invalid DSP channel");}
    public float[] read(int ch)throws IOException{
        channel(ch);byte[] data=new byte[COUNT*4];int n=usb.transfer(0xc0,REQUEST,0,ch,data);
        if(n!=data.length)throw new IOException("DSP read failed ("+n+"/"+data.length+")");
        float[] values=new float[COUNT];ByteBuffer buffer=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for(int p=0;p<COUNT;p++){values[p]=buffer.getFloat();try{validate(p,values[p]);}catch(IllegalArgumentException e){throw new IOException("DSP response outside range for "+NAMES[p],e);}}
        return values;
    }
    public void write(int ch,int p,float value)throws IOException{
        channel(ch);validate(p,value);float[] before=read(ch);
        if(Float.compare(before[p],value)==0)return;
        byte[] data=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array();
        // wIndex is input channel, wValue is parameter ID. Read is a whole 68-byte bank.
        if(usb.transfer(0x40,REQUEST,p,ch,data)!=4)throw new IOException("DSP write failed; change may have applied");
        float actual=read(ch)[p];
        if(Math.abs(actual-value)>Math.max(0.0001f,Math.abs(value)*0.00001f))throw new IOException("DSP readback mismatch for "+NAMES[p]);
    }
    public void apply(int ch,float[] values)throws IOException{
        channel(ch);if(values==null||values.length!=COUNT)throw new IllegalArgumentException("Preset needs 17 parameters");
        for(int p=0;p<COUNT;p++)validate(p,values[p]);read(ch);
        // Apply parameters first, then section enables, so recalled gain/EQ settings are established first.
        for(int p=2;p<COUNT;p++)write(ch,p,values[p]);write(ch,0,values[0]);write(ch,1,values[1]);
    }
    public static String format(int p,float v){
        if(p==5)return new String[]{"3:1","5:1","10:1"}[(int)v];
        if(p==15)return Math.round(v*100)+"%";
        if(p==7||p==11||p==12||p==13)return Math.round(v)+" Hz";
        if(p==6||p==8||p==9||p==10||p==14)return String.format(Locale.ROOT,"%.1f dB",v);
        return String.format(Locale.ROOT,"%.1f",v);
    }
}
