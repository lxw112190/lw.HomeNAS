"""Real server isolation: folder hierarchy, inherited ACL and private media routes."""
import io,json,time,requests,sqlite3,contextlib
from PIL import Image
from smoke import Harness,ROOT
h=Harness(ROOT/'build/app/Release/NasServer.exe')
try:
 h.start();pw=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip();h.post('login',{'username':'admin','password':pw})
 users={};sessions={}
 for name,role in [('alice','member'),('bob','member'),('reader','viewer')]:
  users[name]=h.post('users',{'username':name,'display_name':name,'role':role,'password':'test123'},status=201);s=requests.Session();h.post('login',{'username':name,'password':'test123'},session=s);sessions[name]=s
 a,b,v=[sessions[n] for n in ['alice','bob','reader']]
 def create(name,parent='',session=a):return h.post('folders',{'name':name,'parent_id':parent},session=session,status=201)
 def policy(folder,mode,grants=[],session=a):return h.post('folders/'+folder['id']+'/permissions',{'policy':mode,'grants':[{'user_id':users[n]['id'],'level':level} for n,level in grants],'confirm_access_change':True},session=session)
 root=create('个人资料');child=create('手机照片',root['id']);grand=create('2026年',child['id'])
 h.check(root['policy']=='private' and child['policy']=='inherit' and grand['depth']==3,'new root private, descendants inherit, breadcrumbs have actual depth')
 h.check(b.get(h.url+'folders/'+child['id']).status_code==403 and not any(x['id']==root['id'] for x in b.get(h.url+'folders').json()['items']),'private folder absent from other member lists and direct access denied')
 policy(root,'restricted',[('bob',2),('reader',3)])
 h.check(b.get(h.url+'folders/'+grand['id']).json()['can_write'] and not v.get(h.url+'folders/'+grand['id']).json()['can_write'],'inherited editor works; account viewer caps a manager grant')
 policy(child,'restricted',[('bob',1),('reader',1)])
 h.check(not b.get(h.url+'folders/'+grand['id']).json()['can_write'],'child restriction caps inherited write access')
 policy(child,'private')
 stream=io.BytesIO();Image.new('RGB',(40,30),'orange').save(stream,format='JPEG');data=stream.getvalue();task,_=h.create(data,name='alice-private.jpg',session=a,folder_id=grand['id']);h.chunk(task,data,0,session=a);asset=h.finish(task)['asset_id']
 for route in ['original','original?download=1','metadata','thumbnail']:
  h.check(b.get(h.url+'assets/'+asset+'/'+route).status_code==404,'private media route rejected: '+route)
 h.check(not b.get(h.url+'assets',params={'order':'timeline','q':'alice-private'}).json()['items'],'search and timeline do not reveal private filenames')
 h.check(not b.get(h.url+'assets/calendar').json()['items'] and b.get(h.url+'overview').json()['library']['files']=='0','calendar and overview counts exclude private files')
 h.post('assets/export',{'asset_ids':[asset]},session=b,status=404)
 album=h.post('albums',{'name':'主题'},session=a,status=201);h.post('albums/'+album['id']+'/add',{'asset_ids':[asset]},session=a)
 listing=b.get(h.url+'albums').json()['items'];seen=next(x for x in listing if x['id']==album['id']);h.check(seen['cover'] is None and seen['cover_id']=='' and seen['item_count']=='0' and not b.get(h.url+'albums/'+album['id']+'/items').json()['items'],'album reference, cover and count cannot grant private media access')
 own=create('Bob资料',session=b);h.post('folders/'+own['id']+'/add',{'asset_ids':[asset],'confirm_access_change':True},session=b,status=404)
 duplicate,_=h.create(data,name='bob-owned.jpg',session=b,folder_id=own['id']);h.check(duplicate['state']=='UPLOADING','private hash match never returns another user file instantly');h.chunk(duplicate,data,0,session=b);other=h.finish(duplicate)['asset_id'];h.check(other!=asset and b.get(h.url+'assets/'+other+'/metadata').json()['name']=='bob-owned.jpg','separate destination retains its own media identity and name')
 policy(child,'inherit');payload=b'x'*8192;pending,_=h.create(payload,name='resumable.bin',session=b,folder_id=grand['id']);h.chunk(pending,payload[:4096],0,session=b)
 policy(root,'restricted',[('bob',1)])
 h.check(b.get(h.url+'uploads/'+pending['id']).status_code==403,'revocation stops task polling without changing destination')
 h.chunk(pending,payload,4096,session=b,status=403);h.check(h.admin.get(h.url+'uploads/'+pending['id']).json()['offset']=='4096','revoked write preserves durable confirmation')
 policy(root,'restricted',[('bob',2)]);h.chunk(pending,payload,4096,session=b);h.post('uploads/'+pending['id']+'/complete',session=b,status=202);h.check(h.finish(pending)['state']=='READY','restored permission resumes same bytes to READY')
 h.post('folders/'+root['id']+'/delete',session=a,status=409)
 h.post('folders/'+root['id']+'/relocate',{'parent_id':grand['id'],'confirm_access_change':True},session=a,status=400)
 current=root
 for i in range(2,17):current=create('层'+str(i),current['id'])
 h.post('folders',{'name':'第17层','parent_id':current['id']},session=a,status=400);h.check(True,'depth16 allowed; level17, cycle and nonempty deletion rejected')
 h.stop();h.start();h.check(a.get(h.url+'folders/'+grand['id']).json()['full_path']=='个人资料 / 手机照片 / 2026年','restart preserves closure, inherited ACL and full path')
 # Tightening can never grant above the parent's level; rejection leaves old policy intact.
 h.post('folders/'+grand['id']+'/permissions',{'policy':'restricted','grants':[{'user_id':users['bob']['id'],'level':3}],'confirm_access_change':True},session=a,status=400)
 h.check(b.get(h.url+'folders/'+grand['id']).json()['access_level']==2,'over-broad child grant rejected without changing effective access')
 public=create('共享只读');policy(public,'public');branch=create('子目录',root['id']);leaf=create('下级',branch['id'])
 h.post('folders/'+branch['id']+'/relocate',{'parent_id':public['id'],'confirm_access_change':True},session=a)
 h.check(b.get(h.url+'folders/'+leaf['id']).json()['access_level']==1,'moving a subtree recomputes inherited permission from its new ancestors')
 h.post('folders/'+branch['id']+'/relocate',{'parent_id':'','confirm_access_change':True},session=a)
 h.check(b.get(h.url+'folders/'+branch['id']).status_code==403 and a.get(h.url+'folders/'+branch['id']).json()['depth']==1,'inherited subtree moved to root becomes private, never implicit public')
 shared,meta=h.create(b'public fixture',name='public.txt',session=a);h.chunk(shared,b'public fixture',0,session=a);public_asset=h.finish(shared)['asset_id']
 h.post('folders/'+own['id']+'/add',{'asset_ids':[public_asset],'confirm_access_change':True},session=b,status=403)
 h.check(a.get(h.url+'assets/'+public_asset+'/original').content==b'public fixture','read-only access cannot attach a restricting reference to another owner file')
 held_bytes=b'parked permission verification';held,_=h.create(held_bytes,name='held.bin',session=b,folder_id=grand['id']);h.chunk(held,held_bytes,0,session=b);policy(root,'restricted',[('bob',1)]);h.stop()
 with sqlite3.connect(h.home/'数据/catalog.db') as db:db.execute("UPDATE uploads SET state='VERIFYING',error='FOLDER_ACCESS_DENIED' WHERE id=?",(held['id'],))
 h.start();h.check(b.get(h.url+'uploads/'+held['id']).status_code==403,'parked verification remains blocked after restart')
 policy(root,'restricted',[('bob',2)]);held_ready=h.finish(held);h.check(b.get(h.url+'assets/'+held_ready['asset_id']+'/original').content==held_bytes,'parked verification resumes after permission restoration without losing bytes')
 empty=create('将删除的空目标',session=b);cancel_task,_=h.create(b'z',session=b,folder_id=empty['id']);h.chunk(cancel_task,b'z',0,session=b);h.post('folders/'+empty['id']+'/delete',session=b)
 result=h.post('uploads/'+cancel_task['id']+'/cancel',session=b);h.check(result['state']=='CANCELLED' and not (h.home/'数据/storage/uploads'/(cancel_task['id']+'.part')).exists(),'owner can cancel a deleted-target task without default fallback or deleting originals')
 managed=create('授权管理');policy(managed,'restricted',[('bob',3)]);managed_child=create('同名',managed['id'])
 h.post('folders/'+managed_child['id']+'/permissions',{'policy':'private','confirm_access_change':True},session=b,status=409)
 h.check(b.get(h.url+'folders/'+managed_child['id']).json()['can_manage'],'delegated manager cannot accidentally revoke their own management access')
 h.post('folders',{'name':'同名','parent_id':managed['id']},session=b,status=409);h.check(True,'child names are unique across different managers of the same parent')
 policy(child,'private');h.post('folders/'+grand['id']+'/edit',{'name':'收紧后的新目录名'},session=a)
 h.check(b.get(h.url+'uploads/'+held['id']).json()['folder_name']=='目标目录已不可访问' and all('收紧后的新目录名' not in t['folder_name'] for t in b.get(h.url+'tasks/page').json()['items']),'lost access does not reveal later directory renames through owned task history')
 policy(child,'inherit');race_bytes=b'permission-during-body'*200;race,_=h.create(race_bytes,name='race.bin',session=b,folder_id=grand['id'])
 # http.client keeps Content-Length framing; requests would mark a generator chunked.
 import hashlib,http.client,urllib.parse
 endpoint=urllib.parse.urlsplit(h.url);path=endpoint.path+'uploads/'+race['id']+'/data';part=h.home/'数据/storage/uploads'/(race['id']+'.part')
 headers={'Content-Type':'application/octet-stream','Content-Length':str(len(race_bytes)),'X-Upload-Offset':'0','X-Chunk-SHA256':hashlib.sha256(race_bytes).hexdigest()}
 headers.update({key:value for key,value in b.prepare_request(requests.Request('PUT',h.url+'uploads/'+race['id']+'/data')).headers.items() if key.lower() in ('cookie','authorization')})
 conn=http.client.HTTPConnection(endpoint.hostname,endpoint.port,timeout=20);conn.putrequest('PUT',path)
 for key,value in headers.items():conn.putheader(key,value)
 conn.endheaders();conn.send(race_bytes[:100])
 for _ in range(300):
  if part.stat().st_size==100:break
  time.sleep(.01)
 assert part.stat().st_size==100,'server must receive bytes before revocation'
 policy(root,'restricted',[('bob',1)]);conn.send(race_bytes[100:]);reply=conn.getresponse();response_code=reply.status;reply.read();conn.close()
 h.check(response_code==403 and h.admin.get(h.url+'uploads/'+race['id']).json()['offset']=='0' and (h.home/'数据/storage/uploads'/(race['id']+'.part')).stat().st_size==0,'revocation during chunk streaming rolls bytes and confirmation back together')
 policy(root,'restricted',[('bob',2)]);h.chunk(race,race_bytes,0,session=b);race_ready=h.finish(race);h.check(b.get(h.url+'assets/'+race_ready['asset_id']+'/original').content==race_bytes,'the revoked chunk can be retried without offset or content corruption')
 (h.home/'FOLDER-ACL-PASS.json').write_text(json.dumps({'count':len(h.checks),'checks':h.checks},ensure_ascii=False,indent=2),encoding='utf-8');print('ACL checks:',len(h.checks),'evidence:',h.home)
finally:h.stop()
