package com.lw.homenas;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.InterruptedIOException;

/** Short periodic work only. User initiated bulk uploads use BackupService, not a long WorkManager FGS. */
public final class BackupWorker extends Worker {
    private final RunControl control=new RunControl(2*60*1000L);
    public BackupWorker(@NonNull Context context,@NonNull WorkerParameters parameters){super(context,parameters);}
    @NonNull public Result doWork() {
        Settings settings=new Settings(getApplicationContext());
        Diagnostics.initialize(getApplicationContext());Diagnostics.event("worker.start","attempt="+getRunAttemptCount()+" "+Diagnostics.network(getApplicationContext()));
        if(!settings.prefs.getBoolean("auto",false)||settings.paused())return Result.success();
        try{new BackupEngine(getApplicationContext()).run(control,settings::message);return Result.success();}
        catch(InterruptedIOException error){settings.message("定时批次已暂停或预算用尽，确认进度保留；大文件可点立即备份");return Result.success();}
        catch(Exception error){settings.message("定时备份等待重试："+MainActivity.safeError(error)+" ["+Diagnostics.error("worker.failed",error)+"]");return settings.prefs.getBoolean("auto",false)?Result.retry():Result.success();}
    }
    public void onStopped(){Diagnostics.event("worker.stopped","attempt="+getRunAttemptCount());control.cancel();}
}
