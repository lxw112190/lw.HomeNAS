package com.lw.homenas;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class TaskStoreTest {
    @Test public void restartKeepsConfirmedOffsetAndUserScopes() throws Exception {
        Context context=RuntimeEnvironment.getApplication();TaskStore store=TaskStore.get(context);
        long id=store.enqueue("server:alice","source-a","content://photos/1","sample.mp4","Camera",6_000_000_000L,1);
        assertTrue(id>0);assertEquals(-1,store.enqueue("server:alice","source-a","content://photos/1","sample.mp4","Camera",6_000_000_000L,1));
        store.hashed(id,6_000_000_000L,"f".repeat(64));
        store.confirmed(id,new JSONObject().put("id","1".repeat(48)).put("offset","4294967296").put("state","UPLOADING"));
        String client=store.next("server:alice").client;store.close();
        assertEquals(4294967296L,store.next("server:alice").offset);assertEquals(client,store.next("server:alice").client);
        assertNull(store.next("server:bob"));assertEquals(0,store.summary("server:bob").getJSONArray("items").length());
        store.state(id,"READY","");store.retry("server:alice");assertNull(store.next("server:alice"));
        assertEquals(1,store.summary("server:alice").getJSONObject("counts").getInt("READY"));
    }
}
