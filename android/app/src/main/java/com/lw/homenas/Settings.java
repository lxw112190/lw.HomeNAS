package com.lw.homenas;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Settings {
    private static final Object LOCK = new Object();
    final SharedPreferences prefs;
    Settings(Context context) { prefs = context.getSharedPreferences("nas-settings", Context.MODE_PRIVATE); }
    static final class Session {
        final String endpoint, pem, token, scope, user, role, serverId, userId;
        final boolean allowHttp, canUpload;
        final long revision;
        Session(String endpoint, String pem, String token, String serverId, JSONObject me, boolean allowHttp, long revision) {
            this.endpoint=endpoint;this.pem=pem;this.token=token;this.serverId=serverId;
            userId=me.optString("user_id");user=me.optString("username");role=me.optString("role");
            canUpload=me.optBoolean("can_upload");scope=serverId+":"+userId;this.allowHttp=allowHttp;this.revision=revision;
        }
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if (!store.containsAlias("nas-session-v1")) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("nas-session-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey)store.getKey("nas-session-v1",null);
    }
    Session session() throws Exception {
        synchronized (LOCK) {
            if (!prefs.contains("token")) return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
            String endpoint=prefs.getString("endpoint","");cipher.updateAAD(endpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String token=new String(cipher.doFinal(Base64.decode(prefs.getString("token",""),Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
            return new Session(endpoint,prefs.getString("pem",""),token,prefs.getString("server_id",""),new JSONObject(prefs.getString("me","{}")),prefs.getBoolean("http",false),prefs.getLong("revision",0));
        }
    }
    void save(String endpoint, String pem, boolean allowHttp, String serverId, JSONObject me, String token) throws Exception {
        synchronized (LOCK) {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(endpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String encrypted=Base64.encodeToString(cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
            SharedPreferences.Editor edit=prefs.edit().putString("endpoint",endpoint).putString("pem",pem).putBoolean("http",allowHttp)
                .putString("server_id",serverId).putString("me",me.toString()).putString("token",encrypted).putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putLong("revision",prefs.getLong("revision",0)+1).putBoolean("paused",true).putBoolean("auto",false);
            String oldScope=prefs.getString("server_id","")+":"+new JSONObject(prefs.getString("me","{}")).optString("user_id");
            if (!(serverId+":"+me.optString("user_id")).equals(oldScope)) edit.remove("albums");
            if(!edit.commit())throw new java.io.IOException("无法保存登录状态");
        }
    }
    boolean current(Session session) { return prefs.getLong("revision",0)==session.revision && prefs.contains("token"); }
    boolean invalidate(Session session) { synchronized (LOCK) { if (!current(session))return false;clear();return true; } }
    void clear() { synchronized (LOCK) { prefs.edit().remove("token").remove("iv").putBoolean("auto",false).putBoolean("paused",true).putLong("revision",prefs.getLong("revision",0)+1).commit(); } }
    boolean paused() { return prefs.getBoolean("paused",true); }
    void pause(boolean value) { prefs.edit().putBoolean("paused",value).commit(); }
    Set<String> albums() { return new HashSet<>(prefs.getStringSet("albums",Collections.emptySet())); }
    String folder(String scope){return prefs.getString("backup_folder_"+Protocol.key(scope),"default");}
    String folderName(String scope){return prefs.getString("backup_folder_name_"+Protocol.key(scope),"默认文件夹");}
    void folder(String scope,String id,String name){if(!Protocol.folderId(id))throw new IllegalArgumentException("文件夹标识无效");synchronized(LOCK){if(!prefs.edit().putString("backup_folder_"+Protocol.key(scope),id).putString("backup_folder_name_"+Protocol.key(scope),name).commit())throw new IllegalStateException("无法保存目标文件夹");}}
    void fallbackFolder(String scope,String previous){synchronized(LOCK){if(folder(scope).equals(previous))folder(scope,"default","默认文件夹");}}
    void ensureFolder(Session session,NativeApi api,RunControl control) throws Exception {
        String requested=folder(session.scope);JSONObject result;boolean fallback=false;
        try{result=api.get("folders/"+requested,control);}catch(NativeApi.Failure failure){if(failure.status!=404||requested.equals("default"))throw failure;result=api.get("folders/default",control);fallback=true;}
        if(!current(session)||!folder(session.scope).equals(requested))throw new java.io.InterruptedIOException("账号或目标目录已改变，请重试");
        String expected=fallback?"default":requested;if(!expected.equals(result.optString("id")))throw new java.io.IOException("服务器返回的目标目录不一致");if(!result.optBoolean("can_write")){pause(true);throw new SecurityException("目标文件夹不可写，备份已暂停，请重新选择");}
        String name=result.getString("name");if(fallback||!folderName(session.scope).equals(name))folder(session.scope,expected,name);
        if(fallback)message("原目标文件夹已删除，后续备份使用默认文件夹；已排队任务保留");
    }
    void rememberConnection(String endpoint,boolean http) {
        if(!prefs.edit().putString("draft_endpoint",endpoint).putBoolean("draft_http",http).commit())throw new IllegalStateException("无法保存电脑地址");
    }
    void message(String value) { prefs.edit().putString("message",value).apply(); }
    JSONObject publicState() throws Exception {
        JSONObject result=new JSONObject().put("endpoint",prefs.getString("endpoint","")).put("allowHttp",prefs.getBoolean("http",false))
            .put("uploadParallel",Math.max(1,Math.min(3,prefs.getInt("upload_parallel",2)))).put("auto",prefs.getBoolean("auto",false)).put("wifiOnly",prefs.getBoolean("wifi",true)).put("charging",prefs.getBoolean("charging",false))
            .put("paused",paused()).put("message",prefs.getString("message","欢迎使用家庭备份"));
        result.put("lastUsername",new JSONObject(prefs.getString("me","{}")).optString("username","admin"));
        result.put("connectionAddress",prefs.getString("draft_endpoint",prefs.getString("endpoint",Protocol.DEFAULT_CONNECTION)))
            .put("connectionHttp",prefs.getBoolean("draft_http",prefs.getBoolean("http",!prefs.contains("endpoint"))));
        try { Session s=session();if(s!=null)result.put("user",s.user).put("role",s.role).put("canUpload",s.canUpload).put("scope",s.scope).put("backupFolderId",folder(s.scope)).put("backupFolderName",folderName(s.scope)); }
        catch(Exception error) { clear();result.put("message","本机凭据无法解密，请重新登录"); }
        return result;
    }
}
