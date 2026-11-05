package com.fuzznug.devicecore;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
public class Duet3MeterPacketTest {
    @Test public void readsOnlyKnownInRequestWithExactLength() throws Exception {
        int[] words=Duet3MeterPacket.read((type,request,value,index,data)->{
            assertEquals(0xc0,type);assertEquals(0x55,request);assertEquals(0,value);assertEquals(0,index);assertEquals(28,data.length);
            data[0]=(byte)0x80;data[1]=1;data[26]=0x12;data[27]=0x34;return 28;
        });
        assertEquals(14,words.length);assertEquals(0x8001,words[0]);assertEquals(0x1234,words[13]);
    }
    @Test public void shortPacketsAreNeverDecoded(){for(int length:new int[]{-1,0,27})assertThrows(IOException.class,()->Duet3MeterPacket.read((t,r,v,i,d)->length));}
    @Test public void dbfsDecodingPreservesClipFlagWithoutChangingLevel(){
        assertEquals(Double.NEGATIVE_INFINITY,Duet3MeterPacket.dbfs(0),0);
        assertEquals(-6.0205999,Duet3MeterPacket.dbfs(16384),0.00001);
        assertEquals(Duet3MeterPacket.dbfs(16384),Duet3MeterPacket.dbfs(0xc000),0);
        assertTrue(Duet3MeterPacket.clipped(0xc000));assertFalse(Duet3MeterPacket.clipped(16384));
        assertThrows(IllegalArgumentException.class,()->Duet3MeterPacket.dbfs(-1));
    }
    @Test public void mixerMappingSkipsGainReductionWords(){
        assertArrayEquals(new int[]{4},Duet3MeterPacket.MIXER_WORDS[0]);
        assertArrayEquals(new int[]{5},Duet3MeterPacket.MIXER_WORDS[1]);
        assertArrayEquals(new int[]{8,9},Duet3MeterPacket.MIXER_WORDS[2]);
        assertArrayEquals(new int[]{10,11},Duet3MeterPacket.MIXER_WORDS[3]);
        assertArrayEquals(new int[]{12,13},Duet3MeterPacket.MIXER_WORDS[4]);
    }
    @Test public void rejectsWrongBufferLength(){assertThrows(IllegalArgumentException.class,()->Duet3MeterPacket.decode(new byte[26]));}
}
