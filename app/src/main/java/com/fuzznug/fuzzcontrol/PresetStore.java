package com.fuzznug.fuzzcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import com.fuzznug.devicecore.Duet3DspProtocol;
import org.json.*;
import java.util.*;

/** App-owned presets only. Import validates the whole file before changing saved data. */
final class PresetStore {
    private final SharedPreferences prefs;
    PresetStore(Context context){prefs=context.getSharedPreferences("presets",Context.MODE_PRIVATE);}
    static final class Preset {
        final String name,kind;final float[] data;
        Preset(String name,String kind,float[] data){this.name=name.trim();this.kind=kind;this.data=data.clone();validate();}
        private void validate(){
            if(name.isEmpty()||name.length()>60)throw new IllegalArgumentException("Name must contain 1–60 characters");
            if(kind.equals("dsp")){if(data.length!=17)throw new IllegalArgumentException("DSP preset needs 17 parameters");for(int i=0;i<17;i++)Duet3DspProtocol.validate(i,data[i]);}
            else if(kind.equals("mixer")){
                if(data.length!=20)throw new IllegalArgumentException("Mixer preset needs 20 values");
                for(int i=0;i<20;i++){float v=data[i];int field=i%4;int max=field==0?54:field==1?128:1;if(!Float.isFinite(v)||v<0||v>max||v!=Math.rint(v))throw new IllegalArgumentException("Invalid mixer value");}
            }else throw new IllegalArgumentException("Unknown preset type");
        }
        JSONObject json()throws JSONException{JSONObject o=new JSONObject();o.put("name",name);o.put("kind",kind);JSONArray a=new JSONArray();for(float v:data)a.put(v);return o.put("values",a);}
        static Preset parse(JSONObject o)throws JSONException{JSONArray a=o.getJSONArray("values");if(a.length()>20)throw new IllegalArgumentException("Too many preset values");float[] values=new float[a.length()];for(int i=0;i<values.length;i++)values[i]=(float)a.getDouble(i);return new Preset(o.getString("name"),o.getString("kind"),values);}
    }
    List<Preset> list(){try{return decode(prefs.getString("data","{\"format\":\"fuzz-control-presets\",\"version\":1,\"presets\":[]}"));}catch(JSONException|IllegalArgumentException e){return new ArrayList<>();}}
    static List<Preset> decode(String text)throws JSONException{
        if(text.length()>65536)throw new IllegalArgumentException("Preset file is too large");JSONObject root=new JSONObject(text);
        if(!root.getString("format").equals("fuzz-control-presets")||root.getInt("version")!=1)throw new IllegalArgumentException("Unsupported preset format");
        JSONArray array=root.getJSONArray("presets");if(array.length()>50)throw new IllegalArgumentException("Maximum 50 presets");List<Preset> all=new ArrayList<>();Set<String> names=new HashSet<>();
        for(int i=0;i<array.length();i++){Preset p=Preset.parse(array.getJSONObject(i));if(!names.add(p.name))throw new IllegalArgumentException("Duplicate preset name");all.add(p);}return all;
    }
    String exportText()throws JSONException{return encode(list());}
    private static String encode(List<Preset> all)throws JSONException{JSONArray a=new JSONArray();for(Preset p:all)a.put(p.json());return new JSONObject().put("format","fuzz-control-presets").put("version",1).put("presets",a).toString(2);}
    void save(Preset p)throws JSONException{List<Preset> all=list();all.removeIf(x->x.name.equals(p.name));if(all.size()>=50)throw new IllegalArgumentException("Maximum 50 presets");all.add(p);prefs.edit().putString("data",encode(all)).apply();}
    void delete(String name)throws JSONException{List<Preset> all=list();all.removeIf(x->x.name.equals(name));prefs.edit().putString("data",encode(all)).apply();}
    void importText(String text)throws JSONException{List<Preset> incoming=decode(text),merged=list();for(Preset p:incoming){merged.removeIf(x->x.name.equals(p.name));merged.add(p);}if(merged.size()>50)throw new IllegalArgumentException("Import would exceed 50 presets");prefs.edit().putString("data",encode(merged)).apply();}
}
