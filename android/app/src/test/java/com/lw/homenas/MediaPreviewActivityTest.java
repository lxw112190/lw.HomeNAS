package com.lw.homenas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class MediaPreviewActivityTest {
    @Test public void unreadableSessionFinishesWithoutCrashing() {
        Settings settings=new Settings(RuntimeEnvironment.getApplication());
        settings.prefs.edit().clear().putString("token","invalid-test-ciphertext").putString("iv","invalid-test-iv").commit();
        org.robolectric.android.controller.ActivityController<MediaPreviewActivity> activity=Robolectric.buildActivity(MediaPreviewActivity.class);
        try{
            assertTrue(activity.create().get().isFinishing());
        }finally{activity.destroy();settings.prefs.edit().clear().commit();}
    }
}
