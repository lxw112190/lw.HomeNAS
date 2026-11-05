package com.lw.homenas;

import android.content.Context;
import java.lang.reflect.Field;

/** Test-only lifecycle for the production process singleton and its SQLite/WAL files. */
final class QueueFixture {
    private QueueFixture() {}
    static void reset(Context context) throws Exception {
        synchronized(TaskStore.class) {
            Field singleton=TaskStore.class.getDeclaredField("instance");singleton.setAccessible(true);
            TaskStore previous=(TaskStore)singleton.get(null);
            if(previous!=null)previous.close();
            singleton.set(null,null);
            boolean existed=context.getDatabasePath("backup-queue.db").exists();
            boolean removed=context.deleteDatabase("backup-queue.db");
            if(existed&&!removed)throw new IllegalStateException("Could not reset fixture queue database");
        }
    }
}
