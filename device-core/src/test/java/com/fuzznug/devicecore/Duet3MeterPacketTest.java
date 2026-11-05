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
    @Test public void rejectsWrongBufferLength(){assertThrows(IllegalArgumentException.class,()->Duet3MeterPacket.decode(new byte[26]));}
}
