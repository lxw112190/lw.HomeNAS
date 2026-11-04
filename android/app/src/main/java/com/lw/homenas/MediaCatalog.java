package com.lw.homenas;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class MediaCatalog {
    static final class Info {
        final String name;final long size,modified;
        Info(String name,long size,long modified){this.name=name;this.size=size;this.modified=modified;}
    }
    static Info describe(ContentResolver resolver,Uri uri) throws Exception {
        if(!"content".equals(uri.getScheme()))throw new SecurityException("只接受系统授权的content文件");
        try(Cursor c=resolver.query(uri,null,null,null,null)) {
            if(c==null||!c.moveToFirst())throw new java.io.FileNotFoundException("文件已移除或授权不可用");
            int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=c.getColumnIndex(OpenableColumns.SIZE),m=c.getColumnIndex("last_modified");
            boolean seconds=false;if(m<0){m=c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);seconds=true;}
            String name=Protocol.fileName(n<0?null:c.getString(n));
            long modified=m<0||c.isNull(m)?0:c.getLong(m)*(seconds?1000L:1L);
            return new Info(name,s<0||c.isNull(s)?-1:c.getLong(s),modified);
        }
    }
    static boolean granted(Context context,String permission) {return context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}
    static Uri originalIfAllowed(Context context,Uri uri) {
        return "media".equals(uri.getAuthority())&&granted(context,Manifest.permission.ACCESS_MEDIA_LOCATION)?MediaStore.setRequireOriginal(uri):uri;
    }
    static boolean canRead(Context context,boolean video) {
        if(Build.VERSION.SDK_INT<33)return granted(context,Manifest.permission.READ_EXTERNAL_STORAGE);
        return granted(context,video?Manifest.permission.READ_MEDIA_VIDEO:Manifest.permission.READ_MEDIA_IMAGES)
            ||(Build.VERSION.SDK_INT>=34&&granted(context,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED));
    }
    static String access(Context context) {
        try{return MediaPermissions.state(context).getString("label");}catch(Exception ignored){return "请检查系统照片与视频权限";}
    }
    interface Row {void accept(String key,String album,Uri uri,String name,long size,long modified) throws Exception;}
    private static void visit(Context context,RunControl control,Row row) throws Exception {
        for(boolean video:new boolean[]{false,true}) {
            if(!canRead(context,video))continue;
            Uri base=video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection={"_id","_display_name","_size","date_modified","bucket_id","bucket_display_name","volume_name"};
            try(Cursor c=context.getContentResolver().query(base,projection,"is_pending=0",null,"_id ASC")) {
                if(c==null)continue;
                while(c.moveToNext()) {
                    control.check();String volume=c.getString(6),bucket=c.getString(4);String key=(video?"video:":"image:")+volume+":"+bucket;
                    Uri specific=video?MediaStore.Video.Media.getContentUri(volume):MediaStore.Images.Media.getContentUri(volume);
                    String name=Protocol.fileName(c.getString(1));
                    String album=c.getString(5);if(album==null||album.isEmpty())album="未命名相册";
                    if(album.length()>64)album=album.substring(0,64);
                    row.accept(key,album,originalIfAllowed(context,ContentUris.withAppendedId(specific,c.getLong(0))),name,c.getLong(2),c.getLong(3)*1000L);
                }
            }
        }
    }
    static JSONArray albums(Context context,RunControl control) throws Exception {
        Map<String,JSONObject> albums=new LinkedHashMap<>();Set<String> selected=new Settings(context).albums();
        visit(context,control,(key,album,uri,name,size,modified)->{
            if(!albums.containsKey(key))albums.put(key,new JSONObject().put("key",key).put("name",album+(key.startsWith("video:")?" · 视频":" · 照片")).put("count",0).put("selected",selected.contains(key)));
            JSONObject info=albums.get(key);info.put("count",info.getInt("count")+1);
        });
        return new JSONArray(albums.values());
    }
    static long scan(Context context,Settings.Session session,RunControl control) throws Exception {
        Set<String> selected=new Settings(context).albums();if(selected.isEmpty())return 0;
        TaskStore tasks=TaskStore.get(context);long[] added={0};int[] batch={0};
        android.database.sqlite.SQLiteDatabase db=tasks.getWritableDatabase();db.beginTransaction();
        try{visit(context,control,(key,album,uri,name,size,modified)->{
            if(selected.contains(key)&&size>0) {
                String source=Protocol.key(uri+":"+size+":"+modified);
                if(tasks.enqueue(session.scope,source,uri.toString(),name,album,size,modified)!=-1)added[0]++;
                if(++batch[0]%100==0){db.setTransactionSuccessful();db.endTransaction();db.beginTransaction();}
            }
        });db.setTransactionSuccessful();}finally{db.endTransaction();}return added[0];
    }
}
