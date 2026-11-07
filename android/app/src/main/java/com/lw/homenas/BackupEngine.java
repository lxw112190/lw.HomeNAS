package com.lw.homenas;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.util.concurrent.locks.ReentrantLock;

/** One coordinator, bounded parallel file senders, durable server-confirmed offsets. */
final class BackupEngine {
    static final ReentrantLock RUNNING=new ReentrantLock();
    private static volatile RunControl active;
    static void stopActive(){RunControl control=active;if(control!=null)control.cancel();}
    private final Context context;
    private final Settings settings;
    private final TaskStore tasks;
    private long nextNetworkCheck;
    interface Progress { void update(String message); }
    BackupEngine(Context context){this.context=context.getApplicationContext();settings=new Settings(context);tasks=TaskStore.get(context);}
    void run(RunControl control,Progress progress) throws Exception {
        Progress observer=progress;progress=message->{synchronized(observer){observer.update(message);}};
        Diagnostics.event("backup.begin",Diagnostics.network(context));
        if(!RUNNING.tryLock(10,java.util.concurrent.TimeUnit.SECONDS)){Diagnostics.event("backup.busy","another sender owns the queue");return;}
        active=control;
        Settings.Session session=null;
        try {
            session=settings.session();if(session==null||!session.canUpload||settings.paused()){Diagnostics.event("backup.skipped","logged_in="+(session!=null)+" paused="+settings.paused());return;}
            TransferProgress.begin(session.scope);check(session,control);NativeApi api=new NativeApi(session);
            JSONObject server=api.get("server",control);if(!server.optBoolean("backup_folders"))throw new IOException("请先升级电脑服务至0.5，确保备份目录正确");
            if(!session.serverId.equals(server.getString("server_id")))throw new IOException("服务器身份已改变，请核对后重新登录");
            settings.ensureFolder(session,api,control);
            JSONObject overview=api.get("overview",control);long advertised=Long.parseLong(overview.getString("chunk_bytes"));
            int block=(int)Math.min(8*1024*1024L,advertised);if(block<1)throw new IOException("服务器块大小无效");
            int parallel=Math.max(1,Math.min(3,settings.prefs.getInt("upload_parallel",2)));
            Diagnostics.event("backup.parallel","workers="+parallel);
            drain(session,block,parallel,control,progress);
            check(session,control);progress.update("正在扫描已选相册");
            long added=MediaCatalog.scan(context,session,control);Diagnostics.event("scan.done","new_tasks="+added);
            drain(session,block,parallel,control,progress);
            progress.update("本轮完成；仅“已备份”表示服务器完整校验并入库");
        }catch(NativeApi.Failure error){
            if(error.status==401&&session!=null){if(settings.invalidate(session)){BackupScheduler.cancel(context);settings.message("登录已过期或账号被调整，请重新登录；队列保留");}}
            else settings.message(error.getMessage());throw error;
        }catch(InterruptedIOException error){Diagnostics.event("backup.paused","confirmed progress retained");throw error;}catch(Exception error){Diagnostics.error("backup.failed",error);throw error;}finally{TransferProgress.end();active=null;RUNNING.unlock();Diagnostics.event("backup.end","");}
    }
    private void drain(Settings.Session session,int block,int parallel,RunControl root,Progress progress) throws Exception {
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(parallel);
        java.util.concurrent.CompletionService<Void> completion=new java.util.concurrent.ExecutorCompletionService<>(pool);
        Exception failure=null;boolean interrupted=false;
        try {
            for(int i=0;i<parallel;i++){
                RunControl control=root.fork();
                completion.submit(()->{
                    NativeApi api=new NativeApi(session);TaskStore.Task task;
                    while(true){
                        check(session,control);task=tasks.claim(session.scope);if(task==null)return null;
                        try{
                            Diagnostics.event("task.start","task="+task.id+" state="+task.state+" size="+task.size+" confirmed="+task.offset+" folder="+(Protocol.folderId(task.folderId)?task.folderId:"invalid"));
                            try{send(task,session,api,block,control,progress);}
                            catch(InterruptedIOException error){throw error;}
                            catch(SecurityException|java.io.FileNotFoundException error){Diagnostics.error("task.source task="+task.id,error);tasks.state(task.id,"SOURCE_MISSING","文件不存在或读取授权失效，请重新选择/授权");}
                            catch(NativeApi.Failure error){
                                if(error.status==401||error.retryable())throw error;
                                tasks.state(task.id,"ERROR",error.getMessage());
                                if(error.status==403){settings.pause(true);throw new InterruptedIOException("目标目录无写入权限，已暂停");}
                            }
                            TransferProgress.finished(task.id,tasks.ready(task.id));
                        } finally {TransferProgress.remove(task.id);tasks.release(task.id);}
                    }
                });
            }
            for(int i=0;i<parallel;i++)completion.take().get();
        }catch(java.util.concurrent.ExecutionException error){failure=error.getCause() instanceof Exception?(Exception)error.getCause():new IOException("上传工作线程异常",error.getCause());root.cancel();}
        catch(InterruptedException error){interrupted=true;failure=new InterruptedIOException("备份已中断，确认进度保留");root.cancel();}
        catch(Exception error){failure=error;root.cancel();}
        finally{
            pool.shutdownNow();
            // Never release the coordinator lock while an old sender can still write.
            boolean stopped=false;while(!stopped)try{stopped=pool.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){interrupted=true;root.cancel();}
            if(interrupted)Thread.currentThread().interrupt();
        }
        if(failure!=null)throw failure;
    }
    private synchronized void check(Settings.Session session,RunControl control) throws Exception {
        control.check();if(settings.paused()||!settings.current(session))throw new InterruptedIOException("已暂停或登录身份已改变");
        long now=System.nanoTime();if(now<nextNetworkCheck)return;nextNetworkCheck=now+1000000000L;
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities=manager.getNetworkCapabilities(manager.getActiveNetwork());
        if(capabilities==null)throw new IOException("等待网络连接");
        if(settings.prefs.getBoolean("wifi",true)&&!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))throw new IOException("仅在非计费网络备份，等待Wi-Fi");
    }
    private static boolean changed(MediaCatalog.Info expected,MediaCatalog.Info actual) {
        return (expected.size>=0&&actual.size>=0&&expected.size!=actual.size)||(expected.modified>0&&actual.modified>0&&expected.modified!=actual.modified);
    }
    private void confirm(TaskStore.Task task,JSONObject remote,String hash,long size) throws Exception {
        String state=remote.getString("state");long offset=Protocol.offset(remote.getString("offset"),size);
        if(!task.folderId.equals(remote.optString("folder_id"))||!Protocol.folderId(remote.optString("effective_folder_id"))||!(remote.optBoolean("folder_defaulted")?remote.optString("effective_folder_id").equals("default"):remote.optString("effective_folder_id").equals(task.folderId)))throw new IOException("服务器返回的备份目录不一致，未确认成功");
        if(!Protocol.assetId(remote.getString("id"))||!hash.equals(remote.getString("sha256"))
            ||Long.parseLong(remote.getString("size"))!=size||(state.equals("READY")&&(offset!=size||!Protocol.assetId(remote.optString("asset_id"))))
            ||!java.util.Arrays.asList("UPLOADING","VERIFYING","COMMITTING","READY","ERROR","CANCELLED").contains(state))
            throw new IOException("服务器任务摘要、长度或状态不一致，未确认备份成功");
        if(remote.optBoolean("folder_defaulted")){settings.fallbackFolder(task.scope,task.folderId);settings.message("目标文件夹已删除，本任务保存到默认文件夹；后续备份改用默认文件夹");}
        if(tasks.confirmed(task.id,remote))Diagnostics.event("task.confirmed","task="+task.id+" state="+state+" offset="+offset+" size="+size);
    }
    private void send(TaskStore.Task task,Settings.Session session,NativeApi api,int block,RunControl control,Progress progress) throws Exception {
        TransferProgress.phase(task,"PREPARING",task.offset,task.size);
        Uri uri=Uri.parse(task.uri);MediaCatalog.Info before=MediaCatalog.describe(context.getContentResolver(),uri);
        if((task.size>=0&&before.size>=0&&task.size!=before.size)||(task.modified>0&&before.modified>0&&task.modified!=before.modified)) {
            tasks.state(task.id,"ERROR","源文件已改变，请重新扫描或选择文件");return;
        }
        String hash=task.sha;long size=task.size;
        if(hash.isEmpty()) {
            long hashStarted=System.nanoTime();Diagnostics.event("hash.start","task="+task.id+" size="+size);
            tasks.state(task.id,"HASHING","");TransferProgress.phase(task,"HASHING",0,before.size);progress.update("计算文件摘要："+task.name);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long length=0,lastHashProgress=0;byte[] buffer=new byte[256*1024];
            try(InputStream input=context.getContentResolver().openInputStream(uri)) {
                if(input==null)throw new java.io.FileNotFoundException();int n;
                while((n=input.read(buffer))!=-1){check(session,control);digest.update(buffer,0,n);length=Math.addExact(length,n);
                    long now=android.os.SystemClock.elapsedRealtime();if(now-lastHashProgress>=1000){lastHashProgress=now;TransferProgress.hashed(task.id,length,before.size);progress.update("校验文件："+task.name+" · "+(before.size>0?Math.min(100,Math.round(100.0*length/before.size))+"%":"已读取 "+(length/(1024*1024))+" MiB"));}}
            }
            if((before.size>=0&&before.size!=length)||changed(before,MediaCatalog.describe(context.getContentResolver(),uri))) {
                tasks.state(task.id,"ERROR","计算摘要时源文件发生变化，未上传");return;
            }
            size=length;hash=Protocol.hex(digest.digest());tasks.hashed(task.id,size,hash);
            Diagnostics.event("hash.done","task="+task.id+" bytes="+size+" ms="+((System.nanoTime()-hashStarted)/1000000));
        }
        check(session,control);
        JSONObject creation=new JSONObject().put("client_task_id",task.client).put("name",task.name).put("size",Long.toString(size)).put("sha256",hash)
            .put("folder_id",task.folderId).put("source_key","android:"+Protocol.key(task.uri+":"+task.modified+":"+size)).put("album",task.album).put("modified",Long.toString(Math.max(0,task.modified)));
        JSONObject remote=api.post("uploads",creation,control);confirm(task,remote,hash,size);
        if(remote.getString("state").equals("READY"))return;
        String id=remote.getString("id");long offset=Protocol.offset(remote.getString("offset"),size);
        if(remote.getString("state").equals("UPLOADING")) {
            TransferProgress.phase(task,"UPLOADING",offset,size);
            byte[] bytes=new byte[block];
            try(InputStream input=openAt(uri,offset)) {
                while(offset<size) {
                    check(session,control);int wanted=(int)Math.min(bytes.length,size-offset),count=0;
                    while(count<wanted){int n=input.read(bytes,count,wanted-count);if(n<0){tasks.state(task.id,"ERROR","源文件提前结束，请重新扫描或选择文件");return;}count+=n;check(session,control);}
                    // If acknowledgement is lost, leave the old local offset and query the server next run.
                    long sentAt=System.nanoTime();remote=api.chunk(id,offset,bytes,count,control);long confirmed=Protocol.offset(remote.getString("offset"),size);
                    if(confirmed!=offset+count)throw new IOException("服务器确认长度与发送块不一致");
                    confirm(task,remote,hash,size);offset=confirmed;TransferProgress.acknowledged(task.id,offset,size,count,System.nanoTime()-sentAt);
                    progress.update("正在上传："+task.name+" · "+(size==0?100:Math.round(100.0*offset/size))+"%");
                }
            }
            if(changed(before,MediaCatalog.describe(context.getContentResolver(),uri))){tasks.state(task.id,"ERROR","上传期间源文件改变，未提交");return;}
            check(session,control);remote=api.post("uploads/"+id+"/complete",new JSONObject(),control);confirm(task,remote,hash,size);
        }
        int verifyDelay=600;while(remote.optString("state").equals("VERIFYING")||remote.optString("state").equals("COMMITTING")) {
            TransferProgress.phase(task,remote.optString("state"),size,size);check(session,control);progress.update("电脑完整校验中："+task.name);Thread.sleep(verifyDelay);verifyDelay=Math.min(2000,verifyDelay+300);
            check(session,control);remote=api.get("uploads/"+id,control);confirm(task,remote,hash,size);
        }
        if(!remote.optString("state").equals("READY"))tasks.state(task.id,"ERROR","电脑未确认入库："+remote.optString("error",remote.optString("state")));
    }
    private InputStream openAt(Uri uri,long offset) throws Exception {
        InputStream stream=context.getContentResolver().openInputStream(uri);if(stream==null)throw new java.io.FileNotFoundException();
        try {
            if(stream instanceof FileInputStream) {
                try{java.nio.channels.FileChannel channel=((FileInputStream)stream).getChannel();channel.position(Math.addExact(channel.position(),offset));return stream;}catch(IOException ignored){stream.close();stream=context.getContentResolver().openInputStream(uri);if(stream==null)throw new java.io.FileNotFoundException();}
            }
            Protocol.skip(stream,offset);return stream;
        }catch(Exception error){stream.close();throw error;}
    }
}
