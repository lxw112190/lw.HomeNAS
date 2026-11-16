"""Isolated maintenance, sorting, permissions preview and history regressions."""
from pathlib import Path
import argparse, hashlib, json, sqlite3, time, requests
from smoke import Harness

p=argparse.ArgumentParser();p.add_argument('--exe',type=Path,required=True)
h=Harness(p.parse_args().exe)
def get(path):
 r=h.admin.get(h.url+path);assert r.status_code==200,r.text;return r.json()
def wait_for(fn,timeout=20):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  value=fn()
  if value:return value
  time.sleep(.05)
 raise AssertionError('Timed out')
try:
 h.start();h.post('login',{'username':'admin','password':(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip()})
 h.post('users',{'username':'reader','display_name':'读者','password':'Test12345','role':'viewer'},status=201)
 reader=requests.Session();h.post('login',{'username':'reader','password':'Test12345'},session=reader)
 for path in ['admin/library-maintenance','admin/health/results','admin/history']:
  assert reader.get(h.url+path).status_code==403
 h.post('admin/health/start',{'rate_mib':1},session=reader,status=403)
 h.post('admin/backup-schedule',{'enabled':True,'interval_hours':24},session=reader,status=403)
 public=h.post('folders',{'name':'public'},status=201);child=h.post('folders',{'name':'child','parent_id':public['id']},status=201)
 private=h.post('folders',{'name':'private'},status=201)
 h.post('folders/'+public['id']+'/permissions',{'policy':'public','confirm_access_change':True})
 history_before=get('admin/history')['items']
 impact=h.post('folders/'+child['id']+'/relocation-impact',{'parent_id':private['id']})
 assert impact['folders']=='1' and any(x['name']=='读者' and x['before']['readable']=='1' and x['after']['readable']=='0' for x in impact['changes'])
 assert get('folders/'+child['id'])['parent_id']==public['id']
 assert get('admin/history')['items']==history_before,'Preview must not persist changes or history'
 names=['IMG_001.txt','IMGX001.txt','100%.txt','100x.txt','large.bin'];ids=[];tasks=[]
 for i,name in enumerate(names):
  payload=(bytes([65+i])*(3*1024*1024)) if name=='large.bin' else (b'fixture-'+str(i).encode())
  task,request=h.create(payload,name=name,folder_id=child['id']);tasks.append((task,request))
  for offset in range(0,len(payload),1048576):h.chunk(task,payload,offset)
  ids.append(h.finish(task)['asset_id'])
 assert [x['name'] for x in get('assets?q=IMG_001')['items']]==['IMG_001.txt']
 assert [x['name'] for x in get('folders/'+child['id']+'/items?q=100%25')['items']]==['100%.txt']
 ordered=get('assets?sort=name_asc&limit=2');second=get('assets?sort=name_asc&limit=2&before='+ordered['next']);last=get('assets?sort=name_asc&limit=2&before=@last')
 assert [x['name'] for x in ordered['items']+second['items']+last['items']]==sorted(names,key=str.lower)
 assert ordered['page_index']==0 and last['page_index']==2 and not last['next']
 assert get('folders/'+child['id']+'/items?sort=size_desc')['items'][0]['name']=='large.bin'
 assert h.admin.get(h.url+'assets?sort=bad').status_code==400
 # Old attribute-derived task IDs are resumed only when content and destination match.
 old,req=tasks[0];request=dict(req,client_task_id='new-content-identity',legacy_client_task_id=req['client_task_id'])
 assert h.post('uploads',request,status=201)['id']==old['id']
 changed=dict(request,sha256=hashlib.sha256(b'OTHERDATA').hexdigest(),size='9',client_task_id='new-other-content')
 assert h.post('uploads',changed,status=201)['id']!=old['id']
 manual=h.post('admin/backups',status=201);old_auto=[]
 for _ in range(2):
  snap=h.post('admin/backups',status=201);file=h.home/'数据/backups'/snap['id']/'backup.json';value=json.loads(file.read_text(encoding='utf-8'));value['automatic']=True;file.write_text(json.dumps(value),encoding='utf-8');old_auto.append(file)
 h.post('admin/backup-schedule',{'enabled':True,'interval_hours':24,'keep':1})
 h.post('admin/health/start',{'rate_mib':1})
 h.post('admin/health/pause');paused=get('admin/library-maintenance');assert paused['health']['state']=='PAUSED'
 h.post('admin/health/resume');h.stop()
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  schedule=json.loads(db.execute("SELECT value FROM settings WHERE key='backup_schedule'").fetchone()[0]);schedule['next_run']='0';db.execute("UPDATE settings SET value=? WHERE key='backup_schedule'",(json.dumps(schedule),))
  path=db.execute('SELECT path FROM assets WHERE id=?',(ids[0],)).fetchone()[0]
 corrupt=h.home/'数据'/path;before=corrupt.read_bytes();corrupt.write_bytes(b'X'*len(before))
 h.start();assert get('admin/library-maintenance')['health']['state']=='INTERRUPTED'
 wait_for(lambda:get('admin/library-maintenance')['schedule']['last_success']!='0')
 assert (h.home/'数据/backups'/manual['id']/'backup.json').is_file()
 assert not any(x.exists() for x in old_auto),'Only old scheduled snapshots should be removed'
 # Restart the check to include intentionally changed test bytes in the run.
 h.post('admin/health/cancel');h.post('admin/health/start',{'rate_mib':16})
 wait_for(lambda:get('admin/library-maintenance')['health']['state']=='COMPLETED')
 report=get('admin/health/results');assert any(x['asset_id']==ids[0] and x['issue']=='HASH_MISMATCH' for x in report['items'])
 assert corrupt.read_bytes()==b'X'*len(before),'Health check must not repair/delete original bytes'
 assert get('admin/library-maintenance')['counts']['total']=='5'
 history=get('admin/history')['items'];assert any(x['action']=='backup.created' and x['actor']=='system' for x in history)
 assert any(x['action']=='health.completed' for x in history)
 assert all('password' not in x and 'token' not in x for x in history)
 album=h.post('albums',{'name':'cover regression'},status=201);photos=[]
 for name,payload in [('photo_1.jpg',b'photo-one'),('photoX1.jpg',b'photo-two')]:
  task,_=h.create(payload,name=name);h.chunk(task,payload,0);photos.append(h.finish(task)['asset_id'])
 h.post('albums/'+album['id']+'/add',{'asset_ids':photos})
 h.post('albums/'+album['id']+'/cover',{'asset_id':min(photos)})
 automatic=h.post('albums/'+album['id']+'/cover',{'asset_id':''})
 assert automatic['cover']['id']==max(photos)
 assert [x['name'] for x in get('albums/'+album['id']+'/items?sort=name_desc')['items']]==['photoX1.jpg','photo_1.jpg']
 assert len(get('albums/'+album['id']+'/items?q=photo_1')['items'])==1
 h.post('albums/'+album['id']+'/cover',{'asset_id':''},session=reader,status=403)
 assert get('admin/library-maintenance')['counts']['total']=='5','Completed inventory must exclude files added after start'
 h.check(True,'Maintenance ACL, pause/restart, bounded hash verification, scheduled retention/manual preservation, literal search, sorting, legacy resume, atomic history and non-mutating permission preview')
 print('Evidence:',h.home)
finally:h.stop()
