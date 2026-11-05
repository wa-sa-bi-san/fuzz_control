package com.fuzznug.devicecore;
import java.io.IOException;

/** Read-only Duet 3 meters. Mapping recovered from Control 2 DeviceInfo meter groups. */
public final class Duet3MeterPacket {
    private Duet3MeterPacket() {}
    public static final int[][] MIXER_WORDS={{4},{5},{8,9},{10,11},{12,13}};
    public static final int[][] OUTPUT_WORDS={{0,1},{2,3}};
    public static double dbfs(int word) {
        if(word<0 || word>65535)throw new IllegalArgumentException("Invalid meter word");
        int magnitude=word&0x7fff;
        return magnitude==0?Double.NEGATIVE_INFINITY:20*Math.log10(magnitude/32768.0);
    }
    public static boolean clipped(int word) { return (word&0x8000)!=0; }

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
