package com.lw.homenas;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class TransferProgressTest {
    @Test public void scanStageIsScopedAndVisibleWithoutInventingTransferredBytes() throws Exception {
        TransferProgress.begin("stage-owner");TransferProgress.stage("stage-owner","SCANNING");
        assertEquals("SCANNING",TransferProgress.snapshot("stage-owner").getString("stage"));
        assertEquals(0,TransferProgress.snapshot("stage-owner").getLong("bytes_per_second"));
        assertFalse(TransferProgress.snapshot("someone-else").has("stage"));
        TransferProgress.stage("someone-else","TRANSFERRING");assertEquals("SCANNING",TransferProgress.snapshot("stage-owner").getString("stage"));
    }
    @Before public void reset() throws Exception {QueueFixture.reset(RuntimeEnvironment.getApplication());}
    @After public void cleanup() throws Exception {TransferProgress.end();TransferProgress.clock=System::nanoTime;QueueFixture.reset(RuntimeEnvironment.getApplication());}
    @Test public void resumeDoesNotCountOldOffsetAsNewUploadAndHashIsNotConfirmation() throws Exception {
        TaskStore store=TaskStore.get(RuntimeEnvironment.getApplication());String scope="progress-test";store.enqueue(scope,"one","content://fixture/1","sample.mp4","CI",1000,1);TaskStore.Task task=store.next(scope);
        java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong();TransferProgress.clock=now::get;
        TransferProgress.begin(scope);TransferProgress.phase(task,"HASHING",0,1000);TransferProgress.hashed(task.id,700,1000);JSONObject hash=TransferProgress.snapshot(scope);assertEquals("0",hash.getString("confirmed"));assertEquals(0,hash.getLong("bytes_per_second"));
        TransferProgress.phase(task,"UPLOADING",800,1000);now.set(1_000_000_000L);TransferProgress.acknowledged(task.id,900,1000,100,1_000_000_000L);JSONObject upload=TransferProgress.snapshot(scope);assertEquals(100,upload.getLong("bytes_per_second"));assertEquals("900",upload.getString("confirmed"));assertFalse(TransferProgress.snapshot("other-user").has("name"));
        TransferProgress.end();assertFalse(TransferProgress.snapshot(scope).getBoolean("running"));assertEquals(0,TransferProgress.snapshot(scope).getLong("bytes_per_second"));
    }
    @Test public void parallelFilesHaveIndependentProgressAndOneAggregateSpeed() throws Exception {
        TaskStore store=TaskStore.get(RuntimeEnvironment.getApplication());String scope="parallel-test";
        store.enqueue(scope,"one","content://fixture/1","first.jpg","CI",1000,1);store.enqueue(scope,"two","content://fixture/2","second.jpg","CI",2000,1,"a".repeat(48),"旅行");
        TaskStore.Task first=store.claim(scope),second=store.claim(scope);java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong();TransferProgress.clock=now::get;TransferProgress.begin(scope);
        try{TransferProgress.phase(first,"UPLOADING",800,1000);TransferProgress.phase(second,"HASHING",0,2000);TransferProgress.hashed(second.id,700,2000);
            now.set(1_000_000_000L);TransferProgress.acknowledged(first.id,900,1000,100,1_000_000_000L);JSONObject snapshot=TransferProgress.snapshot(scope);
            assertEquals(2,snapshot.getJSONArray("items").length());assertEquals("0",snapshot.getJSONArray("items").getJSONObject(1).getString("confirmed"));assertEquals(100,snapshot.getLong("bytes_per_second"));
            assertEquals("default",snapshot.getJSONArray("items").getJSONObject(0).getString("folder_id"));assertEquals("默认文件夹",snapshot.getJSONArray("items").getJSONObject(0).getString("folder_name"));assertEquals("a".repeat(48),snapshot.getJSONArray("items").getJSONObject(1).getString("folder_id"));assertEquals("旅行",snapshot.getJSONArray("items").getJSONObject(1).getString("folder_name"));
            TransferProgress.phase(second,"UPLOADING",0,2000);TransferProgress.acknowledged(second.id,300,2000,300,1_000_000_000L);assertEquals(400,TransferProgress.snapshot(scope).getLong("bytes_per_second"));
            TransferProgress.finished(first.id,true);assertEquals(1,TransferProgress.snapshot(scope).getLong("completed"));assertEquals(1,TransferProgress.snapshot(scope).getJSONArray("items").length());
            now.set(7_000_000_000L);assertEquals(0,TransferProgress.snapshot(scope).getLong("bytes_per_second"));
        }finally{store.release(first.id);store.release(second.id);}
    }
}
