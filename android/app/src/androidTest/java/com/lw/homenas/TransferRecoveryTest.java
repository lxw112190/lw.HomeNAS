package com.lw.homenas;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Exercises the actual ContentResolver -> hash -> native HTTP -> durable queue flow in a disposable emulator. */
@RunWith(AndroidJUnit4.class)
public class TransferRecoveryTest {
    private static String repeated(char value,int count){char[] data=new char[count];Arrays.fill(data,value);return new String(data);}
    @Before public void isolatedEmulatorOnly(){org.junit.Assume.assumeTrue("Use an isolated CI emulator, not a personal phone",android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"));new Settings(InstrumentationRegistry.getInstrumentation().getTargetContext()).prefs.edit().clear().commit();}
    @Test public void lostAcknowledgementResumesUsingServerOffsetWithoutDuplicatingBytes() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();Settings settings=new Settings(context);
        byte[] payload=new byte[3*65536+17];new java.util.Random(20261104).nextBytes(payload);
        String digest=Protocol.hash(payload,payload.length),serverId=repeated('a',48),taskId=repeated('b',48),token=repeated('c',64);
        ByteArrayOutputStream received=new ByteArrayOutputStream();AtomicBoolean disconnected=new AtomicBoolean();String[] remoteState={"UPLOADING"};
        JSONObject me=new JSONObject().put("user_id","test-member").put("username","test-member").put("role","member").put("can_upload",true);
        try(MockWebServer server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){
                private MockResponse response(JSONObject object){return new MockResponse().setHeader("Content-Type","application/json").setBody(object.toString());}
                private JSONObject task() throws Exception {return new JSONObject().put("id",taskId).put("state",remoteState[0]).put("asset_id",remoteState[0].equals("READY")?repeated('d',48):"").put("sha256",digest).put("size",Integer.toString(payload.length)).put("offset",Integer.toString(received.size())).put("error","");}
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        String path=request.getPath();
                        if(path.equals("/api/v1/server"))return response(new JSONObject().put("server_id",serverId));
                        if(path.equals("/api/v1/login")){JSONObject input=new JSONObject(request.getBody().readUtf8());assertEquals("native",input.getString("client"));return response(new JSONObject(me.toString()).put("token",token));}
                        if(!("Bearer "+token).equals(request.getHeader("Authorization")))return new MockResponse().setResponseCode(401).setBody("{}");
                        if(path.equals("/api/v1/overview"))return response(new JSONObject().put("chunk_bytes","65536"));
                        if(path.equals("/api/v1/uploads")) {JSONObject input=new JSONObject(request.getBody().readUtf8());assertEquals(digest,input.getString("sha256"));assertEquals(payload.length,Long.parseLong(input.getString("size")));return response(task());}
                        if(path.endsWith("/data")) {
                            if(Long.parseLong(request.getHeader("X-Upload-Offset"))!=received.size())return new MockResponse().setResponseCode(409).setBody("{}");
                            byte[] bytes=request.getBody().readByteArray();assertEquals(Protocol.hash(bytes,bytes.length),request.getHeader("X-Chunk-SHA256"));received.write(bytes);
                            if(received.size()==2*65536&&disconnected.compareAndSet(false,true))return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                            return response(task());
                        }
                        if(path.endsWith("/complete")){assertArrayEquals(payload,received.toByteArray());remoteState[0]="READY";return response(task());}
                        if(path.equals("/api/v1/uploads/"+taskId))return response(task());
                        return new MockResponse().setResponseCode(404).setBody("{}");
                    }catch(Exception error){return new MockResponse().setResponseCode(500).setBody("{}");}
                }
            });server.start();String endpoint=server.url("/").toString();
            JSONObject login=new NativeApi(endpoint,"",true,"").post("login",new JSONObject().put("username","test-member").put("password","test-only-password").put("client","native"),new RunControl(10000));
            settings.save(Protocol.endpoint(endpoint,true),"",true,serverId,me,login.getString("token"));settings.prefs.edit().putBoolean("wifi",false).remove("albums").commit();settings.pause(false);
            Uri source=null;String scope=settings.session().scope;
            try {
                ContentValues info=new ContentValues();info.put(MediaStore.MediaColumns.DISPLAY_NAME,"HomeNAS-CI-recovery.bin");info.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");info.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/lw.HomeNAS-CI");info.put(MediaStore.MediaColumns.IS_PENDING,1);
                source=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,info);assertNotNull(source);
                try(OutputStream output=context.getContentResolver().openOutputStream(source)){assertNotNull(output);output.write(payload);}
                ContentValues published=new ContentValues();published.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(source,published,null,null);
                MediaCatalog.Info metadata=MediaCatalog.describe(context.getContentResolver(),source);TaskStore store=TaskStore.get(context);
                store.enqueue(scope,"ci-recovery-source",source.toString(),metadata.name,"CI test",metadata.size,metadata.modified);
                try {new BackupEngine(context).run(new RunControl(60000),message->{});fail("Lost acknowledgement must interrupt the first attempt");}catch(IOException expected){}
                assertTrue(disconnected.get());assertEquals(2*65536,received.size());assertEquals(65536,store.next(scope).offset);
                new BackupEngine(context).run(new RunControl(60000),message->{});
                assertArrayEquals(payload,received.toByteArray());assertNull(store.next(scope));assertEquals(1,store.summary(scope).getJSONObject("counts").getInt("READY"));
            }finally {
                if(source!=null)context.getContentResolver().delete(source,null,null);
                TaskStore.get(context).getWritableDatabase().delete("tasks","scope=?",new String[]{scope});settings.clear();
            }
        }finally{settings.clear();}
    }
}
