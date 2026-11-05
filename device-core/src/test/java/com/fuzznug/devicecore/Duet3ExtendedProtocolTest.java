package com.fuzznug.devicecore;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.*;
import java.util.*;

public class Duet3ExtendedProtocolTest {
    static final class Device implements Duet3Protocol.Transport {
        final Map<String,Integer> bytes=new HashMap<>();final List<String> writes=new ArrayList<>();
        final float[][] dsp={Duet3DspProtocol.FLAT.clone(),Duet3DspProtocol.FLAT.clone()};
        boolean ignore,firmwareGrouping;int truncate=-1;
        int get(int req,int ch){return bytes.getOrDefault(req+":"+ch,0);}
        void put(int req,int ch,int value){bytes.put(req+":"+ch,value);}
        public int transfer(int type,int req,int value,int ch,byte[] data){
            if(type==0x40){writes.add(req+":"+ch+":"+value);if(ignore)return data.length;}
            if(req==0xda){
                if(type==0xc0){assertEquals(0,value);assertEquals(68,data.length);ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);for(float f:dsp[ch])b.putFloat(f);}
                else{assertEquals(4,data.length);dsp[ch][value]=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat();}
            }else{assertEquals(0,value);assertEquals(1,data.length);if(type==0xc0)data[0]=(byte)get(req,ch);else{
                int old=get(req,ch);put(req,ch,data[0]&255);
                if(firmwareGrouping&&(req==0x34||req==0x3e)&&get(0x44,ch)==1&&get(0x44,1-ch)==1)put(req,1-ch,get(req,1-ch)+(data[0]&255)-old);
            }}return truncate<0?data.length:truncate;
        }
    }
    private Duet3Controller controller(Device f){return new Duet3Controller(new Duet3InputProtocol(f),new Duet3MixerProtocol(f),new Duet3DspProtocol(f));}
    @Test public void inputTypeClearsPhantomBeforeChangingReference()throws Exception{
        Device f=new Device();f.put(0x16,1,2);f.put(0x15,1,1);new Duet3InputProtocol(f).write(1,0x16,3);
        assertEquals(Arrays.asList("21:1:0","22:1:0"),f.writes);assertEquals(0,f.get(0x15,1));assertEquals(3,f.get(0x16,1));
    }
    @Test public void phantomCannotBeEnabledForLineOrInstrument(){
        Device f=new Device();Duet3InputProtocol p=new Duet3InputProtocol(f);for(int type:new int[]{0,1,3}){f.put(0x16,0,type);assertThrows(IOException.class,()->p.write(0,0x15,1));}assertTrue(f.writes.isEmpty());
    }
    @Test public void micAndInstrumentHaveDifferentGainRanges(){
        Device f=new Device();Duet3InputProtocol p=new Duet3InputProtocol(f);
        assertThrows(IllegalArgumentException.class,()->p.write(0,0x34,66));assertThrows(IllegalArgumentException.class,()->p.write(0,0x3e,46));assertTrue(f.writes.isEmpty());
    }
    @Test public void groupedGainPreservesOffsetWithOrWithoutFirmwareMirroring()throws Exception{
        for(boolean firmware:new boolean[]{false,true}){Device f=new Device();f.firmwareGrouping=firmware;
            for(int c=0;c<2;c++){f.put(0x16,c,2);f.put(0x44,c,1);}f.put(0x34,0,20);f.put(0x34,1,25);
            new Duet3InputProtocol(f).setGain(0,23);assertEquals(23,f.get(0x34,0));assertEquals(28,f.get(0x34,1));assertEquals(firmware?1:2,f.writes.size());}
    }
    @Test public void groupedBoundsRejectedBeforeAnyWrite(){
        Device f=new Device();for(int c=0;c<2;c++){f.put(0x16,c,2);f.put(0x44,c,1);}f.put(0x34,0,20);f.put(0x34,1,65);
        assertThrows(IOException.class,()->new Duet3InputProtocol(f).setGain(0,21));assertTrue(f.writes.isEmpty());
    }
    @Test public void dspUsesLittleEndianFloatsAndParameterInWValue()throws Exception{
        Device f=new Device();Duet3DspProtocol p=new Duet3DspProtocol(f);p.write(1,14,-23.5f);assertEquals(-23.5f,p.read(1)[14],0);assertEquals(0,p.read(0)[14],0);assertEquals(Arrays.asList("218:1:14"),f.writes);
    }
    @Test public void invalidDspPresetCannotPartiallyApply(){
        Device f=new Device();float[] values=Duet3DspProtocol.FLAT.clone();values[0]=0;values[16]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->new Duet3DspProtocol(f).apply(0,values));assertTrue(f.writes.isEmpty());
    }
    @Test public void rejectsShortDspReadAndNonfiniteResponse(){
        Device f=new Device();f.truncate=64;assertThrows(IOException.class,()->new Duet3DspProtocol(f).write(0,14,-20));assertTrue(f.writes.isEmpty());
        f.truncate=-1;f.dsp[0][12]=Float.POSITIVE_INFINITY;assertThrows(IOException.class,()->new Duet3DspProtocol(f).read(0));
    }
    @Test public void ignoredDspAndInputWritesFailReadback(){
        Device f=new Device();f.ignore=true;assertThrows(IOException.class,()->new Duet3DspProtocol(f).write(0,14,-20));assertThrows(IOException.class,()->new Duet3InputProtocol(f).write(0,0x11,1));
    }
    @Test public void linkedMixerMirrorsLevelButProtectsIndividualPan()throws Exception{
        Device f=new Device();f.put(0xdb,0,1);Duet3Controller p=controller(f);p.setMixer(1,0x4c,50);assertEquals(50,f.get(0x4c,0));assertEquals(50,f.get(0x4c,1));
        int count=f.writes.size();assertThrows(IOException.class,()->p.setMixer(0,0x4d,64));assertEquals(count,f.writes.size());
    }
    @Test public void stereoLinkCopiesLeftMixerAndPositionsPair()throws Exception{
        Device f=new Device();f.put(0x4c,0,48);f.put(0x4f,0,1);Duet3Controller p=controller(f);p.setStereo(true);
        assertEquals(1,f.get(0xdb,0));assertEquals(48,f.get(0x4c,1));assertEquals(1,f.get(0x4f,1));assertEquals(0,f.get(0x4d,0));assertEquals(128,f.get(0x4d,1));
        p.setStereo(false);assertEquals(64,f.get(0x4d,0));assertEquals(64,f.get(0x4d,1));
    }
    @Test public void linkedFxWritesSharedBankZero()throws Exception{
        Device f=new Device();f.put(0xdb,0,1);controller(f).setDsp(1,16,2.5f);assertEquals(2.5f,f.dsp[0][16],0);assertEquals(0,f.dsp[1][16],0);
    }
    @Test public void outputMuteIsIndependentFromMixerMute()throws Exception{
        Device f=new Device();new Duet3Protocol(f).toggle(1,Duet3Protocol.MUTE);assertEquals(1,f.get(0x35,1));assertEquals(0,f.get(0x4f,1));
    }
    @Test public void brightnessAndHoldValidateBeforeWrite()throws Exception{
        Device f=new Device();Duet3SystemProtocol p=new Duet3SystemProtocol(f);assertThrows(IllegalArgumentException.class,()->p.write(4,1,55));assertTrue(f.writes.isEmpty());p.write(4,1,60);assertEquals(60,f.get(4,1));assertEquals(0,f.get(4,0));assertThrows(IllegalArgumentException.class,()->p.write(0x17,0,3));
    }
}
