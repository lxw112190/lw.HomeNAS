"""Large isolated metadata fixture, cursor boundaries and account isolation. No real media."""
import json,sqlite3,requests
from smoke import Harness,ROOT
h=Harness(ROOT/'out/Release/NasServer.exe')
try:
 h.start();password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip();h.post('login',{'username':'admin','password':password})
 member=h.post('users',{'username':'page_member','display_name':'pagination','role':'member','password':'PageTest12345'},status=201);h.stop()
 (h.home/'数据/fixture').mkdir(exist_ok=True);(h.home/'数据/fixture/shared').write_bytes(b'abc')
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  db.executemany("INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state,preview) VALUES(?,?,3,?,?,'application/octet-stream',?,1000,1000,'READY','UNAVAILABLE')",[(f'{i:048x}',f'{i:064x}','fixture/shared',f'fixture-{i:05d}', 'video' if i%2 else 'image') for i in range(1,10002)])
  db.executemany("INSERT INTO uploads(id,client_task_id,device_id,user_id,name,sha256,size,offset,state,source_key,created,updated) VALUES(?,?,?,?,?,?,3,0,'ERROR','fixture',1000,1000)",[(f'{i:048x}',str(i),'administrator',member['id'] if i%2 else 'administrator',f'task-{i}',f'{i:064x}') for i in range(1,10002)])
 h.start()
 def walk(route,expected):
  before='';seen=set();pages=0
  while True:
   data=h.admin.get(h.url+route,params={'before':before},timeout=20).json();items=data['items'];assert len(items)<=50
   ids={x['id'] for x in items};assert not(seen&ids);seen.update(ids);pages+=1;before=data['next']
   if not before:break
   assert pages<500
  h.check(len(seen)==expected,route+' visits every row exactly once including same-timestamp boundaries')
 walk('assets?limit=48',10001);walk('tasks/page',10001)
 h.check(h.admin.get(h.url+'assets',params={'limit':0}).status_code==400,'invalid page size rejected')
 h.check(h.admin.get(h.url+'assets',params={'before':'bad:cursor'}).status_code==400,'malformed asset cursor rejected')
 h.check(h.admin.get(h.url+'tasks/page',params={'before':'bad:cursor'}).status_code==400,'malformed task cursor rejected')
 h.check(h.admin.get(h.url+'assets',params={'type':'video','limit':48}).json()['items'][0]['category']=='video','category filtering works with bounded page')
 client=requests.Session();h.post('login',{'username':'page_member','password':'PageTest12345'},session=client)
 rows=client.get(h.url+'tasks/page').json()['items'];h.check(all(x['user_id']==member['id'] for x in rows),'member task page cannot expose other accounts')
 h.check(h.admin.get(h.url+'tasks/page',params={'filter':'ready'}).json()=={'items':[],'next':''},'empty filter has no next page')
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  plan=str(db.execute("EXPLAIN QUERY PLAN SELECT id FROM assets INDEXED BY asset_ready_timeline WHERE state='READY' AND (created,id)<(1000,'ffffffffffffffffffffffffffffffffffffffffffffffff') ORDER BY created DESC,id DESC LIMIT 49").fetchall())
  h.check('TEMP B-TREE' not in plan,'asset cursor uses ordered index without temporary sorting')
 (h.home/'PAGINATION-PASS.json').write_text(json.dumps({'count':len(h.checks),'checks':h.checks,'fixture_assets':10001,'fixture_tasks':10001},indent=2),encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
