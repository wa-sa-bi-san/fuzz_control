package com.fuzznug.devicecore;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;

public class Duet3MixerProtocolTest {
    static final class Fake implements Duet3Protocol.Transport {
        final Map<String,Integer> values=new HashMap<>();
        final List<String> calls=new ArrayList<>();
        int count=1;boolean ignoreWrites;
        public int transfer(int type,int request,int word,int index,byte[] data){
            assertEquals(0,word);assertEquals(1,data.length);String key=request+":"+index;
            calls.add(type+":"+key+":"+(data[0]&255));
            if(type==0xc0)data[0]=(byte)(int)values.getOrDefault(key,0);
            else if(!ignoreWrites)values.put(key,data[0]&255);
            return count;
        }
    }
    @Test public void faderEncodingIncludesUnityAndPositiveGain(){
        assertEquals(0,Duet3MixerProtocol.encodeDb(-48));assertEquals(48,Duet3MixerProtocol.encodeDb(0));assertEquals(54,Duet3MixerProtocol.encodeDb(6));
        for(int db=-48;db<=6;db++)assertEquals(db,Duet3MixerProtocol.decodeDb(Duet3MixerProtocol.encodeDb(db)));
    }
    @Test public void playbackAndMasterUseTheirOwnIndexes() throws Exception {
        Fake f=new Fake();Duet3MixerProtocol p=new Duet3MixerProtocol(f);
        p.write(3,0x4c,48);p.write(4,0x4c,54);p.toggle(0,0xe2);
        assertTrue(f.calls.contains("64:76:3:48"));assertTrue(f.calls.contains("64:76:4:54"));assertTrue(f.calls.contains("64:226:0:1"));
    }
    @Test public void mixerRouteDoesNotTouchOtherOutput() throws Exception {
        Fake f=new Fake();f.values.put("83:0",1);new Duet3MixerProtocol(f).write(1,0x53,2);
        assertEquals(Integer.valueOf(1),f.values.get("83:0"));assertEquals(Integer.valueOf(2),f.values.get("83:1"));
        assertEquals(Arrays.asList("192:83:1:0","64:83:1:2","192:83:1:0"),f.calls);
    }
    @Test public void controlsHaveDistinctWireRequests() throws Exception {
        Fake f=new Fake();Duet3MixerProtocol p=new Duet3MixerProtocol(f);p.write(2,0x4d,64);p.toggle(2,0x4f);p.toggle(2,0x4e);
        assertTrue(f.calls.contains("64:77:2:64"));assertTrue(f.calls.contains("64:79:2:1"));assertTrue(f.calls.contains("64:78:2:1"));
    }
    @Test public void snapshotReadsAllChannelsAndMasterWithoutWrites() throws Exception {
        Fake f=new Fake();f.values.put("76:4",48);f.values.put("77:3",128);int[][] v=new Duet3MixerProtocol(f).snapshot();
        assertEquals(5,v.length);assertEquals(48,v[4][0]);assertEquals(128,v[3][1]);assertEquals(18,f.calls.size());for(String c:f.calls)assertTrue(c.startsWith("192:"));
    }
    @Test public void malformedAndShortReadsPreventWrites(){
        for(int count:new int[]{0,-1,2}){Fake f=new Fake();f.count=count;assertThrows(IOException.class,()->new Duet3MixerProtocol(f).write(0,0x4c,48));assertEquals(1,f.calls.size());}
        Fake f=new Fake();f.values.put("77:1",255);assertThrows(IOException.class,()->new Duet3MixerProtocol(f).write(1,0x4d,64));assertEquals(1,f.calls.size());
    }
    @Test public void ignoredWritesFailVerification(){
        Fake f=new Fake();f.ignoreWrites=true;assertThrows(IOException.class,()->new Duet3MixerProtocol(f).write(0,0x4f,1));
    }
    @Test public void recordingSourceIsSeparateFromOutputRoute() throws Exception {
        Fake f=new Fake();f.values.put("83:0",1);Duet3MixerProtocol p=new Duet3MixerProtocol(f);
        p.write(0,Duet3MixerProtocol.RECORDING_SOURCE,2);
        assertEquals(Arrays.asList("192:220:0:0","64:220:0:2","192:220:0:0"),f.calls);
        assertEquals(Integer.valueOf(1),f.values.get("83:0"));
        assertThrows(IllegalArgumentException.class,()->p.write(1,0xdc,2));
        assertThrows(IllegalArgumentException.class,()->p.write(0,0xdc,3));
    }
    @Test public void illegalCombinationsNeverReachUsb(){
        Fake f=new Fake();Duet3MixerProtocol p=new Duet3MixerProtocol(f);
        assertThrows(IllegalArgumentException.class,()->p.write(4,0x4e,1));assertThrows(IllegalArgumentException.class,()->p.write(4,0x4d,64));assertThrows(IllegalArgumentException.class,()->p.write(1,0xe2,1));
        assertThrows(IllegalArgumentException.class,()->p.write(0,0x4c,55));assertThrows(IllegalArgumentException.class,()->p.write(0,0x53,3));assertThrows(IllegalArgumentException.class,()->p.write(0,0xdb,1));assertThrows(IllegalArgumentException.class,()->p.toggle(0,0x53));assertTrue(f.calls.isEmpty());
    }
}
