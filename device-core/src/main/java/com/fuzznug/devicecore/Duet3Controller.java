package com.fuzznug.devicecore;

import java.io.IOException;

/** Coordinates paired settings. All USB work must run on one worker. */
public final class Duet3Controller {
    private final Duet3InputProtocol input;
    private final Duet3MixerProtocol mixer;
    private final Duet3DspProtocol dsp;
    public Duet3Controller(Duet3InputProtocol input,Duet3MixerProtocol mixer,Duet3DspProtocol dsp){this.input=input;this.mixer=mixer;this.dsp=dsp;}
    public boolean linked()throws IOException{return input.read(0,Duet3InputProtocol.STEREO)==1;}
    public void setStereo(boolean on)throws IOException{
        if(linked()==on)return;
        int[][] before=mixer.snapshot();
        input.write(0,Duet3InputProtocol.STEREO,on?1:0);
        // MixerSideEffects centers unlinked inputs and pans a stereo pair fully L/R.
        mixer.write(0,Duet3MixerProtocol.PAN,on?0:64);mixer.write(1,Duet3MixerProtocol.PAN,on?128:64);
        if(on){
            mixer.write(1,Duet3MixerProtocol.LEVEL,before[0][0]);
            mixer.write(1,Duet3MixerProtocol.MUTE,before[0][2]);mixer.write(1,Duet3MixerProtocol.SOLO,before[0][3]);
        }
        // Group is a distinct hardware setting; preserve gain offsets and only group eligible preamps.
        if(input.read(0,Duet3InputProtocol.TYPE)>=2&&input.read(1,Duet3InputProtocol.TYPE)>=2)input.setGroup(on);
    }
    public void setMixer(int channel,int request,int value)throws IOException{
        boolean paired=channel<2&&linked();
        if(paired&&request==Duet3MixerProtocol.PAN)throw new IOException("Unlink analog inputs to change their individual pans");
        if(paired)mixer.read(1-channel,request);
        mixer.write(channel,request,value);
        if(paired)mixer.write(1-channel,request,value);
    }
    public void toggleMixer(int channel,int request)throws IOException{setMixer(channel,request,1-mixer.read(channel,request));}
    public int dspChannel(int channel)throws IOException{return linked()?0:channel;}
    public void setDsp(int channel,int parameter,float value)throws IOException{dsp.write(dspChannel(channel),parameter,value);}
    public void applyDsp(int channel,float[] values)throws IOException{dsp.apply(dspChannel(channel),values);}
    public void setFxBypass(int channel,int value)throws IOException{
        boolean paired=linked();if(paired)input.read(1-channel,Duet3InputProtocol.FX_BYPASS);
        input.write(channel,Duet3InputProtocol.FX_BYPASS,value);if(paired)input.write(1-channel,Duet3InputProtocol.FX_BYPASS,value);
    }
}
