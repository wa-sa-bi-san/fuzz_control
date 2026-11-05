package com.fuzznug.devicecore;
import java.io.IOException;

/** Raw, read-only meter capture. Physical channel labels remain unverified. */
public final class Duet3MeterPacket {
    private Duet3MeterPacket() {}
    public static int[] read(Duet3Protocol.Transport transport) throws IOException {
        byte[] data=new byte[28];
        int count=transport.transfer(0xc0,0x55,0,0,data);
        if(count!=28)throw new IOException("Meter packet length "+count+", expected 28");
        return decode(data);
    }
    public static int[] decode(byte[] data) {
        if(data.length!=28)throw new IllegalArgumentException("Meter packet must have 28 bytes");
        int[] words=new int[14];for(int i=0;i<14;i++)words[i]=((data[i*2]&255)<<8)|(data[i*2+1]&255);
        return words;
    }
}
