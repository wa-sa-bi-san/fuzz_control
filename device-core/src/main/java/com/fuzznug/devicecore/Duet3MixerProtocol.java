package com.fuzznug.devicecore;

import java.io.IOException;

/** Duet 3 monitor mixer. Values are wire bytes, never audio samples. */
public final class Duet3MixerProtocol {
    public static final int LEVEL=0x4c, PAN=0x4d, SOLO=0x4e, MUTE=0x4f, MASTER_MUTE=0xe2, ROUTE=0x53;
    public static final String[] CHANNELS={"Analog 1", "Analog 2", "Playback 1–2", "Playback 3–4", "Mix master"};
    public static final String[] ROUTES={"Playback 1–2", "Playback 3–4", "Mixer", "Follow", "Analog 1", "Analog 2", "Analog 1–2"};
    private final Duet3Protocol.Transport transport;
    public Duet3MixerProtocol(Duet3Protocol.Transport transport) { this.transport=transport; }
    private static int max(int index,int request) {
        switch(request) {
            case LEVEL: if(index>=0 && index<=4) return 54; break;
            case PAN: if(index>=0 && index<=3) return 128; break;
            case MUTE: case SOLO: if(index>=0 && index<=3) return 1; break;
            case MASTER_MUTE: if(index==0) return 1; break;
            case ROUTE: if(index>=0 && index<=1) return 6; break;
            default: break;
        }
        throw new IllegalArgumentException("Unsupported mixer request/index");
    }
    public int read(int index,int request) throws IOException {
        int limit=max(index,request); byte[] data=new byte[1];
        int count=transport.transfer(0xc0,request,0,index,data);
        if(count!=1) throw new IOException(String.format("Mixer read %02x/%d transferred %d bytes",request,index,count));
        int value=data[0]&255;
        if(value>limit) throw new IOException("Unexpected mixer value: "+value);
        return value;
    }
    public void write(int index,int request,int value) throws IOException {
        int limit=max(index,request);
        if(value<0 || value>limit) throw new IllegalArgumentException("Mixer value outside range");
        // Follow depends on another output's configuration; display it but do not create it.
        if(request==ROUTE && value==3) throw new IllegalArgumentException("Follow routing is read-only");
        read(index,request); // Never write after a failed preflight read.
        int count=transport.transfer(0x40,request,0,index,new byte[]{(byte)value});
        if(count!=1) throw new IOException("Mixer write failed; reread device");
        int actual=read(index,request);
        if(actual!=value) throw new IOException("Mixer readback mismatch: requested "+value+", received "+actual);
    }
    public void toggle(int index,int request) throws IOException {
        if(request!=MUTE && request!=SOLO && request!=MASTER_MUTE) throw new IllegalArgumentException("Not a toggle");
        write(index,request,1-read(index,request));
    }
    public static int encodeDb(int db) {
        if(db < -48 || db > 6) throw new IllegalArgumentException("Mixer level must be -48..+6 dB");
        return db+48;
    }
    public static int decodeDb(int wire) {
        if(wire<0 || wire>54) throw new IllegalArgumentException("Invalid mixer level");
        return wire-48;
    }
    public int[][] snapshot() throws IOException {
        int[][] channels=new int[5][4];
        for(int i=0;i<5;i++) {
            channels[i][0]=read(i,LEVEL);
            if(i<4) {
                channels[i][1]=read(i,PAN);
                channels[i][2]=read(i,MUTE);
                channels[i][3]=read(i,SOLO);
            } else channels[i][2]=read(0,MASTER_MUTE);
        }
        return channels;
    }
}
