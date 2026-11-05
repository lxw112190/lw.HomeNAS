"""Actual server tests for shared-name permissions, concurrency and unchanged original bytes."""
import hashlib,json,sqlite3,requests
from urllib.parse import unquote
from smoke import Harness,ROOT
h=Harness(ROOT/'build/app/Release/NasServer.exe')
try:
 h.start();password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip();h.post('login',{'username':'admin','password':password})
 sessions={}
 for name,role in [('rename_owner','member'),('rename_other','member'),('rename_viewer','viewer')]:
  h.post('users',{'username':name,'display_name':name,'role':role,'password':'test123'},status=201);s=requests.Session();h.post('login',{'username':name,'password':'test123'},session=s);sessions[name]=s
 owner,other,viewer=[sessions[x] for x in ['rename_owner','rename_other','rename_viewer']]
 payload=b'rename-original-fixture';task,_=h.create(payload,name='old.txt',session=owner);h.chunk(task,payload,0,session=owner);h.post('uploads/'+task['id']+'/complete',session=owner,status=202)
 import time
 for _ in range(150):
  ready=owner.get(h.url+'uploads/'+task['id']).json()
  if ready['state']=='READY':break
  time.sleep(.1)
 assert ready['state']=='READY';asset=ready['asset_id'];path='assets/'+asset+'/rename'
 with sqlite3.connect(h.home/'数据/catalog.db') as db:before=db.execute('SELECT path,sha256,size,modified,created,mime FROM assets WHERE id=?',(asset,)).fetchone()
 def item(session):return next(x for x in session.get(h.url+'assets').json()['items'] if x['id']==asset)
 h.check(item(owner)['can_rename'] and item(h.admin)['can_rename'] and not item(other)['can_rename'] and not item(viewer)['can_rename'],'list exposes server-authoritative rename capability')
 h.post(path,{'name':'other.txt','old_name':'old.txt'},session=other,status=403);h.post(path,{'name':'viewer.txt','old_name':'old.txt'},session=viewer,status=403)
 h.check(requests.post(h.url+path,json={'name':'anon.txt','old_name':'old.txt'}).status_code==401,'anonymous rename rejected')
 renamed=h.post(path,{'name':'周末 记录.txt','old_name':'old.txt'},session=owner);h.check(renamed['id']==asset and renamed['name']=='周末 记录.txt' and 'path' not in renamed,'owner renames Unicode file without exposing physical path')
 h.check(item(viewer)['name']=='周末 记录.txt','shared readers see updated name')
 h.post(path,{'name':'stale.txt','old_name':'old.txt'},session=owner,status=409);h.check(item(owner)['name']=='周末 记录.txt','stale edit does not overwrite newer name')
 for name in ['../bad.txt','bad\\name.txt','bad?.txt','bad\t.txt','x.txt ','x.txt.','CON.txt','lpt1.txt','changed.jpg','a'*240+'.txt','']:
  h.post(path,{'name':name,'old_name':'周末 记录.txt'},session=owner,status=400)
 h.check(True,'paths, reserved names, control characters, length and changed extension rejected')
 h.post(path,{'name':'周末 记录.txt','old_name':'周末 记录.txt'},session=owner)
 h.post(path,{'name':'管理员整理.txt','old_name':'周末 记录.txt'})
 with sqlite3.connect(h.home/'数据/catalog.db') as db:after=db.execute('SELECT path,sha256,size,modified,created,mime FROM assets WHERE id=?',(asset,)).fetchone()
 h.check(before==after and owner.get(h.url+'assets/'+asset+'/original').content==payload,'rename preserves path, bytes, digest, size, timeline and MIME')
 download=owner.get(h.url+'assets/'+asset+'/original?download=1');h.check(unquote(download.headers['Content-Disposition'].split("filename*=UTF-8''",1)[1])=='管理员整理.txt','download supplies the renamed Unicode file name')
 check=h.post('sources/status',{'task_ids':[task['id']]},session=owner);h.check(check['items'][0]['state']=='READY','backup confirmation remains READY after rename')
 duplicate,_=h.create(payload,name='old-phone-name.txt',session=other);h.check(duplicate['state']=='READY' and item(owner)['name']=='管理员整理.txt','rebackup deduplicates without reverting edited shared name')
 h.check(item(other)['can_rename'],'another confirmed source may manage shared content')
 h.post('assets/'+'f'*48+'/rename',{'name':'missing.txt','old_name':'old.txt'},status=404)
 # A real image also participates in albums; renamed names must propagate there.
 t,_=h.create(b'image-fixture',name='image.jpg');h.chunk(t,b'image-fixture',0);image=h.finish(t)['asset_id'];a=h.post('albums',{'name':'Rename album'},status=201);h.post('albums/'+a['id']+'/add',{'asset_ids':[image]});h.post('albums/'+a['id']+'/cover',{'asset_id':image});h.post('assets/'+image+'/rename',{'name':'新封面.jpg','old_name':'image.jpg'})
 detail=h.admin.get(h.url+'albums/'+a['id']+'/items').json();h.check(detail['items'][0]['name']=='新封面.jpg' and detail['album']['cover']['name']=='新封面.jpg','album member and cover names follow canonical rename')
 h.stop();h.start();h.check(item(owner)['name']=='管理员整理.txt','rename survives restart')
 (h.home/'RENAME-PASS.json').write_text(json.dumps({'checks':h.checks}),encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
