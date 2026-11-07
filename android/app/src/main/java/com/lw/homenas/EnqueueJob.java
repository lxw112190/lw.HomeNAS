package com.lw.homenas;

import org.json.JSONObject;

/** One bounded, cancellable picker scan per Activity; queued batches survive cancellation. */
final class EnqueueJob {
    private RunControl control;
    private String scope="",state="IDLE",error="",folderId="default",folderName="默认文件夹";
    private long scanned,added,skipped,bytes;
    synchronized JSONObject start(Settings settings,Settings.Session session,MediaPicker picker,NativeApi api,JSONObject input) throws Exception {
        if(state.equals("RUNNING"))throw new java.io.IOException("正在加入队列，请等待或取消");
        JSONObject frozen=new JSONObject(input.toString());
        String destination=settings.folder(session.scope);
        if(!frozen.optString("folder_id",destination).equals(destination))throw new java.io.IOException("目标文件夹已改变，请刷新照片后重选");
        frozen.put("folder_id",destination);folderId=destination;folderName=settings.folderName(session.scope);
        control=new RunControl(30*60*1000L);RunControl run=control;scope=session.scope;state="RUNNING";error="";scanned=added=skipped=bytes=0;
        Thread worker=new Thread(()->{
            try{
                JSONObject result=picker.enqueue(session,frozen,api,run,(count,a,s,b)->{synchronized(this){scanned=count;added=a;skipped=s;bytes=b;}});
                synchronized(this){added=result.optLong("added");skipped=result.optLong("skipped");state="DONE";}
            }catch(Exception failure){synchronized(this){state=settings.current(session)?"STOPPED":"EXPIRED";error=MainActivity.safeError(failure);}Diagnostics.error("picker.batch",failure);}
            finally{synchronized(this){if(control==run)Diagnostics.event("picker.batch_end","state="+state+" scanned="+scanned+" added="+added+" skipped="+skipped);}}
        },"nas-picker-enqueue");worker.start();return snapshot(session.scope);
    }
    synchronized void cancel(){if(control!=null)control.cancel();}
    synchronized JSONObject snapshot(String owner) throws Exception {
        if(!scope.equals(owner))return new JSONObject().put("state","IDLE");
        return new JSONObject().put("state",state).put("scanned",scanned).put("added",added).put("skipped",skipped).put("bytes",Long.toString(bytes)).put("error",error).put("folder_id",folderId).put("folder_name",folderName);
    }
}
