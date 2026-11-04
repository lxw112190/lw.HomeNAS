package com.lw.homenas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class DiagnosticsTest {
    @Test public void redactsCredentialsBodiesUrisAndPrivatePaths() {
        String input="{\"password\":\"secret with spaces\",\"token\":\"private-token\",\"cookie\":\"session-private\"}\nAuthorization: Bearer private-bearer\nCookie: sid=private-cookie\n"
            +"content://media/external/images/42 /storage/emulated/0/DCIM/private.jpg https://user:pw@nas.test/api?token=another-secret\n"+"e".repeat(64);
        String result=Diagnostics.clean(input);
        for(String secret:new String[]{"secret with spaces","private-token","session-private","private-bearer","private-cookie","private.jpg","another-secret","e".repeat(64)})assertFalse(secret,result.contains(secret));
    }
    @Test public void logsRotateAndBundleContainsOnlyLogsAndSafeSummary() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();Diagnostics.initialize(context);
        Settings settings=new Settings(context);settings.prefs.edit().putString("token","CREDENTIAL_MUST_NOT_EXPORT").putString("password","PASSWORD_MUST_NOT_EXPORT").commit();
        java.nio.file.Files.write(new File(context.getFilesDir(),"private-media.bin").toPath(),"MEDIA_MUST_NOT_EXPORT".getBytes(StandardCharsets.UTF_8));
        for(int i=0;i<4500;i++)Diagnostics.event("rotation.test","index="+i+" "+"x".repeat(3072));
        File[] logs=new File(context.getFilesDir(),"logs").listFiles();assertNotNull(logs);assertTrue(logs.length<=5);boolean rotated=false;
        for(File log:logs){assertTrue(log.length()<=Diagnostics.LIMIT);if(log.getName().equals("runtime.1.log"))rotated=true;}assertTrue(rotated);
        String combined="";for(File log:logs)combined+=java.nio.file.Files.readString(log.toPath());assertTrue(combined.contains("index=4499 "));assertFalse(combined.contains("index=0 "));
        assertEquals(5,Diagnostics.view(context).getJSONObject("summary").getJSONObject("log_policy").getInt("retained_files"));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();Diagnostics.export(context,bytes);
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry;boolean summary=false;while((entry=zip.getNextEntry())!=null){String name=entry.getName();assertTrue(name,name.equals("diagnostics.json")||name.matches("runtime(?:\\.[1-4])?\\.log"));
                String text=new String(zip.readAllBytes(),StandardCharsets.UTF_8);assertFalse(text.contains("CREDENTIAL_MUST_NOT_EXPORT"));assertFalse(text.contains("PASSWORD_MUST_NOT_EXPORT"));assertFalse(text.contains("MEDIA_MUST_NOT_EXPORT"));if(name.equals("diagnostics.json"))summary=true;
            }assertTrue(summary);
        }finally{settings.prefs.edit().remove("token").remove("password").commit();}
    }
}
