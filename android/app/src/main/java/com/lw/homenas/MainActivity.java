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
    private static final int FILES=10,CERTIFICATE=11,SAVE=12,EXPORT_LOGS=13,MEDIA_PERMISSION=20,NOTIFICATION_PERMISSION=21,METADATA_PERMISSION=22;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16));
    private final ConcurrentHashMap<String,JSONObject> assets=new ConcurrentHashMap<>();
    private volatile String queueBefore="",queueFilter="active";
    private WebView web;
    private ClientMaintenance maintenance;
    private java.io.File loadedUi;
    private Settings settings;
    private PreviewStreams previews;
    private MediaPicker mediaPicker;
    private Settings.Session fileSession;private String fileFolder,fileFolderName;
    private JSONObject pendingDownload;
    private Settings.Session downloadSession;
    private volatile boolean visible;
    private final EnqueueJob enqueueJob=new EnqueueJob();
    private final java.util.concurrent.atomic.AtomicLong mediaRevision=new java.util.concurrent.atomic.AtomicLong();
    private boolean requestingMedia;
    private NativeApi cachedApi;
    private long cachedRevision=-1;
    private synchronized NativeApi api(Settings.Session session) throws Exception {
        if(cachedApi==null||cachedRevision!=session.revision){cachedApi=new NativeApi(session);cachedRevision=session.revision;}return cachedApi;
    }
    private synchronized void resetApi(){cachedApi=null;cachedRevision=-1;if(previews!=null)previews.closeAll();if(mediaPicker!=null)mediaPicker.clear();}
    private void checkAuthentication(Settings.Session session,Exception error) {
        if(error instanceof NativeApi.Failure&&((NativeApi.Failure)error).status==401&&settings.invalidate(session)){
            enqueueJob.cancel();BackupEngine.stopActive();BackupScheduler.cancel(this);settings.message("登录已过期或权限被调整，请重新登录；本机队列保留");resetApi();assets.clear();
        }
    }
    private JSONObject authenticatedGet(Settings.Session session,String path) throws Exception {
        try{return api(session).get(path,new RunControl(35000));}catch(Exception error){checkAuthentication(session,error);throw error;}
    }
    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);maintenance=new ClientMaintenance(this);loadedUi=maintenance.beginLaunch();settings=new Settings(this);previews=new PreviewStreams(settings);mediaPicker=new MediaPicker(this,settings);Diagnostics.initialize(this);Diagnostics.event("activity.create","restored="+(state!=null));
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Diagnostics.event("webview.unsupported","WEB_MESSAGE_LISTENER unavailable");TextView error=new TextView(this);error.setPadding(30,60,30,30);error.setText("请先更新 Android System WebView 或 Chrome，再打开家庭备份。");setContentView(error);return;
        }
        web=new WebView(this);web.setBackgroundColor(Color.rgb(241,246,245));
        WebSettings prefs=web.getSettings();prefs.setJavaScriptEnabled(true);prefs.setDomStorageEnabled(false);prefs.setCacheMode(WebSettings.LOAD_NO_CACHE);
        prefs.setAllowContentAccess(false);prefs.setAllowFileAccess(false);prefs.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        prefs.setSupportMultipleWindows(false);prefs.setJavaScriptCanOpenWindowsAutomatically(false);prefs.setSafeBrowsingEnabled(true);
        web.setWebChromeClient(new android.webkit.WebChromeClient(){@Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message){if(message.messageLevel()==android.webkit.ConsoleMessage.MessageLevel.ERROR||message.messageLevel()==android.webkit.ConsoleMessage.MessageLevel.WARNING)Diagnostics.event("webview.console","level="+message.messageLevel()+" line="+message.lineNumber()+" text omitted");return true;}});
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebViewAssetLoader loader=loadedUi==null?new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build():new WebViewAssetLoader.Builder().addPathHandler("/assets/www/",new WebViewAssetLoader.InternalStoragePathHandler(this,loadedUi)).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){Diagnostics.event("webview.ready","local="+local(Uri.parse(url)));}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,android.webkit.WebResourceError error){Diagnostics.event("webview.load_error","code="+error.getErrorCode()+" main="+request.isForMainFrame());}
            @Override public boolean onRenderProcessGone(WebView view,android.webkit.RenderProcessGoneDetail detail){Diagnostics.event("webview.renderer_gone","crashed="+detail.didCrash());return false;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !local(request.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                Uri uri=request.getUrl();
                if(!ORIGIN.equals(uri.getScheme()+"://"+uri.getAuthority()))return blocked();
                if(uri.getPath()!=null&&uri.getPath().matches("/media/[a-f0-9]{48}")){
                    try{Settings.Session session=settings.session();if(session==null)return blocked();String id=uri.getLastPathSegment();return previews.open(request,session,api(session),assets.get(id));}catch(Exception error){Diagnostics.error("preview.open",error);return blocked();}
                }
                if(uri.getPath()!=null&&uri.getPath().matches("/local-thumb/[a-f0-9]{32}\\.jpg")){
                    try{Settings.Session session=settings.session();if(session==null||request.isForMainFrame())return blocked();String handle=uri.getLastPathSegment().substring(0,32);byte[] bytes=mediaPicker.thumbnail(session,handle);if(!settings.current(session))return blocked();WebResourceResponse result=new WebResourceResponse("image/jpeg",null,new ByteArrayInputStream(bytes));result.setResponseHeaders(Collections.singletonMap("Cache-Control","no-store"));return result;}catch(Exception error){return blocked();}
                }
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
            String raw=message.getData();if(raw==null||raw.length()>262144)return;
            try {
                JSONObject input=new JSONObject(raw);String id=input.getString("id");if(!id.matches("[a-zA-Z0-9_-]{1,64}"))return;
                io.execute(()->{
                    JSONObject output=new JSONObject();
                    String action=input.optString("action");String safeAction=action.matches("[A-Za-z]{1,40}")?action:"invalid";long started=System.nanoTime();
                    boolean trace=!action.equals("snapshot")&&!action.equals("clientLog");if(trace)Diagnostics.event("bridge.start","action="+safeAction+" request="+id);
                    try{output.put("id",id).put("ok",true).put("data",dispatch(input));if(trace)Diagnostics.event("bridge.done","action="+safeAction+" request="+id+" ms="+((System.nanoTime()-started)/1000000));}
                    catch(Exception error){String incident=Diagnostics.error("bridge."+safeAction,error);try{output.put("id",id).put("ok",false).put("error",safeError(error)).put("incident",incident);}catch(Exception ignored){}}
                    runOnUiThread(()->{try{if(web!=null&&!isFinishing())reply.postMessage(output.toString());}catch(RuntimeException ignored){/* The originating frame may have been destroyed. */}});
                });
            }catch(Exception ignored){Diagnostics.event("bridge.rejected","malformed or busy; payload omitted");}
        });
        FrameLayout root=new FrameLayout(this);root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });setContentView(root);root.requestApplyInsets();web.loadUrl(ORIGIN+"/assets/www/index.html");
        io.execute(()->{try{if(settings.session()!=null){maintenance.check();settings.message("更新检查完成；当前资源 "+maintenance.version()+"，可在设置查看更新");}}catch(Exception error){Diagnostics.error("updates.check",error);}});
    }
    static boolean local(Uri uri){return "https".equals(uri.getScheme())&&"appassets.androidplatform.net".equals(uri.getAuthority())&&uri.getPath()!=null&&uri.getPath().startsWith("/assets/www/");}
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","utf-8",403,"Forbidden",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    static String safeError(Exception error) {
        if(error instanceof javax.net.ssl.SSLException)return "HTTPS证书或主机名校验失败，请核对电脑地址与导入的公开证书";
        if(error instanceof java.net.ConnectException)return "无法连接电脑：请确认电脑服务已启动，HTTP模式已开放内网，并检查IP、端口和防火墙";
        if(error instanceof java.net.UnknownHostException)return "找不到电脑地址，请检查输入的IP或主机名";
        if(error instanceof java.net.SocketTimeoutException)return "连接或传输超时，请检查电脑服务、网络和防火墙，可导出诊断日志";
        String message=error.getMessage();if(message==null||message.isEmpty())return "操作未完成，请重试或查看连接设置";
        // Do not expose request headers, bearer values or full stack traces to Web content/logs.
        return message.length()>240?message.substring(0,240):message;
    }
    private Settings.Session requireSession(boolean upload) throws Exception {
        Settings.Session session=settings.session();if(session==null)throw new SecurityException("请先登录家庭服务器");
        if(upload&&!session.canUpload)throw new SecurityException("当前为只读账号，不能备份");return session;
    }
    private void ensureDestination(Settings.Session session) throws Exception {try{settings.ensureFolder(session,api(session),new RunControl(35000));}catch(Exception error){checkAuthentication(session,error);throw error;}}
    private synchronized JSONObject snapshot() throws Exception {
        JSONObject result=settings.publicState().put("access",MediaCatalog.access(this)).put("mediaAccess",MediaPermissions.state(this)).put("mediaRevision",mediaRevision.get()).put("version",BuildConfig.VERSION_NAME).put("build",BuildConfig.BUILD_REF).put("logSession",Diagnostics.session()).put("resourceVersion",maintenance.version()).put("nativeApi",11).put("queueCursor",queueBefore).put("queueFilter",queueFilter);
        Settings.Session session=settings.session();result.put("queue",TaskStore.get(this).summary(session==null?"":session.scope,queueBefore,queueFilter));
        JSONObject transfer=TransferProgress.snapshot(session==null?"":session.scope);long[] totals=TaskStore.get(this).pendingTotals(session==null?"":session.scope);
        transfer.put("remaining_items",totals[0]).put("remaining_bytes",Long.toString(totals[1]));result.put("enqueue",enqueueJob.snapshot(session==null?"":session.scope));
        result.put("transfer",transfer).put("playbackDiagnostics",true).put("nativeMediaPlayback",true);
        String pem=settings.prefs.getString("pending_cert","");
        if(!pem.isEmpty())result.put("certificate",Protocol.hex(MessageDigest.getInstance("SHA-256").digest(NativeApi.certificate(pem).getEncoded())));
        return result;
    }
    private String connection(JSONObject data) throws Exception {
        String endpoint=data.has("host")?Protocol.connectionAddress(data.getString("host"),data.optString("protocol","http"),data.optString("port","")):Protocol.endpoint(data.getString("endpoint"),data.optBoolean("allowHttp"));
        settings.rememberConnection(endpoint,endpoint.startsWith("http:"));Diagnostics.event("connection.selected",Diagnostics.server(endpoint)+" "+Diagnostics.network(this));return endpoint;
    }
    private Object dispatch(JSONObject input) throws Exception {
        String action=input.getString("action");JSONObject data=input.optJSONObject("data");if(data==null)data=new JSONObject();
        switch(action) {
            case "finishActivity": ui(this::finish);return new JSONObject();
            case "backupFolder": {
                Settings.Session session=requireSession(true);String id=data.getString("id");if(!Protocol.folderId(id))throw new IllegalArgumentException("文件夹标识无效");
                JSONObject folder=authenticatedGet(session,"folders/"+id);if(!folder.optBoolean("can_write"))throw new SecurityException("不能备份到其他用户的文件夹");
                if(!settings.current(session))throw new SecurityException("登录身份已改变");enqueueJob.cancel();settings.folder(session.scope,id,folder.getString("name"));mediaPicker.clear();return snapshot();
            }
            case "folderList": case "folderItems": case "folderChange": {
                boolean write=action.equals("folderChange");Settings.Session session=requireSession(write);String folderId=data.optString("folderId","");
                if(!folderId.isEmpty()&&!Protocol.folderId(folderId))throw new IllegalArgumentException("文件夹标识无效");
                JSONObject result;
                if(write){String operation=data.optString("operation");if(!operation.matches("create|edit|delete|add|remove|move")||(!operation.equals("create")&&folderId.isEmpty()))throw new IllegalArgumentException("文件夹操作无效");
                    JSONObject value=data.optJSONObject("value");if(value==null)value=new JSONObject();
                    try{result=api(session).post(operation.equals("create")?"folders":"folders/"+folderId+"/"+operation,value,new RunControl(35000));}catch(Exception error){checkAuthentication(session,error);throw error;}
                }else{String before=data.optString("before","");if(before.length()>48)throw new IllegalArgumentException("分页游标无效");
                    String query="before="+java.net.URLEncoder.encode(before,"UTF-8");
                    if(action.equals("folderItems")){if(folderId.isEmpty())throw new IllegalArgumentException("请选择文件夹");query+="&type="+java.net.URLEncoder.encode(data.optString("type","all"),"UTF-8")+"&q="+java.net.URLEncoder.encode(data.optString("search",""),"UTF-8");}
                    result=authenticatedGet(session,action.equals("folderList")?"folders?"+query:"folders/"+folderId+"/items?"+query);
                }
                if(!settings.current(session))throw new SecurityException("登录身份已改变");
                if(action.equals("folderItems")){assets.clear();JSONArray items=result.getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);assets.put(item.getString("id"),item);}}
                return result;
            }
            case "albumList": case "albumItems": case "albumChange": {
                boolean write=action.equals("albumChange");Settings.Session session=requireSession(write);String albumId=data.optString("albumId","");
                if(!albumId.isEmpty()&&!Protocol.assetId(albumId))throw new IllegalArgumentException("图集标识无效");
                JSONObject result;
                if(write){String operation=data.optString("operation");if(!operation.matches("create|edit|delete|add|remove|cover")||(!operation.equals("create")&&albumId.isEmpty()))throw new IllegalArgumentException("图集操作无效");
                    JSONObject value=data.optJSONObject("value");if(value==null)value=new JSONObject();
                    try{result=api(session).post(operation.equals("create")?"albums":"albums/"+albumId+"/"+operation,value,new RunControl(35000));}catch(Exception error){checkAuthentication(session,error);throw error;}
                }else{String before=data.optString("before","");if(before.length()>48)throw new IllegalArgumentException("分页游标无效");
                    String query="before="+java.net.URLEncoder.encode(before,"UTF-8");
                    if(action.equals("albumItems")){if(albumId.isEmpty())throw new IllegalArgumentException("请选择图集");query+="&type="+java.net.URLEncoder.encode(data.optString("type","all"),"UTF-8")+"&q="+java.net.URLEncoder.encode(data.optString("search",""),"UTF-8");}
                    result=authenticatedGet(session,action.equals("albumList")?"albums?"+query:"albums/"+albumId+"/items?"+query);
                }
                if(!settings.current(session))throw new SecurityException("登录身份已改变");
                if(action.equals("albumItems")){assets.clear();JSONArray items=result.getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);assets.put(item.getString("id"),item);}}
                return result;
            }
            case "uiReady": maintenance.ready();return new JSONObject();
            case "checkUpdates": return maintenance.check();
            case "updateResources": settings.message(maintenance.stageUi());return snapshot();
            case "uploadLogs": settings.message(maintenance.uploadLogs(data.optString("description","")));return snapshot();
            case "installApk": {
                java.io.File apk=maintenance.prepareApk();ui(()->{
                    if(!getPackageManager().canRequestPackageInstalls()){startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));settings.message("允许安装应用后，请再次点击APK升级");return;}
                    Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".updates",apk);
                    settings.pause(true);BackupEngine.stopActive();stopService(new Intent(this,BackupService.class));
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                });return new JSONObject();
            }
            case "queuePage": {Settings.Session session=requireSession(false);String before=data.optString("before",""),filter=data.optString("filter","active");TaskStore.get(this).summary(session.scope,before,filter);synchronized(this){queueBefore=before;queueFilter=filter;return snapshot();}}
            case "previewClose": previews.closeAll();return new JSONObject();
            case "playMedia": {
                Settings.Session session=requireSession(false);String id=data.getString("id");JSONObject item=assets.get(id);
                if(!Protocol.assetId(id)||item==null)throw new IllegalArgumentException("请刷新文件列表后重新播放");
                String mime=PreviewStreams.previewType(item);long size=Long.parseLong(item.getString("size"));
                if(!(mime.startsWith("video/")||mime.startsWith("audio/"))||size<=0)throw new IllegalArgumentException("该文件不能播放");
                Intent intent=new Intent(this,MediaPreviewActivity.class).putExtra("asset_id",id).putExtra("mime",mime).putExtra("name",item.optString("name")).putExtra("size",size).putExtra("revision",session.revision);
                ui(()->{if(settings.current(session)){previews.closeAll();startActivity(intent);}});return new JSONObject();
            }
            case "playbackError": {requireSession(false);JSONObject item=assets.get(data.optString("id"));if(item==null)throw new IllegalArgumentException("请重新打开预览");Diagnostics.event("preview.playback_error","code="+Math.max(0,Math.min(4,data.optInt("code")))+" network="+Math.max(0,Math.min(3,data.optInt("network")))+" ready="+Math.max(0,Math.min(4,data.optInt("ready")))+" mime="+item.optString("mime").replaceAll("[^A-Za-z0-9/+-]","")+" sdk="+Build.VERSION.SDK_INT);return new JSONObject();}
            case "uploadConcurrency": {requireSession(true);int count=data.getInt("count");if(count<1||count>3)throw new IllegalArgumentException("同时上传数量需1至3");settings.prefs.edit().putInt("upload_parallel",count).commit();return snapshot();}
            case "mediaMonths": {Settings.Session session=requireSession(true);if(enqueueJob.snapshot(session.scope).optString("state").equals("RUNNING"))throw new java.io.IOException("正在加入队列，请等待或取消");return mediaPicker.months(session,data.optString("kind","all"),new RunControl(90000));}
            case "mediaEnqueueStart": {Settings.Session session=requireSession(true);return enqueueJob.start(settings,session,mediaPicker,api(session),data);}
            case "mediaEnqueueCancel": {Settings.Session session=requireSession(true);if(enqueueJob.snapshot(session.scope).optString("state").equals("RUNNING"))enqueueJob.cancel();return enqueueJob.snapshot(session.scope);}
            case "mediaDays": {Settings.Session session=requireSession(true);if(enqueueJob.snapshot(session.scope).optString("state").equals("RUNNING"))throw new java.io.IOException("正在加入队列，请等待或取消");if(data.optBoolean("reset"))ensureDestination(session);return mediaPicker.days(session,data.optString("before",""),data.optBoolean("reset"),new RunControl(90000),data.optString("kind","all"));}
            case "mediaItems": {Settings.Session session=requireSession(true);if(enqueueJob.snapshot(session.scope).optString("state").equals("RUNNING"))throw new java.io.IOException("正在加入队列，请等待或取消");return mediaPicker.items(session,data.getString("day"),data.optString("before",""),api(session),new RunControl(90000),data.optString("kind","all"));}
            case "mediaEnqueue": {Settings.Session session=requireSession(true);JSONObject result=mediaPicker.enqueue(session,data,api(session),new RunControl(90000));settings.message("已加入 "+result.getInt("added")+" 项，跳过已完成或已入队 "+result.getInt("skipped")+" 项");return result;}
            case "snapshot": return snapshot();
            case "diagnostics": return Diagnostics.view(this);
            case "clientLog": {
                String kind=data.optString("kind"),name=data.optString("name");
                if(!kind.matches("error|rejection|timeout")||!name.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("无效日志类型");
                Diagnostics.event("web."+kind,"name="+name+" line="+Math.max(0,data.optInt("line"))+" column="+Math.max(0,data.optInt("column")));return new JSONObject();
            }
            case "exportDiagnostics": ui(()->{Intent pick=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"HomeNAS-诊断-"+Diagnostics.session()+".zip");startActivityForResult(pick,EXPORT_LOGS);});return new JSONObject();
            case "testConnection": {
                String endpoint=connection(data);String pem=endpoint.startsWith("https:")&&data.optBoolean("trustCertificate")?settings.prefs.getString("pending_cert",""):"";
                JSONObject server=new NativeApi(endpoint,pem,endpoint.startsWith("http:"),"").get("server",new RunControl(15000));
                if(!server.optString("authentication").equals("username_password"))throw new java.io.IOException("电脑端需使用0.2及以上账号版服务");
                settings.message("连接成功：电脑服务 "+server.optString("version")+"；可继续输入账号密码登录");return new JSONObject().put("endpoint",endpoint).put("version",server.optString("version"));
            }
            case "login": {
                enqueueJob.cancel();settings.pause(true);BackupEngine.stopActive();stopService(new Intent(this,BackupService.class));
                String endpoint=connection(data);boolean http=endpoint.startsWith("http:");
                String pem=!http&&data.optBoolean("trustCertificate")?settings.prefs.getString("pending_cert",""):"";
                NativeApi probe=new NativeApi(endpoint,pem,http,"");RunControl control=new RunControl(60000);
                JSONObject server=probe.get("server",control);
                if(!server.optString("server_id").matches("[a-f0-9]{48}"))throw new java.io.IOException("目标不是兼容的家庭NAS服务器");
                JSONObject login=new JSONObject().put("username",data.getString("username")).put("password",data.getString("password")).put("client","native");
                JSONObject result;try{result=probe.post("login",login,control);}finally{login.remove("password");data.remove("password");}
                String token=result.getString("token");if(!token.matches("[a-f0-9]{64}"))throw new java.io.IOException("登录响应不合法");
                JSONObject me=new NativeApi(endpoint,pem,http,token).get("me",control);
                queueBefore="";queueFilter="active";settings.save(endpoint,pem,http,server.getString("server_id"),me,token);BackupScheduler.cancel(this);assets.clear();resetApi();
                Diagnostics.event("login.saved","role="+me.optString("role")+" can_upload="+me.optBoolean("can_upload")+" encrypted_session=true");
                settings.message("登录成功；请选择文件或相册，点击立即备份");return snapshot();
            }
            case "logout": {enqueueJob.cancel();queueBefore="";queueFilter="active";
                Settings.Session old=settings.session();settings.clear();BackupEngine.stopActive();stopService(new Intent(this,BackupService.class));BackupScheduler.cancel(this);assets.clear();resetApi();
                if(old!=null)try{new NativeApi(old).post("logout",new JSONObject(),new RunControl(12000));}catch(Exception ignored){}
                return snapshot();
            }
            case "importCertificate": ui(()->pick(CERTIFICATE));return new JSONObject().put("message","请选择电脑导出的公开证书，不要选择私钥");
            case "clearCertificate": settings.prefs.edit().remove("pending_cert").commit();return snapshot();
            case "chooseFiles": {Settings.Session session=requireSession(true);ensureDestination(session);fileSession=session;fileFolder=settings.folder(session.scope);fileFolderName=settings.folderName(session.scope);ui(()->pick(FILES));return new JSONObject().put("message","请在系统文件选择器中选择文件");}
            case "permission": requireSession(true);ui(this::requestMedia);return new JSONObject();
            case "mediaSettings": requireSession(true);ui(this::openMediaSettings);return new JSONObject();
            case "metadataPermission": requireSession(true);ui(()->requestPermissions(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},METADATA_PERMISSION));return new JSONObject();
            case "albums": requireSession(true);return new JSONObject().put("items",MediaCatalog.albums(this,new RunControl(90000))).put("access",MediaCatalog.access(this));
            case "preferences": {
                requireSession(true);JSONArray list=data.getJSONArray("albums");if(list.length()>500)throw new IllegalArgumentException("相册数量过多");Set<String> albums=new HashSet<>();
                for(int i=0;i<list.length();i++){String key=list.getString(i);if(!key.matches("(image|video):[a-zA-Z0-9_-]+:-?[0-9]+"))throw new IllegalArgumentException("相册标识无效");albums.add(key);}
                settings.prefs.edit().putStringSet("albums",albums).putBoolean("auto",data.optBoolean("auto")).putBoolean("wifi",data.optBoolean("wifiOnly",true)).putBoolean("charging",data.optBoolean("charging",false)).commit();
                Diagnostics.event("preferences.saved","album_count="+albums.size()+" auto="+data.optBoolean("auto")+" wifi="+data.optBoolean("wifiOnly",true)+" charging="+data.optBoolean("charging"));
                if(data.optBoolean("auto"))settings.pause(false);BackupScheduler.configure(this);return snapshot();
            }
            case "scan": {Settings.Session session=requireSession(true);ensureDestination(session);long added=MediaCatalog.scan(this,session,new RunControl(90000));settings.message("本轮扫描新增 "+added+" 项；未上传项保留在本机队列");return snapshot();}
            case "start": requireSession(true);settings.pause(false);ui(this::startManual);return new JSONObject();
            case "pause": requireSession(true);settings.pause(true);BackupEngine.stopActive();stopService(new Intent(this,BackupService.class));return snapshot();
            case "retry": {Settings.Session session=requireSession(true);TaskStore.get(this).retry(session.scope);return snapshot();}
            case "overview": {Settings.Session session=requireSession(false);return authenticatedGet(session,"overview");}
            case "assetMetadata": {
                Settings.Session session=requireSession(false);String id=data.getString("id");if(!Protocol.assetId(id)||!assets.containsKey(id))throw new IllegalArgumentException("请刷新文件列表后重试");
                return authenticatedGet(session,"assets/"+id+"/metadata");
            }
            case "assetRename": {
                Settings.Session session=requireSession(true);String id=data.getString("id");if(!Protocol.assetId(id))throw new IllegalArgumentException("文件标识无效");
                JSONObject result;try{result=api(session).post("assets/"+id+"/rename",new JSONObject().put("name",data.getString("name")).put("old_name",data.getString("old_name")),new RunControl(35000));}catch(Exception error){checkAuthentication(session,error);throw error;}
                if(!settings.current(session))throw new SecurityException("登录身份已改变");assets.put(id,result);return result;
            }
            case "assets": {
                Settings.Session session=requireSession(false);String cursor=data.optString("before","");if(cursor.length()>100)throw new IllegalArgumentException("分页游标无效");
                String query="before="+java.net.URLEncoder.encode(cursor,"UTF-8")+"&limit=48&type="+java.net.URLEncoder.encode(data.optString("type","all"),"UTF-8")+"&q="+java.net.URLEncoder.encode(data.optString("search",""),"UTF-8")+"&order="+java.net.URLEncoder.encode(data.optString("order","created"),"UTF-8");
                for(String key:new String[]{"from","to","album_id"})if(data.has(key))query+="&"+key+"="+java.net.URLEncoder.encode(data.getString(key),"UTF-8");
                String path="assets?"+query;
                JSONObject result=authenticatedGet(session,path);for(String key:new String[]{"from","to"})if(data.has(key)&&!result.optString(key).equals(data.getString(key)))throw new java.io.IOException("按天选择需要升级电脑端至1.0.2");if(data.has("album_id")&&!result.optString("membership_album_id").equals(data.getString("album_id")))throw new java.io.IOException("图集标记需要升级电脑端至1.0.2");if(data.optString("order").equals("timeline")&&!result.optString("order").equals("timeline"))throw new java.io.IOException("按文件时间需升级电脑服务至0.3；可先选择入库时间");if(!settings.current(session))throw new SecurityException("登录身份已改变");
                if(!data.optBoolean("selectionOnly")){if(data.optBoolean("pageMode")||assets.size()>200)assets.clear();JSONArray items=result.getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);assets.put(item.getString("id"),item);}}return result;
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
        runOnUiThread(()->{if(!visible||isFinishing()){settings.message("请回到应用前台操作");Diagnostics.event("ui.deferred","activity not visible");return;}try{action.run();}catch(RuntimeException error){String incident=Diagnostics.error("ui.action",error);settings.message(safeError(error)+" ["+incident+"]");}});
    }
    private void pick(int request) {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        if(request==FILES)intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,request);
    }
    private void requestMedia() {
        if(requestingMedia)return;
        boolean rationale=false;String[] permissions=MediaPermissions.requestList(Build.VERSION.SDK_INT);
        for(String permission:permissions)rationale|=shouldShowRequestPermissionRationale(permission);
        boolean asked=getSharedPreferences("media-permissions",0).getBoolean("asked",false);
        if(MediaPermissions.useSettings(MediaPermissions.fullImages(this),MediaPermissions.fullVideos(this),MediaPermissions.selected(this),asked,rationale)){openMediaSettings();return;}
        requestingMedia=true;getSharedPreferences("media-permissions",0).edit().putBoolean("asked",true).apply();
        Diagnostics.event("media.permission_request","sdk="+Build.VERSION.SDK_INT+" selected="+MediaPermissions.selected(this));
        requestPermissions(permissions,MEDIA_PERMISSION);
    }
    private void openMediaSettings(){Diagnostics.event("media.permission_settings","opened");startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    private void mediaChanged(){mediaRevision.incrementAndGet();if(web!=null)web.evaluateJavascript("window.dispatchEvent(new Event('homenas-media-changed'));",null);}
    private void startManual() {
        if(Build.VERSION.SDK_INT>=33&&!MediaCatalog.granted(this,Manifest.permission.POST_NOTIFICATIONS)){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);return;}
        ContextCompat.startForegroundService(this,new Intent(this,BackupService.class));
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        StringBuilder states=new StringBuilder("request=").append(request);for(int i=0;i<permissions.length;i++)states.append(' ').append(permissions[i]).append('=').append(i<grants.length?grants[i]:-1);Diagnostics.event("permission.result",states.toString());
        if(request==MEDIA_PERMISSION||request==METADATA_PERMISSION){requestingMedia=false;settings.message(MediaCatalog.access(this));mediaChanged();}
        if(request==NOTIFICATION_PERMISSION)try{ContextCompat.startForegroundService(this,new Intent(this,BackupService.class));}catch(RuntimeException error){settings.message(safeError(error));}
    }
    @Override protected void onActivityResult(int request,int result,Intent intent) {
        super.onActivityResult(request,result,intent);
        Diagnostics.event("picker.result","request="+request+" result="+result);
        if(request==EXPORT_LOGS){if(result==RESULT_OK&&intent!=null&&intent.getData()!=null){Uri destination=intent.getData();if(!"content".equals(destination.getScheme())){settings.message("请选择系统文档保存位置");return;}io.execute(()->{try(OutputStream output=getContentResolver().openOutputStream(destination,"wt")){if(output==null)throw new java.io.IOException("无法写入日志文件");Diagnostics.export(this,output);settings.message("诊断日志ZIP已导出，可随问题描述发送");}catch(Exception error){settings.message("日志导出失败："+safeError(error)+" ["+Diagnostics.error("logs.export",error)+"]");}});}return;}
        if(request==SAVE) {
            JSONObject item=pendingDownload;Settings.Session session=downloadSession;pendingDownload=null;downloadSession=null;
            if(result==RESULT_OK&&item==null){settings.message("保存操作被系统中断，请重新选择要下载的文件");return;}
            if(result==RESULT_OK&&intent!=null&&intent.getData()!=null&&item!=null) {Uri destination=intent.getData();if(!"content".equals(destination.getScheme())){settings.message("仅支持系统文档提供器的保存位置");return;}io.execute(()->download(item,session,destination));}return;
        }
        final Settings.Session selectedFileSession=fileSession;final String selectedFileFolder=fileFolder,selectedFileFolderName=fileFolderName;
        if(request==FILES){fileSession=null;fileFolder=null;fileFolderName=null;}
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
                    Settings.Session session=requireSession(true);if(selectedFileSession==null||!settings.current(selectedFileSession)||!session.scope.equals(selectedFileSession.scope)||selectedFileFolder==null)throw new SecurityException("选择操作已中断或账号已改变，请重新选择文件");int count=0;ClipData clips=intent.getClipData();int total=clips==null?1:clips.getItemCount();
                    android.database.sqlite.SQLiteDatabase queueDb=TaskStore.get(this).getWritableDatabase();queueDb.beginTransaction();
                    try{for(int i=0;i<Math.min(total,5000);i++) {
                        Uri uri=clips==null?intent.getData():clips.getItemAt(i).getUri();if(uri==null||!"content".equals(uri.getScheme()))continue;
                        getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        uri=MediaCatalog.originalIfAllowed(this,uri);
                        MediaCatalog.Info info=MediaCatalog.describe(getContentResolver(),uri);
                        String source=Protocol.key("chosen:"+uri+":"+info.size+":"+(info.modified==0?UUID.randomUUID():info.modified));
                        if(TaskStore.get(this).enqueue(session.scope,source,uri.toString(),info.name,"手动选择",info.size,info.modified,selectedFileFolder,selectedFileFolderName)!=-1)count++;
                        if((i+1)%100==0){queueDb.setTransactionSuccessful();queueDb.endTransaction();queueDb.beginTransaction();}
                    }queueDb.setTransactionSuccessful();}finally{queueDb.endTransaction();}
                    settings.message("已加入 "+count+" 项，请点击立即备份"+(total>5000?"；单次最多5000项，大相册可使用相册扫描":""));
                    Diagnostics.event("picker.enqueued","selected="+total+" added="+count);
                }
            }catch(Exception error){settings.message("选择未完成："+safeError(error)+" ["+Diagnostics.error("picker.read",error)+"]");}
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
        }catch(Exception error){checkAuthentication(session,error);settings.message("下载未完成（所选位置可能保留部分文件）："+safeError(error)+" ["+Diagnostics.error("download",error)+"]");}
        finally{control.detach();}
    }
    @Override protected void onResume(){super.onResume();visible=true;Diagnostics.event("activity.resume",Diagnostics.network(this));if(web!=null){web.onResume();mediaChanged();}}
    @Override protected void onPause(){visible=false;Diagnostics.event("activity.pause","");if(web!=null)web.onPause();super.onPause();}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){if(web!=null){Uri page=Uri.parse(web.getUrl()==null?"":web.getUrl());if(local(page)&&!page.getPath().endsWith("/index.html")){if(web.canGoBack())web.goBack();else web.loadUrl(ORIGIN+"/assets/www/index.html");return;}}if(web!=null&&local(Uri.parse(web.getUrl()==null?"":web.getUrl())))web.evaluateJavascript("typeof window.homeNasBack==='function' ? (window.homeNasBack(),true) : false",handled->{if(!"true".equals(handled)&&!isFinishing())finish();});else super.onBackPressed();}
    @Override protected void onDestroy(){enqueueJob.cancel();if(previews!=null)previews.closeAll();if(web!=null){WebViewCompat.removeWebMessageListener(web,"HomeNasNative");web.destroy();web=null;}io.shutdownNow();super.onDestroy();}
}
