package com.lw.homenas;

import android.Manifest;
import android.app.Application;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.Assert.*;

/** Provider fixture deliberately ignores structured sorting when SQL selection is used. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class MediaPickerQueryTest {
    public static class Provider extends ContentProvider {
        final List<long[]> rows=new ArrayList<>();boolean ignoreOrder;String requestedSort="";
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri uri,String[] projection,Bundle options,CancellationSignal cancel) {
            if(projection==null){MatrixCursor result=new MatrixCursor(new String[]{"_display_name","_size","date_modified"});for(long[] row:rows)if(Long.toString(row[0]).equals(uri.getLastPathSegment()))result.addRow(new Object[]{"fixture.jpg",123L,row[1]});return result;}
            requestedSort=options.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,"");
            String selection=options.getString(ContentResolver.QUERY_ARG_SQL_SELECTION,"");String[] args=options.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
            List<long[]> matches=new ArrayList<>();
            for(long[] row:rows){if(selection.contains("date_modified>=?")){if(row[1]<Long.parseLong(args[0])||row[1]>=Long.parseLong(args[1]))continue;}else if(selection.contains("date_modified<?")&&row[1]>=Long.parseLong(args[0]))continue;matches.add(row);}
            if(!ignoreOrder&&requestedSort.equals("date_modified DESC, _id DESC"))matches.sort((a,b)->{int order=Long.compare(b[1],a[1]);return order!=0?order:Long.compare(b[0],a[0]);});
            int limit=options.getInt(ContentResolver.QUERY_ARG_LIMIT,Integer.MAX_VALUE);MatrixCursor result=new MatrixCursor(projection);
            for(long[] row:matches.subList(0,Math.min(limit,matches.size()))){Object[] values=new Object[projection.length];for(int i=0;i<projection.length;i++){switch(projection[i]){case "_id":values[i]=row[0];break;case "date_modified":values[i]=row[1];break;case "_display_name":values[i]="fixture.jpg";break;case "_size":values[i]=123L;break;case "volume_name":values[i]="external_primary";break;case "bucket_display_name":values[i]="CI";break;default:values[i]=32L;}}result.addRow(values);}return result;
        }
        @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order){throw new AssertionError("Use explicit Bundle query");}
        @Override public String getType(Uri u){return "image/jpeg";}
        @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
        @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
        @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    }
    Context context;Settings settings;Settings.Session session;Provider provider;MediaPicker picker;
    @Before public void prepare() throws Exception {
        context=RuntimeEnvironment.getApplication();Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        provider=new Provider();ProviderInfo info=new ProviderInfo();info.authority="media";provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal("media",provider);
        long start=LocalDate.of(2026,8,1).atTime(12,0).toEpochSecond(ZoneOffset.UTC);for(int i=0;i<80;i++)provider.rows.add(new long[]{i+1,start+i*86400L});
        settings=new Settings(context);settings.prefs.edit().putLong("revision",42).putString("token","fixture-only").commit();
        session=new Settings.Session("http://127.0.0.1:8787","","fixture-only","fixture-server",new JSONObject().put("user_id","picker-test"),true,42);picker=new MediaPicker(context,settings);
    }
    @Test public void newestDaysCannotDisappearBehindTheFirstThirtyOldDays() throws Exception {
        JSONObject page=picker.days(session,"",true,new RunControl(30000));String newest=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(provider.rows.get(79)[1]*1000));
        assertEquals("date_modified DESC, _id DESC",provider.requestedSort);assertEquals(newest,page.getJSONArray("items").getJSONObject(0).getString("day"));
        Set<String> visited=new HashSet<>();String before="";do{page=picker.days(session,before,before.isEmpty(),new RunControl(30000));for(int i=0;i<page.getJSONArray("items").length();i++)assertTrue(visited.add(page.getJSONArray("items").getJSONObject(i).getString("day")));before=page.getString("next");}while(!before.isEmpty());assertEquals(80,visited.size());
        JSONObject records=picker.items(session,newest,"",null,new RunControl(30000));assertEquals(1,records.getJSONArray("items").length());assertFalse(records.toString().contains("content://"));
    }
    @Test public void providerIgnoringOrderMustFailInsteadOfSilentlyHidingRecentPhotos() throws Exception {
        provider.ignoreOrder=true;try{picker.days(session,"",true,new RunControl(30000));fail("Unordered provider was silently accepted");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("日期"));}
    }
    @Test public void wholeDayExceptOneDoesNotEnqueueTheExcludedPhoto() throws Exception {
        long when=provider.rows.get(0)[1];provider.rows.clear();provider.rows.add(new long[]{1,when});provider.rows.add(new long[]{2,when});
        JSONObject days=picker.days(session,"",true,new RunControl(30000));String day=days.getJSONArray("items").getJSONObject(0).getString("day");
        JSONArray first=picker.items(session,day,"",null,new RunControl(30000)).getJSONArray("items");
        JSONArray refreshed=picker.items(session,day,"",null,new RunControl(30000)).getJSONArray("items");
        String excluded=first.getJSONObject(0).getString("id");assertEquals(excluded,refreshed.getJSONObject(0).getString("id"));
        JSONObject result=picker.enqueue(session,new JSONObject().put("days",new JSONArray().put(day)).put("ids",new JSONArray()).put("exclude_ids",new JSONArray().put(excluded)),null,new RunControl(30000));
        assertEquals(1,result.getInt("added"));
        try(Cursor queued=TaskStore.get(context).getReadableDatabase().rawQuery("SELECT uri FROM tasks WHERE scope=?",new String[]{session.scope})){
            assertEquals(1,queued.getCount());assertTrue(queued.moveToFirst());assertEquals("1",Uri.parse(queued.getString(0)).getLastPathSegment());
        }
    }
    @Test public void expiredExclusionRejectsBeforeAnyEnqueue() throws Exception {
        String day=picker.days(session,"",true,new RunControl(30000)).getJSONArray("items").getJSONObject(0).getString("day");
        JSONObject input=new JSONObject().put("days",new JSONArray().put(day)).put("exclude_ids",new JSONArray().put("stale-handle"));
        assertThrows(java.io.IOException.class,()->picker.enqueue(session,input,null,new RunControl(30000)));
        try(Cursor queued=TaskStore.get(context).getReadableDatabase().rawQuery("SELECT id FROM tasks WHERE scope=?",new String[]{session.scope})){assertEquals(0,queued.getCount());}
    }
    @Test public void changingDestinationInvalidatesAnOldSelection() throws Exception {
        String day=picker.days(session,"",true,new RunControl(30000)).getJSONArray("items").getJSONObject(0).getString("day");
        JSONObject input=new JSONObject().put("days",new JSONArray().put(day)).put("folder_id","a".repeat(48));
        assertThrows(java.io.IOException.class,()->picker.enqueue(session,input,null,new RunControl(30000)));
        try(Cursor queued=TaskStore.get(context).getReadableDatabase().rawQuery("SELECT id FROM tasks WHERE scope=?",new String[]{session.scope})){assertEquals(0,queued.getCount());}
    }
}
