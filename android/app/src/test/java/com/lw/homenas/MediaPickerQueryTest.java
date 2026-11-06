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
        final List<long[]> rows=new ArrayList<>();Runnable onCollectionQuery;boolean ignoreOrder;String requestedSort="";
        @Override public boolean onCreate(){return true;}
        private static boolean isItem(Uri uri){return uri.getPath()!=null&&uri.getPath().matches(".*/(images|video)/media/[0-9]+$");}
        private static Object value(long[] row,String column){switch(column){case "_id":return row[0];case "date_modified":return row[1];case "_display_name":return "fixture.jpg";case "_size":return 123L;case "volume_name":return "external_primary";case "bucket_display_name":return "CI";default:return 32L;}}
        private Cursor itemMetadata(Uri uri,String[] projection){
            String[] columns=projection==null?new String[]{"_display_name","_size","date_modified","volume_name","_id"}:projection;
            MatrixCursor result=new MatrixCursor(columns);
            for(long[] row:rows)if(Long.toString(row[0]).equals(uri.getLastPathSegment())){Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++)values[i]=value(row,columns[i]);result.addRow(values);}
            return result;
        }
        @Override public Cursor query(Uri uri,String[] projection,Bundle options,CancellationSignal cancel) {
            if(isItem(uri))return itemMetadata(uri,projection);
            if(onCollectionQuery!=null)onCollectionQuery.run();
            requestedSort=options.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,"");
            String selection=options.getString(ContentResolver.QUERY_ARG_SQL_SELECTION,"");String[] args=options.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
            List<long[]> matches=new ArrayList<>();
            for(long[] row:rows){
                int arg=0;boolean match=true;
                if(selection.contains("date_modified>=?")){long from=Long.parseLong(args[arg++]),to=Long.parseLong(args[arg++]);if(row[1]<from||row[1]>=to)match=false;}
                else if(selection.contains("date_modified<=0")&&row[1]>0)match=false;
                if(selection.contains("_id<=?")){if(row[0]>Long.parseLong(args[arg++]))match=false;}
                if(selection.contains("OR (date_modified=?")){long time=Long.parseLong(args[arg++]);arg++;long id=Long.parseLong(args[arg++]);boolean inclusive=selection.contains("date_modified=? AND _id<=?");if(!(row[1]<time||row[1]==time&&(inclusive?row[0]<=id:row[0]<id)))match=false;}
                else if(selection.contains("date_modified<?")&&!selection.contains("date_modified>=?")){if(row[1]>=Long.parseLong(args[arg++]))match=false;}
                if(match)matches.add(row);
            }
            if(!ignoreOrder&&requestedSort.equals("date_modified DESC, _id DESC"))matches.sort((a,b)->{int order=Long.compare(b[1],a[1]);return order!=0?order:Long.compare(b[0],a[0]);});
            int limit=options.getInt(ContentResolver.QUERY_ARG_LIMIT,Integer.MAX_VALUE);MatrixCursor result=new MatrixCursor(projection);
            for(long[] row:matches.subList(0,Math.min(limit,matches.size()))){Object[] values=new Object[projection.length];for(int i=0;i<projection.length;i++)values[i]=value(row,projection[i]);result.addRow(values);}return result;
        }
        @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order){if(isItem(u))return itemMetadata(u,p);throw new AssertionError("Use explicit Bundle query for collection listings");}
        @Override public String getType(Uri u){return "image/jpeg";}
        @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
        @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
        @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    }
    Context context;Settings settings;Settings.Session session;Provider provider;MediaPicker picker;
    @Before public void prepare() throws Exception {
        context=RuntimeEnvironment.getApplication();QueueFixture.reset(context);Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        provider=new Provider();ProviderInfo info=new ProviderInfo();info.authority="media";provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal("media",provider);
        long start=LocalDate.of(2026,8,1).atTime(12,0).toEpochSecond(ZoneOffset.UTC);for(int i=0;i<80;i++)provider.rows.add(new long[]{i+1,start+i*86400L});
        settings=new Settings(context);settings.prefs.edit().clear().putLong("revision",42).putString("token","fixture-only").commit();
        session=new Settings.Session("http://127.0.0.1:8787","","fixture-only","fixture-server",new JSONObject().put("user_id","picker-test"),true,42);picker=new MediaPicker(context,settings);
        try(Cursor empty=TaskStore.get(context).getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tasks",null)){assertTrue(empty.moveToFirst());assertEquals("Fixture queue must start empty",0,empty.getInt(0));}
    }
    @After public void cleanup() throws Exception {
        if(picker!=null)picker.clear();
        if(context!=null)QueueFixture.reset(context);
        if(settings!=null)settings.prefs.edit().clear().commit();
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
    @Test public void itemMetadataSupportsLegacyAndBundleQueries() throws Exception {
        Uri uri=Uri.parse("content://media/external_primary/images/media/1");
        MediaCatalog.Info info=MediaCatalog.describe(context.getContentResolver(),uri);
        assertEquals("fixture.jpg",info.name);assertEquals(123L,info.size);assertEquals(provider.rows.get(0)[1]*1000L,info.modified);
        try(Cursor row=context.getContentResolver().query(uri,new String[]{"volume_name","_id"},new Bundle(),null)){assertNotNull(row);assertTrue(row.moveToFirst());assertEquals("external_primary",row.getString(0));assertEquals(1L,row.getLong(1));assertFalse(row.moveToNext());}
    }
    @Test public void collectionLegacyQueryStillFailsTheFixtureContract() {
        assertThrows(AssertionError.class,()->provider.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id"},null,null,null));
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
    @Test public void monthlyAndAllSelectionCrossPagesAndKeepDayExceptions() throws Exception {
        picker.days(session,"",true,new RunControl(30000));
        JSONObject months=picker.months(session,"image",new RunControl(30000));assertEquals(3,months.getJSONArray("items").length());
        JSONObject input=new JSONObject().put("months",new JSONArray().put("2026-08")).put("exclude_days",new JSONArray().put("2026-08-02")).put("kind","image");
        JSONObject result=picker.enqueue(session,input,null,new RunControl(30000));assertEquals(30,result.getInt("added"));
        JSONObject all=picker.enqueue(session,new JSONObject().put("all",true).put("kind","image"),null,new RunControl(30000));assertEquals(50,all.getInt("added"));assertEquals(30,all.getInt("skipped"));
    }
    @Test public void allSelectionOverFiveThousandUsesBoundedPagesAndCanCancel() throws Exception {
        long when=provider.rows.get(0)[1];provider.rows.clear();for(int i=1;i<=5101;i++)provider.rows.add(new long[]{i,when+i});
        picker.days(session,"",true,new RunControl(30000));java.util.concurrent.atomic.AtomicLong batches=new java.util.concurrent.atomic.AtomicLong();
        JSONObject result=picker.enqueue(session,new JSONObject().put("all",true).put("kind","image"),null,new RunControl(120000),(n,a,skip,bytes)->batches.set(n));
        assertEquals(5101,result.getInt("added"));assertEquals(5101,batches.get());assertEquals(5101,TaskStore.get(context).counts(session.scope).getLong("QUEUED"));
    }
    @Test public void cancellationKeepsOnlyAlreadyQueuedFiles() throws Exception {
        picker.days(session,"",true,new RunControl(30000));RunControl control=new RunControl(30000);
        assertThrows(java.io.InterruptedIOException.class,()->picker.enqueue(session,new JSONObject().put("all",true),null,control,(n,a,skip,bytes)->{if(n==7)control.cancel();}));
        assertEquals(7,TaskStore.get(context).counts(session.scope).getLong("QUEUED"));
    }
    @Test public void targetChangeStopsFurtherEnqueueWithoutRetargeting() throws Exception {
        picker.days(session,"",true,new RunControl(30000));RunControl control=new RunControl(30000);
        assertThrows(java.io.IOException.class,()->picker.enqueue(session,new JSONObject().put("all",true),null,control,(n,a,skip,bytes)->{if(n==5)settings.prefs.edit().putString("backup_folder_"+Protocol.key(session.scope),"b".repeat(48)).commit();}));
        try(Cursor rows=TaskStore.get(context).getReadableDatabase().rawQuery("SELECT folder_id FROM tasks",null)){assertEquals(5,rows.getCount());while(rows.moveToNext())assertEquals("default",rows.getString(0));}
    }

    @Test public void asyncEnqueueRejectsDuplicateStartAndCancelLeavesQueueIntact() throws Exception {
        picker.days(session,"",true,new RunControl(30000));EnqueueJob job=new EnqueueJob();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        provider.onCollectionQuery=()->{entered.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Fixture gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}};
        try{
            assertEquals("RUNNING",job.start(settings,session,picker,null,new JSONObject().put("all",true)).getString("state"));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.io.IOException.class,()->job.start(settings,session,picker,null,new JSONObject().put("all",true)));
            job.cancel();release.countDown();long deadline=System.nanoTime()+10_000_000_000L;while(job.snapshot(session.scope).getString("state").equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals("STOPPED",job.snapshot(session.scope).getString("state"));assertFalse(TaskStore.get(context).counts(session.scope).has("QUEUED"));
            assertEquals("IDLE",job.snapshot("another-user").getString("state"));
        }finally{job.cancel();release.countDown();provider.onCollectionQuery=null;}
    }
    @Test public void photosCreatedDuringSelectionAreNotAddedToTheFixedRange() throws Exception {
        long when=provider.rows.get(0)[1];provider.rows.clear();for(int i=1;i<=120;i++)provider.rows.add(new long[]{i,when+i});picker.days(session,"",true,new RunControl(30000));
        JSONObject result=picker.enqueue(session,new JSONObject().put("all",true),null,new RunControl(30000),(n,a,skip,bytes)->{if(n==1)provider.rows.add(new long[]{9000,when});});
        assertEquals(120,result.getInt("added"));try(Cursor rows=TaskStore.get(context).getReadableDatabase().rawQuery("SELECT uri FROM tasks",null)){while(rows.moveToNext())assertFalse(rows.getString(0).endsWith("/9000"));}
    }

    @Test public void combinedImageAndVideoPagesKeepEqualMediaIdsDistinct() throws Exception {
        Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO);
        picker.days(session,"",true,new RunControl(30000));JSONObject result=picker.enqueue(session,new JSONObject().put("all",true),null,new RunControl(60000));
        assertEquals(160,result.getInt("added"));assertEquals(160,TaskStore.get(context).counts(session.scope).getLong("QUEUED"));
    }

}
