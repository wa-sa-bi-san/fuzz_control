package com.fuzznug.devicecore;

import java.io.IOException;

public final class Duet3SystemProtocol {
    public static final int BRIGHTNESS=4, OS_VOLUME=0x14, OVER_HOLD=0x17, PEAK_HOLD=0x18;
    private final Duet3Protocol.Transport usb;
    public Duet3SystemProtocol(Duet3Protocol.Transport usb){this.usb=usb;}
    private static int limit(int request,int index){
        if(request==BRIGHTNESS&&index>=0&&index<=1)return 100;
        if(index!=0)throw new IllegalArgumentException("Invalid system index");
        if(request==OS_VOLUME)return 1;
        if(request==OVER_HOLD||request==PEAK_HOLD)return 2;
        throw new IllegalArgumentException("Unsupported system command");
    }
    public int read(int request,int index)throws IOException{
        int max=limit(request,index);byte[] data=new byte[1];
        if(usb.transfer(0xc0,request,0,index,data)!=1)throw new IOException("System setting read failed");
        int value=data[0]&255;if(value>max)throw new IOException("Unexpected system setting");return value;
    }
    public void write(int request,int index,int value)throws IOException{
        if(value<0||value>limit(request,index)||(request==BRIGHTNESS&&value%10!=0))throw new IllegalArgumentException("Invalid system value");
        if(read(request,index)==value)return;
        if(usb.transfer(0x40,request,0,index,new byte[]{(byte)value})!=1)throw new IOException("System write failed; change may have applied");
        if(read(request,index)!=value)throw new IOException("System readback mismatch");
    }
    public void clearMeters()throws IOException{
        if(usb.transfer(0x40,0x19,0,0,new byte[]{1})!=1)throw new IOException("Meter reset failed");
    }
    public int[] snapshot()throws IOException{return new int[]{read(BRIGHTNESS,1),read(BRIGHTNESS,0),read(OS_VOLUME,0),read(PEAK_HOLD,0),read(OVER_HOLD,0)};}
}
