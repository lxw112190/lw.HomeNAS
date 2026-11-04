package com.lw.homenas;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class ClientMaintenanceTest {
    @Test public void failedUiLaunchReturnsToPreviousVersion() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();android.content.SharedPreferences prefs=context.getSharedPreferences("client-maintenance",0);prefs.edit().clear().commit();
        for(String name:new String[]{"r1","r2"}){File folder=new File(context.getFilesDir(),"ui-bundles/"+name);folder.mkdirs();java.nio.file.Files.write(new File(folder,"index.html").toPath(),"test".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        ClientMaintenance manager=new ClientMaintenance(context);prefs.edit().putString("active","r1").putString("staged","r2").commit();
        assertEquals("r2",manager.beginLaunch().getName());assertTrue(prefs.getBoolean("unconfirmed",false));
        assertEquals("r1",new ClientMaintenance(context).beginLaunch().getName());manager.ready();assertFalse(prefs.getBoolean("unconfirmed",true));
        prefs.edit().clear().commit();assertNull(manager.beginLaunch());
    }
}
