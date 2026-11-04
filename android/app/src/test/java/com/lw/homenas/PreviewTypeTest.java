package com.lw.homenas;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class PreviewTypeTest {
    @Test public void legacyMkvAndAviAreAllowedWithoutTrustingAnArbitraryMime() throws Exception {
        JSONObject old=new JSONObject().put("category","video").put("mime","application/octet-stream").put("name","sample.MKV");assertEquals("video/x-matroska",PreviewStreams.previewType(old));old.put("name","sample.avi");assertEquals("video/x-msvideo",PreviewStreams.previewType(old));
        old.put("name","sample.exe");assertThrows(java.io.IOException.class,()->PreviewStreams.previewType(old));old.put("category","file").put("name","sample.mkv");assertThrows(java.io.IOException.class,()->PreviewStreams.previewType(old));
    }
}
