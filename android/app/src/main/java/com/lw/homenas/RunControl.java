package com.lw.homenas;

import java.io.InterruptedIOException;
import okhttp3.Call;

final class RunControl {
    private volatile boolean cancelled;
    private volatile Call connection;
    private final long deadline;
    RunControl(long durationMillis) { deadline = System.nanoTime() + durationMillis * 1000000L; }
    void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new InterruptedIOException("已暂停；确认进度已保留，下次继续");
    }
    long remainingMillis(){return Math.max(1,(deadline-System.nanoTime())/1000000L);}
    void attach(Call value) throws InterruptedIOException { connection = value; check(); }
    void detach() { connection = null; }
    void cancel() { cancelled = true; Call active = connection; if (active != null) active.cancel(); }
}
