package com.lw.homenas;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class TaskStoreTest {
    @Before public void reset() throws Exception {java.lang.reflect.Field field=TaskStore.class.getDeclaredField("instance");field.setAccessible(true);TaskStore previous=(TaskStore)field.get(null);if(previous!=null)previous.close();field.set(null,null);RuntimeEnvironment.getApplication().deleteDatabase("backup-queue.db");}
    @Test public void destinationsKeepSeparateTasksAndReuseConfirmedDigest() throws Exception {
        TaskStore store=TaskStore.get(RuntimeEnvironment.getApplication());String scope="server:alice",folder="a".repeat(48);
        long first=store.enqueue(scope,"same-source","content://photos/1","fixture.jpg","Camera",100,200);
        store.hashed(first,100,"f".repeat(64));store.state(first,"READY","");
        long second=store.enqueue(scope,"same-source","content://photos/1","fixture.jpg","Camera",100,200,folder,"旅行");assertTrue(second>first);
        TaskStore.Task next=store.next(scope);assertEquals(folder,next.folderId);assertEquals("f".repeat(64),next.sha);
        assertEquals(-1,store.enqueue(scope,"same-source","content://photos/1","fixture.jpg","Camera",100,200,folder,"旅行"));
        try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT folder_id,state FROM tasks WHERE id=?",new String[]{Long.toString(first)})){assertTrue(row.moveToFirst());assertEquals("default",row.getString(0));assertEquals("READY",row.getString(1));}
    }
    @Test public void versionOneQueueMigrationRetainsOffsetAndClientIdentity() throws Exception {
        Context context=RuntimeEnvironment.getApplication();java.io.File file=context.getDatabasePath("backup-queue.db");assertTrue(file.getParentFile().isDirectory()||file.getParentFile().mkdirs());
        try(android.database.sqlite.SQLiteDatabase old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)){
            old.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY,scope TEXT NOT NULL,source TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,album TEXT NOT NULL,size INTEGER NOT NULL,modified INTEGER NOT NULL,client TEXT NOT NULL,sha TEXT NOT NULL DEFAULT '',remote TEXT NOT NULL DEFAULT '',offset INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'QUEUED',error TEXT NOT NULL DEFAULT '',UNIQUE(scope,source))");
            old.execSQL("CREATE INDEX task_scope_state ON tasks(scope,state,id)");old.execSQL("CREATE INDEX task_scope_timeline ON tasks(scope,id DESC)");
            old.execSQL("INSERT INTO tasks(id,scope,source,uri,name,album,size,modified,client,sha,remote,offset,state) VALUES(7,'server:alice','source','content://photos/1','fixture.mp4','Camera',6000000000,1,'unchanged-client','cached-sha','remote-task',4294967296,'UPLOADING')");old.setVersion(1);
        }
        TaskStore store=TaskStore.get(context);TaskStore.Task task=store.next("server:alice");assertEquals(7,task.id);assertEquals(4294967296L,task.offset);assertEquals("unchanged-client",task.client);assertEquals("cached-sha",task.sha);assertEquals("remote-task",task.remote);assertEquals("default",task.folderId);assertEquals(2,store.getReadableDatabase().getVersion());
    }
    @Test public void destinationPreferencesAreScopedByServerAndAccount() {
        Settings settings=new Settings(RuntimeEnvironment.getApplication());settings.folder("server:alice","a".repeat(48),"Alice");assertEquals("default",settings.folder("server:bob"));assertEquals("default",settings.folder("another:alice"));
        settings.fallbackFolder("server:alice","b".repeat(48));assertEquals("a".repeat(48),settings.folder("server:alice"));settings.fallbackFolder("server:alice","a".repeat(48));assertEquals("default",settings.folder("server:alice"));
    }
    @Test public void restartKeepsConfirmedOffsetAndUserScopes() throws Exception {
        Context context=RuntimeEnvironment.getApplication();TaskStore store=TaskStore.get(context);
        long id=store.enqueue("server:alice","source-a","content://photos/1","sample.mp4","Camera",6_000_000_000L,1);
        assertTrue(id>0);assertEquals(-1,store.enqueue("server:alice","source-a","content://photos/1","sample.mp4","Camera",6_000_000_000L,1));
        store.hashed(id,6_000_000_000L,"f".repeat(64));
        JSONObject confirmation=new JSONObject().put("id","1".repeat(48)).put("offset","4294967296").put("state","UPLOADING");
        assertTrue(store.confirmed(id,confirmation));assertFalse(store.confirmed(id,confirmation));
        String client=store.next("server:alice").client;store.close();
        assertEquals(4294967296L,store.next("server:alice").offset);assertEquals(client,store.next("server:alice").client);
        assertNull(store.next("server:bob"));assertEquals(0,store.summary("server:bob").getJSONArray("items").length());
        store.state(id,"READY","");store.retry("server:alice");assertNull(store.next("server:alice"));
        assertEquals(1,store.summary("server:alice").getJSONObject("counts").getInt("READY"));
    }
}
