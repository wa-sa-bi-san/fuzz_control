package com.fuzznug.fuzzcontrol;

import android.graphics.Bitmap;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import com.fuzznug.devicecore.Duet3DspProtocol;

public class ScreenTest {
    private static void field(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void render(MainActivity a,int tab)throws Exception{field(a,"tab",tab);Method m=MainActivity.class.getDeclaredMethod("render");m.setAccessible(true);m.invoke(a);}
    @Test public void allScreensRenderWithRealisticStateInBothOrientations()throws Exception{
        for(int orientation:new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}){
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                scenario.onActivity(a->a.setRequestedOrientation(orientation));InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a->{try{
                    // This is a test-only in-memory snapshot; no device/transport is created and no writes occur.
                    field(a,"ready",true);field(a,"inputs",new int[][]{{2,25,10,0,1,0,0,0},{3,20,15,0,0,1,0,1}});
                    field(a,"effects",new float[][]{Duet3DspProtocol.FLAT.clone(),Duet3DspProtocol.FLAT.clone()});field(a,"systemSettings",new int[]{60,70,0,1,2});
                    field(a,"mix",new int[][]{{48,64,0,0},{42,64,0,1},{48,64,0,0},{36,64,1,0},{48,0,0,0}});
                }catch(Exception e){throw new AssertionError(e);}});
                for(int tab=0;tab<7;tab++){final int current=tab;scenario.onActivity(a->{try{render(a,current);assertNotNull(a.findViewById(android.R.id.content));}catch(Exception e){throw new AssertionError(e);}});
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
                    File root=InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir("screenshots");assertNotNull(root);assertTrue(root.exists()||root.mkdirs());
                    try(FileOutputStream out=new FileOutputStream(new File(root,"screen-"+orientation+"-"+tab+".png"))){assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,out));}screenshot.recycle();
                }
            }
        }
    }
    @Test public void presetsRoundTripAndInvalidImportIsAtomic()throws Exception{
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();PresetStore store=new PresetStore(context);
        store.save(new PresetStore.Preset("Test guitar","dsp",Duet3DspProtocol.FLAT));String before=store.exportText();
        assertEquals(17,PresetStore.decode(before).get(0).data.length);
        org.json.JSONObject invalid=new org.json.JSONObject(before);invalid.getJSONArray("presets").getJSONObject(0).getJSONArray("values").put(7,99999);
        try{store.importText(invalid.toString());fail("Invalid parameter accepted");}catch(IllegalArgumentException expected){}
        assertEquals(before,store.exportText());store.delete("Test guitar");
    }
}
