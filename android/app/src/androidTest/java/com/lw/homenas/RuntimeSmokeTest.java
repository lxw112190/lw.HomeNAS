package com.lw.homenas;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RuntimeSmokeTest {
    @Before public void isolatedEmulatorOnly(){org.junit.Assume.assumeTrue("Use an isolated CI emulator, not a personal phone",android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"));new Settings(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext()).prefs.edit().clear().commit();}
    private static String repeated(char value,int count){char[] data=new char[count];java.util.Arrays.fill(data,value);return new String(data);}
    private WebView find(View view) {if(view instanceof WebView)return (WebView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){WebView result=find(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
    private String read(ActivityScenario<MainActivity> scenario,String expression) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>("");
        scenario.onActivity(activity->{WebView web=find(activity.getWindow().getDecorView());assertNotNull(web);web.evaluateJavascript(expression,result->{value.set(result);done.countDown();});});
        assertTrue(done.await(5,TimeUnit.SECONDS));return value.get();
    }
    @Test public void localPageNativeSnapshotAndSettingsAreUsable() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);String version="";
            while(System.nanoTime()<deadline){version=read(scenario,"document.getElementById('version')?.textContent || ''");if(version.contains(BuildConfig.VERSION_NAME))break;Thread.sleep(200);}
            assertTrue("Native snapshot did not reach local Web page: "+version,version.contains(BuildConfig.VERSION_NAME));
            assertTrue(read(scenario,"document.body.innerText").contains("我的家庭备份"));
            assertEquals("false",read(scenario,"document.getElementById('page-login').hidden"));
            assertEquals("true",read(scenario,"document.getElementById('bottom-nav').hidden"));
            read(scenario,"page('library')");
            assertEquals("true",read(scenario,"document.getElementById('page-library').hidden"));
            read(scenario,"document.getElementById('login-settings').click(); document.getElementById('page-settings').hidden");
            assertEquals("false",read(scenario,"document.getElementById('page-settings').hidden"));
            assertEquals("\"http\"",read(scenario,"document.getElementById('protocol').value"));
            assertEquals("\"192.168.1.100\"",read(scenario,"document.getElementById('host').value"));
            assertEquals("\"8787\"",read(scenario,"document.getElementById('port').value"));
            assertEquals("false",read(scenario,"document.documentElement.scrollWidth > window.innerWidth"));
        }
    }
    @Test public void queuePagesAreBoundedStableAndAccountScoped() throws Exception {
        android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        TaskStore store=TaskStore.get(context);String scope="ci-page-"+java.util.UUID.randomUUID();
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
        try {
            db.beginTransaction();try {
                for(int i=0;i<501;i++)store.enqueue(scope,"source-"+i,"content://ci/"+i,"synthetic-"+i,"",1,0);
                store.enqueue(scope+"-other","other","content://ci/other","other","",1,0);
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
            String before="";java.util.Set<String> seen=new java.util.HashSet<>();int pages=0;
            do {
                org.json.JSONObject result=store.summary(scope,before,"all");org.json.JSONArray items=result.getJSONArray("items");assertTrue(items.length()<=50);
                for(int i=0;i<items.length();i++){assertTrue(items.getJSONObject(i).getString("name").startsWith("synthetic-"));assertTrue(seen.add(items.getJSONObject(i).getString("diagnostic_id")));}
                before=result.getString("next");assertTrue(++pages<20);
            } while(!before.isEmpty());
            assertEquals(501,seen.size());assertEquals(11,pages);
            assertEquals(0,store.summary(scope,"","ready").getJSONArray("items").length());
            try {store.summary(scope,"bad","all");fail("Invalid cursor accepted");}catch(IllegalArgumentException expected){}
        } finally {db.delete("tasks","scope IN (?,?)",new String[]{scope,scope+"-other"});}
    }
    @Test public void sessionSurvivesReloadWithoutPlaintextTokenOrPassword() throws Exception {
        android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();Settings settings=new Settings(context);
        String token=repeated('e',64);org.json.JSONObject me=new org.json.JSONObject().put("user_id","test-user").put("username","test-member").put("role","member").put("can_upload",true);
        try {
            settings.save("https://192.0.2.1:9443","",false,repeated('f',48),me,token);
            assertEquals(token,new Settings(context).session().token);
            assertFalse(settings.prefs.getString("token","").equals(token));
            assertFalse(settings.publicState().toString().contains(token));
            assertFalse(settings.prefs.contains("password"));
            settings.rememberConnection("http://192.0.2.2:8787",true);
            assertEquals("https://192.0.2.1:9443",settings.session().endpoint);
            assertEquals(token,settings.session().token);
            settings.clear();assertNull(settings.session());
        }finally{settings.clear();}
    }
    @Test public void diagnosticsExportContainsValidZipAndVersionWithoutDatabase() throws Exception {
        android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        Diagnostics.initialize(context);Diagnostics.event("instrumentation.logs","device export check");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();Diagnostics.export(context,bytes);
        boolean found=false;try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertTrue(entry.getName(),entry.getName().equals("diagnostics.json")||entry.getName().matches("runtime(?:\\.[1-4])?\\.log"));
            if(entry.getName().equals("diagnostics.json")){java.io.ByteArrayOutputStream text=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=zip.read(buffer))!=-1)text.write(buffer,0,n);org.json.JSONObject data=new org.json.JSONObject(new String(text.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));assertEquals(BuildConfig.VERSION_NAME,data.getString("version"));found=true;}
        }}assertTrue(found);
    }
}
