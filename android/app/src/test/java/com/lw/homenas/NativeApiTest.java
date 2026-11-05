package com.lw.homenas;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class NativeApiTest {
    private static final String ID="0123456789abcdef0123456789abcdef0123456789abcdef";
    @Test public void deletedPreferenceFallsBackOnlyAfterDefaultIsValidated() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();Settings settings=new Settings(context);settings.prefs.edit().putLong("revision",77).putString("token","fixture-only").commit();
        Settings.Session session=new Settings.Session("http://127.0.0.1:8787","","fixture-only","folder-fallback-test",new JSONObject().put("user_id","alice"),true,77);settings.folder(session.scope,ID,"Deleted");
        try(MockWebServer server=new MockWebServer()){
            server.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"test-token");server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"deleted\"}"));server.enqueue(new MockResponse().setBody("{\"id\":\"default\",\"name\":\"默认文件夹\",\"can_write\":true}"));
            settings.ensureFolder(session,api,new RunControl(3000));assertEquals("default",settings.folder(session.scope));assertEquals("默认文件夹",settings.folderName(session.scope));
        }
    }
    @Test public void folderRoutesAreFixedAndPreserveDestination() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"test-token");server.enqueue(new MockResponse().setBody("{\"id\":\"default\",\"can_write\":true}"));
            assertTrue(api.get("folders/default",new RunControl(3000)).getBoolean("can_write"));
            server.enqueue(new MockResponse().setBody("{\"id\":\""+ID+"\"}"));api.post("folders/default/move",new JSONObject().put("target_id",ID).put("asset_ids",new org.json.JSONArray().put(ID)),new RunControl(3000));
            RecordedRequest get=server.takeRequest(2,TimeUnit.SECONDS),post=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(get);assertNotNull(post);assertEquals("/api/v1/folders/default",get.getPath());assertEquals(ID,new JSONObject(post.getBody().readUtf8()).getString("target_id"));
            assertThrows(java.io.IOException.class,()->api.get("folders/../../private",new RunControl(3000)));
        }
    }
    @Test public void renameUsesFixedAuthenticatedRouteAndPreservesConflict() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"test-token");
            server.enqueue(new MockResponse().setBody("{\"id\":\""+ID+"\",\"name\":\"新名称.jpg\",\"can_rename\":true}"));
            JSONObject input=new JSONObject().put("name","新名称.jpg").put("old_name","旧名称.jpg");
            assertEquals("新名称.jpg",api.json("POST","assets/"+ID+"/rename",input,new RunControl(3000)).getString("name"));
            RecordedRequest request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);
            assertEquals("POST",request.getMethod());assertEquals("/api/v1/assets/"+ID+"/rename",request.getPath());
            assertEquals("Bearer test-token",request.getHeader("Authorization"));
            JSONObject posted=new JSONObject(request.getBody().readUtf8());assertEquals("旧名称.jpg",posted.getString("old_name"));assertEquals("新名称.jpg",posted.getString("name"));
            server.enqueue(new MockResponse().setResponseCode(409).setBody("{\"code\":\"NAME_CHANGED\",\"message\":\"名称已更改\"}"));
            assertEquals(409,assertThrows(NativeApi.Failure.class,()->api.json("POST","assets/"+ID+"/rename",input,new RunControl(3000))).status);
            assertThrows(java.io.IOException.class,()->api.json("POST","assets/../../private/rename",input,new RunControl(3000)));
            assertEquals(2,server.getRequestCount());
        }
    }
    @Test public void chunkHasBoundedBodyDigestAndConfirmedOffset() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"test-token");
            server.enqueue(new MockResponse().setBody("{\"id\":\""+ID+"\",\"state\":\"UPLOADING\",\"offset\":\"4294967300\"}"));
            byte[] bytes={1,2,3,4,5,6};JSONObject reply=api.chunk(ID,4294967296L,bytes,4,new RunControl(3000));
            assertEquals("4294967300",reply.getString("offset"));RecordedRequest request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);
            assertEquals("4",request.getHeader("Content-Length"));assertEquals("4294967296",request.getHeader("X-Upload-Offset"));
            assertEquals(Protocol.hash(bytes,4),request.getHeader("X-Chunk-SHA256"));assertEquals("Bearer test-token",request.getHeader("Authorization"));
            assertArrayEquals(new byte[]{1,2,3,4},request.getBody().readByteArray());
        }
    }
    @Test public void redirectDoesNotForwardTokenAndUnauthorizedIsDistinct() throws Exception {
        try(MockWebServer server=new MockWebServer();MockWebServer destination=new MockWebServer()) {
            server.start();destination.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"test-token");
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",destination.url("/api/v1/me")).setBody("{}"));
            NativeApi.Failure failure=assertThrows(NativeApi.Failure.class,()->api.get("me",new RunControl(3000)));assertEquals(302,failure.status);
            assertEquals(0,destination.getRequestCount());
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\":\"login required\"}"));
            assertEquals(401,assertThrows(NativeApi.Failure.class,()->api.get("me",new RunControl(3000))).status);
            assertThrows(java.io.IOException.class,()->api.get("../../private",new RunControl(3000)));
        }
    }
    @Test public void stoppedTransferDoesNotSendAnotherRequest() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"");RunControl control=new RunControl(3000);control.cancel();
            assertThrows(java.io.InterruptedIOException.class,()->api.get("server",control));assertEquals(0,server.getRequestCount());
        }
    }
    @Test public void importedCertificateStillRequiresMatchingHostname() throws Exception {
        okhttp3.tls.HeldCertificate certificate=new okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        okhttp3.tls.HandshakeCertificates tls=new okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build();
        try(MockWebServer server=new MockWebServer()) {
            server.useHttps(tls.sslSocketFactory(),false);server.start();String endpoint="https://localhost:"+server.getPort();
            NativeApi untrusted=new NativeApi(endpoint,"",false,"");
            assertThrows(javax.net.ssl.SSLException.class,()->untrusted.get("server",new RunControl(5000)));
            server.enqueue(new MockResponse().setBody("{\"server_id\":\"test\"}"));
            NativeApi trusted=new NativeApi(endpoint,certificate.certificatePem(),false,"");assertEquals("test",trusted.get("server",new RunControl(5000)).getString("server_id"));
            NativeApi wrongName=new NativeApi("https://127.0.0.1:"+server.getPort(),certificate.certificatePem(),false,"");
            assertThrows(javax.net.ssl.SSLException.class,()->wrongName.get("server",new RunControl(5000)));
        }
    }
}
