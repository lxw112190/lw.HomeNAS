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

/** One native sender, bounded blocks; only server-confirmed offsets are committed locally. */
final class BackupEngine {
    static final ReentrantLock RUNNING=new ReentrantLock();
    private static volatile RunControl active;
    static void stopActive(){RunControl control=active;if(control!=null)control.cancel();}
    private final Context context;
    private final Settings settings;
    private final TaskStore tasks;
    interface Progress { void update(String message); }
    BackupEngine(Context context){this.context=context.getApplicationContext();settings=new Settings(context);tasks=TaskStore.get(context);}
    void run(RunControl control,Progress progress) throws Exception {
        if(!RUNNING.tryLock(10,java.util.concurrent.TimeUnit.SECONDS))return;
        active=control;
        Settings.Session session=null;
        try {
            session=settings.session();if(session==null||!session.canUpload||settings.paused())return;
            check(session,control);NativeApi api=new NativeApi(session);
            if(!session.serverId.equals(api.get("server",control).getString("server_id")))throw new IOException("服务器身份已改变，请核对后重新登录");
            JSONObject overview=api.get("overview",control);long advertised=Long.parseLong(overview.getString("chunk_bytes"));
            int block=(int)Math.min(8*1024*1024L,advertised);if(block<1)throw new IOException("服务器块大小无效");
            progress.update("正在扫描已选择的相册");MediaCatalog.scan(context,session,control);
            TaskStore.Task task;
            while((task=tasks.next(session.scope))!=null) {
                check(session,control);progress.update("正在备份："+task.name);
                try { send(task,session,api,block,control,progress); }
                catch(InterruptedIOException error){throw error;}
                catch(SecurityException|java.io.FileNotFoundException error){tasks.state(task.id,"SOURCE_MISSING","文件不存在或读取授权失效，请重新选择/授权");}
                catch(NativeApi.Failure error){
                    if(error.status==401)throw error;
                    if(error.retryable())throw error;
                    tasks.state(task.id,"ERROR",error.getMessage());
                }
            }
            progress.update("本轮完成；仅“已备份”表示服务器完整校验并入库");
        }catch(NativeApi.Failure error){
            if(error.status==401&&session!=null){if(settings.invalidate(session)){BackupScheduler.cancel(context);settings.message("登录已过期或账号被调整，请重新登录；队列保留");}}
            else settings.message(error.getMessage());throw error;
        }finally{active=null;RUNNING.unlock();}
    }
    private void check(Settings.Session session,RunControl control) throws Exception {
        control.check();if(settings.paused()||!settings.current(session))throw new InterruptedIOException("已暂停或登录身份已改变");
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities=manager.getNetworkCapabilities(manager.getActiveNetwork());
        if(capabilities==null)throw new IOException("等待网络连接");
        if(settings.prefs.getBoolean("wifi",true)&&!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))throw new IOException("仅在非计费网络备份，等待Wi-Fi");
    }
    private static boolean changed(MediaCatalog.Info expected,MediaCatalog.Info actual) {
        return (expected.size>=0&&actual.size>=0&&expected.size!=actual.size)||(expected.modified>0&&actual.modified>0&&expected.modified!=actual.modified);
    }
    private void confirm(long localId,JSONObject remote,String hash,long size) throws Exception {
        String state=remote.getString("state");long offset=Protocol.offset(remote.getString("offset"),size);
        if(!Protocol.assetId(remote.getString("id"))||!hash.equals(remote.getString("sha256"))
            ||Long.parseLong(remote.getString("size"))!=size||(state.equals("READY")&&(offset!=size||!Protocol.assetId(remote.optString("asset_id"))))
            ||!java.util.Arrays.asList("UPLOADING","VERIFYING","COMMITTING","READY","ERROR","CANCELLED").contains(state))
            throw new IOException("服务器任务摘要、长度或状态不一致，未确认备份成功");
        tasks.confirmed(localId,remote);
    }
    private void send(TaskStore.Task task,Settings.Session session,NativeApi api,int block,RunControl control,Progress progress) throws Exception {
        Uri uri=Uri.parse(task.uri);MediaCatalog.Info before=MediaCatalog.describe(context.getContentResolver(),uri);
        if((task.size>=0&&before.size>=0&&task.size!=before.size)||(task.modified>0&&before.modified>0&&task.modified!=before.modified)) {
            tasks.state(task.id,"ERROR","源文件已改变，请重新扫描或选择文件");return;
        }
        String hash=task.sha;long size=task.size;
        if(hash.isEmpty()) {
            tasks.state(task.id,"HASHING","");progress.update("计算文件摘要："+task.name);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long length=0;byte[] buffer=new byte[256*1024];
            try(InputStream input=context.getContentResolver().openInputStream(uri)) {
                if(input==null)throw new java.io.FileNotFoundException();int n;
                while((n=input.read(buffer))!=-1){check(session,control);digest.update(buffer,0,n);length=Math.addExact(length,n);}
            }
            if((before.size>=0&&before.size!=length)||changed(before,MediaCatalog.describe(context.getContentResolver(),uri))) {
                tasks.state(task.id,"ERROR","计算摘要时源文件发生变化，未上传");return;
            }
            size=length;hash=Protocol.hex(digest.digest());tasks.hashed(task.id,size,hash);
        }
        JSONObject creation=new JSONObject().put("client_task_id",task.client).put("name",task.name).put("size",Long.toString(size)).put("sha256",hash)
            .put("source_key","android:"+Protocol.key(task.uri+":"+task.modified+":"+size)).put("album",task.album).put("modified",Long.toString(Math.max(0,task.modified)));
        JSONObject remote=api.post("uploads",creation,control);confirm(task.id,remote,hash,size);
        if(remote.getString("state").equals("READY"))return;
        String id=remote.getString("id");long offset=Protocol.offset(remote.getString("offset"),size);
        if(remote.getString("state").equals("UPLOADING")) {
            byte[] bytes=new byte[block];
            try(InputStream input=openAt(uri,offset)) {
                while(offset<size) {
                    check(session,control);int wanted=(int)Math.min(bytes.length,size-offset),count=0;
                    while(count<wanted){int n=input.read(bytes,count,wanted-count);if(n<0){tasks.state(task.id,"ERROR","源文件提前结束，请重新扫描或选择文件");return;}count+=n;check(session,control);}
                    // If acknowledgement is lost, leave the old local offset and query the server next run.
                    remote=api.chunk(id,offset,bytes,count,control);long confirmed=Protocol.offset(remote.getString("offset"),size);
                    if(confirmed!=offset+count)throw new IOException("服务器确认长度与发送块不一致");
                    confirm(task.id,remote,hash,size);offset=confirmed;
                    progress.update("正在上传："+task.name+" · "+(size==0?100:Math.round(100.0*offset/size))+"%");
                }
            }
            if(changed(before,MediaCatalog.describe(context.getContentResolver(),uri))){tasks.state(task.id,"ERROR","上传期间源文件改变，未提交");return;}
            remote=api.post("uploads/"+id+"/complete",new JSONObject(),control);confirm(task.id,remote,hash,size);
        }
        while(remote.optString("state").equals("VERIFYING")||remote.optString("state").equals("COMMITTING")) {
            check(session,control);progress.update("电脑完整校验中："+task.name);Thread.sleep(600);
            remote=api.get("uploads/"+id,control);confirm(task.id,remote,hash,size);
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
