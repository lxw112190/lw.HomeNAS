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
 report={'count':len(h.checks),'checks':h.checks};(h.home/'RECYCLE-PASS.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print('Recycle:',len(h.checks),'checks')
finally:h.stop()
