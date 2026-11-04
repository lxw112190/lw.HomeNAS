package com.lw.homenas;

import org.json.JSONObject;

/** In-memory progress only. Hash/read progress is never a committed upload offset. */
final class TransferProgress {
    private static String scope="",phase="IDLE",name="";private static long task,done,total,confirmed;private static double rate;private static boolean running;private static long updated;
    static synchronized void begin(String owner){scope=owner;running=true;phase="PREPARING";name="";task=done=total=confirmed=0;rate=0;updated=System.nanoTime();}
    static synchronized void phase(TaskStore.Task item,String next,long processed,long size){if(task!=item.id){confirmed=processed;rate=0;}if(!phase.equals(next))rate=0;task=item.id;name=item.name;phase=next;done=processed;total=size;updated=System.nanoTime();}
    static synchronized void hashed(long processed,long size){done=processed;total=size;updated=System.nanoTime();}
    static synchronized void acknowledged(long offset,long size,long newlySent,long elapsedNanos){phase="UPLOADING";done=confirmed=offset;total=size;if(newlySent>0&&elapsedNanos>0){double sample=newlySent*1e9/elapsedNanos;rate=rate>0?rate*.65+sample*.35:sample;}updated=System.nanoTime();}
    static synchronized void end(){running=false;phase="IDLE";rate=0;updated=System.nanoTime();}
    static synchronized JSONObject snapshot(String owner) throws Exception {
        if(!scope.equals(owner))return new JSONObject().put("running",false);
        double speed=running&&phase.equals("UPLOADING")&&System.nanoTime()-updated<8000000000L?rate:0;
        return new JSONObject().put("running",running).put("phase",phase).put("name",name).put("task_id",Long.toString(task)).put("processed",Long.toString(done)).put("size",Long.toString(total)).put("confirmed",Long.toString(confirmed))
            .put("bytes_per_second",Math.max(0,Math.round(speed))).put("eta_seconds",speed>0&&total>=done?Math.max(0,Math.round((total-done)/speed)):-1);
    }
}
