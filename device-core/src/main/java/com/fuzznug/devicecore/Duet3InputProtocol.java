package com.fuzznug.devicecore;

import java.io.IOException;

/** Duet 3 input controls recovered from named Control 2 read and write paths. */
public final class Duet3InputProtocol {
    public static final int TYPE=0x16, MIC_GAIN=0x34, INSTRUMENT_GAIN=0x3e,
        PHANTOM=0x15, SOFT_LIMIT=0x11, POLARITY=0x13, GROUP=0x44, STEREO=0xdb, FX_BYPASS=5;
    public static final int[] FIELDS={TYPE,MIC_GAIN,INSTRUMENT_GAIN,PHANTOM,SOFT_LIMIT,POLARITY,GROUP,FX_BYPASS};
    public static final String[] TYPES={"Line +4 dBu","Line −10 dBV","Microphone","Instrument / Hi-Z"};
    private final Duet3Protocol.Transport usb;
    public Duet3InputProtocol(Duet3Protocol.Transport usb){this.usb=usb;}
    private static int max(int request){
        switch(request){case TYPE:return 3;case MIC_GAIN:return 65;case INSTRUMENT_GAIN:return 45;
            case PHANTOM:case SOFT_LIMIT:case POLARITY:case GROUP:case STEREO:case FX_BYPASS:return 1;
            default:throw new IllegalArgumentException("Unknown input command");}
    }
    private static void validate(int ch,int request,int value){
        int limit=max(request);
        if(ch<0||ch>1||(request==STEREO&&ch!=0)||value<0||value>limit)throw new IllegalArgumentException("Invalid input parameter");
    }
    public int read(int ch,int request)throws IOException{
        validate(ch,request,0);byte[] data=new byte[1];int count=usb.transfer(0xc0,request,0,ch,data);
        if(count!=1)throw new IOException("Input read 0x"+Integer.toHexString(request)+" failed ("+count+")");
        int value=data[0]&255;
        if(value>max(request))throw new IOException("Unexpected input value "+value);
        return value;
    }
    public void write(int ch,int request,int value)throws IOException{
        validate(ch,request,value);int old=read(ch,request);
        if(old==value)return;
        if(request==PHANTOM&&value==1&&read(ch,TYPE)!=2)throw new IOException("48 V requires Microphone input type");
        if(request==TYPE)write(ch,PHANTOM,0); // Original setter clears phantom before switching reference level.
        int count=usb.transfer(0x40,request,0,ch,new byte[]{(byte)value});
        if(count!=1)throw new IOException("Input write failed; change may have applied");
        if(read(ch,request)!=value)throw new IOException("Input readback mismatch; reread device");
    }
    public void setGain(int ch,int gain)throws IOException{
        int type=read(ch,TYPE);if(type<2)throw new IOException("Line inputs have fixed gain");
        int request=type==2?MIC_GAIN:INSTRUMENT_GAIN;validate(ch,request,gain);
        int old=read(ch,request);int peer=1-ch;int peerRequest=0,peerGain=0;
        if(read(ch,GROUP)==1&&read(peer,GROUP)==1){
            int peerType=read(peer,TYPE);
            if(peerType>=2){peerRequest=peerType==2?MIC_GAIN:INSTRUMENT_GAIN;peerGain=read(peer,peerRequest)+gain-old;
                if(peerGain<0||peerGain>max(peerRequest))throw new IOException("Grouped gain would exceed the other input's range. Ungroup or use a smaller change.");}
        }
        write(ch,request,gain);
        // Some firmware applies grouping itself. Fresh reads avoid applying the offset twice.
        if(peerRequest!=0)write(peer,peerRequest,peerGain);
    }
    public void setGroup(boolean enabled)throws IOException{
        if(enabled&&(read(0,TYPE)<2||read(1,TYPE)<2))throw new IOException("Both inputs must be Mic or Instrument to group gain");
        read(0,GROUP);read(1,GROUP);write(0,GROUP,enabled?1:0);write(1,GROUP,enabled?1:0);
    }
    public int[][] snapshot()throws IOException{
        int[][] result=new int[2][FIELDS.length];for(int c=0;c<2;c++)for(int p=0;p<FIELDS.length;p++)result[c][p]=read(c,FIELDS[p]);return result;
    }
}
