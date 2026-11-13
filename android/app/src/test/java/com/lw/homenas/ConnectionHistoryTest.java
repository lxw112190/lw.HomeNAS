package com.lw.homenas;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class ConnectionHistoryTest {
    private Settings settings;
    @Before public void reset(){settings=new Settings(RuntimeEnvironment.getApplication());settings.prefs.edit().clear().commit();}
    @After public void cleanup(){if(settings!=null)settings.prefs.edit().clear().commit();}
    @Test public void recentServersAreBoundedDeduplicatedAndNeverChangeTheAuthenticatedEndpoint() throws Exception {
        settings.prefs.edit().putString("endpoint","https://original.example:9443").putString("token","encrypted-sentinel").putString("iv","iv-sentinel").commit();
        for(int i=1;i<=6;i++)settings.rememberServer("http://192.168.1."+i+":8787","NAS");
        assertEquals(5,settings.recentServers().length());assertEquals("http://192.168.1.6:8787",settings.recentServers().getJSONObject(0).getString("endpoint"));
        settings.rememberServer("http://192.168.1.3:8787","Home");assertEquals(5,settings.recentServers().length());assertEquals("http://192.168.1.3:8787",settings.recentServers().getJSONObject(0).getString("endpoint"));
        settings.rememberConnection("http://192.168.1.100:8787",true);
        assertEquals("https://original.example:9443",settings.prefs.getString("endpoint",""));assertEquals("encrypted-sentinel",settings.prefs.getString("token",""));assertEquals("iv-sentinel",settings.prefs.getString("iv",""));
        String stored=settings.prefs.getString("recent_servers","");assertFalse(stored.contains("encrypted-sentinel"));assertFalse(stored.contains("password"));
    }
    @Test public void qrAddressRulesRejectCredentialsPathsAndOtherSchemes() {
        for(String value:new String[]{"javascript:alert(1)","file:///private","http://user:secret@host:8787","http://host:8787/path","http://host:8787/?token=secret"})
            assertThrows(IllegalArgumentException.class,()->Protocol.endpoint(value,true));
        assertEquals("http://192.168.1.10:8080",Protocol.endpoint("http://192.168.1.10:8080/",true));
    }
}
