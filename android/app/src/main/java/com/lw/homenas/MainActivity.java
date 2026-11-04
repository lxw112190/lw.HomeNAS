package com.lw.homenas;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** NAS-specific Web2Android-style local runtime. The bridge is never exposed to remote pages. */
public final class MainActivity extends Activity {
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private static final int FILES=10,CERTIFICATE=11,SAVE=12,MEDIA_PERMISSION=20,NOTIFICATION_PERMISSION=21,METADATA_PERMISSION=22;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16));
    private final ConcurrentHashMap<String,JSONObject> assets=new ConcurrentHashMap<>();
    private WebView web;
    private Settings settings;
    private JSONObject pendingDownload;
    private Settings.Session downloadSession;
    private volatile boolean visible;
    private NativeApi cachedApi;
    private long cachedRevision=-1;
    private synchronized NativeApi api(Settings.Session session) throws Exception {
        if(cachedApi==null||cachedRevision!=session.revision){cachedApi=new NativeApi(session);cachedRevision=session.revision;}return cachedApi;
    }
    private synchronized void resetApi(){cachedApi=null;cachedRevision=-1;}
    private void checkAuthentication(Settings.Session session,Exception error) {
        if(error instanceof NativeApi.Failure&&((NativeApi.Failure)error).status==401&&settings.invalidate(session)){
            BackupEngine.stopActive();BackupScheduler.cancel(this);settings.message("登录已过期或权限被调整，请重新登录；本机队列保留");resetApi();assets.clear();
        }
    }
    private JSONObject authenticatedGet(Settings.Session session,String path) throws Exception {
        try{return api(session).get(path,new RunControl(35000));}catch(Exception error){checkAuthentication(session,error);throw error;}
    }
    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);settings=new Settings(this);
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            TextView error=new TextView(this);error.setPadding(30,60,30,30);error.setText("请先更新 Android System WebView 或 Chrome，再打开家庭备份。");setContentView(error);return;
        }
        web=new WebView(this);web.setBackgroundColor(Color.rgb(241,246,245));
        WebSettings prefs=web.getSettings();prefs.setJavaScriptEnabled(true);prefs.setDomStorageEnabled(false);
        prefs.setAllowContentAccess(false);prefs.setAllowFileAccess(false);prefs.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        prefs.setSupportMultipleWindows(false);prefs.setJavaScriptCanOpenWindowsAutomatically(false);prefs.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !local(request.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                Uri uri=request.getUrl();
                if(!ORIGIN.equals(uri.getScheme()+"://"+uri.getAuthority()))return blocked();
                if(uri.getPath()!=null&&uri.getPath().matches("/thumb/[a-f0-9]{48}\\.jpg")) {
                    try{Settings.Session session=settings.session();if(session==null)return blocked();
                        String id=uri.getLastPathSegment().substring(0,48);byte[] data=api(session).thumbnail(id,new RunControl(15000));
                        if(!settings.current(session))return blocked();
                        WebResourceResponse result=new WebResourceResponse("image/jpeg",null,new ByteArrayInputStream(data));result.setResponseHeaders(Collections.singletonMap("Cache-Control","no-store"));return result;
                    }catch(Exception error){return blocked();}
                }
                WebResourceResponse response=loader.shouldInterceptRequest(uri);return response==null?blocked():response;
            }
            // Default WebViewClient cancels SSL errors. No proceed() or trust-all fallback.
        });
        WebViewCompat.addWebMessageListener(web,"HomeNasNative",Collections.singleton(ORIGIN),(view,message,sourceOrigin,isMainFrame,reply)->{
            if(!isMainFrame||!ORIGIN.equals(sourceOrigin.getScheme()+"://"+sourceOrigin.getAuthority())||!local(Uri.parse(view.getUrl()==null?"":view.getUrl())))return;
            if(message.getType()!=androidx.webkit.WebMessageCompat.TYPE_STRING)return;
            String raw=message.getData();if(raw==null||raw.length()>32768)return;
            try {
                JSONObject input=new JSONObject(raw);String id=input.getString("id");if(!id.matches("[a-zA-Z0-9_-]{1,64}"))return;
                io.execute(()->{
                    JSONObject output=new JSONObject();
                    try{output.put("id",id).put("ok",true).put("data",dispatch(input));}
                    catch(Exception error){try{output.put("id",id).put("ok",false).put("error",safeError(error));}catch(Exception ignored){}}
                    runOnUiThread(()->{try{if(web!=null&&!isFinishing())reply.postMessage(output.toString());}catch(RuntimeException ignored){/* The originating frame may have been destroyed. */}});
                });
            }catch(Exception ignored){/* Malformed or excessive bridge messages are not executable commands. */}
        });
        FrameLayout root=new FrameLayout(this);root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });setContentView(root);root.requestApplyInsets();web.loadUrl(ORIGIN+"/assets/www/index.html");
    }
    static boolean local(Uri uri){return "https".equals(uri.getScheme())&&"appassets.androidplatform.net".equals(uri.getAuthority())&&uri.getPath()!=null&&uri.getPath().startsWith("/assets/www/");}
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","utf-8",403,"Forbidden",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    static String safeError(Exception error) {
        if(error instanceof javax.net.ssl.SSLException)return "HTTPS证书或主机名校验失败，请核对电脑地址与导入的公开证书";
        String message=error.getMessage();if(message==null||message.isEmpty())return "操作未完成，请重试或查看连接设置";
        // Do not expose request headers, bearer values or full stack traces to Web content/logs.
        return message.length()>240?message.substring(0,240):message;
    }
    private Settings.Session requireSession(boolean upload) throws Exception {
        Settings.Session session=settings.session();if(session==null)throw new SecurityException("请先登录家庭服务器");
        if(upload&&!session.canUpload)throw new SecurityException("当前为只读账号，不能备份");return session;
    }
    private JSONObject snapshot() throws Exception {
        JSONObject result=settings.publicState().put("access",MediaCatalog.access(this)).put("version",BuildConfig.VERSION_NAME);
        Settings.Session session=settings.session();result.put("queue",TaskStore.get(this).summary(session==null?"":session.scope));
        String pem=settings.prefs.getString("pending_cert","");
        if(!pem.isEmpty())result.put("certificate",Protocol.hex(MessageDigest.getInstance("SHA-256").digest(NativeApi.certificate(pem).getEncoded())));
        return result;
    }
    private Object dispatch(JSONObject input) throws Exception {
        String action=input.getString("action");JSONObject data=input.optJSONObject("data");if(data==null)data=new JSONObject();
        switch(action) {
            case "snapshot": return snapshot();
            case "login": {
                settings.pause(true);BackupEngine.stopActive();stopService(new Intent(this,BackupService.class));
                String endpoint=Protocol.endpoint(data.getString("endpoint"),data.optBoolean("allowHttp"));
                String pem=data.optBoolean("trustCertificate")?settings.prefs.getString("pending_cert",""):"";
                NativeApi probe=new NativeApi(endpoint,pem,data.optBoolean("allowHttp"),"");RunControl control=new RunControl(60000);
                JSONObject server=probe.get("server",control);
                if(!server.optString("server_id").matches("[a-f0-9]{48}"))throw new java.io.IOException("目标不是兼容的家庭NAS服务器");
                JSONObject login=new JSONObject().put("username",data.getString("username")).put("password",data.getString("password")).put("client","native");
                JSONObject result;try{result=probe.post("login",login,control);}finally{login.remove("password");data.remove("password");}
                String token=result.getString("token");if(!token.matches("[a-f0-9]{64}"))throw new java.io.IOException("登录响应不合法");
                JSONObject me=new NativeApi(endpoint,pem,data.optBoolean("allowHttp"),token).get("me",control);
                settings.save(endpoint,pem,data.optBoolean("allowHttp"),server.getString("server_id"),me,token);BackupScheduler.cancel(this);assets.clear();resetApi();
                settings.message("登录成功；请选择文件或相册，点击立即备份");return snapshot();
            }
            case "logout": {
                Settings.Session old=settings.session();settings.clear();BackupEngine.stopActive();stopService(new Intent(this,BackupService.class));BackupScheduler.cancel(this);assets.clear();resetApi();
                if(old!=null)try{new NativeApi(old).post("logout",new JSONObject(),new RunControl(12000));}catch(Exception ignored){}
                return snapshot();
            }
            case "importCertificate": ui(()->pick(CERTIFICATE));return new JSONObject().put("message","请选择电脑导出的公开证书，不要选择私钥");
            case "clearCertificate": settings.prefs.edit().remove("pending_cert").commit();return snapshot();
            case "chooseFiles": requireSession(true);ui(()->pick(FILES));return new JSONObject().put("message","请在系统文件选择器中选择文件");
            case "permission": requireSession(true);ui(this::requestMedia);return new JSONObject();
            case "metadataPermission": requireSession(true);ui(()->requestPermissions(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},METADATA_PERMISSION));return new JSONObject();
            case "albums": requireSession(true);return new JSONObject().put("items",MediaCatalog.albums(this,new RunControl(90000))).put("access",MediaCatalog.access(this));
            case "preferences": {
                requireSession(true);JSONArray list=data.getJSONArray("albums");if(list.length()>500)throw new IllegalArgumentException("相册数量过多");Set<String> albums=new HashSet<>();
                for(int i=0;i<list.length();i++){String key=list.getString(i);if(!key.matches("(image|video):[a-zA-Z0-9_-]+:-?[0-9]+"))throw new IllegalArgumentException("相册标识无效");albums.add(key);}
                settings.prefs.edit().putStringSet("albums",albums).putBoolean("auto",data.optBoolean("auto")).putBoolean("wifi",data.optBoolean("wifiOnly",true)).putBoolean("charging",data.optBoolean("charging",false)).commit();
                if(data.optBoolean("auto"))settings.pause(false);BackupScheduler.configure(this);return snapshot();
            }
            case "scan": {Settings.Session session=requireSession(true);long added=MediaCatalog.scan(this,session,new RunControl(90000));settings.message("本轮扫描新增 "+added+" 项；未上传项保留在本机队列");return snapshot();}
            case "start": requireSession(true);settings.pause(false);ui(this::startManual);return new JSONObject();
            case "pause": requireSession(true);settings.pause(true);BackupEngine.stopActive();stopService(new Intent(this,BackupService.class));return snapshot();
            case "retry": {Settings.Session session=requireSession(true);TaskStore.get(this).retry(session.scope);return snapshot();}
            case "overview": {Settings.Session session=requireSession(false);return authenticatedGet(session,"overview");}
            case "assets": {
                Settings.Session session=requireSession(false);String cursor=data.optString("before","");if(cursor.length()>100)throw new IllegalArgumentException("分页游标无效");
                String path="assets"+(cursor.isEmpty()?"":"?before="+java.net.URLEncoder.encode(cursor,"UTF-8"));
                JSONObject result=authenticatedGet(session,path);if(!settings.current(session))throw new SecurityException("登录身份已改变");
                if(assets.size()>200)assets.clear();JSONArray items=result.getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);assets.put(item.getString("id"),item);}return result;
            }
            case "download": {
                Settings.Session session=requireSession(false);JSONObject item=assets.get(data.getString("id"));if(item==null)throw new IllegalArgumentException("请刷新文件列表后重试");
                ui(()->{if(pendingDownload!=null){settings.message("请先完成上一个保存窗口");return;}pendingDownload=item;downloadSession=session;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(item.optString("mime","application/octet-stream")).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,item.optString("name","文件"));startActivityForResult(intent,SAVE);});
                return new JSONObject();
            }
            default: throw new IllegalArgumentException("不支持的手机备份操作");
        }
    }
    private void ui(Runnable action) {
        runOnUiThread(()->{if(!visible||isFinishing()){settings.message("请回到应用前台操作");return;}try{action.run();}catch(RuntimeException error){settings.message(safeError(error));}});
    }
    private void pick(int request) {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        if(request==FILES)intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,request);
    }
    private void requestMedia() {
        if(Build.VERSION.SDK_INT>=34)requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},MEDIA_PERMISSION);
        else if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO},MEDIA_PERMISSION);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},MEDIA_PERMISSION);
    }
    private void startManual() {
        if(Build.VERSION.SDK_INT>=33&&!MediaCatalog.granted(this,Manifest.permission.POST_NOTIFICATIONS)){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);return;}
        ContextCompat.startForegroundService(this,new Intent(this,BackupService.class));
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==MEDIA_PERMISSION||request==METADATA_PERMISSION)settings.message(MediaCatalog.access(this));
        if(request==NOTIFICATION_PERMISSION)try{ContextCompat.startForegroundService(this,new Intent(this,BackupService.class));}catch(RuntimeException error){settings.message(safeError(error));}
    }
    @Override protected void onActivityResult(int request,int result,Intent intent) {
        super.onActivityResult(request,result,intent);
        if(request==SAVE) {
            JSONObject item=pendingDownload;Settings.Session session=downloadSession;pendingDownload=null;downloadSession=null;
            if(result==RESULT_OK&&item==null){settings.message("保存操作被系统中断，请重新选择要下载的文件");return;}
            if(result==RESULT_OK&&intent!=null&&intent.getData()!=null&&item!=null) {Uri destination=intent.getData();if(!"content".equals(destination.getScheme())){settings.message("仅支持系统文档提供器的保存位置");return;}io.execute(()->download(item,session,destination));}return;
        }
        if(result!=RESULT_OK||intent==null)return;
        io.execute(()->{
            try {
                if(request==CERTIFICATE) {
                    Uri uri=intent.getData();if(uri==null)return;ByteArrayOutputStream output=new ByteArrayOutputStream();
                    try(InputStream stream=getContentResolver().openInputStream(uri)){if(stream==null)throw new java.io.FileNotFoundException();byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1){if(output.size()+n>32768)throw new java.io.IOException("证书文件过大");output.write(buffer,0,n);}}
                    java.security.cert.X509Certificate cert=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(output.toByteArray()));cert.checkValidity();
                    String pem="-----BEGIN CERTIFICATE-----\n"+android.util.Base64.encodeToString(cert.getEncoded(),android.util.Base64.NO_WRAP)+"\n-----END CERTIFICATE-----\n";
                    settings.prefs.edit().putString("pending_cert",pem).commit();settings.message("公开证书已读取，请对照电脑SHA256摘要后勾选确认，再登录");
                }else if(request==FILES) {
                    Settings.Session session=requireSession(true);int count=0;ClipData clips=intent.getClipData();int total=clips==null?1:clips.getItemCount();
                    for(int i=0;i<Math.min(total,500);i++) {
                        Uri uri=clips==null?intent.getData():clips.getItemAt(i).getUri();if(uri==null||!"content".equals(uri.getScheme()))continue;
                        getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        uri=MediaCatalog.originalIfAllowed(this,uri);
                        MediaCatalog.Info info=MediaCatalog.describe(getContentResolver(),uri);
                        String source=Protocol.key("chosen:"+uri+":"+info.size+":"+(info.modified==0?UUID.randomUUID():info.modified));
                        if(TaskStore.get(this).enqueue(session.scope,source,uri.toString(),info.name,"手动选择",info.size,info.modified)!=-1)count++;
                    }
                    settings.message("已加入 "+count+" 项，请点击立即备份"+(total>500?"；单次最多500项，请分批选择":""));
                }
            }catch(Exception error){settings.message("选择未完成："+safeError(error));}
        });
    }
    private void download(JSONObject item,Settings.Session session,Uri destination) {
        RunControl control=new RunControl(5*60*60*1000L);
        try {
            if(!settings.current(session))throw new SecurityException("登录身份已改变，请重选文件");
            try(okhttp3.Response response=api(session).original(item.getString("id"),control);InputStream input=response.body().byteStream();OutputStream output=getContentResolver().openOutputStream(destination,"wt")) {
                if(output==null)throw new java.io.IOException("无法写入所选位置");byte[] buffer=new byte[256*1024];MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;int n;
                while((n=input.read(buffer))!=-1){control.check();if(!settings.current(session))throw new SecurityException("登录身份已改变");output.write(buffer,0,n);digest.update(buffer,0,n);total+=n;}
                output.flush();if(total!=Long.parseLong(item.getString("size"))||!Protocol.hex(digest.digest()).equals(item.getString("sha256")))throw new java.io.IOException("下载长度或摘要不一致，该文件未确认完整，请重新下载");
            }
            settings.message("原件已保存且通过SHA256校验："+item.getString("name"));
        }catch(Exception error){checkAuthentication(session,error);settings.message("下载未完成（所选位置可能保留部分文件）："+safeError(error));}
        finally{control.detach();}
    }
    @Override protected void onResume(){super.onResume();visible=true;if(web!=null)web.onResume();}
    @Override protected void onPause(){visible=false;if(web!=null)web.onPause();super.onPause();}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){if(web!=null){WebViewCompat.removeWebMessageListener(web,"HomeNasNative");web.destroy();web=null;}io.shutdownNow();super.onDestroy();}
}
