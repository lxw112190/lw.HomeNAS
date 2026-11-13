package com.lw.homenas;

import android.content.Context;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class BackupStatusTest {
    private Context context;
    @Before public void before(){context=RuntimeEnvironment.getApplication();clear();}
    @After public void after(){clear();}
    private void clear(){context.getSharedPreferences("backup-status",0).edit().clear().commit();context.getSharedPreferences("nas-settings",0).edit().clear().commit();}
    @Test public void historySurvivesNewInstanceAndDoesNotLeakAcrossAccounts() throws Exception {
        BackupStatus history=new BackupStatus(context);history.begin("account-a");history.scanned("account-a");history.finish("account-a","ATTENTION",3,1);
        JSONObject saved=new BackupStatus(context).snapshot("account-a",false,new Settings(context));
        assertEquals("ATTENTION",saved.getString("result"));assertEquals(3,saved.getLong("completed"));assertEquals(1,saved.getLong("failed"));assertTrue(saved.getLong("last_finished")>0);assertTrue(saved.getLong("last_scan")>0);
        assertFalse(history.snapshot("account-b",false,new Settings(context)).has("result"));assertFalse(history.snapshot("",false,new Settings(context)).has("result"));
    }
    @Test public void InterruptedRunIsNotReportedAsSuccessOrAsStillRunning() throws Exception {
        BackupStatus history=new BackupStatus(context);history.begin("account");
        assertEquals("RUNNING",history.snapshot("account",true,new Settings(context)).getString("result"));
        assertEquals("INTERRUPTED",new BackupStatus(context).snapshot("account",false,new Settings(context)).getString("result"));
        assertFalse(history.snapshot("account",false,new Settings(context)).has("last_finished"));
    }
    @Test public void policyExplainsManualPauseAndMissingAlbumsWithoutClaimingSchedulerIsRunning() throws Exception {
        Settings settings=new Settings(context);BackupStatus history=new BackupStatus(context);
        assertEquals("MANUAL",history.snapshot("account",false,settings).getString("wait_reason"));
        settings.prefs.edit().putBoolean("auto",true).putBoolean("paused",true).commit();assertEquals("PAUSED",history.snapshot("account",false,settings).getString("wait_reason"));
        settings.prefs.edit().putBoolean("paused",false).commit();assertEquals("NO_ALBUMS",history.snapshot("account",false,settings).getString("wait_reason"));
    }
    @Test public void invalidHistoryFallsBackWithoutClearingOtherPreferences() throws Exception {
        context.getSharedPreferences("backup-status",0).edit().putString("run_"+Protocol.key("account"),"{broken").putString("unrelated","retained").commit();
        BackupStatus history=new BackupStatus(context);assertFalse(history.snapshot("account",false,new Settings(context)).has("result"));
        history.begin("account");history.finish("account","PAUSED",0,0);assertEquals("PAUSED",history.snapshot("account",false,new Settings(context)).getString("result"));
        assertEquals("retained",context.getSharedPreferences("backup-status",0).getString("unrelated",""));
    }
}
