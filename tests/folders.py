"""Isolated real server: shared folders, per-owner writes, dedup destinations and migration."""
import json,requests,sqlite3
from smoke import Harness,ROOT
h=Harness(ROOT/'build/app/Release/NasServer.exe')
try:
 h.start();h.post('login',{'username':'admin','password':(h.home/'数据/initial-admin-password.txt').read_text().strip()})
 sessions={}
 for name,role in [('folder_owner','member'),('folder_other','member'),('folder_viewer','viewer')]:
  h.post('users',{'username':name,'display_name':name,'role':role,'password':'test123'},status=201);s=requests.Session();h.post('login',{'username':name,'password':'test123'},session=s);sessions[name]=s
 owner,other,viewer=[sessions[x] for x in ['folder_owner','folder_other','folder_viewer']]
 def items(ident,session=None):return (session or h.admin).get(h.url+'folders/'+ident+'/items').json()['items']
 default=owner.get(h.url+'folders').json()['items'][0];h.check(default['id']=='default' and default['can_write'] and not default['can_edit'],'default folder is writable but cannot be renamed/deleted')
 h.post('folders',{'name':'Viewer'},session=viewer,status=403);h.post('folders/default/delete',status=403)
 a=h.post('folders',{'name':'我的备份','description':'fixture'},session=owner,status=201);b=h.post('folders',{'name':'我的备份'},session=other,status=201)
 h.post('folders',{'name':'我的备份'},session=owner,status=409);h.check(a['id']!=b['id'],'different owners may use the same name; own duplicates rejected')
 h.post('folders/'+a['id']+'/edit',{'name':'不能修改'},session=other,status=403);h.post('folders/'+a['id']+'/delete',session=viewer,status=403)
 h.check(viewer.get(h.url+'folders/'+a['id']).json()['can_write'] is False,'all users browse folders while writes remain owner restricted')
 payload=b'shared folder fixture';t,input=h.create(payload,name='fixture.txt',session=owner,folder_id=a['id']);h.chunk(t,payload,0,session=owner)
 h.stop();h.start();resumed=owner.get(h.url+'uploads/'+t['id']).json();h.check(resumed['folder_id']==a['id'] and int(resumed['offset'])==len(payload),'restart retains the queued destination and acknowledged offset')
 h.post('uploads/'+t['id']+'/complete',session=owner,status=202)
 # Admin can await any task, while ownership stays with the original member.
 import time
 for _ in range(150):
  ready=h.admin.get(h.url+'uploads/'+t['id']).json()
  if ready['state']=='READY':break
  time.sleep(.1)
 assert ready['state']=='READY';asset=ready['asset_id'];h.check([x['id'] for x in items(a['id'])]==[asset] and not items('default'),'new upload attaches only to the selected folder')
 h.post('uploads',{**input,'folder_id':'default'},session=owner,status=409);h.check(True,'an existing upload id cannot change its destination')
 dup,_=h.create(payload,session=other,folder_id=b['id']);h.check(dup['state']=='READY' and dup['asset_id']==asset and len(items(b['id']))==1,'dedup immediately attaches the same original to another user folder')
 denied={**input,'client_task_id':'forbidden','folder_id':a['id']};h.post('uploads',denied,session=other,status=403)
 status=h.post('sources/status',{'task_ids':[t['id']],'folder_id':a['id']},session=owner);assert status['items'][0]['in_folder']
 h.post('folders/'+a['id']+'/remove',{'asset_ids':[asset]},session=owner);h.check(not items(a['id']) and len(items('default'))==1 and len(items(b['id']))==1,'moving to default retains other folder references and original')
 h.post('folders/default/move',{'asset_ids':[asset],'target_id':a['id']},session=owner);h.check(not items('default') and len(items(a['id']))==1,'owner can move their uploaded file from default')
 h.post('folders/'+a['id']+'/add',{'asset_ids':[asset,'f'*48]},session=owner,status=404);h.check(len(items(a['id']))==1,'invalid batch rolls back all folder associations')
 h.post('folders/'+a['id']+'/edit',{'name':'中文新目录'},session=owner);h.check(viewer.get(h.url+'folders/'+a['id']).json()['name']=='中文新目录','folder rename is shared')
 pending,data=h.create(b'pending fixture',name='pending.txt',session=owner,folder_id=a['id']);h.chunk(pending,b'pending fixture',0,session=owner)
 h.post('folders/'+a['id']+'/delete',session=owner);h.check(len(items('default'))==1 and len(items(b['id']))==1,'delete moves references to default without removing shared originals')
 h.post('uploads/'+pending['id']+'/complete',session=owner,status=202)
 for _ in range(150):
  done=owner.get(h.url+'uploads/'+pending['id']).json()
  if done['state']=='READY':break
  time.sleep(.1)
 h.check(done['state']=='READY' and done['effective_folder_id']=='default' and done['folder_defaulted'],'pending upload to deleted folder finishes in default with an explicit notice')
 h.post('folders/default/move',{'asset_ids':[done['asset_id']],'target_id':b['id']},session=other,status=403);h.check(any(x['id']==done['asset_id'] for x in items('default')),'member cannot remove another contributor original from shared default')
 newer,_=h.create(payload,session=owner,folder_id=a['id']);h.check(newer['folder_defaulted'] and newer['state']=='READY','queued request for a deleted destination safely falls back to default')
 h.check(h.admin.get(h.url+'assets/'+asset+'/original').content==payload,'all folder changes preserve original bytes')
 c=h.post('folders',{'name':'After restart'},session=owner,status=201);h.post('folders/'+c['id']+'/add',{'asset_ids':[asset]},session=owner)
 h.stop();h.start();h.check(len(items(c['id']))==1 and len(items(b['id']))==1,'folder links survive restart and are not reclassified into default')
 # Independent references permit normal files and stable 48-item paging.
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  template=db.execute('SELECT sha256,size,path,name,mime,category,created,modified,state FROM assets WHERE id=?',(asset,)).fetchone()
  for i in range(55):
   ident=f'{i+100:048x}';db.execute('INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state) VALUES(?,?,?,?,?,?,?,?,?,?)',(ident,f'{i+100:064x}',*template[1:]));db.execute('INSERT INTO folder_items(folder_id,asset_id) VALUES(?,?)',(c['id'],ident))
 before='';seen=[]
 while True:
  page=h.admin.get(h.url+'folders/'+c['id']+'/items',params={'before':before,'type':'file'}).json();assert len(page['items'])<=48;seen.extend(x['id'] for x in page['items']);before=page['next']
  if not before:break
 h.check(len(seen)==56 and len(set(seen))==56,'normal files paginate without omissions or duplicate rows')
 for i in range(50):h.post('folders',{'name':'Folder '+str(i)},session=owner,status=201)
 before='';seen=[]
 while True:
  page=owner.get(h.url+'folders',params={'before':before}).json();assert len(page['items'])<=48;seen.extend(x['id'] for x in page['items']);before=page['next']
  if not before:break
 h.check(len(seen)==len(set(seen))==53 and seen[0]=='default','folder list pages preserve default-first order and all visible owners')
 legacy,_=h.create(b'legacy default fixture',name='old-client.txt');h.chunk(legacy,b'legacy default fixture',0);legacy=h.finish(legacy);h.check(any(x['id']==legacy['asset_id'] for x in items('default')),'old clients omitting folder_id continue backing up into default')
 # Simulate a pre-folder catalog: only remove this feature's own schema and marker.
 h.stop()
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  db.execute('DROP TABLE folder_items');db.execute('DROP TABLE folders');db.execute("DELETE FROM settings WHERE key='folder_schema'")
 h.start();before='';migrated=set()
 while True:
  page=h.admin.get(h.url+'folders/default/items',params={'before':before}).json();migrated.update(x['id'] for x in page['items']);before=page['next']
  if not before:break
 h.check(migrated=={x['id'] for x in h.admin.get(h.url+'assets',params={'limit':100}).json()['items']},'old catalog migrates existing READY assets to default without touching originals')
 (h.home/'FOLDERS-PASS.json').write_text(json.dumps({'checks':h.checks},ensure_ascii=False,indent=2),encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
