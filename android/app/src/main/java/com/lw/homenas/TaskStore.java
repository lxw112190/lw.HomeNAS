package com.lw.homenas;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

final class TaskStore extends SQLiteOpenHelper {
    private static TaskStore instance;
    static synchronized TaskStore get(Context context) { if(instance==null)instance=new TaskStore(context.getApplicationContext());return instance; }
    private TaskStore(Context context) { super(context,"backup-queue.db",null,2);setWriteAheadLoggingEnabled(true); }
    public void onConfigure(SQLiteDatabase db) { db.execSQL("PRAGMA synchronous=FULL"); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY,scope TEXT NOT NULL,source TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,album TEXT NOT NULL,size INTEGER NOT NULL,modified INTEGER NOT NULL,client TEXT NOT NULL,sha TEXT NOT NULL DEFAULT '',remote TEXT NOT NULL DEFAULT '',offset INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'QUEUED',error TEXT NOT NULL DEFAULT '',folder_id TEXT NOT NULL DEFAULT 'default',folder_name TEXT NOT NULL DEFAULT '默认文件夹',UNIQUE(scope,source,folder_id))");
        db.execSQL("CREATE INDEX task_scope_state ON tasks(scope,state,id)");
    }
    @Override public void onOpen(SQLiteDatabase db){super.onOpen(db);db.execSQL("CREATE INDEX IF NOT EXISTS task_scope_timeline ON tasks(scope,id DESC)");db.execSQL("CREATE INDEX IF NOT EXISTS task_media_match ON tasks(scope,size,modified,id DESC)");}
    public void onUpgrade(SQLiteDatabase db,int old,int next) {
        if(old!=1||next!=2)throw new IllegalStateException("Unsupported queue schema");
        db.execSQL("ALTER TABLE tasks RENAME TO tasks_v1");db.execSQL("DROP INDEX IF EXISTS task_scope_state");db.execSQL("DROP INDEX IF EXISTS task_scope_timeline");
        onCreate(db);
        db.execSQL("INSERT INTO tasks(id,scope,source,uri,name,album,size,modified,client,sha,remote,offset,state,error) SELECT id,scope,source,uri,name,album,size,modified,client,sha,remote,offset,state,error FROM tasks_v1");
        db.execSQL("DROP TABLE tasks_v1");
    }
    static final class Task {
        final long id,size,modified,offset;
        final String scope,uri,name,album,client,sha,remote,state,folderId;
        Task(Cursor c) { id=c.getLong(0);scope=c.getString(1);uri=c.getString(2);name=c.getString(3);album=c.getString(4);size=c.getLong(5);modified=c.getLong(6);client=c.getString(7);sha=c.getString(8);remote=c.getString(9);offset=c.getLong(10);state=c.getString(11);folderId=c.getString(12); }
    }
    long enqueue(String scope,String source,String uri,String name,String album,long size,long modified) {
        return enqueue(scope,source,uri,name,album,size,modified,"default","默认文件夹");
    }
    long enqueue(String scope,String source,String uri,String name,String album,long size,long modified,String folder,String folderName) {
        if(!Protocol.folderId(folder))throw new IllegalArgumentException("文件夹标识无效");
        ContentValues v=new ContentValues();v.put("scope",scope);v.put("source",source);v.put("uri",uri);v.put("name",name);v.put("album",album);v.put("size",size);v.put("modified",modified);v.put("client",UUID.randomUUID().toString());
        v.put("folder_id",folder);v.put("folder_name",folderName);
        if(size>=0&&modified>0)try(Cursor c=getReadableDatabase().rawQuery("SELECT sha FROM tasks WHERE scope=? AND uri=? AND size=? AND modified=? AND state='READY' AND length(sha)=64 ORDER BY id DESC LIMIT 1",new String[]{scope,uri,Long.toString(size),Long.toString(modified)})){if(c.moveToFirst())v.put("sha",c.getString(0));}
        return getWritableDatabase().insertWithOnConflict("tasks",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    Task next(String scope) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,scope,uri,name,album,size,modified,client,sha,remote,offset,state,folder_id FROM tasks WHERE scope=? AND state IN ('QUEUED','HASHING','UPLOADING','VERIFYING','COMMITTING') ORDER BY id LIMIT 1",new String[]{scope})) { return c.moveToFirst()?new Task(c):null; }
    }
    void state(long id,String state,String message) { ContentValues v=new ContentValues();v.put("state",state);v.put("error",message);getWritableDatabase().update("tasks",v,"id=?",new String[]{Long.toString(id)});Diagnostics.event("task.state","task="+id+" state="+state); }
    void hashed(long id,long size,String sha) { ContentValues v=new ContentValues();v.put("size",size);v.put("sha",sha);v.put("state","UPLOADING");getWritableDatabase().update("tasks",v,"id=?",new String[]{Long.toString(id)}); }
    boolean confirmed(long id,JSONObject remote) throws Exception {
        String ident=remote.getString("id"),offset=remote.getString("offset"),state=remote.getString("state"),error=remote.optString("error","");
        ContentValues v=new ContentValues();v.put("remote",ident);v.put("offset",offset);v.put("state",state);v.put("error",error);if(remote.has("folder_name"))v.put("folder_name",remote.optString("folder_name","默认文件夹"));return getWritableDatabase().update("tasks",v,"id=? AND (remote<>? OR offset<>? OR state<>? OR error<>?)",new String[]{Long.toString(id),ident,offset,state,error})>0;
    }
    void retry(String scope) {
        // Explicit user retry uses a new idempotency key; an ERROR server task is terminal.
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try(Cursor c=db.rawQuery("SELECT id FROM tasks WHERE scope=? AND state IN ('ERROR','SOURCE_MISSING')",new String[]{scope})) {
            while(c.moveToNext()) {ContentValues v=new ContentValues();v.put("client",UUID.randomUUID().toString());v.put("state","QUEUED");v.put("error","");v.put("remote","");v.put("sha","");v.put("offset",0);db.update("tasks",v,"id=?",new String[]{c.getString(0)});}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    boolean requeue(long id,String uri) {
        ContentValues value=new ContentValues();value.put("uri",uri);value.put("client",UUID.randomUUID().toString());value.put("remote","");value.put("sha","");value.put("offset",0);value.put("state","QUEUED");value.put("error","");
        return getWritableDatabase().update("tasks",value,"id=? AND state IN ('READY','ERROR','SOURCE_MISSING')",new String[]{Long.toString(id)})>0;
    }
    boolean relink(long id){ContentValues v=new ContentValues();v.put("client",UUID.randomUUID().toString());v.put("remote","");v.put("offset",0);v.put("state","QUEUED");v.put("error","");return getWritableDatabase().update("tasks",v,"id=? AND state='READY'",new String[]{Long.toString(id)})>0;}
    JSONObject summary(String scope) throws Exception {return summary(scope,"","active");}
    JSONObject summary(String scope,String before,String filter) throws Exception {
        SQLiteDatabase db=getReadableDatabase();String where="scope=?";java.util.ArrayList<String> args=new java.util.ArrayList<>();args.add(scope);
        if(filter.equals("active"))where+=" AND state IN ('QUEUED','HASHING','UPLOADING','VERIFYING','COMMITTING')";
        else if(filter.equals("failed"))where+=" AND state IN ('ERROR','SOURCE_MISSING')";
        else if(filter.equals("ready"))where+=" AND state='READY'";
        else if(!filter.equals("all"))throw new IllegalArgumentException("任务筛选无效");
        if(!before.isEmpty()){if(!before.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("任务游标无效");Long.parseLong(before);where+=" AND id<?";args.add(before);}
        JSONArray items=new JSONArray();String next="";
        try(Cursor c=db.rawQuery("SELECT name,size,offset,state,error,id,folder_name FROM tasks WHERE "+where+" ORDER BY id DESC LIMIT 51",args.toArray(new String[0]))){
            while(c.moveToNext()){if(items.length()==50){next=items.getJSONObject(49).getString("diagnostic_id");break;}
                items.put(new JSONObject().put("name",c.getString(0)).put("size",Long.toString(c.getLong(1))).put("offset",Long.toString(c.getLong(2))).put("state",c.getString(3)).put("error",c.getString(4)).put("diagnostic_id",Long.toString(c.getLong(5))).put("folder_name",c.getString(6)));
            }
        }
        return new JSONObject().put("counts",counts(scope)).put("items",items).put("next",next);
    }
    JSONObject counts(String scope) throws Exception {
        JSONObject result=new JSONObject();try(Cursor c=getReadableDatabase().rawQuery("SELECT state,COUNT(*) FROM tasks WHERE scope=? GROUP BY state",new String[]{scope})){while(c.moveToNext())result.put(c.getString(0),c.getLong(1));}return result;
    }
}
