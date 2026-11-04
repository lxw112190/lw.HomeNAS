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
    void message(String value) { prefs.edit().putString("message",value).apply(); }
    JSONObject publicState() throws Exception {
        JSONObject result=new JSONObject().put("endpoint",prefs.getString("endpoint","")).put("allowHttp",prefs.getBoolean("http",false))
            .put("auto",prefs.getBoolean("auto",false)).put("wifiOnly",prefs.getBoolean("wifi",true)).put("charging",prefs.getBoolean("charging",false))
            .put("paused",paused()).put("message",prefs.getString("message","欢迎使用家庭备份"));
        result.put("lastUsername",new JSONObject(prefs.getString("me","{}")).optString("username","admin"));
        try { Session s=session();if(s!=null)result.put("user",s.user).put("role",s.role).put("canUpload",s.canUpload).put("scope",s.scope); }
        catch(Exception error) { clear();result.put("message","本机凭据无法解密，请重新登录"); }
        return result;
    }
}
