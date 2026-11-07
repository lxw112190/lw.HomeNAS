package com.lw.homenas;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.zip.*;

/** Signed, immutable UI bundles; APKs additionally require the installed signing certificate. */
final class ClientMaintenance {
    private final Context context;
    private final android.content.SharedPreferences prefs;
    ClientMaintenance(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("client-maintenance",0);}
    private File root(){File f=new File(context.getFilesDir(),"ui-bundles");f.mkdirs();return f;}
    synchronized File beginLaunch(){
        // An older downloaded UI must not hide features bundled in a new native protocol.
        if(prefs.getInt("builtinNative",0)<12)prefs.edit().putInt("builtinNative",12).remove("active").remove("previous").remove("staged").putBoolean("unconfirmed",false).commit();
        String active=prefs.getString("active","");
        if(prefs.getBoolean("unconfirmed",false)){active=prefs.getString("previous","");prefs.edit().putString("active",active).remove("staged").putBoolean("unconfirmed",false).commit();}
        String staged=prefs.getString("staged","");if(!staged.isEmpty()){prefs.edit().putString("previous",active).putString("active",staged).remove("staged").commit();active=staged;}
        if(active.isEmpty())return null;
        File folder=new File(root(),active);if(!new File(folder,"index.html").isFile()){prefs.edit().putString("active","").putBoolean("unconfirmed",false).commit();return null;}
        prefs.edit().putBoolean("unconfirmed",true).commit();return folder;
    }
    synchronized void ready(){prefs.edit().putBoolean("unconfirmed",false).commit();String active=prefs.getString("active",""),previous=prefs.getString("previous",""),staged=prefs.getString("staged","");File[] folders=root().listFiles();if(folders!=null)for(File f:folders)if(!f.getName().equals(active)&&!f.getName().equals(previous)&&!f.getName().equals(staged))deleteTree(f);}
    String version(){String value=prefs.getString("active","");return value.isEmpty()?"builtin":value;}
    private NativeApi api() throws Exception {Settings.Session s=new Settings(context).session();if(s==null)throw new IOException("请先登录服务器");return new NativeApi(s);}
    JSONObject check() throws Exception {JSONObject catalog=api().get("client/latest",new RunControl(20000));prefs.edit().putString("catalog",catalog.toString()).apply();return catalog.put("resourceVersion",version()).put("nativeApi",12);}
    private File download(String hash,String ext,long expected) throws Exception {
        if(!hash.matches("[a-f0-9]{64}")||expected<=0||expected>128L*1024*1024)throw new IOException("更新包信息无效");
        File dir=new File(context.getCacheDir(),"updates");dir.mkdirs();File file=new File(dir,hash+ext);boolean complete=false;
        RunControl control=new RunControl(15*60*1000L);
        try(okhttp3.Response response=api().packageFile(hash,ext,control);InputStream input=response.body().byteStream();FileOutputStream out=new FileOutputStream(file)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];long total=0;int n;
            while((n=input.read(bytes))!=-1){control.check();total+=n;if(total>expected)throw new IOException("更新包超过声明大小");out.write(bytes,0,n);digest.update(bytes,0,n);}
            out.getFD().sync();if(total!=expected||!Protocol.hex(digest.digest()).equals(hash))throw new IOException("更新包校验失败");complete=true;return file;
        }finally{control.detach();if(!complete)file.delete();}
    }
    synchronized String stageUi() throws Exception {
        JSONObject envelope=check().optJSONObject("ui");if(envelope==null)throw new IOException("服务器尚未发布资源包");
        byte[] payload=android.util.Base64.decode(envelope.getString("payload"),0);
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(android.util.Base64.decode(ResourceTrust.KEY,0))));verifier.update(payload);
        if(!verifier.verify(android.util.Base64.decode(envelope.getString("signature"),0)))throw new IOException("资源发布签名不匹配");
        JSONObject manifest=new JSONObject(new String(payload,java.nio.charset.StandardCharsets.UTF_8));long revision=manifest.getLong("revision");
        if(manifest.getInt("minNative")>12||revision<=0)throw new IOException("资源需要更新的APK");
        if(manifest.getInt("minNative")<12)throw new IOException("此资源早于当前APK内置界面，请使用适配新版APK的资源");
        if(revision<7)throw new IOException("APK已内置新版界面，请使用r7或更新的界面资源");String name="r"+revision;
        if(name.equals(version()))return "当前已使用此资源版本";
        File archive=download(manifest.getString("sha256"),".zip",manifest.getLong("size"));File temporary=new File(root(),"tmp-"+java.util.UUID.randomUUID());temporary.mkdirs();boolean retained=false;
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))){
            ZipEntry entry;int count=0;long total=0;java.util.HashSet<String> names=new java.util.HashSet<>();
            while((entry=zip.getNextEntry())!=null){String path=entry.getName();if(entry.isDirectory())continue;
                if(++count>200||!names.add(path)||!path.matches("[A-Za-z0-9_./-]+\\.(html|css|js|png|jpg|jpeg|webp|svg|woff2|json)")||path.startsWith("/")||path.contains(".."))throw new IOException("资源文件路径或类型无效");
                File output=new File(temporary,path);if(!output.getCanonicalPath().startsWith(temporary.getCanonicalPath()+File.separator))throw new IOException("资源路径越界");output.getParentFile().mkdirs();
                try(FileOutputStream out=new FileOutputStream(output)){byte[] bytes=new byte[65536];int n;while((n=zip.read(bytes))!=-1){total+=n;if(total>20L*1024*1024)throw new IOException("资源展开超过20MiB");out.write(bytes,0,n);}}
            }
            for(String required:new String[]{"index.html","app.js","style.css"})if(!new File(temporary,required).isFile())throw new IOException("资源缺少必要文件");
            File target=new File(root(),name);if(target.exists())throw new IOException("资源版本已存在，请发布新的revision");if(!temporary.renameTo(target))throw new IOException("无法保存资源版本");retained=true;
            prefs.edit().putString("staged",name).commit();return "资源已下载，下次打开应用生效；加载失败会回退";
        }finally{archive.delete();if(!retained)deleteTree(temporary);}
    }
    private void deleteTree(File file){File[] files=file.listFiles();if(files!=null)for(File child:files)deleteTree(child);file.delete();}
    File prepareApk() throws Exception {
        JSONObject apk=check().optJSONObject("apk");if(apk==null)throw new IOException("APK尚未发布");
        if(apk.getLong("versionCode")<=BuildConfig.VERSION_CODE)throw new IOException("当前已经是最新APK版本");
        File file=download(apk.getString("sha256"),".apk",Long.parseLong(apk.getString("size")));
        android.content.pm.PackageManager pm=context.getPackageManager();android.content.pm.PackageInfo incoming=pm.getPackageArchiveInfo(file.getPath(),android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        android.content.pm.PackageInfo installed=pm.getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        if(incoming==null||!context.getPackageName().equals(incoming.packageName)||incoming.getLongVersionCode()!=apk.getLong("versionCode")||incoming.signingInfo==null||installed.signingInfo==null||!java.util.Arrays.equals(incoming.signingInfo.getApkContentsSigners(),installed.signingInfo.getApkContentsSigners())){file.delete();throw new IOException("APK包名、版本或签名不匹配，不能覆盖升级");}
        return file;
    }
    synchronized String uploadLogs(String description) throws Exception {
        if(description.length()>700)throw new IOException("问题描述最多500字");
        File snapshot=new File(context.getCacheDir(),"pending-diagnostics.zip");
        if(!snapshot.exists())try(FileOutputStream out=new FileOutputStream(snapshot)){Diagnostics.export(context,out);}catch(Exception error){snapshot.delete();throw error;}
        JSONObject report=api().uploadReport(snapshot,new RunControl(180000),description);snapshot.delete();return "日志上传成功，报告编号："+report.getString("id");
    }
}
