package com.lw.homenas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/** Starts only from an explicit visible Activity action, never from BOOT_COMPLETED. */
public final class BackupService extends Service {
    static final String STOP="com.lw.homenas.PAUSE";
    private RunControl control;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private static final String CHANNEL="backup-progress";
    public void onCreate(){super.onCreate();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"备份进度",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause=PendingIntent.getService(this,1,new Intent(this,BackupService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_home).setContentTitle("lw.HomeNAS 家庭备份")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"暂停",pause).build()).build();
    }
    public int onStartCommand(Intent intent,int flags,int startId) {
        Diagnostics.initialize(this);Diagnostics.event("service.start","pause="+(intent!=null&&STOP.equals(intent.getAction()))+" start_id="+startId);
        if(intent!=null&&STOP.equals(intent.getAction())) {new Settings(this).pause(true);BackupEngine.stopActive();if(control!=null)control.cancel();stopSelf();return START_NOT_STICKY;}
        startForeground(42,notification("准备原生备份，关闭页面后可继续"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if(worker!=null)return START_NOT_STICKY;
        BackupEngine.stopActive();control=new RunControl(5*60*60*1000L);
        wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":manual-backup");wakeLock.acquire(5*60*60*1000L);
        worker=new Thread(()->{
            Settings settings=new Settings(this);
            try{new BackupEngine(this).run(control,message->{settings.message(message);
                if(android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)
                    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(42,notification(message));
            });}
            catch(Exception error){settings.message("备份已暂停："+MainActivity.safeError(error)+" ["+Diagnostics.error("service.worker",error)+"]");}
            finally{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
        },"nas-manual-backup");worker.start();return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId,int fgsType){Diagnostics.event("service.timeout","start_id="+startId+" type="+fgsType);new Settings(this).message("系统已到同步时限，进度保留，请稍后手动继续");if(control!=null)control.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    public void onDestroy(){Diagnostics.event("service.destroy","");if(control!=null)control.cancel();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();super.onDestroy();}
    public IBinder onBind(Intent intent){return null;}
}
