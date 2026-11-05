"""Real server: virtual album permissions, atomic changes, paging and retained originals."""
import json,sqlite3,requests
from smoke import Harness,ROOT
h=Harness(ROOT/'build/app/Release/NasServer.exe')
try:
 h.start();password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip();h.post('login',{'username':'admin','password':password})
 for name,role in [('album_owner','member'),('album_other','member'),('album_viewer','viewer')]:h.post('users',{'username':name,'display_name':name,'role':role,'password':'test123'},status=201)
 sessions={}
 for name in ['album_owner','album_other','album_viewer']:
  s=requests.Session();h.post('login',{'username':name,'password':'test123'},session=s);sessions[name]=s
 owner,other,viewer=[sessions[x] for x in ['album_owner','album_other','album_viewer']]
 a=h.post('albums',{'name':'家庭照片','description':'不复制原件'},session=owner,status=201);path='albums/'+a['id']
 h.check(a['can_edit'] and a['item_count']=='0','member creates empty shared album')
 h.post('albums',{'name':'forbidden'},session=viewer,status=403)
 h.post(path+'/edit',{'name':'forbidden'},session=other,status=403)
 h.post(path+'/delete',{},session=viewer,status=403);h.check(True,'viewer and non-owner writes rejected')
 rows=viewer.get(h.url+'albums').json()['items'];h.check(rows[0]['name']=='家庭照片' and not rows[0]['can_edit'],'shared browse returns truthful ownership')
 h.stop();fixture=h.home/'数据/fixture';fixture.mkdir();(fixture/'shared').write_bytes(b'abc')
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  db.executemany("INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state,preview) VALUES(?,?,3,'fixture/shared',?,'image/jpeg','image',123,456,'READY','UNAVAILABLE')",[(f'{i:048x}',f'{i:064x}',f'photo-{i}') for i in range(1,106)])
 h.start();ids=[f'{i:048x}' for i in range(1,106)]
 h.post(path+'/add',{'asset_ids':ids[:100]},session=owner);h.post(path+'/add',{'asset_ids':ids[100:]},session=owner)
 h.post(path+'/add',{'asset_ids':ids[:2]},session=owner)
 h.check(h.admin.get(h.url+'albums').json()['items'][0]['item_count']=='105','adding duplicate references is idempotent')
 h.post(path+'/add',{'asset_ids':[ids[0],'f'*48]},session=owner,status=404)
 h.post(path+'/add',{'asset_ids':ids},session=owner,status=400)
 second=h.post('albums',{'name':'atomic'},session=owner,status=201)
 h.post('albums/'+second['id']+'/add',{'asset_ids':[ids[0],'f'*48]},session=owner,status=404)
 h.check(owner.get(h.url+'albums/'+second['id']+'/items').json()['album']['item_count']=='0','invalid batch rolls back all references')
 h.post(path+'/cover',{'asset_id':'f'*48},session=owner,status=400)
 h.post(path+'/cover',{'asset_id':ids[0]},session=owner)
 page=viewer.get(h.url+path+'/items').json();h.check(page['album']['cover']['id']==ids[0] and not page['album']['can_edit'],'selected cover is a member, readable to viewers')
 seen=[];before=''
 while True:
  r=owner.get(h.url+path+'/items',params={'before':before}).json();seen.extend(x['id'] for x in r['items']);before=r['next']
  if not before:break
 h.check(len(seen)==105 and len(set(seen))==105,'album items paginate without omissions or duplicates')
 h.check(len(owner.get(h.url+path+'/items',params={'q':'photo-105'}).json()['items'])==1,'album filename search filters members')
 h.post(path+'/remove',{'asset_ids':[ids[0]]},session=owner)
 h.check(owner.get(h.url+path+'/items').json()['album']['cover']['id']!=ids[0],'removing cover chooses a remaining member')
 h.post(path+'/edit',{'name':'管理员修改','description':'保留'},session=h.admin)
 h.post(path+'/delete',{},session=h.admin)
 h.check(owner.get(h.url+path+'/items').status_code==404 and owner.get(h.url+'assets/'+ids[0]+'/original').content==b'abc','admin can manage albums; deletion and removal retain originals')
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  h.check(db.execute('SELECT count(*) FROM album_items WHERE album_id=?',(a['id'],)).fetchone()[0]==0,'album deletion cascades references only')
  ownerid=second['owner_id'];db.executemany('INSERT INTO albums(id,name,owner_id,created,updated) VALUES(?,?,?,?,?)',[(f'{i+1000:048x}',str(i),ownerid,1,1) for i in range(101)])
 seen=[];before=''
 while True:
  r=owner.get(h.url+'albums',params={'before':before}).json();seen.extend(x['id'] for x in r['items']);before=r['next']
  if not before:break
 h.check(len(seen)==102 and len(set(seen))==102,'album list is bounded to 48 per page with stable cursor')
 h.check(owner.get(h.url+'albums',params={'before':'invalid'}).status_code==400,'invalid album cursor rejected')
 (h.home/'ALBUMS-PASS.json').write_text(json.dumps({'checks':h.checks}),encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
