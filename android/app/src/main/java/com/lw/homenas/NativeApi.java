package com.lw.homenas;

import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.*;

/** No redirects, cookies, arbitrary URLs or trust-all TLS. Token never crosses the WebView bridge. */
final class NativeApi {
    static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
    static final MediaType BINARY=MediaType.get("application/octet-stream");
    final String endpoint;
    private final String token;
    private final OkHttpClient client;
    private static final java.util.concurrent.atomic.AtomicLong REQUESTS=new java.util.concurrent.atomic.AtomicLong();
    static final class Failure extends IOException {
        final int status;
        Failure(int status,String message) { super(message);this.status=status; }
        boolean retryable() { return status==408||status==409||status==429||status>=500; }
    }
    NativeApi(Settings.Session session) throws Exception { this(session.endpoint,session.pem,session.allowHttp,session.token); }
    NativeApi(String endpoint,String pem,boolean allowHttp,String token) throws Exception {
        this.endpoint=Protocol.endpoint(endpoint,allowHttp);this.token=token;
        OkHttpClient.Builder builder=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).writeTimeout(25,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS);
        builder.eventListenerFactory(call->new okhttp3.EventListener(){
            private String id(){return String.valueOf(call.request().tag(String.class));}
            @Override public void dnsStart(Call current,String domain){Diagnostics.event("http.dns","request="+id());}
            @Override public void connectStart(Call current,java.net.InetSocketAddress address,java.net.Proxy proxy){Diagnostics.event("http.connect","request="+id()+" port="+address.getPort()+" proxy="+proxy.type());}
            @Override public void secureConnectStart(Call current){Diagnostics.event("http.tls","request="+id());}
        });
        if (!pem.isEmpty()) {
            X509Certificate trusted=certificate(pem);trusted.checkValidity();
            KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);store.setCertificateEntry("nas",trusted);
            TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
            X509TrustManager delegate=(X509TrustManager)factory.getTrustManagers()[0];
            X509TrustManager manager=new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain,String auth) throws java.security.cert.CertificateException { delegate.checkClientTrusted(chain,auth); }
                public void checkServerTrusted(X509Certificate[] chain,String auth) throws java.security.cert.CertificateException {
                    delegate.checkServerTrusted(chain,auth);
                    if(chain.length==0)throw new java.security.cert.CertificateException("Missing certificate");
                    chain[0].checkValidity();
                    if(!java.util.Arrays.equals(chain[0].getEncoded(),trusted.getEncoded()))throw new java.security.cert.CertificateException("Server certificate changed");
                }
                public X509Certificate[] getAcceptedIssuers(){return delegate.getAcceptedIssuers();}
            };
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{manager},null);
            builder.sslSocketFactory(tls.getSocketFactory(),manager);
            // OkHttp's default hostname verifier remains active, including IP SAN checks.
        }
        client=builder.build();
    }
    static X509Certificate certificate(String pem) throws Exception {
        if(pem.length()>32768)throw new IOException("证书过大");
        return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    private Request.Builder request(String path) throws IOException {
        if(!path.matches("(client/latest|diagnostics|server|login|me|logout|overview|assets(\\?.*)?|assets/[a-f0-9]{48}/(original|thumbnail)|uploads|uploads/[a-f0-9]{48}(/(data|complete))?)"))throw new IOException("不支持的接口");
        Request.Builder request=new Request.Builder().url(endpoint+"/api/v1/"+path);
        if(!token.isEmpty())request.header("Authorization","Bearer "+token);
        return request;
    }
    private static byte[] readLimited(InputStream stream,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int read;
        while((read=stream.read(buffer))!=-1){if(out.size()+read>limit)throw new IOException("服务器响应过大");out.write(buffer,0,read);}return out.toByteArray();
    }
    Response execute(Request request,RunControl control) throws IOException {
        return execute(request,control,false);
    }
    private Response execute(Request request,RunControl control,boolean streaming) throws IOException {
        control.check();long timeout=streaming?control.remainingMillis():Math.min(35000,control.remainingMillis());
        String id="H"+REQUESTS.incrementAndGet();long started=System.nanoTime();request=request.newBuilder().tag(String.class,id).header("X-NAS-Trace",Diagnostics.session()+"-"+id).build();
        long bytes=-1;try{if(request.body()!=null&&!request.url().encodedPath().endsWith("/login"))bytes=request.body().contentLength();}catch(IOException ignored){}
        Diagnostics.event("http.start","request="+id+" "+request.method()+" "+request.url().encodedPath()+" "+Diagnostics.server(endpoint)+" bytes="+bytes+" offset="+request.header("X-Upload-Offset")+" timeout_ms="+timeout);
        Call call=client.newBuilder().callTimeout(timeout,TimeUnit.MILLISECONDS).build().newCall(request);control.attach(call);
        try { Response response=call.execute();Diagnostics.event("http.response","request="+id+" status="+response.code()+" ms="+((System.nanoTime()-started)/1000000));if(response.isSuccessful())return response;
            try(Response failed=response){String message="服务器返回 "+response.code();
                try{JSONObject body=new JSONObject(new String(readLimited(failed.body().byteStream(),65536),java.nio.charset.StandardCharsets.UTF_8));message=body.optString("message",message);String code=body.optString("code","");if(code.matches("[A-Z0-9_]{1,64}"))Diagnostics.event("http.rejected","request="+id+" code="+code);}catch(Exception ignored){}
                throw new Failure(response.code(),message);
            }
        }catch(IOException error){Diagnostics.error("http.failure request="+id,error);control.detach();throw error;}
    }
    JSONObject json(String method,String path,JSONObject input,RunControl control) throws Exception {
        Request.Builder builder=request(path);
        if(method.equals("POST"))builder.post(RequestBody.create(input.toString(),JSON));
        try(Response response=execute(builder.build(),control)){
            return new JSONObject(new String(readLimited(response.body().byteStream(),2*1024*1024),java.nio.charset.StandardCharsets.UTF_8));
        }finally{control.detach();}
    }
    JSONObject get(String path,RunControl control) throws Exception {return json("GET",path,null,control);}
    JSONObject post(String path,JSONObject input,RunControl control) throws Exception {return json("POST",path,input,control);}
    JSONObject chunk(String id,long offset,byte[] bytes,int count,RunControl control) throws Exception {
        if(!Protocol.assetId(id))throw new IOException("上传任务ID不合法");
        RequestBody body=RequestBody.create(BINARY,bytes,0,count);
        Request request=request("uploads/"+id+"/data").header("X-Upload-Offset",Long.toString(offset))
            .header("X-Chunk-SHA256",Protocol.hash(bytes,count)).put(body).build();
        try(Response response=execute(request,control,true)){return new JSONObject(new String(readLimited(response.body().byteStream(),65536),java.nio.charset.StandardCharsets.UTF_8));}
        finally{control.detach();}
    }
    byte[] thumbnail(String id,RunControl control) throws Exception {
        if(!Protocol.assetId(id))throw new IOException("图片ID不合法");
        try(Response response=execute(request("assets/"+id+"/thumbnail").build(),control)){return readLimited(response.body().byteStream(),4*1024*1024);}
        finally{control.detach();}
    }
    Response original(String id,RunControl control) throws Exception {
        if(!Protocol.assetId(id))throw new IOException("文件ID不合法");
        return execute(request("assets/"+id+"/original").build(),control,true);
    }
    Response packageFile(String hash,String extension,RunControl control) throws Exception {
        if(!hash.matches("[a-f0-9]{64}")||!(extension.equals(".apk")||extension.equals(".zip")))throw new IOException("更新包路径无效");
        Request.Builder builder=new Request.Builder().url(endpoint+"/api/v1/client/packages/"+hash+extension);
        if(!token.isEmpty())builder.header("Authorization","Bearer "+token);
        return execute(builder.build(),control,true);
    }
    JSONObject uploadReport(java.io.File file,RunControl control,String note) throws Exception {
        try(Response response=execute(request("diagnostics").header("X-NAS-Note-Base64",android.util.Base64.encodeToString(note.getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.NO_WRAP)).post(RequestBody.create(MediaType.get("application/zip"),file)).build(),control,true)){
            return new JSONObject(new String(readLimited(response.body().byteStream(),65536),java.nio.charset.StandardCharsets.UTF_8));
        }finally{control.detach();}
    }

}
