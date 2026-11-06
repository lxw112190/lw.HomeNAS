package com.lw.homenas;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class ClientMaintenanceTest {
    private android.content.Context context;
    private android.content.SharedPreferences prefs;
    @Before public void resetPreferences(){
        context=RuntimeEnvironment.getApplication();prefs=context.getSharedPreferences("client-maintenance",0);prefs.edit().clear().commit();
    }
    private File ui(String name) throws Exception {
        File folder=new File(context.getFilesDir(),"ui-bundles/"+name);assertTrue(folder.isDirectory()||folder.mkdirs());
        java.nio.file.Files.write(new File(folder,"index.html").toPath(),"test".getBytes(java.nio.charset.StandardCharsets.UTF_8));return folder;
    }
    @Test public void failedUiLaunchReturnsToPreviousVersion() throws Exception {
        ClientMaintenance manager=new ClientMaintenance(context);
        // First launch migrates the native protocol before a compatible UI is staged.
        assertNull(manager.beginLaunch());manager.ready();ui("r7");ui("r8");
        prefs.edit().putString("active","r7").putString("staged","r8").commit();
        File staged=manager.beginLaunch();assertNotNull(staged);assertEquals("r8",staged.getName());assertTrue(prefs.getBoolean("unconfirmed",false));
        File recovered=new ClientMaintenance(context).beginLaunch();assertNotNull(recovered);assertEquals("r7",recovered.getName());
        manager.ready();assertFalse(prefs.getBoolean("unconfirmed",true));
    }
    @Test public void nativeUpgradeUsesBuiltinUiAndPreservesOtherState() throws Exception {
        File old=ui("r6"),staged=ui("r7");
        prefs.edit().putInt("builtinNative",10).putString("active","r6").putString("previous","r5").putString("staged","r7").putBoolean("unconfirmed",true).putString("unrelated-setting","retained").commit();
        ClientMaintenance manager=new ClientMaintenance(context);assertNull(manager.beginLaunch());assertEquals("builtin",manager.version());
        for(String key:new String[]{"active","previous","staged"})assertFalse(prefs.contains(key));
        assertFalse(prefs.getBoolean("unconfirmed",true));assertEquals("retained",prefs.getString("unrelated-setting",""));
        assertTrue(new File(old,"index.html").isFile());assertTrue(new File(staged,"index.html").isFile());
        // Migration runs once; a compatible bundle can be used on later launches.
        prefs.edit().putString("active","r7").commit();File next=manager.beginLaunch();assertNotNull(next);assertEquals("r7",next.getName());
    }
    @Test public void confirmedUiSurvivesAnotherLaunch() throws Exception {
        ClientMaintenance manager=new ClientMaintenance(context);assertNull(manager.beginLaunch());ui("r7");
        prefs.edit().putString("staged","r7").commit();File staged=manager.beginLaunch();assertNotNull(staged);assertEquals("r7",staged.getName());
        manager.ready();File next=new ClientMaintenance(context).beginLaunch();assertNotNull(next);assertEquals("r7",next.getName());
    }
    @Test public void missingActiveUiFallsBackToBuiltin() throws Exception {
        ClientMaintenance manager=new ClientMaintenance(context);assertNull(manager.beginLaunch());
        prefs.edit().putString("active","r999999-missing").commit();assertNull(manager.beginLaunch());assertEquals("builtin",manager.version());assertFalse(prefs.getBoolean("unconfirmed",true));
    }
}
