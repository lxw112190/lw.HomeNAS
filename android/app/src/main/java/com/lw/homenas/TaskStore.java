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
    private TaskStore(Context context) { super(context,"backup-queue.db",null,1);setWriteAheadLoggingEnabled(true); }
    public void onConfigure(SQLiteDatabase db) { db.execSQL("PRAGMA synchronous=FULL"); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY,scope TEXT NOT NULL,source TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,album TEXT NOT NULL,size INTEGER NOT NULL,modified INTEGER NOT NULL,client TEXT NOT NULL,sha TEXT NOT NULL DEFAULT '',remote TEXT NOT NULL DEFAULT '',offset INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'QUEUED',error TEXT NOT NULL DEFAULT '',UNIQUE(scope,source))");
        db.execSQL("CREATE INDEX task_scope_state ON tasks(scope,state,id)");
    }
    public void onUpgrade(SQLiteDatabase db,int old,int next) { throw new IllegalStateException("Unsupported queue schema"); }
    static final class Task {
        final long id,size,modified,offset;
        final String scope,uri,name,album,client,sha,remote,state;
        Task(Cursor c) { id=c.getLong(0);scope=c.getString(1);uri=c.getString(2);name=c.getString(3);album=c.getString(4);size=c.getLong(5);modified=c.getLong(6);client=c.getString(7);sha=c.getString(8);remote=c.getString(9);offset=c.getLong(10);state=c.getString(11); }
    }
    long enqueue(String scope,String source,String uri,String name,String album,long size,long modified) {
        ContentValues v=new ContentValues();v.put("scope",scope);v.put("source",source);v.put("uri",uri);v.put("name",name);v.put("album",album);v.put("size",size);v.put("modified",modified);v.put("client",UUID.randomUUID().toString());
        return getWritableDatabase().insertWithOnConflict("tasks",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    Task next(String scope) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,scope,uri,name,album,size,modified,client,sha,remote,offset,state FROM tasks WHERE scope=? AND state IN ('QUEUED','HASHING','UPLOADING','VERIFYING','COMMITTING') ORDER BY id LIMIT 1",new String[]{scope})) { return c.moveToFirst()?new Task(c):null; }
    }
    void state(long id,String state,String message) { ContentValues v=new ContentValues();v.put("state",state);v.put("error",message);getWritableDatabase().update("tasks",v,"id=?",new String[]{Long.toString(id)}); }
    void hashed(long id,long size,String sha) { ContentValues v=new ContentValues();v.put("size",size);v.put("sha",sha);v.put("state","UPLOADING");getWritableDatabase().update("tasks",v,"id=?",new String[]{Long.toString(id)}); }
    void confirmed(long id,JSONObject remote) throws Exception {
        ContentValues v=new ContentValues();v.put("remote",remote.getString("id"));v.put("offset",remote.getString("offset"));v.put("state",remote.getString("state"));v.put("error",remote.optString("error",""));getWritableDatabase().update("tasks",v,"id=?",new String[]{Long.toString(id)});
    }
    void retry(String scope) {
        // Explicit user retry uses a new idempotency key; an ERROR server task is terminal.
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try(Cursor c=db.rawQuery("SELECT id FROM tasks WHERE scope=? AND state IN ('ERROR','SOURCE_MISSING')",new String[]{scope})) {
            while(c.moveToNext()) {ContentValues v=new ContentValues();v.put("client",UUID.randomUUID().toString());v.put("state","QUEUED");v.put("error","");v.put("remote","");v.put("sha","");v.put("offset",0);db.update("tasks",v,"id=?",new String[]{c.getString(0)});}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    JSONObject summary(String scope) throws Exception {
        JSONArray items=new JSONArray();JSONObject counts=new JSONObject();SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery("SELECT state,COUNT(*) FROM tasks WHERE scope=? GROUP BY state",new String[]{scope})) {while(c.moveToNext())counts.put(c.getString(0),c.getLong(1));}
        try(Cursor c=db.rawQuery("SELECT name,size,offset,state,error FROM tasks WHERE scope=? ORDER BY CASE WHEN state='READY' THEN 1 ELSE 0 END,id DESC LIMIT 100",new String[]{scope})) {
            while(c.moveToNext())items.put(new JSONObject().put("name",c.getString(0)).put("size",Long.toString(c.getLong(1))).put("offset",Long.toString(c.getLong(2))).put("state",c.getString(3)).put("error",c.getString(4)));
        }
        return new JSONObject().put("counts",counts).put("items",items);
    }
}
