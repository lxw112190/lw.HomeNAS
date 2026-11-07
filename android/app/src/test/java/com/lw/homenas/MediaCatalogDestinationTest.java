package com.lw.homenas;

import android.Manifest;
import android.app.Application;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

/** Auto-album scans use the legacy collection query, independently of the daily picker. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class MediaCatalogDestinationTest {
    Context context;Settings settings;Settings.Session session;Provider provider;
    public static final class Provider extends ContentProvider {
        Runnable changed;int changeAt=104;
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
            assertEquals("_id ASC",sort);
            MatrixCursor cursor=new MatrixCursor(projection){@Override public boolean onMove(int oldPosition,int newPosition){if(newPosition==changeAt&&changed!=null){Runnable action=changed;changed=null;action.run();}return super.onMove(oldPosition,newPosition);}};
            for(int i=0;i<130;i++){Object[] values=new Object[projection.length];for(int c=0;c<projection.length;c++)switch(projection[c]){case "_id":values[c]=(long)i+1;break;case "_display_name":values[c]="fixture.jpg";break;case "_size":values[c]=123L;break;case "date_modified":values[c]=1700000000L+i;break;case "bucket_id":values[c]="camera";break;case "bucket_display_name":values[c]="Camera";break;case "volume_name":values[c]="external_primary";break;default:throw new AssertionError(projection[c]);}cursor.addRow(values);}return cursor;
        }
        @Override public String getType(Uri uri){return "image/jpeg";}
        @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
        @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
        @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    }
    @Before public void prepare() throws Exception {
        context=RuntimeEnvironment.getApplication();QueueFixture.reset(context);settings=new Settings(context);settings.prefs.edit().clear().putString("token","fixture").putLong("revision",42).putStringSet("albums",Collections.singleton("image:external_primary:camera")).commit();
        session=new Settings.Session("http://127.0.0.1:8787","","fixture","server",new JSONObject().put("user_id","owner"),true,42);
        Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        provider=new Provider();ProviderInfo info=new ProviderInfo();info.authority="media";provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal("media",provider);
    }
    @After public void cleanup() throws Exception {QueueFixture.reset(context);settings.prefs.edit().clear().commit();}
    @Test public void changingFolderStopsOldScanAndANewScanUsesTheNewDestination() throws Exception {
        String next="a".repeat(48);provider.changed=()->settings.folder(session.scope,next,"旅行");
        assertThrows(java.io.InterruptedIOException.class,()->MediaCatalog.scan(context,session,new RunControl(30000)));
        TaskStore tasks=TaskStore.get(context);
        try(Cursor rows=tasks.getReadableDatabase().rawQuery("SELECT folder_id FROM tasks",null)){assertEquals(100,rows.getCount());while(rows.moveToNext())assertEquals("default",rows.getString(0));}
        assertEquals(130,MediaCatalog.scan(context,session,new RunControl(30000)));
        try(Cursor rows=tasks.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tasks WHERE folder_id=?",new String[]{next})){assertTrue(rows.moveToFirst());assertEquals(130,rows.getInt(0));}
    }
    @Test public void changingAccountStopsBeforeCommittingAnOldScopeBatch() throws Exception {
        provider.changeAt=4;provider.changed=()->settings.prefs.edit().putLong("revision",43).commit();
        assertThrows(java.io.InterruptedIOException.class,()->MediaCatalog.scan(context,session,new RunControl(30000)));
        assertEquals(0,TaskStore.get(context).summary(session.scope).getJSONArray("items").length());
    }
}
