package com.lw.homenas;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

/** Internal activity: receives an asset ID, never an arbitrary remote URL or bearer token. */
@androidx.annotation.OptIn(markerClass=UnstableApi.class)
public final class MediaPreviewActivity extends Activity {
    private Settings settings; private Settings.Session session; private ExoPlayer player; private PlayerView view;
    private TextView status; private String id,mime; private long size,position; private boolean playing=true;
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);settings=new Settings(this);
        try{session=settings.session();}catch(Exception error){Diagnostics.error("player.session",error);android.widget.Toast.makeText(this,"无法读取登录状态，请返回重新登录",android.widget.Toast.LENGTH_LONG).show();finish();return;}
        id=getIntent().getStringExtra("asset_id");mime=getIntent().getStringExtra("mime");size=getIntent().getLongExtra("size",-1);
        if(session==null||!Protocol.assetId(id==null?"":id)||mime==null||!(mime.startsWith("audio/")||mime.startsWith("video/"))||size<=0||session.revision!=getIntent().getLongExtra("revision",-1)){finish();return;}
        if(state!=null){position=state.getLong("position");playing=state.getBoolean("playing",true);}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),0,dp(4),0);
        TextView title=new TextView(this);title.setText(getIntent().getStringExtra("name"));title.setTextColor(Color.WHITE);title.setTextSize(16);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
        Button close=new Button(this);close.setText("×");close.setTextSize(28);close.setContentDescription("关闭播放");close.setOnClickListener(v->finish());header.addView(close,new LinearLayout.LayoutParams(dp(56),dp(56)));root.addView(header);
        view=new PlayerView(this);view.setShowPreviousButton(false);view.setShowNextButton(false);view.setControllerShowTimeoutMs(0);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setTextColor(Color.LTGRAY);status.setTextSize(13);status.setPadding(dp(16),dp(10),dp(16),dp(16));root.addView(status);root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});setContentView(root);root.requestApplyInsets();
    }
    @Override protected void onStart(){super.onStart();if(view==null||isFinishing())return;
        try{
            if(!settings.current(session)){finish();return;}
            NativeApi api=new NativeApi(session);
            DefaultLoadControl buffer=new DefaultLoadControl.Builder().setBufferDurationsMs(5000,15000,500,1500).setTargetBufferBytes(16*1024*1024).build();
            player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true)).setLoadControl(buffer).build();
            player.setAudioAttributes(AudioAttributes.DEFAULT,true);player.setHandleAudioBecomingNoisy(true);view.setPlayer(player);
            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int value){status.setText(value==Player.STATE_BUFFERING?"正在缓冲…":value==Player.STATE_READY?"可拖动进度条；返回关闭播放":value==Player.STATE_ENDED?"播放完毕":"准备播放");}
                @Override public void onIsPlayingChanged(boolean value){if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
                @Override public void onPlayerError(PlaybackException error){Diagnostics.event("player.error","code="+error.errorCode+" mime="+mime.replaceAll("[^A-Za-z0-9/+-]","")+" sdk="+android.os.Build.VERSION.SDK_INT);status.setText("播放失败（"+error.errorCode+"）。请检查连接；若设备不支持该编码，可返回保存原件。请导出诊断日志协助排查。");}
            });
            MediaItem item=new MediaItem.Builder().setUri(NasMediaDataSource.mediaUri(id)).setMimeType(mime).build();
            player.setMediaSource(new ProgressiveMediaSource.Factory(()->new NasMediaDataSource(settings,session,api,id,size)).createMediaSource(item));
            player.seekTo(position);player.prepare();player.setPlayWhenReady(playing);status.setText("正在加载…");Diagnostics.event("player.start","mime="+mime.replaceAll("[^A-Za-z0-9/+-]","")+" sdk="+android.os.Build.VERSION.SDK_INT);
        }catch(Exception error){Diagnostics.error("player.prepare",error);status.setText("无法开始播放，请返回重新登录后重试");release();}
    }
    private void release(){if(player!=null){position=player.getCurrentPosition();playing=player.getPlayWhenReady();view.setPlayer(null);player.release();player=null;}getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onSaveInstanceState(Bundle state){if(player!=null){position=player.getCurrentPosition();playing=player.getPlayWhenReady();}state.putLong("position",position);state.putBoolean("playing",playing);super.onSaveInstanceState(state);}
    @Override protected void onStop(){release();super.onStop();}
    @Override protected void onDestroy(){release();super.onDestroy();}
}
