package com.lw.homenas;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;

/** Bounded per-sender progress; only server acknowledgements count as transferred bytes. */
final class TransferProgress {
    private static String scope="";
    private static boolean running;
    private static long completed,failed,started;
    private static final LinkedHashMap<Long,Entry> entries=new LinkedHashMap<>();
    private static final ArrayDeque<long[]> samples=new ArrayDeque<>();
    static java.util.function.LongSupplier clock=System::nanoTime;
    private static final class Entry {String name,phase;long done,size,confirmed;}
    static synchronized void begin(String owner){scope=owner;running=true;completed=failed=0;started=clock.getAsLong();entries.clear();samples.clear();}
    static synchronized void phase(TaskStore.Task task,String phase,long processed,long size){
        Entry item=entries.get(task.id);if(item==null){item=new Entry();item.name=task.name;item.confirmed=task.offset;entries.put(task.id,item);}
        item.phase=phase;item.done=processed;item.size=size;if(phase.equals("UPLOADING"))item.confirmed=processed;
    }
    static synchronized void hashed(long id,long processed,long size){Entry item=entries.get(id);if(item!=null){item.done=processed;item.size=size;}}
    static synchronized void acknowledged(long id,long offset,long size,long bytes,long elapsed){
        Entry item=entries.get(id);if(item!=null){item.phase="UPLOADING";item.done=item.confirmed=offset;item.size=size;}
        long now=clock.getAsLong();if(bytes>0)samples.addLast(new long[]{now,bytes});trim(now);
    }
    private static void trim(long now){while(!samples.isEmpty()&&(samples.peekFirst()[0]<now-5_000_000_000L||samples.size()>256))samples.removeFirst();}
    static synchronized void finished(long id,boolean ready){entries.remove(id);if(ready)completed++;else failed++;}
    static synchronized void remove(long id){entries.remove(id);}
    static synchronized void end(){running=false;entries.clear();samples.clear();}
    static synchronized JSONObject snapshot(String owner) throws Exception {
        if(!scope.equals(owner))return new JSONObject().put("running",false);
        long now=clock.getAsLong();trim(now);long bytes=0;for(long[] sample:samples)bytes+=sample[1];
        long rate=running?Math.round(bytes*1e9/Math.max(1_000_000_000L,Math.min(5_000_000_000L,now-started))):0;
        JSONArray active=new JSONArray();for(java.util.Map.Entry<Long,Entry> row:entries.entrySet()){
            Entry item=row.getValue();active.put(new JSONObject().put("task_id",Long.toString(row.getKey())).put("name",item.name).put("phase",item.phase)
                .put("processed",Long.toString(item.done)).put("size",Long.toString(item.size)).put("confirmed",Long.toString(item.confirmed)));
        }
        JSONObject first=active.length()>0?active.getJSONObject(0):new JSONObject().put("name","").put("phase",running?"PREPARING":"IDLE").put("processed","0").put("size","0").put("confirmed","0");
        return new JSONObject(first.toString()).put("running",running).put("items",active).put("completed",completed).put("failed",failed)
            .put("bytes_per_second",rate).put("eta_seconds",-1);
    }
}
