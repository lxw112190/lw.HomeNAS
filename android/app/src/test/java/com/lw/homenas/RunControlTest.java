package com.lw.homenas;

import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunControlTest {
    private Call call(){return new OkHttpClient().newCall(new Request.Builder().url("http://127.0.0.1/never-executed").build());}
    @Test public void parentCancellationCancelsEverySenderAndRejectsLateAttach() throws Exception {
        RunControl root=new RunControl(30000),first=root.fork(),second=root.fork(),third=root.fork();Call a=call(),b=call();
        first.attach(a);second.attach(b);root.cancel();assertTrue(a.isCanceled());assertTrue(b.isCanceled());
        Call late=call();assertThrows(java.io.InterruptedIOException.class,()->third.attach(late));assertTrue(late.isCanceled());
        assertThrows(java.io.InterruptedIOException.class,root::fork);
    }
    @Test public void completedRequestDoesNotRemoveOtherSenderCancellation() throws Exception {
        RunControl root=new RunControl(30000),first=root.fork(),second=root.fork();Call a=call(),b=call();first.attach(a);second.attach(b);first.detach();root.cancel();
        assertFalse(a.isCanceled());assertTrue(b.isCanceled());
    }
}
