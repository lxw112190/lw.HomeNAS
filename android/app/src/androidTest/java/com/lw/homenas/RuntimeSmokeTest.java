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
            while(System.nanoTime()<deadline){version=read(scenario,"document.getElementById('version')?.textContent || ''");if(version.contains("0.3.0-dev"))break;Thread.sleep(200);}
            assertTrue("Native snapshot did not reach local Web page: "+version,version.contains("0.3.0-dev"));
            assertTrue(read(scenario,"document.body.innerText").contains("我的家庭备份"));
            read(scenario,"document.getElementById('go-connect').click(); document.getElementById('page-settings').hidden");
            assertEquals("false",read(scenario,"document.getElementById('page-settings').hidden"));
            assertEquals("false",read(scenario,"document.getElementById('allow-http').checked"));
            assertEquals("false",read(scenario,"document.documentElement.scrollWidth > window.innerWidth"));
        }
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
            settings.clear();assertNull(settings.session());
        }finally{settings.clear();}
    }
}
