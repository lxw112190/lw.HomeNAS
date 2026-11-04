package com.lw.homenas;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaPermissionsTest {
    @Test public void firstRequestAndReselectUseRuntimeDialog() {
        assertFalse(MediaPermissions.useSettings(false,false,false,false,false));
        assertFalse(MediaPermissions.useSettings(false,false,true,true,false));
        assertFalse(MediaPermissions.useSettings(true,false,false,true,true));
    }
    @Test public void FullAccessAndPermanentlyDeniedGoToSettings() {
        assertTrue(MediaPermissions.useSettings(true,true,false,true,false));
        assertTrue(MediaPermissions.useSettings(false,false,false,true,false));
        assertTrue(MediaPermissions.useSettings(true,false,false,true,false));
    }
    @Test public void requestOnlyTheMediaPermissionsForTheOsVersion() {
        assertEquals(1,MediaPermissions.requestList(32).length);
        assertEquals(2,MediaPermissions.requestList(33).length);
        assertEquals(3,MediaPermissions.requestList(34).length);
        assertEquals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED",MediaPermissions.requestList(35)[2]);
    }
}
