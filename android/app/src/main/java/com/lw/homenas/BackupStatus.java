package com.lw.homenas;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import org.json.JSONObject;

/** Per-account run history. Never persists filenames, endpoints, tokens or exception text. */
final class BackupStatus {
    private static final Object LOCK=new Object();
    private final SharedPreferences prefs;
    private final Context context;
    BackupStatus(Context context){this.context=context.getApplicationContext();prefs=this.context.getSharedPreferences("backup-status",Context.MODE_PRIVATE);}
    private String key(String scope){return "run_"+Protocol.key(scope);}
    private JSONObject read(String scope){
        try{String raw=prefs.getString(key(scope),"{}");if(raw.length()>4096)return new JSONObject();return new JSONObject(raw);}catch(Exception ignored){return new JSONObject();}
    }
    void begin(String scope){update(scope,"RUNNING",0,0,false);}
    void scanned(String scope){update(scope,null,0,0,true);}
    void finish(String scope,String result,long completed,long failed){update(scope,result,completed,failed,false);}
    private void update(String scope,String result,long completed,long failed,boolean scan){
        if(scope==null||scope.isEmpty())return;
        synchronized(LOCK){try{
            JSONObject value=read(scope);long now=System.currentTimeMillis();
            if(scan)value.put("last_scan",Long.toString(now));
            else if("RUNNING".equals(result))value.put("last_started",Long.toString(now)).put("result",result).put("completed",0).put("failed",0);
            else value.put("last_finished",Long.toString(now)).put("result",result).put("completed",Math.max(0,completed)).put("failed",Math.max(0,failed));
            if(!prefs.edit().putString(key(scope),value.toString()).commit())Diagnostics.event("backup.history","save_failed=true");
        }catch(Exception error){Diagnostics.error("backup.history",error);}}
    }
    JSONObject snapshot(String scope,boolean running,Settings settings) throws Exception {
        JSONObject value=scope==null||scope.isEmpty()?new JSONObject():read(scope);
        if(!running&&value.optString("result").equals("RUNNING"))value.put("result","INTERRUPTED");
        value.put("auto_album_count",settings.albums().size()).put("wait_reason",waiting(settings));
        return value;
    }
    private String waiting(Settings settings){
        if(!settings.prefs.getBoolean("auto",false))return "MANUAL";
        if(settings.paused())return "PAUSED";
        if(settings.albums().isEmpty())return "NO_ALBUMS";
        try{
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities network=manager==null?null:manager.getNetworkCapabilities(manager.getActiveNetwork());
            if(network==null)return "NETWORK";
            if(settings.prefs.getBoolean("wifi",true)&&!network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))return "UNMETERED";
            Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(battery!=null){
                int status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1),level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
                boolean charging=status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL;
                if(settings.prefs.getBoolean("charging",false)&&!charging)return "CHARGING";
                if(!charging&&level>=0&&scale>0&&100L*level/scale<=15)return "BATTERY";
            }
            return "SCHEDULED";
        }catch(Exception ignored){return "UNKNOWN";}
    }
}
