package com.lw.homenas;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.IOException;
import static org.junit.Assert.*;

@UnstableApi
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class NasMediaDataSourceTest {
    private static final String ID="0123456789abcdef0123456789abcdef0123456789abcdef";
    private Settings settings;private Settings.Session session;
    @Before public void setup() throws Exception {
        settings=new Settings(RuntimeEnvironment.getApplication());settings.prefs.edit().clear().putLong("revision",123).putString("token","fixture-only").commit();
        session=new Settings.Session("http://127.0.0.1:8787","","fixture-only","test-player",new JSONObject(),true,123);
    }
    private DataSpec spec(long position,long length){return new DataSpec.Builder().setUri(NasMediaDataSource.mediaUri(ID)).setPosition(position).setLength(length).setHttpRequestHeaders(java.util.Collections.singletonMap("Icy-MetaData","1")).build();}
    @Test public void nativePlayerSeeksWithAuthenticationAndStopsAfterSessionChange() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"native-test-token");
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-7/8").setBody("abcdefgh"));
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 2-5/8").setBody("cdef"));
            NasMediaDataSource source=new NasMediaDataSource(settings,session,api,ID,8);
            try{
                assertEquals(8,source.open(spec(0,C.LENGTH_UNSET)));byte[] data=new byte[4];assertEquals(4,source.read(data,0,4));assertArrayEquals(new byte[]{'a','b','c','d'},data);
                assertEquals(4,source.open(spec(2,4)));assertEquals(4,source.read(data,0,4));assertArrayEquals(new byte[]{'c','d','e','f'},data);assertEquals(C.RESULT_END_OF_INPUT,source.read(data,0,4));
                okhttp3.mockwebserver.RecordedRequest first=server.takeRequest();assertEquals("Bearer native-test-token",first.getHeader("Authorization"));assertEquals("bytes=0-7",first.getHeader("Range"));assertNull(first.getHeader("Icy-MetaData"));assertEquals("bytes=2-5",server.takeRequest().getHeader("Range"));
                settings.clear();assertThrows(IOException.class,()->source.read(data,0,4));assertThrows(IOException.class,()->source.open(spec(0,4)));assertEquals(2,server.getRequestCount());
            }finally{source.close();}
        }
    }
    @Test public void rejectsArbitraryUriAndIncorrectPartialResponse() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"native-test-token");NasMediaDataSource source=new NasMediaDataSource(settings,session,api,ID,8);
            try{
                assertThrows(IOException.class,()->source.open(new DataSpec(Uri.parse("https://example.org/track.mp3"))));assertEquals(0,server.getRequestCount());
                for(String header:new String[]{"Authorization","Cookie","Range","Host","Icy-MetaData"}){
                    DataSpec injected=new DataSpec.Builder().setUri(NasMediaDataSource.mediaUri(ID)).setHttpRequestHeaders(java.util.Collections.singletonMap(header,"unexpected")).build();
                    assertThrows(IOException.class,()->source.open(injected));
                }
                assertEquals(0,server.getRequestCount());
                server.enqueue(new MockResponse().setBody("abcdefgh"));assertThrows(IOException.class,()->source.open(spec(2,4)));
                server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-3/8").setBody("abcd"));assertThrows(IOException.class,()->source.open(spec(2,4)));
                assertEquals(2,server.getRequestCount());
            }finally{source.close();}
        }
    }
}
