"""Timeline paging and authoritative account-scoped backup status on a real isolated NAS."""
import json,sqlite3,requests,hashlib,argparse
from smoke import Harness,ROOT
p=argparse.ArgumentParser();p.add_argument('--exe',type=__import__('pathlib').Path,default=ROOT/'out/Release/NasServer.exe')
h=Harness(p.parse_args().exe)
try:
 h.start();password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip();h.post('login',{'username':'admin','password':password})
 member=h.post('users',{'username':'timeline_member','display_name':'fixture','role':'member','password':'test123'},status=201)
 payload=b'timeline-status-fixture';task,_=h.create(payload,name='fixture.bin',modified='1720000000000');h.chunk(task,payload,0);ready=h.finish(task)
 result=h.post('sources/status',{'task_ids':[task['id']]});h.check(result['items'][0]['state']=='READY' and result['items'][0]['sha256']==hashlib.sha256(payload).hexdigest(),'status confirms READY with full server hash')
 other=requests.Session();h.post('login',{'username':'timeline_member','password':'test123'},session=other);h.check(h.post('sources/status',{'task_ids':[task['id']]},session=other)=={'items':[]},'status never discloses another account task')
 h.post('sources/status',{'task_ids':[task['id'],task['id']]},status=400);h.post('sources/status',{'task_ids':['bad']},status=400);h.check(True,'status input bounded and validated')
 h.stop();(h.home/'数据/fixture').mkdir(exist_ok=True);(h.home/'数据/fixture/shared').write_bytes(b'abc')
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  db.executemany("INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state,preview) VALUES(?,?,3,'fixture/shared',?,'image/jpeg','image',? ,?,'READY','UNAVAILABLE')",[(f'{i:048x}',f'{i:064x}',f'timeline-{i}',10000+i,100000 if i<60 else 0) for i in range(1,125)])
 h.start();before='';seen=[];times=[]
 while True:
  response=h.admin.get(h.url+'assets',params={'order':'timeline','before':before,'limit':17});response.raise_for_status();page=response.json();h.check(page['order']=='timeline','timeline order acknowledged') if not seen else None
  for item in page['items']:seen.append(item['id']);times.append(int(item['timeline_time']))
  before=page['next']
  if not before:break
 h.check(len(seen)==125 and len(set(seen))==125 and times==sorted(times,reverse=True),'timeline walks equal timestamps and missing modified times without duplicates or omissions')
 h.check(h.admin.get(h.url+'assets',params={'order':'bad'}).status_code==400,'invalid timeline order rejected')
 with sqlite3.connect(h.home/'数据/catalog.db') as db:
  plan=str(db.execute("EXPLAIN QUERY PLAN SELECT id FROM assets INDEXED BY asset_file_time WHERE state='READY' AND ((CASE WHEN modified>0 THEN modified ELSE created END),id)<(CAST(? AS INTEGER),?) ORDER BY (CASE WHEN modified>0 THEN modified ELSE created END) DESC,id DESC LIMIT 49",('100000','f'*48)).fetchall());h.check('TEMP B-TREE' not in plan,'file timeline uses ordered expression index')
  original=db.execute('SELECT path FROM assets WHERE id=?',(ready['asset_id'],)).fetchone()[0]
 (h.home/'数据'/original).unlink();h.check(h.post('sources/status',{'task_ids':[task['id']]})['items'][0]['state']=='MISSING','status will not mark a vanished original as backed up')
 (h.home/'TIMELINE-PASS.json').write_text(json.dumps({'checks':h.checks}),encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
