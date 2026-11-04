package com.lw.homenas;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class TransferProgressTest {
    @Test public void resumeDoesNotCountOldOffsetAsNewUploadAndHashIsNotConfirmation() throws Exception {
        TaskStore store=TaskStore.get(RuntimeEnvironment.getApplication());String scope="progress-test";store.getWritableDatabase().delete("tasks","scope=?",new String[]{scope});store.enqueue(scope,"one","content://fixture/1","sample.mp4","CI",1000,1);TaskStore.Task task=store.next(scope);
        try{TransferProgress.begin(scope);TransferProgress.phase(task,"HASHING",0,1000);TransferProgress.hashed(700,1000);JSONObject hash=TransferProgress.snapshot(scope);assertEquals("0",hash.getString("confirmed"));assertEquals(0,hash.getLong("bytes_per_second"));
            TransferProgress.phase(task,"UPLOADING",800,1000);TransferProgress.acknowledged(900,1000,100,1000000000L);JSONObject upload=TransferProgress.snapshot(scope);assertEquals(100,upload.getLong("bytes_per_second"));assertEquals(1,upload.getLong("eta_seconds"));assertFalse(TransferProgress.snapshot("other-user").has("name"));
            TransferProgress.end();assertFalse(TransferProgress.snapshot(scope).getBoolean("running"));assertEquals(0,TransferProgress.snapshot(scope).getLong("bytes_per_second"));
        }finally{TransferProgress.end();store.getWritableDatabase().delete("tasks","scope=?",new String[]{scope});}
    }
}
