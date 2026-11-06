package com.lw.homenas;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class MediaSelectionTest {
    @Test public void monthSelectionIncludesLaterPagesAndHonorsDayExceptions() throws Exception {
        MediaSelection selection=new MediaSelection(new JSONObject().put("months",new JSONArray().put("2026-11")).put("exclude_days",new JSONArray().put("2026-11-06")));
        assertTrue(selection.includes("2026-11-30"));assertFalse(selection.includes("2026-11-06"));assertFalse(selection.includes("2026-10-31"));
    }
    @Test public void explicitlyRestoredDayOverridesExcludedMonth() throws Exception {
        MediaSelection selection=new MediaSelection(new JSONObject().put("all",true).put("exclude_months",new JSONArray().put("2026-11")).put("days",new JSONArray().put("2026-11-06")));
        assertTrue(selection.includes("2026-11-06"));assertFalse(selection.includes("2026-11-07"));assertTrue(selection.includes("unknown"));
    }
    @Test public void invalidCalendarDateAndTypeAreRejected() {
        assertThrows(Exception.class,()->new MediaSelection(new JSONObject().put("days",new JSONArray().put("2026-02-30"))));
        assertThrows(Exception.class,()->new MediaSelection(new JSONObject().put("kind","../image")));
    }
}
