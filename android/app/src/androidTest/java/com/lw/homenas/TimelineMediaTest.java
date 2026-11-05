package com.lw.homenas;

import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TimelineMediaTest {
    private static String repeat(char value,int length){char[] data=new char[length];Arrays.fill(data,value);return new String(data);}
    @Before public void isolatedEmulatorOnly(){Assume.assumeTrue(android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"));}
    @Test public void dailySelectionSkipsOnlyServerConfirmedReadyAndDoesNotLeakUris() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();Settings settings=new Settings(context);settings.clear();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.READ_MEDIA_IMAGES);
        List<Uri> created=new ArrayList<>();String remoteId=repeat('a',48);boolean[] available={true},inFolder={true};TaskStore tasks=TaskStore.get(context);String[] scope={""};
        try(MockWebServer server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){try {
                if(request.getPath().equals("/api/v1/sources/status"))return new MockResponse().setHeader("Content-Type","application/json").setBody(new JSONObject().put("items",new JSONArray().put(new JSONObject().put("id",remoteId).put("state",available[0]?"READY":"MISSING").put("size",recordSize).put("sha256",recordHash).put("asset_id",repeat('d',48)).put("in_folder",available[0]&&inFolder[0]))).toString());
                return new MockResponse().setResponseCode(404);
            }catch(Exception error){return new MockResponse().setResponseCode(500);}}});server.start();
            JSONObject me=new JSONObject().put("user_id","picker-user").put("username","picker").put("role","member").put("can_upload",true);settings.save(Protocol.endpoint(server.url("/").toString(),true),"",true,repeat('b',48),me,repeat('c',64));Settings.Session session=settings.session();scope[0]=session.scope;
            settings.folder(session.scope,"default","默认文件夹");
            for(int i=0;i<4;i++) {
                ContentValues row=new ContentValues();row.put(MediaStore.Images.Media.DISPLAY_NAME,"HomeNAS-CI-picker-"+UUID.randomUUID()+".jpg");row.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");row.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/HomeNAS-CI");row.put(MediaStore.Images.Media.IS_PENDING,1);
                Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,row);assertNotNull(uri);created.add(uri);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff07c160+i);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,bytes);bitmap.recycle();byte[] jpeg=bytes.toByteArray();try(OutputStream out=context.getContentResolver().openOutputStream(uri)){out.write(jpeg);}
                row.clear();row.put(MediaStore.Images.Media.IS_PENDING,0);context.getContentResolver().update(uri,row,null,null);
                if(i==0){MediaCatalog.Info info=MediaCatalog.describe(context.getContentResolver(),uri);recordSize=Long.toString(info.size);recordHash=Protocol.hash(jpeg,jpeg.length);long id=tasks.enqueue(session.scope,Protocol.key(uri+":"+info.size+":"+info.modified),uri.toString(),info.name,"CI",info.size,info.modified);tasks.hashed(id,info.size,recordHash);tasks.confirmed(id,new JSONObject().put("id",remoteId).put("offset",recordSize).put("state","READY"));}
            }
            MediaPicker picker=new MediaPicker(context,settings);JSONObject dates=picker.days(session,"",true,new RunControl(30000));assertTrue(dates.getJSONArray("items").length()>0);
            long modified=MediaCatalog.describe(context.getContentResolver(),created.get(0)).modified;String day=modified>0?new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(modified)):"unknown";
            JSONObject page=picker.items(session,day,"",new NativeApi(session),new RunControl(30000));assertFalse(page.toString().contains("content://"));boolean ready=false;for(int i=0;i<page.getJSONArray("items").length();i++)if(page.getJSONArray("items").getJSONObject(i).getString("status").equals("READY"))ready=true;assertTrue("Matching local record must be verified with server",ready);
            inFolder[0]=false;page=picker.items(session,day,"",new NativeApi(session),new RunControl(30000));boolean backedUp=false;for(int i=0;i<page.getJSONArray("items").length();i++){String status=page.getJSONArray("items").getJSONObject(i).getString("status");assertNotEquals("READY",status);if(status.equals("BACKED_UP"))backedUp=true;}assertTrue("Existing original outside the selected folder remains selectable",backedUp);
            available[0]=false;page=picker.items(session,day,"",new NativeApi(session),new RunControl(30000));for(int i=0;i<page.getJSONArray("items").length();i++)assertNotEquals("READY",page.getJSONArray("items").getJSONObject(i).getString("status"));
            available[0]=true;inFolder[0]=true;JSONObject result=picker.enqueue(session,new JSONObject().put("days",new JSONArray().put(day)).put("ids",new JSONArray()).put("folder_id","default"),new NativeApi(session),new RunControl(30000));assertTrue(result.getInt("skipped")>=1);assertTrue(result.getInt("added")>=3);
        }finally{for(Uri uri:created)context.getContentResolver().delete(uri,null,null);if(!scope[0].isEmpty())tasks.getWritableDatabase().delete("tasks","scope=?",new String[]{scope[0]});settings.clear();}
    }
    private volatile String recordSize="",recordHash="";
    @Test public void previewPreservesRangeAndStopsAfterLogout() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();Settings settings=new Settings(context);settings.clear();
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Type","video/mp4").setHeader("Content-Range","bytes 2-5/8").setHeader("Accept-Ranges","bytes").setBody("cdef"));server.start();JSONObject me=new JSONObject().put("user_id","preview-user").put("username","preview").put("role","viewer");settings.save(Protocol.endpoint(server.url("/").toString(),true),"",true,repeat('b',48),me,repeat('c',64));Settings.Session session=settings.session();String id=repeat('d',48);
            WebResourceRequest request=new WebResourceRequest(){public Uri getUrl(){return Uri.parse("https://appassets.androidplatform.net/media/"+id);}public boolean isForMainFrame(){return false;}public boolean isRedirect(){return false;}public boolean hasGesture(){return false;}public String getMethod(){return "GET";}public Map<String,String> getRequestHeaders(){return Collections.singletonMap("Range","bytes=2-5");}};
            PreviewStreams preview=new PreviewStreams(settings);WebResourceResponse response=preview.open(request,session,new NativeApi(session),new JSONObject().put("id",id).put("mime","video/mp4"));assertEquals(206,response.getStatusCode());assertEquals("bytes 2-5/8",response.getResponseHeaders().get("Content-Range"));assertEquals("bytes=2-5",server.takeRequest().getHeader("Range"));assertEquals('c',response.getData().read());settings.clear();try{response.getData().read();fail("Preview continued after logout");}catch(IOException expected){}preview.closeAll();
        }finally{settings.clear();}
    }
}
