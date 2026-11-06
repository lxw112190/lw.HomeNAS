package com.lw.homenas;

import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Size;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Permission-scoped daily picker. JS receives opaque handles, never content URIs. */
final class MediaPicker {
    private final Context context;private final Settings settings;
    private String scope="";private final Map<String,Stamp> stamps=new HashMap<>();
    private final Map<String,Item> handles=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,String> identities=new HashMap<>();
    private static final class Stamp {long id,size,modified;String state,remote,sha,folder;}
    private static final class Item {String handle,name,album,kind,identity;Uri uri;long id,size,modified,width,height,duration;}
    MediaPicker(Context context,Settings settings){this.context=context;this.settings=settings;}
    synchronized void clear(){scope="";stamps.clear();handles.clear();identities.clear();}
    private String key(Uri uri,long size,long modified) {
        try {
            Uri media=DocumentsContract.isDocumentUri(context,uri)?MediaStore.getMediaUri(context,uri):uri;
            if(media!=null&&"media".equals(media.getAuthority())) {
                List<String> parts=media.getPathSegments();String volume=parts.get(0),id=media.getLastPathSegment(),kind=parts.contains("video")?"video":"image";
                if(volume.equals("external"))try(Cursor cursor=context.getContentResolver().query(media,new String[]{"volume_name"},null,null,null)){if(cursor!=null&&cursor.moveToFirst())volume=cursor.getString(0);}
                return volume+":"+kind+":"+id+":"+size+":"+modified+":"+(uri.getQuery()==null?"":uri.getQuery());
            }
        }catch(Exception ignored){}
        return uri+":"+size+":"+modified;
    }
    private void index(Settings.Session session,RunControl control) throws Exception {control.check();clear();scope=session.scope;}
    private void lookup(Settings.Session session,Item item,RunControl control) throws Exception {
        if(stamps.containsKey(item.identity))return;Stamp found=null;
        try(Cursor c=TaskStore.get(context).getReadableDatabase().rawQuery("SELECT id,uri,size,modified,state,remote,sha,folder_id FROM tasks WHERE scope=? AND size=? AND modified=? ORDER BY (folder_id=?) DESC,(state='READY') DESC,id DESC",new String[]{session.scope,Long.toString(item.size),Long.toString(item.modified),settings.folder(session.scope)})){
            while(c.moveToNext()){control.check();Uri uri=Uri.parse(c.getString(1));String last=uri.getLastPathSegment();
                if("media".equals(uri.getAuthority())&&!Long.toString(item.id).equals(last))continue;
                if("com.android.providers.media.documents".equals(uri.getAuthority())&&last!=null&&!last.endsWith(":"+item.id))continue;
                if(!key(uri,c.getLong(2),c.getLong(3)).equals(item.identity))continue;
                found=new Stamp();found.id=c.getLong(0);found.size=c.getLong(2);found.modified=c.getLong(3);found.state=c.getString(4);found.remote=c.getString(5);found.sha=c.getString(6);found.folder=c.getString(7);break;
            }
        }
        stamps.put(item.identity,found);
    }
    private static SimpleDateFormat dates(){SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);format.setLenient(false);return format;}
    private static String day(long time){return time>0?dates().format(new Date(time)):"unknown";}
    private static long[] bounds(String day) throws Exception {
        if(day.equals("unknown"))return new long[]{0,1};
        if(!day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("日期无效");
        long start=dates().parse(day).getTime();if(start<0)throw new IllegalArgumentException("日期无效");Calendar calendar=Calendar.getInstance();calendar.setTimeInMillis(start);calendar.add(Calendar.DATE,1);return new long[]{start/1000,calendar.getTimeInMillis()/1000};
    }
    private Cursor query(boolean video,String selection,String[] args,int limit,String[] projection) {
        // SQL selection must carry an explicit SQL sort, including on OEM providers
        // which do not synthesize SQL order from structured SORT_COLUMNS arguments.
        Bundle options=new Bundle();options.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"is_pending=0"+(selection.isEmpty()?"":" AND ("+selection+")"));options.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args);options.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,"date_modified DESC, _id DESC");if(limit>0)options.putInt(ContentResolver.QUERY_ARG_LIMIT,limit);
        return context.getContentResolver().query(video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,projection,options,null);
    }
    synchronized JSONObject days(Settings.Session session,String before,boolean reset,RunControl control) throws Exception {return days(session,before,reset,control,"all");}
    synchronized JSONObject days(Settings.Session session,String before,boolean reset,RunControl control,String kind) throws Exception {
        if(!kind.matches("all|image|video"))throw new IllegalArgumentException("媒体类型无效");
        if(reset||!scope.equals(session.scope))index(session,control);
        TreeMap<String,Integer> counts=new TreeMap<>(Collections.reverseOrder());
        for(boolean video:new boolean[]{false,true}){if(!MediaCatalog.canRead(context,video)||!kind.equals("all")&&!kind.equals(video?"video":"image"))continue;String previous="";int seen=0;long previousTime=Long.MAX_VALUE;
            try(Cursor c=query(video,before.isEmpty()?"":"date_modified<?",before.isEmpty()?new String[]{}:new String[]{Long.toString(bounds(before)[0])},0,new String[]{"_id","date_modified"})){
                if(c==null)continue;while(c.moveToNext()){control.check();long time=c.getLong(1);if(time>previousTime){Diagnostics.event("media.query_order","unexpected ascending time; sdk="+android.os.Build.VERSION.SDK_INT);throw new IOException("手机媒体库未按日期返回，请导出诊断日志；可先用系统选择文件");}previousTime=time;String date=day(time*1000L);if(!date.equals(previous)){if(++seen>31)break;previous=date;}counts.put(date,counts.getOrDefault(date,0)+1);}
            }
        }
        // Unknown dates belong at the end rather than above actual YYYY-MM-DD values.
        Integer unknown=counts.remove("unknown");JSONArray items=new JSONArray();String next="";
        for(Map.Entry<String,Integer> entry:counts.entrySet()){if(items.length()==30){next=items.getJSONObject(29).getString("day");break;}items.put(new JSONObject().put("day",entry.getKey()).put("count",entry.getValue()));}
        if(next.isEmpty()&&unknown!=null){if(items.length()==30)next=items.getJSONObject(29).getString("day");else items.put(new JSONObject().put("day","unknown").put("count",unknown));}
        Diagnostics.event("media.days","groups="+items.length()+" more="+!next.isEmpty()+" access="+MediaCatalog.access(context)+(items.length()>0?" newest="+items.getJSONObject(0).getString("day"):""));
        return new JSONObject().put("items",items).put("next",next).put("access",MediaCatalog.access(context)).put("mediaAccess",MediaPermissions.state(context));
    }
    private List<Item> records(String date,String before,int limit,RunControl control) throws Exception {return records(date,before,limit,control,"all",null);}
    private List<Item> records(String date,String before,int limit,RunControl control,String kind,long[] ceilings) throws Exception {
        long[] range=date.equals("*")?new long[]{0,Long.MAX_VALUE}:bounds(date);List<Item> result=new ArrayList<>();long cursorTime=Long.MAX_VALUE,cursorId=Long.MAX_VALUE;String cursorKind="";
        if(!before.isEmpty()){if(!before.matches("[0-9]{1,19}:[0-9]{1,19}:[01]"))throw new IllegalArgumentException("照片游标无效");String[] parts=before.split(":");cursorTime=Long.parseLong(parts[0]);cursorId=Long.parseLong(parts[1]);cursorKind=parts[2];}
        for(boolean video:new boolean[]{false,true}){if(!MediaCatalog.canRead(context,video)||!kind.equals("all")&&!kind.equals(video?"video":"image"))continue;
            String where=date.equals("*")?"1=1":date.equals("unknown")?"date_modified<=0":"date_modified>=? AND date_modified<?";List<String> args=new ArrayList<>();if(!date.equals("unknown")&&!date.equals("*")){args.add(Long.toString(range[0]));args.add(Long.toString(range[1]));}
            if(ceilings!=null){where+=" AND _id<=?";args.add(Long.toString(ceilings[video?1:0]));}
            if(!before.isEmpty()){where+=" AND (date_modified<? OR (date_modified=? AND _id"+((video?"1":"0").compareTo(cursorKind)<0?"<=":"<")+"?))";args.add(Long.toString(cursorTime));args.add(Long.toString(cursorTime));args.add(Long.toString(cursorId));}
            String[] projection={"_id","_display_name","_size","date_modified","volume_name","bucket_display_name","width","height"};
            try(Cursor c=query(video,where,args.toArray(new String[0]),limit+1,projection)){
                if(c==null)continue;int read=0;long previousTime=Long.MAX_VALUE,previousId=Long.MAX_VALUE;while(c.moveToNext()&&read++<=limit){control.check();
                    long time=c.getLong(3),mediaId=c.getLong(0);boolean inclusive=(video?"1":"0").compareTo(cursorKind)<0;
                    if(time>previousTime||time==previousTime&&mediaId>previousId||ceilings!=null&&mediaId>ceilings[video?1:0]||!before.isEmpty()&&!(time<cursorTime||time==cursorTime&&(inclusive?mediaId<=cursorId:mediaId<cursorId)))throw new IOException("手机媒体库未按分页条件返回，已停止；请导出诊断日志");
                    previousTime=time;previousId=mediaId;Item item=new Item();item.id=c.getLong(0);item.name=Protocol.fileName(c.getString(1));item.size=c.getLong(2);item.modified=c.getLong(3)*1000L;item.kind=video?"video":"image";item.album=c.getString(5);if(item.album==null)item.album="";item.width=c.getLong(6);item.height=c.getLong(7);Uri base=video?MediaStore.Video.Media.getContentUri(c.getString(4)):MediaStore.Images.Media.getContentUri(c.getString(4));item.uri=MediaCatalog.originalIfAllowed(context,ContentUris.withAppendedId(base,item.id));item.identity=key(item.uri,item.size,item.modified);result.add(item);}
            }
        }
        result.sort((a,b)->{int order=Long.compare(b.modified,a.modified);if(order==0)order=Long.compare(b.id,a.id);if(order==0)order=b.kind.compareTo(a.kind);return order;});return result;
    }
    private Map<String,JSONObject> verify(Settings.Session session,List<Item> items,NativeApi api,RunControl control) throws Exception {
        LinkedHashSet<String> ids=new LinkedHashSet<>();for(Item item:items){Stamp stamp=stamps.get(item.identity);if(stamp!=null&&stamp.state.equals("READY")&&Protocol.assetId(stamp.remote))ids.add(stamp.remote);}
        Map<String,JSONObject> checked=new HashMap<>();List<String> all=new ArrayList<>(ids);
        for(int start=0;start<all.size();start+=100){control.check();JSONArray batch=new JSONArray(all.subList(start,Math.min(start+100,all.size())));JSONObject response=api.post("sources/status",new JSONObject().put("task_ids",batch).put("folder_id",settings.folder(session.scope)),control);JSONArray rows=response.getJSONArray("items");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);checked.put(row.getString("id"),row);}for(int i=0;i<batch.length();i++){String id=batch.getString(i);if(!checked.containsKey(id))checked.put(id,new JSONObject().put("state","MISSING"));}}
        if(!settings.current(session))throw new SecurityException("登录已改变");return checked;
    }
    private String status(Item item,Map<String,JSONObject> checked){
        Stamp stamp=stamps.get(item.identity);if(stamp==null)return "NEW";if(!stamp.state.equals("READY"))return stamp.state;
        JSONObject remote=checked.get(stamp.remote);if(remote==null)return "UNCONFIRMED";
        if(remote.optString("state").equals("TRASHED"))return "TRASHED";
        return remote.optString("state").equals("READY")&&remote.optString("sha256").equals(stamp.sha)&&remote.optString("size").equals(Long.toString(item.size))?(remote.optBoolean("in_folder")?"READY":"BACKED_UP"):"MISSING";
    }
    synchronized JSONObject items(Settings.Session session,String date,String before,NativeApi api,RunControl control) throws Exception {return items(session,date,before,api,control,"all");}
    synchronized JSONObject items(Settings.Session session,String date,String before,NativeApi api,RunControl control,String kind) throws Exception {
        if(!scope.equals(session.scope))index(session,control);List<Item> items=records(date,before,50,control,kind,null);boolean more=items.size()>50;if(more)items=new ArrayList<>(items.subList(0,50));Map<String,JSONObject> checked;for(Item item:items)lookup(session,item,control);
        try{checked=verify(session,items,api,control);}catch(Exception error){Diagnostics.error("picker.status",error);checked=new HashMap<>();}
        JSONArray rows=new JSONArray();for(Item item:items){item.handle=identities.get(item.identity);if(item.handle==null){if(handles.size()>=5000)throw new IOException("请先完成本次选择再刷新");item.handle=UUID.randomUUID().toString().replace("-","");identities.put(item.identity,item.handle);}handles.put(item.handle,item);rows.put(new JSONObject().put("id",item.handle).put("name",item.name).put("size",Long.toString(item.size)).put("modified",Long.toString(item.modified)).put("width",item.width).put("height",item.height).put("kind",item.kind).put("status",status(item,checked)));}
        String next="";if(more){Item last=items.get(items.size()-1);next=(last.modified/1000)+":"+last.id+":"+(last.kind.equals("video")?"1":"0");}
        return new JSONObject().put("items",rows).put("next",next).put("day",date);
    }
    synchronized byte[] thumbnail(Settings.Session session,String handle) throws Exception {
        if(!session.scope.equals(scope)||!settings.current(session))throw new SecurityException("登录已改变");Item item=handles.get(handle);if(item==null)throw new IOException("照片列表已失效");
        Bitmap bitmap=context.getContentResolver().loadThumbnail(item.uri,new Size(256,256),null);try{ByteArrayOutputStream data=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,80,data)||data.size()>1024*1024)throw new IOException("缩略图不可用");return data.toByteArray();}finally{bitmap.recycle();}
    }
    synchronized JSONObject months(Settings.Session session,String kind,RunControl control) throws Exception {
        if(!scope.equals(session.scope))index(session,control);
        return inventory(kind,control);
    }
    private JSONObject inventory(String kind,RunControl control) throws Exception {
        if(!kind.matches("all|image|video"))throw new IllegalArgumentException("媒体类型无效");
        TreeMap<String,long[]> groups=new TreeMap<>(Collections.reverseOrder());long[] ceilings={0,0};
        for(boolean video:new boolean[]{false,true}){
            if(!MediaCatalog.canRead(context,video)||!kind.equals("all")&&!kind.equals(video?"video":"image"))continue;
            try(Cursor c=query(video,"",new String[]{},0,new String[]{"_id","date_modified","_size"})){
                if(c==null)continue;while(c.moveToNext()){control.check();ceilings[video?1:0]=Math.max(ceilings[video?1:0],c.getLong(0));String date=day(c.getLong(1)*1000L),month=date.equals("unknown")?date:date.substring(0,7);long[] group=groups.get(month);if(group==null){group=new long[2];groups.put(month,group);}group[0]++;group[1]=Math.addExact(group[1],Math.max(0,c.getLong(2)));}
            }
        }
        JSONArray items=new JSONArray();long[] unknown=groups.remove("unknown");for(Map.Entry<String,long[]> row:groups.entrySet())items.put(new JSONObject().put("month",row.getKey()).put("count",row.getValue()[0]).put("bytes",Long.toString(row.getValue()[1])));
        if(unknown!=null)items.put(new JSONObject().put("month","unknown").put("count",unknown[0]).put("bytes",Long.toString(unknown[1])));
        return new JSONObject().put("items",items).put("image_ceiling",ceilings[0]).put("video_ceiling",ceilings[1]);
    }
    interface EnqueueProgress {void update(long scanned,long added,long skipped,long bytes);}
    synchronized JSONObject enqueue(Settings.Session session,JSONObject selection,NativeApi api,RunControl control) throws Exception {return enqueue(session,selection,api,control,(n,a,s,b)->{});}
    synchronized JSONObject enqueue(Settings.Session session,JSONObject selection,NativeApi api,RunControl control,EnqueueProgress progress) throws Exception {
        if(!scope.equals(session.scope))throw new IOException("请刷新照片列表");
        MediaSelection rules=new MediaSelection(selection);JSONArray ids=selection.optJSONArray("ids"),exclude=selection.optJSONArray("exclude_ids");if(ids==null)ids=new JSONArray();if(exclude==null)exclude=new JSONArray();
        if(ids.length()>5000||exclude.length()>5000)throw new IllegalArgumentException("单张选择或排除过多，请改用日期选择");
        String destination=settings.folder(session.scope),folderName=settings.folderName(session.scope);
        if(!selection.optString("folder_id",destination).equals(destination))throw new IOException("目标文件夹已改变，请刷新后重新选择");
        // Resolve every explicit/excluded handle before committing the first batch.
        Map<String,Item> explicit=new LinkedHashMap<>();Set<String> excluded=new HashSet<>();
        for(int i=0;i<ids.length();i++){Item item=handles.get(ids.getString(i));if(item==null)throw new IOException("照片选择已过期，请刷新");if(!rules.kind.equals("all")&&!rules.kind.equals(item.kind))throw new IOException("媒体筛选已改变，请重新选择");explicit.put(item.identity,item);}
        for(int i=0;i<exclude.length();i++){Item item=handles.get(exclude.getString(i));if(item==null)throw new IOException("排除的照片已过期，请刷新");excluded.add(item.identity);}
        if(!rules.hasRange()&&explicit.isEmpty())throw new IOException("请先选择照片");
        if(!rules.hasRange()){
            for(String identity:excluded)explicit.remove(identity);if(explicit.isEmpty())throw new IOException("已取消所有照片，请重新选择");
            List<Item> picked=new ArrayList<>(explicit.values());long[] stats={0,0,0,0};
            for(int at=0;at<picked.size();at+=100)enqueueBatch(session,picked.subList(at,Math.min(at+100,picked.size())),api,control,destination,folderName,rules.onlyPending,stats,progress);
            return new JSONObject().put("added",stats[1]).put("skipped",stats[2]).put("bytes",Long.toString(stats[3]));
        }
        JSONObject inventory=inventory(rules.kind,control);long[] ceilings={inventory.getLong("image_ceiling"),inventory.getLong("video_ceiling")};
        long[] stats={0,0,0,0};String cursor="";boolean any=false;
        do{
            control.check();if(!settings.current(session))throw new SecurityException("登录已改变");
            if(!destination.equals(settings.folder(session.scope)))throw new IOException("目标文件夹已改变，扫描停止；已入队任务保留原目标");
            List<Item> page=records("*",cursor,100,control,rules.kind,ceilings);boolean more=page.size()>100;
            if(more)page=new ArrayList<>(page.subList(0,100));List<Item> chosen=new ArrayList<>();
            for(Item item:page){boolean picked=rules.includes(day(item.modified))||explicit.containsKey(item.identity);explicit.remove(item.identity);if(picked&&!excluded.contains(item.identity)){chosen.add(item);any=true;}}
            enqueueBatch(session,chosen,api,control,destination,folderName,rules.onlyPending,stats,progress);
            if(!more||page.isEmpty())break;Item last=page.get(page.size()-1);String next=(last.modified/1000)+":"+last.id+":"+(last.kind.equals("video")?"1":"0");if(next.equals(cursor))throw new IOException("媒体库分页未前进，扫描已停止");cursor=next;
        }while(true);
        if(!explicit.isEmpty())throw new IOException("部分所选文件已变化或不可访问，请刷新；已入队任务保留");
        if(!any)throw new IOException("当前范围没有可选择的照片");
        return new JSONObject().put("added",stats[1]).put("skipped",stats[2]).put("bytes",Long.toString(stats[3]));
    }
    private void enqueueBatch(Settings.Session session,List<Item> items,NativeApi api,RunControl control,String folder,String folderName,boolean onlyPending,long[] stats,EnqueueProgress progress) throws Exception {
        stamps.clear();for(Item item:items)lookup(session,item,control);Map<String,JSONObject> checked=verify(session,items,api,control);TaskStore tasks=TaskStore.get(context);
        for(Item item:items){
            control.check();if(!settings.current(session))throw new SecurityException("登录已改变");if(!folder.equals(settings.folder(session.scope)))throw new IOException("目标文件夹已改变，已加入的任务保留原目标");
            MediaCatalog.Info info=MediaCatalog.describe(context.getContentResolver(),item.uri);if(info.size!=item.size||info.modified!=item.modified)throw new IOException("部分文件已变化，请刷新；已入队项目保留");
            String state=status(item,checked);Stamp stamp=stamps.get(item.identity);boolean added=false;
            if(state.equals("UNCONFIRMED"))throw new IOException("无法核实已备份记录，请检查电脑连接后重试");
            if(!state.equals("READY")&&!state.equals("TRASHED")&&!(onlyPending&&java.util.Arrays.asList("QUEUED","HASHING","UPLOADING","VERIFYING","COMMITTING").contains(state))){
                if(state.equals("BACKED_UP")&&stamp!=null&&folder.equals(stamp.folder))added=tasks.relink(stamp.id);
                else if(stamp!=null&&folder.equals(stamp.folder)&&!state.equals("BACKED_UP")){
                    if(state.equals("ERROR")||state.equals("SOURCE_MISSING")||state.equals("MISSING"))added=tasks.requeue(stamp.id,item.uri.toString());
                }else added=tasks.enqueue(session.scope,Protocol.key(item.uri+":"+item.size+":"+item.modified),item.uri.toString(),item.name,item.album,item.size,item.modified,folder,folderName)!=-1;
            }
            stats[0]++;if(added){stats[1]++;stats[3]=Math.addExact(stats[3],Math.max(0,item.size));}else stats[2]++;
            progress.update(stats[0],stats[1],stats[2],stats[3]);
        }
    }
}
