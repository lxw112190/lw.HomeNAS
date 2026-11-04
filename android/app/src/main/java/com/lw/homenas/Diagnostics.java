package com.lw.homenas;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Local, bounded diagnostics. Never pass request bodies, form values or media names to this logger. */
final class Diagnostics {
    static final long LIMIT=2*1024*1024;
    static final int RETAIN=4;
    private static final Object LOCK=new Object();
    private static final AtomicLong sequence=new AtomicLong();
    private static final String SESSION=UUID.randomUUID().toString().substring(0,8);
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX",Locale.ROOT);
    private static final java.util.regex.Pattern[] FILTERS={
        java.util.regex.Pattern.compile("(?s)-----BEGIN [^-]*KEY-----.*?-----END [^-]*KEY-----"),
        java.util.regex.Pattern.compile("(?im)\\b(authorization|cookie|set-cookie)\\s*:\\s*[^\\r\\n]+"),
        java.util.regex.Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*"),
        java.util.regex.Pattern.compile("(?i)([\"']?(?:password|old_password|new_password|token|api[_-]?key|secret|authorization|cookie|set[_-]cookie)[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;}]+)"),
        java.util.regex.Pattern.compile("(?i)\\b[a-f0-9]{64,}\\b"),
        java.util.regex.Pattern.compile("(?i)https?://[^\\s\"'<>]+"),
        java.util.regex.Pattern.compile("(?i)(?:content|file)://[^\\s\"'<>]+"),
        java.util.regex.Pattern.compile("/(?:storage|sdcard|data|mnt)/[^\\s\"'<>]+")
    };
    private static final String[] REPLACEMENTS={"[KEY OMITTED]","$1: [REDACTED]","Bearer [REDACTED]","$1[REDACTED]","[DIGEST/TOKEN OMITTED]","[URL OMITTED]","[URI OMITTED]","[PATH OMITTED]"};
    private static Context application;
    private static File directory;
    private Diagnostics() {}

