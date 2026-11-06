package com.lw.homenas;

import java.io.InterruptedIOException;
import okhttp3.Call;

final class RunControl {
    private volatile boolean cancelled;
    private volatile Call connection;
    private final java.util.Set<RunControl> children=new java.util.HashSet<>();
    private final long deadline;
    RunControl(long durationMillis) { deadline = System.nanoTime() + durationMillis * 1000000L; }
    private RunControl(RunControl parent) { deadline=parent.deadline; }
    synchronized RunControl fork() throws InterruptedIOException {check();RunControl child=new RunControl(this);children.add(child);return child;}
    void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new InterruptedIOException("已暂停；确认进度已保留，下次继续");
    }
    long remainingMillis(){return Math.max(1,(deadline-System.nanoTime())/1000000L);}
    synchronized void attach(Call value) throws InterruptedIOException {connection=value;try{check();}catch(InterruptedIOException error){value.cancel();connection=null;throw error;}}
    void detach() { connection = null; }
    synchronized void cancel() {cancelled=true;Call active=connection;if(active!=null)active.cancel();for(RunControl child:children)child.cancel();}
}
