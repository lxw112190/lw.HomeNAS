"""Real isolated server: soft deletion, restore, shared ownership and dedup."""
import json, requests, sqlite3
from smoke import Harness, ROOT
h=Harness(ROOT/'build/app/Release/NasServer.exe')
try:
 h.start();h.post('login',{'username':'admin','password':(h.home/'数据/initial-admin-password.txt').read_text().strip()})
 sessions=[]
 for name,role in [('trash_owner','member'),('trash_other','member'),('trash_viewer','viewer')]:
  h.post('users',{'username':name,'display_name':name,'role':role,'password':'test123'},status=201);s=requests.Session();h.post('login',{'username':name,'password':'test123'},session=s);sessions.append(s)
 owner,other,viewer=sessions
 folder=h.post('folders',{'name':'保留的分类'},session=owner,status=201)
 album=h.post('albums',{'name':'保留的图集'},session=owner,status=201)
 payload=b'recycle original fixture';task,_=h.create(payload,name='original.jpg',session=owner,folder_id=folder['id']);h.chunk(task,payload,0,session=owner);task=h.finish(task);aid=task['asset_id']
 h.post('albums/'+album['id']+'/add',{'asset_ids':[aid]},session=owner)
 change=lambda action,ids,session=owner,status=200:h.post('assets/'+action,{'asset_ids':ids},session=session,status=status)
 change('trash',[aid],viewer,403);change('trash',[aid],other,403)
 h.check(owner.get(h.url+'assets/'+aid+'/original').content==payload,'rejected deletions preserve bytes')
 change('trash',[aid,'f'*48],owner,404)
 h.check(owner.get(h.url+'assets/'+aid+'/original').status_code==200,'mixed invalid batch rolls back deletion')
 change('trash',[aid])
 for route in ['original','thumbnail','metadata']:
  h.check(owner.get(h.url+'assets/'+aid+'/'+route).status_code==404,'trashed '+route+' is hidden')
 h.check(not any(x['id']==aid for x in owner.get(h.url+'assets?order=timeline').json()['items']),'trash hidden from timeline')
 h.check(owner.get(h.url+'folders/'+folder['id']+'/items').json()['items']==[] and owner.get(h.url+'albums/'+album['id']+'/items').json()['items']==[],'trash hidden from folders and albums')
 h.check(other.get(h.url+'trash').json()['items']==[] and viewer.get(h.url+'trash').status_code==403,'member trash scope and read-only denial')
 h.check(owner.post(h.url+'sources/status',json={'task_ids':[task['id']],'folder_id':folder['id']}).json()['items'][0]['state']=='TRASHED','phone source status distinguishes trash from missing bytes')
 duplicate,_=h.create(payload,name='again.jpg',session=owner,folder_id=folder['id'])
 h.check(duplicate['asset_id']==aid and owner.get(h.url+'assets/'+aid+'/original').status_code==404,'dedup reuses original without silently restoring trash')
 h.stop();h.start()
 h.check(owner.get(h.url+'trash').json()['items'][0]['id']==aid,'trash persists across restart')
 change('restore',[aid],other,403);change('restore',[aid],viewer,403)
 change('restore',[aid]);h.check(owner.get(h.url+'assets/'+aid+'/original').content==payload,'restore returns exact original bytes')
 h.check(owner.get(h.url+'folders/'+folder['id']+'/items').json()['items'][0]['id']==aid and owner.get(h.url+'albums/'+album['id']+'/items').json()['items'][0]['id']==aid,'restore retains folder and album memberships')
 change('trash',[aid]);h.post('folders/'+folder['id']+'/delete',{},session=owner);change('restore',[aid],h.admin)
 h.check(any(x['id']==aid for x in owner.get(h.url+'folders/default/items').json()['items']),'restore after folder removal has a live default membership')
 change('trash',[aid]);change('restore',[aid],h.admin)
 change('trash',[aid,aid],owner,400);change('trash',[],owner,400)
 h.check(owner.get(h.url+'assets/'+aid+'/original').status_code==200,'invalid requests do not hide original')
 h.check(owner.get(h.url+'trash?before=bad').status_code==400,'invalid recycle cursor rejected')
 # Preview/move contract exposes current memberships, respects existing folder write controls.
 memberships=owner.get(h.url+'assets/'+aid+'/folders').json()['items'];h.check(any(x['id']=='default' and x['can_write'] for x in memberships),'preview can discover writable source folders')
 target=h.post('folders',{'name':'移动目标'},session=owner,status=201);h.post('folders/default/move',{'asset_ids':[aid],'target_id':target['id']},session=owner)
 h.check(owner.get(h.url+'folders/'+target['id']+'/items').json()['items'][0]['id']==aid,'preview move route relocates folder association')
 h.check(owner.get(h.url+'assets/'+aid+'/original').content==payload,'move does not change original')
 # Pagination and search operate on recycle records, not on the active library.
 ids=[]
 for i in range(49):
  content=('trash page '+str(i)).encode();t,_=h.create(content,name='page-'+str(i)+'.txt',session=owner);h.chunk(t,content,0,session=owner);ids.append(h.finish(t)['asset_id'])
 change('trash',ids)
 page=owner.get(h.url+'trash').json();second=owner.get(h.url+'trash',params={'before':page['next']}).json();visited=[x['id'] for x in page['items']+second['items']]
 h.check(len(page['items'])==48 and len(set(visited))==49 and not second['next'],'recycle cursor pagination has no omissions or duplicates')
 h.check(len(owner.get(h.url+'trash',params={'q':'page-48.txt','type':'file'}).json()['items'])==1 and not owner.get(h.url+'trash',params={'type':'image'}).json()['items'],'recycle filename and category filtering are effective')
 summary=owner.get(h.url+'overview').json();h.check(int(summary['trash']['files'])==49 and int(summary['trash']['bytes'])>0,'overview distinguishes retained trash bytes from active library')
 change('restore',ids);h.check(not owner.get(h.url+'trash').json()['items'],'batch restore returns all paged items')
 # Explicit administrator-only permanent deletion; no user files are used here.
 def trash_row(ident):return next(x for x in h.admin.get(h.url+'trash').json()['items'] if x['id']==ident)
 def purge(ident,record,session=h.admin,status=200,confirm='DELETE'):
  return h.post('assets/'+ident+'/purge',{'confirm':confirm,'deleted_at':record},session=session,status=status)
 def original_path(ident):
  with sqlite3.connect(h.home/'数据/catalog.db') as db:relative=db.execute('SELECT path FROM assets WHERE id=?',(ident,)).fetchone()[0]
  path=(h.home/'数据'/relative).resolve();assert path.is_relative_to(h.home.resolve());return path
 purge(aid,'0',status=409);h.check(owner.get(h.url+'assets/'+aid+'/original').content==payload,'active originals cannot be purged')
 change('trash',[aid]);record=trash_row(aid);path=original_path(aid);cache=h.home/'数据/cache'/(aid+'.jpg');cache.write_bytes(b'synthetic cache')
 purge(aid,record['deleted_at'],owner,403);purge(aid,record['deleted_at'],viewer,403);purge(aid,record['deleted_at'],status=400,confirm='');purge(aid,str(int(record['deleted_at'])+1),status=409)
 h.check(path.read_bytes()==payload and cache.exists(),'permission, confirmation and stale-record failures preserve bytes')
 other_task,_=h.create(payload,name='shared.txt',session=other);h.check(other_task['asset_id']==aid,'another member deduplicates to the same trashed original')
 import ctypes
 from ctypes import wintypes
 kernel=ctypes.WinDLL('kernel32',use_last_error=True);kernel.CreateFileW.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,wintypes.DWORD,wintypes.LPVOID,wintypes.DWORD,wintypes.DWORD,wintypes.HANDLE];kernel.CreateFileW.restype=wintypes.HANDLE;kernel.CloseHandle.argtypes=[wintypes.HANDLE]
 handle=kernel.CreateFileW(str(path),0x80000000,1,None,3,128,None);assert handle!=wintypes.HANDLE(-1).value
 try:
  purge(aid,record['deleted_at'],status=409);h.check(path.read_bytes()==payload and trash_row(aid)['purging'],'locked original retains durable pending-delete record and original bytes')
 finally:kernel.CloseHandle(handle)
 purge(aid,record['deleted_at']);h.check(not path.exists() and not cache.exists(),'permanent deletion removes original and thumbnail bytes')
 h.check(not any(x['id']==aid for x in h.admin.get(h.url+'trash').json()['items']),'purged item leaves recycle list')
 change('restore',[aid],h.admin,404);purge(aid,record['deleted_at'],status=409)
 h.check(owner.get(h.url+'assets/'+aid+'/original').status_code==404 and other.get(h.url+'assets/'+aid+'/original').status_code==404,'purged original cannot be downloaded or restored by any member')
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  dangling=sum(db.execute('SELECT count(*) FROM '+table+' WHERE asset_id=?',(aid,)).fetchone()[0] for table in ['sources','folder_items','album_items','asset_trash'])
 h.check(dangling==0,'purge removes source, folder, album and trash references')
 status=owner.post(h.url+'sources/status',json={'task_ids':[task['id']]}).json()['items'][0]['state'];h.check(status!='READY','phone does not report purged original as backed up')
 fresh,_=h.create(payload,name='again-new-task.txt',session=owner);h.check(fresh['state']=='UPLOADING' and fresh['offset']=='0','new upload of purged content does not falsely deduplicate')
 h.chunk(fresh,payload,0,session=owner);newid=h.finish(fresh)['asset_id'];h.check(newid!=aid and owner.get(h.url+'assets/'+newid+'/original').content==payload,'explicit new backup restores content under a new asset')
 # Simulate an interrupted durable deletion with a corrupt path; never touch an outside file.
 change('trash',[newid]);path=original_path(newid);relative=str(path.relative_to(h.home/'数据'));h.stop();outside=h.home/'must-keep.txt';outside.write_bytes(b'outside fixture')
 with sqlite3.connect(h.home/'数据/catalog.db') as db:db.execute("UPDATE assets SET state='PURGING',path='../must-keep.txt' WHERE id=?",(newid,))
 h.start();record=trash_row(newid);h.check(record['purging'] and not record['can_restore'],'interrupted deletion remains visible and cannot be restored')
 purge(newid,record['deleted_at'],status=409);h.check(outside.read_bytes()==b'outside fixture' and path.read_bytes()==payload,'path escape is rejected without deleting either file')
 h.stop()
 with sqlite3.connect(h.home/'数据/catalog.db') as db:db.execute('UPDATE assets SET path=? WHERE id=?',(relative,newid))
 # Original already absent models a crash between the filesystem and database finalization.
 path.unlink();h.start();h.check(not any(x['id']==newid for x in h.admin.get(h.url+'trash').json()['items']),'startup idempotently finishes interrupted deletion after original was removed')
 h.check(owner.get(h.url+'assets/'+ids[0]+'/original').status_code==200 and outside.read_bytes()==b'outside fixture','recovery preserves unrelated originals and files')
 # Restore and permanent-delete must not both win against the same recycle record.
 import concurrent.futures,threading
 race_payload=b'purge restore concurrency fixture';race_task,_=h.create(race_payload,name='race.txt',session=owner);h.chunk(race_task,race_payload,0,session=owner);race_id=h.finish(race_task)['asset_id'];change('trash',[race_id]);race_record=trash_row(race_id);race_path=original_path(race_id);barrier=threading.Barrier(2)
 def compete(action):
  session=requests.Session();session.cookies.update(h.admin.cookies);barrier.wait()
  return session.post(h.url+('assets/restore' if action=='restore' else 'assets/'+race_id+'/purge'),json={'asset_ids':[race_id]} if action=='restore' else {'confirm':'DELETE','deleted_at':race_record['deleted_at']},timeout=15).status_code
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(compete,['restore','purge']))
 h.check(results.count(200)==1 and all(code in (200,404,409) for code in results),'restore and permanent delete have exactly one winner')
 download=owner.get(h.url+'assets/'+race_id+'/original');h.check((results[0]==200 and download.content==race_payload and race_path.exists()) or (results[1]==200 and download.status_code==404 and not race_path.exists()),'concurrent winner agrees with both database visibility and actual bytes')
 report={'count':len(h.checks),'checks':h.checks};(h.home/'RECYCLE-PASS.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print('Recycle:',len(h.checks),'checks')
finally:h.stop()
