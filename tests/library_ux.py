"""Real isolated server: date selection, album membership, recycle thumbnails and ZIP export."""
from smoke import Harness,ROOT
from pathlib import Path
from PIL import Image
import io,zipfile,requests,json
h=Harness(ROOT/'build/app/Release/NasServer.exe')
try:
 h.start();h.post('login',{'username':'admin','password':(h.home/'数据/initial-admin-password.txt').read_text().strip()})
 h.post('users',{'username':'uxmember','display_name':'fixture','role':'member','password':'test123'},status=201);owner=requests.Session();h.post('login',{'username':'uxmember','password':'test123'},session=owner)
 h.post('users',{'username':'uxother','display_name':'fixture','role':'member','password':'test123'},status=201);other=requests.Session();h.post('login',{'username':'uxother','password':'test123'},session=other)
 ids=[];payloads=[];start=1793750400000
 for i in range(55):
  out=io.BytesIO();Image.new('RGB',(8,8),(i,40,80)).save(out,format='PNG');payload=out.getvalue();task,_=h.create(payload,name='同名.png',modified=str(start+i),session=owner);h.chunk(task,payload,0,session=owner);ids.append(h.finish(task)['asset_id']);payloads.append(payload)
 query={'order':'timeline','from':str(start),'to':str(start+86400000),'limit':'48','type':'image'}
 page=owner.get(h.url+'assets',params=query).json();more=owner.get(h.url+'assets',params={**query,'before':page['next']}).json();all_items=page['items']+more['items']
 h.check(len(page['items'])==48 and len(all_items)==55 and not more['next'],'full-day selection follows real cursor pages without omitting photos')
 h.check(owner.get(h.url+'assets',params={**query,'to':str(start)}).status_code==400,'invalid time interval is rejected')
 album=h.post('albums',{'name':'membership fixture'},session=owner,status=201);h.post('albums/'+album['id']+'/add',{'asset_ids':ids[:2]},session=owner)
 annotated=owner.get(h.url+'assets',params={**query,'limit':'100','album_id':album['id']}).json()['items'];h.check(sum(x['in_album'] for x in annotated)==2 and all('in_album' in x for x in annotated),'existing album membership is explicitly marked on candidate photos')
 h.check(owner.get(h.url+'assets',params={**query,'album_id':'bad'}).status_code==404,'invalid target album cannot produce misleading membership marks')
 export=owner.post(h.url+'assets/export',json={'asset_ids':ids[:2]});export.raise_for_status()
 with zipfile.ZipFile(io.BytesIO(export.content)) as archive:
  h.check(archive.testzip() is None and len(set(archive.namelist()))==2,'streamed ZIP64 export has valid CRCs and unique duplicate-name entries')
  h.check(all(info.date_time[0]==2026 for info in archive.infolist()),'ZIP entry timestamps preserve original file year')
  h.check([archive.read(n) for n in archive.namelist()]==payloads[:2] and all('/' not in n and '\\' not in n for n in archive.namelist()),'ZIP preserves exact Unicode-named originals without path entries')
 form=owner.post(h.url+'assets/export',data={'selection':json.dumps({'asset_ids':[ids[0]]})});h.check(form.status_code==200 and zipfile.ZipFile(io.BytesIO(form.content)).testzip() is None,'browser form download works without buffering the archive in JavaScript')
 h.check(requests.post(h.url+'assets/export',json={'asset_ids':[ids[0]]}).status_code==401,'anonymous export is denied')
 h.check(owner.post(h.url+'assets/export',json={'asset_ids':[ids[0],ids[0]]}).status_code==400,'duplicate export selection is rejected')
 folder=h.post('folders',{'name':'批量移动目标'},session=owner,status=201);h.post('folders/default/move',{'asset_ids':ids,'target_id':folder['id']},session=owner)
 options=h.post('folders/move-options',{'asset_ids':ids},session=owner)
 h.check(options['selected_count']==55 and [x['id'] for x in options['items']]==[folder['id']],'cross-page move preflight returns only folders containing every selected asset')
 h.check(other.post(h.url+'folders/move-options',json={'asset_ids':ids}).json()['items']==[],'move preflight excludes folders owned by another member')
 h.check(requests.post(h.url+'folders/move-options',json={'asset_ids':ids}).status_code==401,'move preflight requires authentication')
 h.check(owner.post(h.url+'folders/move-options',json={'asset_ids':[ids[0],ids[0]]}).status_code==400,'move preflight rejects duplicate asset selection')
 h.check(owner.post(h.url+'folders/move-options',json={'asset_ids':[ids[0]]*1001}).status_code==400,'move preflight enforces the 1000 item boundary')
 h.check(owner.post(h.url+'folders/move-options',json={'asset_ids':[format(i,'048x') for i in range(1000)]}).status_code==409,'1000 full-length IDs reach selection validation without the ordinary 16KiB request limit')
 h.check(owner.post(h.url+'folders/move-options',json={'asset_ids':[ids[0],'f'*48]}).status_code==409,'move preflight rejects a stale selection before any move')
 second=h.post('folders',{'name':'独立来源'},session=owner,status=201)
 task,_=h.create(b'mixed source preflight fixture',name='mixed.txt',session=owner,folder_id=second['id']);h.chunk(task,b'mixed source preflight fixture',0,session=owner);different=h.finish(task)['asset_id']
 h.check(h.post('folders/move-options',{'asset_ids':[ids[0],different]},session=owner)['items']==[],'mixed source selection cannot offer an invalid arbitrary source folder')
 h.post('users',{'username':'uxviewer','display_name':'viewer','role':'viewer','password':'test123'},status=201);viewer=requests.Session();h.post('login',{'username':'uxviewer','password':'test123'},session=viewer)
 h.check(viewer.post(h.url+'folders/move-options',json={'asset_ids':ids}).status_code==403,'read-only account cannot use move preflight')
 moved=owner.get(h.url+'folders/'+folder['id']+'/items').json();moved_next=owner.get(h.url+'folders/'+folder['id']+'/items',params={'before':moved['next']}).json()
 h.check(len(moved['items'])+len(moved_next['items'])==55 and owner.get(h.url+'assets/'+ids[0]+'/original').content==payloads[0],'photo batch move retains all members and original bytes across folder pages')
 h.post('assets/trash',{'asset_ids':ids[:2]},session=owner)
 h.check(owner.post(h.url+'folders/move-options',json={'asset_ids':ids[:3]}).status_code==409,'move preflight rejects a cross-page selection containing recycled files')
 cache=h.home/'数据/cache'/(ids[0]+'.jpg');cache.unlink(missing_ok=True)
 thumbnail=owner.get(h.url+'trash/'+ids[0]+'/thumbnail');h.check(thumbnail.status_code==200 and Image.open(io.BytesIO(thumbnail.content)).format=='JPEG','recycle thumbnails remain available to the deleting owner')
 h.check(cache.exists(),'recycle image can regenerate a missing thumbnail without restoring its original')
 h.check(requests.get(h.url+'trash/'+ids[0]+'/thumbnail').status_code==401 and other.get(h.url+'trash/'+ids[0]+'/thumbnail').status_code==404,'recycle thumbnails preserve authentication and deletion-owner scope')
 h.check(owner.get(h.url+'assets/'+ids[0]+'/original').status_code==404 and owner.post(h.url+'assets/export',json={'asset_ids':ids[:3]}).status_code==404,'trash originals stay hidden and mixed export fails before emitting ZIP bytes')
 h.post('assets/restore',{'asset_ids':ids[:2]},session=owner);h.check(owner.get(h.url+'assets/'+ids[0]+'/original').content==payloads[0],'batch restore preserves actual original bytes')
 report={'count':len(h.checks),'checks':h.checks};(h.home/'LIBRARY-UX-PASS.json').write_text(json.dumps(report,ensure_ascii=False),encoding='utf-8');print('Library UX:',len(h.checks),'checks')
finally:h.stop()
