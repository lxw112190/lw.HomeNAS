package com.lw.homenas;

public final class NasApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();Diagnostics.initialize(this);
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            Diagnostics.error("app.uncaught",error);
            if(previous!=null)previous.uncaughtException(thread,error);
            else {android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        });
    }
}
