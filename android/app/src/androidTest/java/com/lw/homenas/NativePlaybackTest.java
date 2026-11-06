package com.lw.homenas;

import android.content.Context;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

@UnstableApi @RunWith(AndroidJUnit4.class)
public class NativePlaybackTest {
    @Test public void realMp3DecodesAndSeeksThroughAuthenticatedRangeSource() throws Exception {
        Assume.assumeTrue(android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"));
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        byte[] payload;try(InputStream in=instrumentation.getContext().getAssets().open("player-tone.mp3")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);payload=out.toByteArray();}
        String id="0123456789abcdef0123456789abcdef0123456789abcdef";Settings settings=new Settings(context);
        settings.prefs.edit().clear().putLong("revision",993).putString("token","fixture-only").commit();
        Settings.Session session=new Settings.Session("http://127.0.0.1:8787","","fixture-only","test-player",new JSONObject(),true,993);
        AtomicInteger ranges=new AtomicInteger();ExoPlayer[] player=new ExoPlayer[1];
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
                if(!("/api/v1/assets/"+id+"/original").equals(request.getPath())||!"Bearer player-test".equals(request.getHeader("Authorization")))return new MockResponse().setResponseCode(403);
                String range=request.getHeader("Range");if(range==null||!range.matches("bytes=[0-9]+-[0-9]+"))return new MockResponse().setResponseCode(400);
                String[] offsets=range.substring(6).split("-");int start=Integer.parseInt(offsets[0]),end=Integer.parseInt(offsets[1]);
                if(start<0||end>=payload.length||end<start)return new MockResponse().setResponseCode(416);ranges.incrementAndGet();
                return new MockResponse().setResponseCode(206).setHeader("Content-Type","audio/mpeg").setHeader("Content-Range","bytes "+start+"-"+end+"/"+payload.length).setBody(new okio.Buffer().write(payload,start,end-start+1));
            }});server.start();NativeApi api=new NativeApi(server.url("/").toString(),"",true,"player-test");
            instrumentation.runOnMainSync(()->{player[0]=new ExoPlayer.Builder(context).build();player[0].setVolume(0);player[0].setMediaSource(new ProgressiveMediaSource.Factory(()->new NasMediaDataSource(settings,session,api,id,payload.length)).createMediaSource(new MediaItem.Builder().setUri(NasMediaDataSource.mediaUri(id)).setMimeType("audio/mpeg").build()));player[0].prepare();});
            AtomicInteger state=new AtomicInteger();AtomicLong duration=new AtomicLong();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            do{instrumentation.runOnMainSync(()->{state.set(player[0].getPlaybackState());duration.set(player[0].getDuration());});if(state.get()==Player.STATE_READY)break;Thread.sleep(50);}while(System.nanoTime()<deadline);
            assertEquals(Player.STATE_READY,state.get());assertTrue(duration.get()>=1800);assertTrue(ranges.get()>0);
            AtomicLong position=new AtomicLong();instrumentation.runOnMainSync(()->{player[0].seekTo(1000);position.set(player[0].getCurrentPosition());});assertTrue(position.get()>=900);
        }finally{instrumentation.runOnMainSync(()->{if(player[0]!=null)player[0].release();});settings.clear();}
    }
}
