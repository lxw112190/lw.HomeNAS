package com.lw.homenas;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

final class BackupScheduler {
    static void configure(Context context) throws Exception {
        Settings settings=new Settings(context);Settings.Session session=settings.session();
        if(!settings.prefs.getBoolean("auto",false)||session==null||!session.canUpload){cancel(context);return;}
        Constraints constraints=new Constraints.Builder()
            .setRequiredNetworkType(settings.prefs.getBoolean("wifi",true)?NetworkType.UNMETERED:NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).setRequiresCharging(settings.prefs.getBoolean("charging",false)).build();
        PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(BackupWorker.class,15,TimeUnit.MINUTES)
            .setConstraints(constraints).setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("nas-periodic-backup",ExistingPeriodicWorkPolicy.UPDATE,request);
    }
    static void cancel(Context context){WorkManager.getInstance(context).cancelUniqueWork("nas-periodic-backup");}
}
