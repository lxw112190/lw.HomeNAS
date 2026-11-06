package com.lw.homenas;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.Response;

/** Seekable native playback through the same authenticated, certificate-pinned API as backup. */
@UnstableApi
final class NasMediaDataSource extends BaseDataSource {
    private final Settings settings; private final Settings.Session session; private final NativeApi api;
    private final String id; private final long size;
    private Response response; private InputStream input; private RunControl control;
    private Uri uri; private long remaining; private boolean started;
    NasMediaDataSource(Settings settings,Settings.Session session,NativeApi api,String id,long size){
        super(true);this.settings=settings;this.session=session;this.api=api;this.id=id;this.size=size;
    }
    static Uri mediaUri(String id){return Uri.parse("homenas://media/"+id);}
    private void checkSession() throws IOException {if(!settings.current(session))throw new IOException("登录已改变，请重新打开播放");}
    @Override public long open(DataSpec spec) throws IOException {
        close();checkSession();
        if(!Protocol.assetId(id)||!mediaUri(id).equals(spec.uri)||spec.httpMethod!=DataSpec.HTTP_METHOD_GET||spec.httpBody!=null||spec.position>size)throw new IOException("无效播放请求");
        // ProgressiveMediaPeriod adds Icy-MetaData: 1 even for ordinary MP3/MP4 files.
        // Accept that local hint but never forward caller-supplied headers to the NAS.
        // NativeApi alone supplies Authorization and the range calculated below.
        for(java.util.Map.Entry<String,String> header:spec.httpRequestHeaders.entrySet())if(!"Icy-MetaData".equalsIgnoreCase(header.getKey())||!"1".equals(header.getValue()))throw new IOException("不支持的播放请求头");
        uri=spec.uri;remaining=Math.min(size-spec.position,spec.length==C.LENGTH_UNSET?Long.MAX_VALUE:spec.length);
        transferInitializing(spec);
        try {
            if(remaining>0){
                control=new RunControl(2*60*60*1000L);
                response=api.preview(id,"bytes="+spec.position+"-"+(spec.position+remaining-1),control);checkSession();
                String expected="bytes "+spec.position+"-"+(spec.position+remaining-1)+"/"+size;
                if(response.code()!=206||!expected.equals(response.header("Content-Range")))throw new IOException("服务器未正确返回播放分段，请升级电脑端");
                input=response.body().byteStream();
                Diagnostics.event("player.open","status=206 offset="+spec.position+" length="+remaining);
            }
            started=true;transferStarted(spec);return remaining;
        }catch(Exception error){close();if(error instanceof IOException)throw (IOException)error;throw new IOException("读取媒体失败",error);}
    }
    @Override public int read(byte[] buffer,int offset,int length) throws IOException {
        if(length==0)return 0;
        try{checkSession();if(remaining==0)return C.RESULT_END_OF_INPUT;if(control==null)throw new IOException("播放已关闭");control.check();
            int count=input.read(buffer,offset,(int)Math.min(length,remaining));if(count<0)throw new EOFException("媒体传输提前结束");remaining-=count;bytesTransferred(count);return count;
        }catch(IOException error){close();throw error;}
    }
    @Override public Uri getUri(){return uri;}
    @Override public void close(){
        if(control!=null){control.cancel();control.detach();control=null;}if(response!=null){response.close();response=null;}input=null;uri=null;
        if(started){started=false;transferEnded();}
    }
}