    static void initialize(Context context) {
        synchronized(LOCK) {
            Context app=context.getApplicationContext();if(app==null)app=context;File next=new File(app.getFilesDir(),"logs");
            if(next.equals(directory))return;
            application=app;directory=next;
            if(!directory.isDirectory()&&!directory.mkdirs())return;
        }
        event("app.start","version="+BuildConfig.VERSION_NAME+" code="+BuildConfig.VERSION_CODE+" commit="+BuildConfig.BUILD_REF+" ci="+BuildConfig.CI_RUN);
        try{event("device",device().toString());}catch(Exception error){error("device.snapshot",error);}
        event("network",network(context));
    }
    static String session(){return SESSION;}
    static String clean(String input) {
        if(input==null)return "";
        String value=input;for(int i=0;i<FILTERS.length;i++)value=FILTERS[i].matcher(value).replaceAll(REPLACEMENTS[i]);
        return value;
    }
    static void event(String name,String detail) {write("INFO",name,detail);}
    static String error(String name,Throwable error) {
        String incident="E"+SESSION+"-"+sequence.incrementAndGet();StringBuilder text=new StringBuilder("incident=").append(incident);
        Throwable current=error;
        for(int causes=0;current!=null&&causes<6;causes++,current=current.getCause()) {
            text.append("\n").append(current.getClass().getName());
            // JSON messages may contain entire parsed bodies. HTTP errors can contain arbitrary server text.
            if(!(current instanceof org.json.JSONException)&&!(current instanceof NativeApi.Failure)&&!(current instanceof java.io.FileNotFoundException)&&!(current instanceof SecurityException))text.append(": ").append(clean(current.getMessage()));
            StackTraceElement[] stack=current.getStackTrace();for(int i=0;i<Math.min(stack.length,28);i++)text.append("\n  at ").append(stack[i]);
        }
        write("ERROR",name,text.toString());return incident;
    }
    private static void write(String level,String name,String detail) {
        try {
            String safe=clean(detail);if(safe.length()>12000)safe=safe.substring(0,12000)+" [TRUNCATED]";
            String line=TIME.format(OffsetDateTime.now())+" "+level+" session="+SESSION+" thread="+Thread.currentThread().getName()+" "+name+" "+safe+"\n";
            byte[] bytes=line.getBytes(StandardCharsets.UTF_8);
            synchronized(LOCK) {
                if(directory==null)return;
                if(!directory.isDirectory()&&!directory.mkdirs())return;
                File active=new File(directory,"runtime.log");
                if(active.length()+bytes.length>LIMIT) {
                    File oldest=new File(directory,"runtime."+RETAIN+".log");if(oldest.exists()&&!oldest.delete())return;
                    for(int i=RETAIN-1;i>=1;i--){File prior=new File(directory,"runtime."+i+".log");if(prior.exists()&&!prior.renameTo(new File(directory,"runtime."+(i+1)+".log")))return;}
                    if(active.exists()&&!active.renameTo(new File(directory,"runtime.1.log")))return;
                }
                try(FileOutputStream output=new FileOutputStream(active,true)){output.write(bytes);output.flush();}
            }
            android.util.Log.println(level.equals("ERROR")?android.util.Log.ERROR:android.util.Log.INFO,"lw.HomeNAS",line);
        }catch(Throwable ignored){/* Logging failure must not fail a backup or recursively crash the process. */}
    }
    /** Endpoint logging is intentional for LAN diagnosis, but userinfo/query/path never enter logs. */
    static String server(String endpoint) {
        try{java.net.URI uri=new java.net.URI(endpoint);return uri.getScheme()+" host="+uri.getHost()+" port="+uri.getPort();}
        catch(Exception ignored){return "invalid endpoint";}
    }
    static String network(Context context) {
        try {
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities n=cm.getNetworkCapabilities(cm.getActiveNetwork());if(n==null)return "connected=false";
            return "connected=true wifi="+n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)+" cellular="+n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                +" ethernet="+n.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)+" vpn="+n.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                +" metered="+cm.isActiveNetworkMetered()+" validated="+n.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }catch(Exception error){return "unavailable="+error.getClass().getSimpleName();}
    }
    private static JSONObject device() throws Exception {
        JSONObject data=new JSONObject().put("version",BuildConfig.VERSION_NAME).put("version_code",BuildConfig.VERSION_CODE).put("commit",BuildConfig.BUILD_REF).put("ci_run",BuildConfig.CI_RUN)
            .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android_release",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("abis",Arrays.toString(Build.SUPPORTED_ABIS));
        try{android.content.pm.PackageInfo info=WebView.getCurrentWebViewPackage();if(info!=null)data.put("webview",info.packageName+" "+info.versionName);}catch(Exception ignored){}
        return data;
    }
    private static JSONObject summary(Context context) throws Exception {
        Settings settings=new Settings(context);JSONObject data=device().put("resource_version",new ClientMaintenance(context).version()).put("session",SESSION).put("exported_at",TIME.format(OffsetDateTime.now())).put("network",network(context));
        data.put("server",Diagnostics.server(settings.prefs.getString("draft_endpoint",settings.prefs.getString("endpoint",""))))
            .put("credential_present",settings.prefs.contains("token")).put("auto",settings.prefs.getBoolean("auto",false)).put("paused",settings.paused())
            .put("wifi_only",settings.prefs.getBoolean("wifi",true)).put("charging",settings.prefs.getBoolean("charging",false)).put("selected_album_count",settings.albums().size())
            .put("usable_app_bytes",context.getFilesDir().getUsableSpace()).put("certificate_configured",!settings.prefs.getString("pem","").isEmpty());
        JSONObject permissions=new JSONObject();for(String permission:new String[]{android.Manifest.permission.READ_MEDIA_IMAGES,android.Manifest.permission.READ_MEDIA_VIDEO,android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,android.Manifest.permission.READ_EXTERNAL_STORAGE,android.Manifest.permission.ACCESS_MEDIA_LOCATION,android.Manifest.permission.POST_NOTIFICATIONS})permissions.put(permission,MediaCatalog.granted(context,permission));
        data.put("permissions",permissions);
        try{JSONObject me=new JSONObject(settings.prefs.getString("me","{}"));String scope=settings.prefs.getString("server_id","")+":"+me.optString("user_id");data.put("role",me.optString("role")).put("queue_counts",TaskStore.get(context).counts(scope));}
        catch(Exception error){data.put("queue_summary_error",error.getClass().getSimpleName());}
        try{android.os.PowerManager power=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);android.os.BatteryManager battery=(android.os.BatteryManager)context.getSystemService(Context.BATTERY_SERVICE);android.app.ActivityManager activity=(android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            data.put("power_save",power.isPowerSaveMode()).put("device_idle",power.isDeviceIdleMode()).put("battery_percent",battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("battery_charging",battery.isCharging()).put("background_restricted",activity.isBackgroundRestricted());
        }catch(Exception error){data.put("power_snapshot_error",error.getClass().getSimpleName());}return data;
    }
    static JSONObject view(Context context) throws Exception {
        initialize(context);String tail="";
        synchronized(LOCK){File file=new File(directory,"runtime.log");if(file.isFile())try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
            long start=Math.max(0,input.length()-32768);input.seek(start);if(start>0)input.readLine();byte[] bytes=new byte[(int)(input.length()-input.getFilePointer())];input.readFully(bytes);tail=new String(bytes,StandardCharsets.UTF_8);
        }}
        return new JSONObject().put("session",SESSION).put("tail",tail).put("summary",summary(context)).put("limit_mb",10);
    }
    static void export(Context context,OutputStream destination) throws Exception {
        initialize(context);event("logs.export","snapshot requested");
        File temporary=new File(context.getCacheDir(),"diagnostics-"+UUID.randomUUID());if(!temporary.mkdirs())throw new IOException("无法准备日志快照");
        try {
            // Snapshot under a short local lock, then release it before slow SAF/cloud writes.
            synchronized(LOCK){for(int i=0;i<=RETAIN;i++){File source=new File(directory,i==0?"runtime.log":"runtime."+i+".log");if(source.isFile())Files.copy(source.toPath(),new File(temporary,source.getName()).toPath());}}
            try(ZipOutputStream zip=new ZipOutputStream(destination)) {
                zip.putNextEntry(new ZipEntry("diagnostics.json"));zip.write(summary(context).toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
                File[] files=temporary.listFiles();if(files!=null)for(File file:files){zip.putNextEntry(new ZipEntry(file.getName()));Files.copy(file.toPath(),zip);zip.closeEntry();}
                zip.finish();
            }
            event("logs.export","completed");
        }finally{File[] files=temporary.listFiles();if(files!=null)for(File file:files)if(!file.delete())file.deleteOnExit();if(!temporary.delete())temporary.deleteOnExit();}
    }
}
