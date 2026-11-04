package com.lw.homenas;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import org.json.JSONObject;

/** Permission policy is separate from the gallery query and never grants access itself. */
final class MediaPermissions {
    static String[] requestList(int sdk) {
        if(sdk>=34)return new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};
        if(sdk>=33)return new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }
    static boolean fullImages(Context c){return MediaCatalog.granted(c,Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE);}
    static boolean fullVideos(Context c){return MediaCatalog.granted(c,Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_VIDEO:Manifest.permission.READ_EXTERNAL_STORAGE);}
    static boolean selected(Context c){return Build.VERSION.SDK_INT>=34&&MediaCatalog.granted(c,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);}
    static boolean useSettings(boolean images,boolean videos,boolean partial,boolean asked,boolean rationale) {
        return images&&videos || asked&&!partial&&!rationale;
    }
    static JSONObject state(Context c) throws Exception {
        boolean images=fullImages(c),videos=fullVideos(c),partial=selected(c);
        String image=images?"全部":partial?"仅所选":"未授权",video=videos?"全部":partial?"仅所选":"未授权";
        return new JSONObject().put("full",images&&videos).put("any",images||videos||partial).put("partial",partial&&!(images&&videos))
            .put("images",image).put("videos",video).put("label","照片："+image+" · 视频："+video);
    }
}
