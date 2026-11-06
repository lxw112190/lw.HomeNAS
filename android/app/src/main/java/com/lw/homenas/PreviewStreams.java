package com.lw.homenas;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Authenticated, bounded streaming proxy. No URL, token or original bytes cross the JS bridge. */
final class PreviewStreams {
    private final Settings settings;
    private final Set<OwnedStream> open = new HashSet<>();
    PreviewStreams(Settings settings){this.settings=settings;}
    synchronized void closeAll(){for(OwnedStream stream:new ArrayList<>(open))try{stream.close();}catch(IOException ignored){}}
    WebResourceResponse open(WebResourceRequest request,Settings.Session session,NativeApi api,JSONObject asset) throws Exception {
        if(asset==null||!settings.current(session)||request.isForMainFrame())throw new IOException("请重新打开文件预览");
        String type=previewType(asset);
        String range="";for(Map.Entry<String,String> header:request.getRequestHeaders().entrySet())if(header.getKey().equalsIgnoreCase("Range"))range=header.getValue();
        RunControl control=new RunControl(15*60*1000L);okhttp3.Response response=null;
        try {
            response=api.preview(asset.getString("id"),range,control);if(!settings.current(session))throw new IOException("登录已改变");
            Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");
            for(String key:new String[]{"Content-Length","Content-Range","Accept-Ranges"})if(response.header(key)!=null)headers.put(key,response.header(key));
            Diagnostics.event("preview.stream","type="+type+" range="+!range.isEmpty()+" status="+response.code());
            OwnedStream stream=new OwnedStream(response,control,session);
            synchronized(this){if(open.size()>=4){stream.close();throw new IOException("请先关闭其他预览");}open.add(stream);}
            return new WebResourceResponse(type,null,response.code(),response.code()==206?"Partial Content":"OK",headers,stream);
        }catch(Exception error){if(response!=null)response.close();control.cancel();throw error;}
    }
    static String previewType(JSONObject asset) throws IOException {
        String type=asset.optString("mime");
        // Existing servers stored MKV/AVI as octet-stream despite category=video.
        if(type.equals("application/octet-stream")&&asset.optString("category").equals("video")){
            String name=asset.optString("name").toLowerCase(Locale.ROOT);if(name.endsWith(".mkv"))type="video/x-matroska";else if(name.endsWith(".avi"))type="video/x-msvideo";
        }
        if(!type.matches("image/(jpeg|png|webp|gif|bmp)|video/(mp4|webm|quicktime|x-matroska|x-msvideo|3gpp|mpeg|mp2t)|audio/(mpeg|mp4|aac|wav|x-wav|ogg|flac|webm|3gpp)"))throw new IOException("该格式请保存后打开");return type;
    }
    private final class OwnedStream extends FilterInputStream {
        final okhttp3.Response response;final RunControl control;final Settings.Session session;boolean closed;
        OwnedStream(okhttp3.Response response,RunControl control,Settings.Session session){super(response.body().byteStream());this.response=response;this.control=control;this.session=session;}
        void check() throws IOException{control.check();if(closed||!settings.current(session)){close();throw new IOException("预览已关闭或登录已改变");}}
        @Override public int read() throws IOException{check();return in.read();}
        @Override public int read(byte[] data,int offset,int length) throws IOException{check();return in.read(data,offset,length);}
        @Override public void close() throws IOException{if(closed)return;closed=true;control.cancel();response.close();control.detach();synchronized(PreviewStreams.this){open.remove(this);}}
    }
}
