"""Real-server exact duplicate discovery, permissions, stale previews and reversible cleanup."""
from pathlib import Path
import argparse, hashlib, json, sqlite3, requests
from concurrent.futures import ThreadPoolExecutor
from smoke import Harness

p=argparse.ArgumentParser();p.add_argument('--exe',required=True,type=Path)
h=Harness(p.parse_args().exe)
def get(path,session=None):
 r=(session or h.admin).get(h.url+path);assert r.status_code==200,r.text;return r.json()
def upload(data,folder,name='photo.jpg'):
 t,_=h.create(data,name=name,folder_id=folder)
 if t['state']=='READY':return t
 for offset in range(0,len(data),1048576):h.chunk(t,data,offset)
 return h.finish(t)
def groups(folder='',session=None):return get('duplicates?type=all&folder_id='+folder,session)
def members(digest,size,folder=''):
 return get(f'duplicates/members?type=all&sha256={digest}&size={size}&folder_id={folder}')
def payload(keep,remove,folder=''):
 return dict(type='all',folder_id=folder,keep_id=keep['id'],keep_stamp=keep['references']['stamp'],remove=[dict(id=x['id'],stamp=x['references']['stamp']) for x in remove],confirm_references=True)
try:
 h.start();h.post('login',dict(username='admin',password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip()))
 resource=h.admin.get(h.url.removesuffix('api/v1/')+'duplicates.js');assert resource.status_code==200 and 'HomeNasDuplicates' in resource.text
 parent=h.post('folders',{'name':'家庭资料'},status=201)['id']
 a=h.post('folders',dict(name='相机',parent_id=parent),status=201)['id']
 b=h.post('folders',dict(name='下载',parent_id=parent),status=201)['id']
 secret=h.post('folders',dict(name='私有'),status=201)['id']
 data=b'same original bytes';digest=hashlib.sha256(data).hexdigest()
 x=upload(data,a);y=upload(data,b,'renamed.jpg');z=upload(data,secret,'private.jpg')
 duplicate=upload(data,a,'another-name.jpg');assert duplicate['already_present'] and duplicate['asset_id']==x['asset_id'] and duplicate['existing_name']=='photo.jpg'
 assert groups(a)['items']==[] and groups(parent)['items'][0]['copies']=='2'
 assert groups()['items'][0]['copies']=='3'
 rows=members(digest,len(data),parent)['items'];assert len(rows)==2
 assert any('/' in f['full_path'] for r in rows for f in r['references']['folders'])
 keep=next(r for r in rows if r['id']==x['asset_id']);remove=next(r for r in rows if r['id']==y['asset_id'])
 # One physical asset associated with another logical directory must still count only once.
 h.post('folders/'+b+'/add',{'asset_ids':[x['asset_id']],'confirm_access_change':True})
 assert groups(parent)['items'][0]['copies']=='2'
 stale=payload(keep,[remove],parent);h.post('duplicates/trash',stale,status=409)
 rows=members(digest,len(data),parent)['items'];keep=next(r for r in rows if r['id']==x['asset_id']);remove=next(r for r in rows if r['id']==y['asset_id'])
 h.post('duplicates/trash',{**payload(keep,[remove],parent),'confirm_references':False},status=400)
 h.post('duplicates/trash',payload(keep,[keep],parent),status=400)
 other=upload(b'other bytes',b,'different.jpg');different=members(hashlib.sha256(b'other bytes').hexdigest(),len(b'other bytes'))['items'][0]
 h.post('duplicates/trash',payload(keep,[different],parent),status=409)
 # Corrupt retained bytes without changing length: cleanup must leave every copy active.
 db=sqlite3.connect(h.home/'数据/nas.db') if (h.home/'数据/nas.db').exists() else None
 if db is None:
  files=list((h.home/'数据').glob('*.db'));assert len(files)==1;db=sqlite3.connect(files[0])
 path=h.home/'数据'/db.execute('SELECT path FROM assets WHERE id=?',(keep['id'],)).fetchone()[0]
 path.write_bytes(b'X'*len(data));h.post('duplicates/trash',payload(keep,[remove],parent),status=409);assert groups(parent)['items'][0]['copies']=='2';path.write_bytes(data)
 album=h.post('albums',dict(name='旅行'),status=201)['id'];h.post('albums/'+album+'/add',{'asset_ids':[remove['id']]})
 h.post('duplicates/trash',payload(keep,[remove],parent),status=409)
 rows=members(digest,len(data),parent)['items'];keep=next(r for r in rows if r['id']==x['asset_id']);remove=next(r for r in rows if r['id']==y['asset_id']);assert remove['references']['has_album_references']
 h.post('users',dict(username='reader',display_name='只读',password='Test12345',role='viewer'),status=201)
 reader=requests.Session();h.post('login',dict(username='reader',password='Test12345'),session=reader)
 assert not groups(session=reader)['items'];h.post('duplicates/trash',payload(keep,[remove],parent),session=reader,status=403)
 # Grant only this subtree: no hidden third copy appears in counts or group members.
 h.post('folders/'+parent+'/permissions',dict(policy='public',confirm_access_change=True))
 assert groups(session=reader)['items'][0]['copies']=='2'
 clean=h.post('duplicates/trash',payload(keep,[remove],parent));assert clean['count']==1 and clean['space_released'] is False
 assert groups(parent)['items']==[];assert db.execute('SELECT count(*) FROM album_items WHERE asset_id=?',(remove['id'],)).fetchone()[0]==1
 h.post('assets/restore',{'asset_ids':[remove['id']]});assert groups(parent)['items'][0]['copies']=='2'
 # Concurrent opposing keep/remove plans may never remove both copies.
 rows=members(digest,len(data),parent)['items'];plans=[payload(rows[0],[rows[1]],parent),payload(rows[1],[rows[0]],parent)]
 with ThreadPoolExecutor(2) as pool:codes=list(pool.map(lambda v:h.admin.post(h.url+'duplicates/trash',json=v).status_code,plans))
 assert sorted(codes)==[200,409],codes
 assert db.execute('SELECT count(*) FROM assets a WHERE a.id IN (?,?) AND NOT EXISTS(SELECT 1 FROM asset_trash t WHERE t.asset_id=a.id)',(keep['id'],remove['id'])).fetchone()[0]==1
 # Discovery pages are bounded even for a large group; no disk bytes are needed to list.
 for i in range(60):
  asset_id=f'{i+1:048x}';db.execute("INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state) VALUES(?,?,?,?,?,'image/jpeg','image',?,1,'READY')",(asset_id,'a'*64,99,'fixture/'+asset_id,'page.jpg',i+1));db.execute('INSERT INTO folder_items(folder_id,asset_id) VALUES(?,?)',(secret,asset_id))
 db.commit();first=members('a'*64,99);assert len(first['items'])==48 and first['next']=='@page:1'
 second=get('duplicates/members?type=all&sha256='+'a'*64+'&size=99&before='+first['next']);assert len(second['items'])==12
 assert len({r['id'] for r in first['items']+second['items']})==60
 for i in range(14):
  for j in range(2):
   asset_id=f'{100+i*2+j:048x}';db.execute("INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state) VALUES(?,?,?,?,?,'image/jpeg','image',1,1,'READY')",(asset_id,hashlib.sha256(str(i).encode()).hexdigest(),1,'fixture/'+asset_id,'group.jpg'));db.execute('INSERT INTO folder_items(folder_id,asset_id) VALUES(?,?)',(secret,asset_id))
 db.commit();page1=groups(secret);page2=get('duplicates?type=all&folder_id='+secret+'&before='+page1['next']);assert len(page1['items'])==12 and len(page2['items'])==3
 assert len({x['sha256'] for x in page1['items']+page2['items']})==15
 db.close();print('Duplicate integration passed: digest reuse, recursive ACL scope, reference stamps, corrupted keeper rejection, restore, concurrent cleanup, 60-member and 15-group pagination')
finally:h.stop()
