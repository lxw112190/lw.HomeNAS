'use strict';
const $=id=>document.getElementById(id);
const state={info:null,me:null,overview:null,page:'home',type:'all',cursor:'',files:[],paused:false,active:0};
const labels={UPLOADING:'等待上传 / 可续传',VERIFYING:'完整文件校验中',COMMITTING:'正在入库',READY:'已备份到电脑',ERROR:'需要处理',CANCELLED:'已取消',HASHING:'计算文件摘要',SENDING:'正在上传',PAUSED:'已暂停'};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function bytes(value){let n=Number(value||0);const units=['B','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<units.length-1){n/=1024;i++;}return `${n.toFixed(i?(n<10?2:1):0)} ${units[i]}`;}
function date(value){const n=Number(value);if(!Number.isFinite(n)||n<=0)return '未记录';const d=new Date(n);return Number.isNaN(d.getTime())?'未记录':d.toLocaleDateString('zh-CN');}
function toast(message){$('toast').textContent=message;$('toast').classList.remove('hidden');clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').classList.add('hidden'),4500);}
function error(message){$('global-error').textContent=message;$('global-error').classList.remove('hidden');}
function showAuth(){collectionView.reset();state.paused=true;state.files.forEach(t=>t.controller?.abort());state.files=[];renderLocal();$('server-tasks').replaceChildren();$('user-list').replaceChildren();state.editingUser=null;if($('preview').open)closePreview();if($('user-editor').open)$('user-editor').close();$('edit-password').value='';$('password').value='';$('create-user-form').reset();$('password-form').reset();state.me=null;$('shell').classList.add('hidden');$('auth').classList.remove('hidden');}
async function api(path,options={}){
  const {signal,timeout=20000,...rest}=options,controller=new AbortController();
  const abort=()=>controller.abort();if(signal?.aborted)throw new DOMException('Paused','AbortError');signal?.addEventListener('abort',abort,{once:true});
  const timer=setTimeout(abort,timeout);
  try{
    const response=await fetch(`./api/v1/${path}`,{credentials:'same-origin',cache:'no-store',...rest,signal:controller.signal});
    let data;try{data=await response.json();}catch{throw new Error(`服务器返回了无法识别的响应（${response.status}）`);}
    if(!response.ok){const e=new Error(data.message||`请求失败（${response.status}）`);e.status=response.status;e.code=data.code;if(response.status===401&&path!=='login')showAuth();throw e;}
    return data;
  }finally{clearTimeout(timer);signal?.removeEventListener('abort',abort);}
}
const post=(path,value={},options={})=>api(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value),...options});
function node(tag,className,text){const n=document.createElement(tag);if(className)n.className=className;if(text!==undefined)n.textContent=text;return n;}
function empty(container,message){container.replaceChildren(node('div','empty',message));}
function navigate(page){
  if(page==='users'&&!state.me?.admin)return;
  state.page=page;document.querySelectorAll('.page').forEach(n=>n.classList.toggle('hidden',n.id!==`page-${page}`));
  document.querySelectorAll('[data-page]').forEach(n=>n.classList.toggle('active',n.dataset.page===page));
  $('page-title').textContent={home:'欢迎回家',library:'照片与文件',transfers:'备份任务',users:'用户管理',settings:'存储与设置'}[page];
  $('global-error').classList.add('hidden');
  if(page==='home')loadRecent().catch(e=>error(e.message));
  if(page==='library')loadLibrary(false).catch(e=>error(e.message));
  if(page==='transfers')refreshTasks().catch(e=>error(e.message));
  if(page==='users')loadUsers().catch(e=>error(e.message));
  window.scrollTo(0,0);
}
async function entered(){
  state.me=await api('me');$('auth').classList.add('hidden');$('shell').classList.remove('hidden');
  document.querySelectorAll('.admin-only').forEach(n=>n.classList.toggle('hidden',!state.me.admin));
  document.querySelectorAll('.upload-only').forEach(n=>n.classList.toggle('hidden',!state.me.can_upload));
  $('account-name').textContent=`${state.me.display_name} · ${roleNames[state.me.role]}`; $('profile-summary').textContent=`${state.me.username} · ${state.me.display_name} · ${roleNames[state.me.role]}`;
  $('transport-warning').classList.toggle('hidden',!state.info.insecure_lan);
  await Promise.all([refreshOverview(),loadRecent()]);navigate('home');
}
async function refreshOverview(){
  const data=await api('overview');state.overview=data;
  $('stat-files').textContent=Number(data.library.files).toLocaleString();$('stat-bytes').textContent=bytes(data.library.bytes);$('stat-free').textContent=bytes(data.available);$('stat-active').textContent=data.transfers.active;
  $('stat-reserved').textContent=`待上传预留 ${bytes(data.transfers.reserved)}`;$('data-path').textContent=(data.data_dir||'仅管理员可查看');$('certificate-hash').textContent=data.certificate_sha256;
  const addresses=$('addresses');addresses.replaceChildren();
  for(const ip of data.addresses){if(ip==='127.0.0.1')continue;const link=node('a','',`https://${ip}:${data.https_port}/`);link.href=link.textContent;link.target='_blank';link.rel='noopener';addresses.append(link);
    if(state.info.insecure_lan){const test=node('a','',`HTTP 联调：http://${ip}:${data.http_port}/`);test.href=`http://${ip}:${data.http_port}/`;test.target='_blank';test.rel='noopener';addresses.append(test);}}
  if(!addresses.childNodes.length)addresses.append(node('p','', '暂未检测到局域网 IPv4 地址。'));
  $('connection').textContent='电脑已连接';
}
function mediaCard(item){
  const card=node('button','media-card');card.dataset.assetId=item.id;card.dataset.category=item.category;card.type='button';const thumb=node('div','media-thumb');
  if(['image','video'].includes(item.category)&&item.preview==='READY'){
    const image=document.createElement('img');image.loading='lazy';image.decoding='async';image.alt=item.name;image.src=`./api/v1/assets/${item.id}/thumbnail`;
    image.addEventListener('error',()=>thumb.replaceChildren(node('span','', '▧')),{once:true});thumb.append(image);
  }else thumb.append(node('span','',item.category==='video'?'▷':item.category==='image'?'▧':'▤'));
  if(item.category==='video')thumb.append(node('small','video-badge','▶ 视频'));
  card.append(thumb,node('span','media-name',item.name),node('span','media-meta',`${bytes(item.size)} · ${date(item.modified||item.created)}`));card.addEventListener('click',()=>preview(item));return card;
}
function renderTimeline(area,items,order){
 area.classList.add('timeline');let previous='';let grid;
 for(const item of items){const time=order==='timeline'?(item.timeline_time||item.modified||item.created):item.created,key=date(time)||'未知日期';
  if(key!==previous){const section=node('section','timeline-day');section.append(node('h2','timeline-heading',key));grid=node('div','timeline-grid');section.append(grid);area.append(section);previous=key;}
  grid.append(mediaCard(item));
 }
}
function metadataText(item){return `${item.mime||item.category} · ${bytes(item.size)} · 文件时间 ${date(item.modified)||'未知'} · 入库 ${date(item.created)}`;}
async function loadRecent(){const data=await api('assets');const area=$('recent');area.replaceChildren();data.items.slice(0,8).forEach(x=>area.append(mediaCard(x)));if(!data.items.length)empty(area,'还没有文件。选几张照片，开始第一次备份。');}
let libraryCursors=[''],libraryIndex=0,libraryGeneration=0;
async function loadLibrary(next=false,previous=false){
 if(collectionView.active())return collectionView.load(next,previous);collectionView.timeline();
 const generation=++libraryGeneration,target=previous?libraryIndex-1:next?libraryIndex+1:0,before=previous?libraryCursors[target]:next?state.cursor:'';
 const query=new URLSearchParams({type:state.type,q:$('search').value,before:before||'',limit:'48',order:$('library-order').value});$('library-status').textContent='正在加载…';$('load-more').disabled=true;$('library-prev').disabled=true;
 try{const data=await api('assets?'+query);if(generation!==libraryGeneration||collectionView.active())return;if(!next&&!previous)libraryCursors=[''];libraryIndex=target;libraryCursors[target]=before||'';state.cursor=data.next;
 const area=$('library');area.replaceChildren();renderTimeline(area,data.items,$('library-order').value);if(!data.items.length)empty(area,'没有找到对应文件。');$('load-more').classList.remove('hidden');$('library-status').textContent=`第 ${target+1} 页 · ${data.items.length} 项`;
 }catch(e){if(generation===libraryGeneration)$('library-status').textContent='加载失败：'+e.message;throw e;}finally{if(generation===libraryGeneration){$('load-more').disabled=!state.cursor;$('library-prev').disabled=!libraryIndex;}}
}
let previewGeneration=0,previewController=null;
function detailedDate(value){return Number(value)>0?new Date(Number(value)).toLocaleString('zh-CN'):'未提供';}
function previewDetails(item,image=null,runtime=''){
 const details=$('preview-details');details.replaceChildren();
 const rows=[['文件名',item.name],['大小',bytes(item.size)],['格式',item.mime],['尺寸',image?.width?`${image.width} × ${image.height}`:runtime||'未提供'],['文件修改时间',detailedDate(item.modified)],['入库时间',detailedDate(item.created)],['SHA256',item.sha256]];
 if(item.category==='image'||item.mime?.startsWith('image/')){const e=image?.exif||{};rows.push(['拍摄时间',e.taken_at],['相机',[e.camera_make,e.camera_model].filter(Boolean).join(' ')],['镜头',e.lens_model],['ISO',e.iso],['曝光时间',e.exposure_seconds?`${Number(e.exposure_seconds.toPrecision(5))} 秒`:null],['光圈',e.f_number?`f/${Number(e.f_number.toPrecision(4))}`:null],['焦距',e.focal_length_mm?`${Number(e.focal_length_mm.toPrecision(5))} mm`:null]);}
 for(const [label,value] of rows)details.append(node('dt','',label),node('dd','',value===undefined||value===null||value===''?'未提供':String(value)));
}
function preview(item){
  const generation=++previewGeneration;previewController?.abort();previewController=new AbortController();let imageDetails=null,runtimeSize='';previewDetails(item);$('preview-metadata-state').textContent=item.mime.startsWith('image/')?'正在读取原图元数据…':'';
  if(item.mime.startsWith('image/'))api(`assets/${item.id}/metadata`,{signal:previewController.signal}).then(data=>{if(generation!==previewGeneration)return;imageDetails=data.image;previewDetails(item,imageDetails,runtimeSize);$('preview-metadata-state').textContent=Object.keys(imageDetails?.exif||{}).length?'拍摄参数来自原图 EXIF，缺失字段不推测。':'原图未提供可读取的 EXIF 拍摄信息。';}).catch(e=>{if(generation===previewGeneration&&e.name!=='AbortError')$('preview-metadata-state').textContent='暂时无法读取拍摄信息；文件信息仍可查看。';});
  const area=$('preview-content');area.replaceChildren();$('preview-title').textContent=item.name;$('preview-info').textContent=metadataText(item);
  const url=`./api/v1/assets/${item.id}/original`;$('download-original').href=`${url}?download=1`;
  let media;
  if(item.mime.startsWith('image/')){media=document.createElement('img');media.alt=item.name;media.src=url;}
  else if(item.mime.startsWith('video/')){media=document.createElement('video');media.controls=true;media.preload='metadata';media.playsInline=true;media.src=url;}
  else if(item.mime.startsWith('audio/')){media=document.createElement('audio');media.controls=true;media.preload='metadata';media.src=url;}
  if(media){const info=()=>{if(generation!==previewGeneration)return;runtimeSize=media.naturalWidth?`${media.naturalWidth} × ${media.naturalHeight}`:media.videoWidth?`${media.videoWidth} × ${media.videoHeight}`:'';previewDetails(item,imageDetails,runtimeSize);$('preview-info').textContent=metadataText(item)+(media.naturalWidth?` · ${media.naturalWidth}×${media.naturalHeight}`:media.videoWidth?` · ${media.videoWidth}×${media.videoHeight}`:'')+(Number.isFinite(media.duration)?` · ${Math.round(media.duration)} 秒`:'');};media.addEventListener('load',info);media.addEventListener('loadedmetadata',info);media.addEventListener('error',()=>{area.replaceChildren(node('p','', '当前浏览器无法预览此格式，可下载原文件。'));},{once:true});area.append(media);}
  else area.append(node('p','', '此文件已保存，可下载原文件查看。'));
  $('preview').showModal();
}
function closePreview(){++previewGeneration;previewController?.abort();previewController=null;for(const m of $('preview-content').querySelectorAll('audio,video')){m.pause();m.removeAttribute('src');m.load();}$('preview').close();$('preview-content').replaceChildren();}
function taskElement(task,local){
  const row=node('div','task'),left=node('div'),right=node('div','task-state');left.append(node('strong','',local?task.file.name:task.name));
  left.append(node('small','',local?`${bytes(task.offset)} / ${bytes(task.file.size)}${task.message?' · '+task.message:''}`:`${bytes(task.offset)} / ${bytes(task.size)} · ${date(task.updated)}${task.error?' · '+task.error:''}`));
  const progress=document.createElement('progress');progress.max=100;progress.value=local?task.percent:Math.min(100,100*Number(task.offset)/Math.max(1,Number(task.size)));left.append(progress);
  right.textContent=labels[task.state]||task.state;
  if(!local&&task.state==='UPLOADING'&&state.me?.can_upload){
    const cancel=node('button','link','取消未完成任务');
    cancel.onclick=async()=>{try{const localTask=state.files.find(x=>x.uploadId===task.id);if(localTask){localTask.cancelled=true;localTask.controller?.abort();}await post(`uploads/${task.id}/cancel`);await refreshTasks();await refreshOverview();toast('任务已取消，未入库的临时进度已丢弃。');}catch(e){error(e.message);}};
    right.append(cancel);
  }
  row.append(left,right);return row;
}
function renderLocal(){clearTimeout(state.renderTimer);state.renderTimer=setTimeout(drawLocal,120);}
function drawLocal(){
 const area=$('local-tasks'),filter=$('local-filter').value,items=state.files.filter(t=>filter==='all'||(filter==='ready'?t.state==='READY':filter==='failed'?['ERROR','CANCELLED'].includes(t.state):!['READY','ERROR','CANCELLED'].includes(t.state)));
 state.localPage=Math.min(state.localPage||0,Math.max(0,Math.ceil(items.length/50)-1));area.replaceChildren();for(const t of items.slice(state.localPage*50,state.localPage*50+50))area.append(taskElement(t,true));if(!items.length)empty(area,'当前筛选下没有文件。');
 const ready=state.files.filter(t=>t.state==='READY').length,failed=state.files.filter(t=>t.state==='ERROR').length;
 $('queue-summary').textContent=`共 ${state.files.length} 项 · 已完成 ${ready} · 失败 ${failed} · 当前筛选 ${items.length} 项`;
 $('local-page').textContent=`第 ${state.localPage+1} / ${Math.max(1,Math.ceil(items.length/50))} 页`;$('local-prev').disabled=!state.localPage;$('local-next').disabled=(state.localPage+1)*50>=items.length;
}
let taskCursors=[''],taskIndex=0,taskNext='',taskBusy=false;
async function refreshTasks(reset=false,direction=0){
 if(taskBusy)return;taskBusy=true;const target=reset?0:taskIndex+direction,before=reset?'':direction===1?taskNext:taskCursors[target]||'';
 try{const query=new URLSearchParams({before,filter:$('remote-filter').value}),data=await api('tasks/page?'+query);if(reset)taskCursors=[''];taskIndex=target;taskCursors[target]=before;taskNext=data.next;const area=$('server-tasks');area.replaceChildren();data.items.forEach(t=>area.append(taskElement(t,false)));if(!data.items.length)empty(area,'当前页暂无任务。');$('remote-page').textContent=`第 ${taskIndex+1} 页 · ${data.items.length} 项`;}finally{taskBusy=false;$('remote-prev').disabled=!taskIndex;$('remote-next').disabled=!taskNext;}
}
function hashBlob(file,signal,onProgress,reusable){
  return new Promise((resolve,reject)=>{
    const worker=reusable||new Worker('./hash-worker.js');
    const stop=()=>{if(!reusable)worker.terminate();worker.onmessage=null;worker.onerror=null;signal?.removeEventListener('abort',abort);};
    const abort=()=>{worker.terminate();stop();reject(new DOMException('Paused','AbortError'));};
    if(signal?.aborted){abort();return;}signal?.addEventListener('abort',abort,{once:true});
    worker.onerror=()=>{stop();reject(new Error('文件摘要计算失败，请检查手机文件是否仍可访问'));};
    worker.onmessage=({data})=>{if(data.error){stop();reject(new Error(data.error));}else if(data.digest){stop();resolve(data.digest);}else onProgress?.(data.progress);};
    worker.postMessage({file});
  });
}
async function addFiles(files){
 if(!state.me?.can_upload){toast('当前账号为只读，不能上传。');return;}if(state.importing){toast('正在整理上一批文件，请稍候再添加。');return;}
 state.importing=true;let count=0;const known=new Map(state.files.map(t=>[t.key,t]));
 try{navigate('transfers');for(let i=0;i<files.length;i++){const file=files[i],key=`${file.webkitRelativePath||file.name}|${file.size}|${file.lastModified}`,old=known.get(key);if(old&&!['ERROR','CANCELLED'].includes(old.state))continue;const task={file,key,state:'UPLOADING',offset:0,percent:0,active:false,message:'等待开始',retried:!!old};state.files.push(task);known.set(key,task);count++;if(i%200===0){renderLocal();await sleep(0);}}}
 finally{state.importing=false;}if(count){state.paused=false;renderLocal();kick();toast(`已加入 ${count} 项，后台逐项处理；本页每页最多50项。`);}
}
function pause(){state.paused=true;state.files.forEach(t=>t.controller?.abort());toast('本次前台上传已暂停，服务器已确认的进度会保留。');}
async function resume(){if(state.paused)while(state.active)await sleep(50);state.paused=false;state.nextCandidate=0;for(const t of state.files)if(['PAUSED','ERROR'].includes(t.state)){if(t.state==='ERROR')t.taskId=null;t.state='UPLOADING';t.message='';}renderLocal();kick();}
function kick(){
  if(state.paused||!state.me?.can_upload)return;
  while(state.active<2){let task;while((state.nextCandidate||0)<state.files.length){const candidate=state.files[state.nextCandidate||0];state.nextCandidate=(state.nextCandidate||0)+1;if(!candidate.active&&candidate.state==='UPLOADING'){task=candidate;break;}}if(!task)break;task.active=true;state.active++;uploadFile(task).finally(()=>{task.active=false;state.active--;renderLocal();kick();});}
}
async function uploadFile(task){
  const controller=new AbortController();task.controller=controller;const signal=controller.signal;
  try{
    task.hashWorker=new Worker('./hash-worker.js');
    if(!task.digest){task.state='HASHING';task.message='分段读取，不将整个文件放入内存';renderLocal();task.digest=await hashBlob(task.file,signal,done=>{task.percent=100*done/Math.max(1,task.file.size);task.message=`正在校验 ${bytes(done)} / ${bytes(task.file.size)}`;renderLocal();},task.hashWorker);}
    const source=new NasSha256().update(new TextEncoder().encode(task.key)).digestHex();
    task.state='UPLOADING';task.message='查询服务器进度';renderLocal();
    if(!task.taskId){task.taskId=source;if(task.retried)task.taskId+='-'+Date.now();}
    let remote=await post('uploads',{client_task_id:task.taskId,name:task.file.name,size:String(task.file.size),sha256:task.digest,source_key:task.key,modified:String(task.file.lastModified),album:(task.file.webkitRelativePath||'').split('/').slice(0,-1).join('/')},{signal});
    task.uploadId=remote.id;
    if(['ERROR','CANCELLED'].includes(remote.state)){task.retried=true;throw new Error(`先前任务需要重建：${remote.error||'已取消'}。点击“继续上传”可重新建立任务。`);}
    let failures=0;
    while(remote.state==='UPLOADING'&&Number(remote.offset)<task.file.size){
      if(state.paused)throw new DOMException('Paused','AbortError');
      task.offset=Number(remote.offset);task.percent=100*task.offset/Math.max(1,task.file.size);task.state='SENDING';task.message='';renderLocal();
      const size=Number(state.overview?.chunk_bytes||8388608),chunk=task.file.slice(task.offset,task.offset+size),digest=await hashBlob(chunk,signal,null,task.hashWorker);
      try{remote=await api(`uploads/${remote.id}/data`,{method:'PUT',headers:{'Content-Type':'application/octet-stream','X-Upload-Offset':String(task.offset),'X-Chunk-SHA256':digest},body:chunk,signal,timeout:90000});failures=0;}
      catch(e){
        if(signal.aborted)throw e;
        if(e.status&&![409,429,500,503].includes(e.status))throw e;
        if(++failures>5)throw e;task.message=`连接中断或服务器忙，正在对账重试（${failures}/5）`;renderLocal();await sleep(Math.min(8000,1000*2**(failures-1)));remote=await api(`uploads/${remote.id}`,{signal});
      }
    }
    if(remote.state==='UPLOADING')remote=await post(`uploads/${remote.id}/complete`,{},{signal});
    task.offset=Number(remote.offset);task.percent=100;
    while(remote.state!=='READY'){
      if(['ERROR','CANCELLED'].includes(remote.state)){task.retried=true;throw new Error(`服务器完整校验未完成：${remote.error||'任务已取消'}`);}
      task.state=remote.state;task.message='服务器确认后才算备份成功';renderLocal();await sleep(600);remote=await api(`uploads/${remote.id}`,{signal});
    }
    task.state='READY';task.offset=task.file.size;task.percent=100;task.message='SHA256 校验通过';renderLocal();
    scheduleTransferRefresh();
  }catch(e){task.state=task.cancelled?'CANCELLED':signal.aborted||state.paused?'PAUSED':'ERROR';task.message=task.state==='PAUSED'?'可继续上传':task.cancelled?'服务器任务取消后可重新选择文件':e.message;renderLocal();}
  finally{task.hashWorker?.terminate();task.hashWorker=null;task.controller=null;}
}
const roleNames={admin:'管理员',member:'普通用户',viewer:'只读用户'};
async function loadUsers(){
  if(!state.me?.admin)return;const users=await api('users'),area=$('user-list');area.replaceChildren();
  for(const user of users){const row=node('div','device'),info=node('div');info.append(node('strong','',`${user.display_name}（${user.username}）`),node('p','',`${roleNames[user.role]} · ${user.enabled==='1'?'已启用':'已禁用'}${Number(user.last_seen)?' · 最近登录 '+new Date(Number(user.last_seen)).toLocaleString('zh-CN'):''}`));const edit=node('button','secondary','编辑账号');edit.onclick=()=>editUser(user);row.append(info,edit);area.append(row);}
}
function editUser(user){state.editingUser=user;$('edit-user-title').textContent=`编辑账号 · ${user.username}`;$('edit-display-name').value=user.display_name;$('edit-role').value=user.role;$('edit-enabled').checked=user.enabled==='1';$('edit-password').value='';$('edit-user-error').textContent='';$('user-editor').showModal();}
$('login-form').onsubmit=async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{await post('login',{username:$('username').value.trim(),password:$('password').value});$('password').value='';$('auth-error').textContent='';await entered();}catch(e){$('auth-error').textContent=e.message;}finally{b.disabled=false;}};
$('create-user-form').onsubmit=async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{await post('users',{username:$('create-username').value.trim(),display_name:$('create-display-name').value.trim(),role:$('create-role').value,password:$('create-password').value});$('create-user-form').reset();await loadUsers();toast('账号已创建，可以使用用户名和密码登录。');}catch(e){error(e.message);}finally{b.disabled=false;}};
$('refresh-users').onclick=()=>loadUsers().catch(e=>error(e.message));
$('close-user-editor').onclick=()=>$('user-editor').close();
$('edit-user-form').onsubmit=async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{const target=state.editingUser,input={display_name:$('edit-display-name').value.trim(),role:$('edit-role').value,enabled:$('edit-enabled').checked};if($('edit-password').value)input.new_password=$('edit-password').value;const result=await post(`users/${target.id}`,input);$('edit-password').value='';$('user-editor').close();if(target.id===state.me.user_id&&result.sessions_revoked){toast('账号已更新，请重新登录。');showAuth();}else{await loadUsers();toast('账号设置已保存。');}}catch(e){$('edit-user-error').textContent=e.message;}finally{b.disabled=false;}};
$('logout').onclick=async()=>{state.paused=true;state.files.forEach(t=>t.controller?.abort());try{await post('logout');}catch{}showAuth();};
$('profile-logout').onclick=$('logout').onclick;
document.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>navigate(b.dataset.page));
for(const id of ['file-input','folder-input'])$(id).onchange=e=>{addFiles(Array.from(e.target.files));e.target.value='';};
for(const id of ['hero-upload','choose-files'])$(id).onclick=()=>$('file-input').click();
$('pause').onclick=pause;$('resume').onclick=resume;$('see-all').onclick=()=>navigate('library');
$('refresh-tasks').onclick=()=>refreshTasks().catch(e=>error(e.message));
document.querySelectorAll('[data-type]').forEach(b=>b.onclick=()=>{state.type=b.dataset.type;document.querySelectorAll('[data-type]').forEach(x=>x.classList.toggle('selected',x===b));loadLibrary(false).catch(e=>error(e.message));});
$('search').oninput=()=>{clearTimeout(state.searchTimer);state.searchTimer=setTimeout(()=>loadLibrary(false).catch(e=>error(e.message)),300);};
$('load-more').onclick=()=>loadLibrary(true).catch(e=>error(e.message));$('close-preview').onclick=closePreview;$('preview').addEventListener('cancel',closePreview);
const zone=$('drop-zone');zone.ondragover=e=>{e.preventDefault();zone.classList.add('dragging');};zone.ondragleave=()=>zone.classList.remove('dragging');zone.ondrop=e=>{e.preventDefault();zone.classList.remove('dragging');addFiles(Array.from(e.dataTransfer.files));};
$('password-form').onsubmit=async e=>{e.preventDefault();try{await post('password',{old_password:$('old-password').value,new_password:$('new-password').value});$('password-form').reset();toast('密码已修改，请重新登录。');showAuth();}catch(e){error(e.message);}};
window.addEventListener('beforeunload',e=>{if(state.active){e.preventDefault();e.returnValue='前台上传尚未完成，刷新后需重选文件继续。';}});
setInterval(async()=>{if(!state.me||document.hidden)return;try{await refreshOverview();if(state.page==='transfers')await refreshTasks();}catch(e){$('connection').textContent='电脑暂时不可达';}},15000);
(async()=>{try{state.info=await api('server');$('server-name').textContent=`${state.info.name} · lw.HomeNAS`;await entered();}catch(e){showAuth();if(e.status!==401)$('auth-error').textContent=e.message;}})();

async function clientDownload(){try{const c=await api('client/latest');for(const id of ['android-download','home-android-download']){const a=$(id),hint=$(id==='android-download'?'android-download-info':'home-android-info');if(c.apk){a.href=`./api/v1/client/packages/${c.apk.sha256}.apk`;a.textContent=`下载安卓 App ${c.apk.versionName}`;a.classList.remove('hidden');hint.textContent=` ${bytes(c.apk.size)}`;}else{a.classList.add('hidden');a.removeAttribute('href');hint.textContent='安卓安装包尚未发布';}}}catch{}}
clientDownload();
$('publish-apk').onsubmit=async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{$('publish-status').textContent='正在上传APK…';const f=$('apk-file').files[0];if(f.size>128*1024*1024)throw new Error('APK不能超过128MiB');const blob=await api('admin/client/apk/blob',{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f,timeout:600000});await post('admin/client/apk/publish',{...blob,versionCode:Number($('apk-code').value),versionName:$('apk-name').value,notes:$('apk-notes').value});$('publish-status').textContent='APK已发布，登录页面可直接下载';clientDownload();}catch(e){$('publish-status').textContent=e.message;}finally{b.disabled=false;}};
$('publish-ui').onsubmit=async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{$('publish-status').textContent='正在上传资源…';const f=$('ui-file').files[0];if(f.size>20*1024*1024)throw new Error('资源不能超过20MiB');const envelope=JSON.parse(await $('ui-manifest').files[0].text());await api('admin/client/ui/blob',{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f,timeout:180000});await post('admin/client/ui/publish',envelope);$('publish-status').textContent='资源已发布，手机可下载后在下次启动使用';}catch(e){$('publish-status').textContent=e.message;}finally{b.disabled=false;}};
$('load-reports').onclick=async()=>{try{const items=await api('admin/diagnostics'),list=$('reports');list.replaceChildren();for(const r of items){const row=node('div','device'),a=node('a','button secondary','下载日志ZIP'),remove=node('button','secondary','删除报告');a.href=`./api/v1/admin/diagnostics/${r.id}`;row.append(node('span','',`${r.user} · ${r.description||"无问题描述"} · ${new Date(Number(r.created)).toLocaleString()} · ${r.id} · ${bytes(r.bytes)}`),a,remove);remove.onclick=async()=>{try{await api(`admin/diagnostics/${r.id}`,{method:'DELETE'});row.remove();}catch(e){error(e.message);}};list.append(row);}if(!items.length)empty(list,'暂无诊断报告');}catch(e){error(e.message);}};

for(const kind of ['apk','ui'])$('withdraw-'+kind).onclick=async()=>{try{await api('admin/client/'+kind,{method:'DELETE'});$('publish-status').textContent='已撤回 '+kind+'，已下载到手机的版本不受影响';await clientDownload();}catch(e){error(e.message);}};

$('local-prev').onclick=()=>{state.localPage--;renderLocal();};$('local-next').onclick=()=>{state.localPage=(state.localPage||0)+1;renderLocal();};$('local-filter').onchange=()=>{state.localPage=0;renderLocal();};
$('clear-finished').onclick=()=>{if(state.active){toast('请先暂停并等待当前任务停止，再清理完成项。');return;}state.files=state.files.filter(t=>t.state!=='READY');state.nextCandidate=0;state.localPage=0;renderLocal();};
$('library-prev').onclick=()=>loadLibrary(false,true).catch(e=>error(e.message));
$('remote-prev').onclick=()=>refreshTasks(false,-1).catch(e=>error(e.message));$('remote-next').onclick=()=>refreshTasks(false,1).catch(e=>error(e.message));$('remote-filter').onchange=()=>refreshTasks(true).catch(e=>error(e.message));
$('refresh-tasks').onclick=()=>refreshTasks(true).catch(e=>error(e.message));

function scheduleTransferRefresh(){if(state.refreshTimer)return;state.refreshTimer=setTimeout(()=>{state.refreshTimer=null;if(!state.me)return;Promise.all([refreshOverview(),state.page==='home'?loadRecent():Promise.resolve(),state.page==='transfers'?refreshTasks():Promise.resolve()]).catch(e=>error(e.message));},1800);}
const menuNames={home:'概览',library:'照片与文件',transfers:'备份任务',users:'用户管理',settings:'存储与设置'};
for(const b of document.querySelectorAll('nav [data-page]')){b.setAttribute('aria-label',menuNames[b.dataset.page]);b.title=menuNames[b.dataset.page];}
function applyMenu(collapsed){document.body.classList.toggle('sidebar-collapsed',collapsed);$('menu-toggle').setAttribute('aria-expanded',String(!collapsed));$('menu-toggle').textContent=collapsed?'☰ 展开菜单':'☰ 折叠菜单';}
try{applyMenu(localStorage.getItem('nas-menu-collapsed')==='1');}catch{applyMenu(false);}
$('menu-toggle').onclick=()=>{const collapsed=!document.body.classList.contains('sidebar-collapsed');applyMenu(collapsed);try{localStorage.setItem('nas-menu-collapsed',collapsed?'1':'0');}catch{}};

for(const input of document.querySelectorAll('input[type=password]')){
 const caption=input.labels?.[0]?.textContent.trim()||'密码';input.setAttribute('aria-label',caption);const box=document.createElement('span');box.className='password-field';input.parentNode.insertBefore(box,input);box.append(input);const toggle=document.createElement('button');toggle.type='button';toggle.textContent='显示';toggle.setAttribute('aria-label','显示'+caption);toggle.setAttribute('aria-controls',input.id);toggle.setAttribute('aria-pressed','false');
 toggle.onclick=()=>{const show=input.type==='password';input.type=show?'text':'password';toggle.textContent=show?'隐藏':'显示';toggle.setAttribute('aria-label',(show?'隐藏':'显示')+caption);toggle.setAttribute('aria-pressed',String(show));};box.append(toggle);
}
document.addEventListener('submit',()=>{for(const box of document.querySelectorAll('.password-field')){const input=box.querySelector('input'),button=box.querySelector('button');input.type='password';button.textContent='显示';button.setAttribute('aria-pressed','false');button.setAttribute('aria-label','显示'+input.getAttribute('aria-label'));}},true);

$('library-order').onchange=()=>loadLibrary(false).catch(e=>error(e.message));

// Virtual collections share originals; server remains authoritative for ownership and writes.
const collectionView=(()=>{
 const mobile=typeof nativeCall==='function',area=document.getElementById('library'),mode=document.getElementById('library-order');
 const make=(tag,text='',css='')=>{const n=document.createElement(tag);n.textContent=text;n.className=css;return n;};
 const notice=e=>mobile?showError(e.message||e):error(e.message||e);
 const writable=()=>mobile?!!current?.canUpload:!!state.me?.can_upload;
 const supported=()=>!mobile||Number(current?.nativeApi||0)>=4;
 let detail=null,history=[''],position=0,next='',generation=0,selecting=false,selected=new Set(),target=null,busy=false,lastMode='timeline';
 const bar=make('div','','collection-tools'),actions=make('div','','collection-actions'),selection=make('div','','collection-selection');
 const heading=make('strong'),description=make('span','','collection-description');
 area.before(bar);bar.append(actions,heading,description,selection);
 mode.hidden=true;mode.closest('label')?.classList.add('collection-mode-source');
 const tabs=make('div','','collection-tabs');tabs.setAttribute('role','group');tabs.setAttribute('aria-label','资料库展示');bar.prepend(tabs);
 for(const [value,label] of [['timeline','时间线'],['albums','图集']]){const b=make('button',label);b.type='button';b.dataset.mode=value;b.onclick=()=>{if(mode.value===value)return;mode.value=value;mode.dispatchEvent(new Event('change',{bubbles:true}));if(mobile)syncChoices();};tabs.append(b);}
 const button=(parent,text,fn,css='secondary')=>{const b=make('button',text,css);b.type='button';b.onclick=()=>Promise.resolve().then(fn).catch(notice);parent.append(b);return b;};
 const back=button(actions,'返回图集',()=>{detail=null;selected.clear();selecting=false;load();});
 const create=button(actions,'新建图集',()=>editForm(null));
 const rename=button(actions,'编辑图集',()=>editForm(detail));
 const add=button(actions,'添加照片 / 视频',()=>{target=detail;detail=null;mode.value='timeline';lastMode='timeline';selecting=true;selected.clear();loadLibrary(false);update();if(mobile)syncChoices();});
 const removeAlbum=button(actions,'删除图集',async()=>{if(await confirmDialog('删除图集？','只删除图集及其关联，照片和视频原件保留。')){await change('delete');detail=null;selecting=false;selected.clear();await load();}});
 const select=button(actions,'选择照片 / 视频',()=>{selecting=!selecting;selected.clear();update();});
 const count=make('span');selection.append(count);
 button(selection,'全选本页',()=>{for(const n of area.querySelectorAll('[data-asset-id]'))if(['image','video'].includes(n.dataset.category))selected.add(n.dataset.assetId);update();});
 const put=button(selection,'加入图集',async()=>{if(!selected.size)throw new Error('请先选择照片或视频');if(selected.size>100)throw new Error('每次最多选择100项');const destination=target||await chooseAlbum();if(!destination)return;await change('add',{asset_ids:[...selected]},destination.id);target=null;selected.clear();selecting=false;mode.value='albums';detail=destination;if(mobile)syncChoices();await load();});
 const remove=button(selection,'移出图集',async()=>{if(!selected.size)throw new Error('请先选择照片或视频');if(await confirmDialog('移出所选项目？','原件会保留在时间线中。')){await change('remove',{asset_ids:[...selected]});selected.clear();await load();}});
 const cover=button(selection,'设为封面',async()=>{if(selected.size!==1)throw new Error('请选择一项作为封面');await change('cover',{asset_id:[...selected][0]});selected.clear();await load();});
 button(selection,'取消选择',()=>{selected.clear();selecting=false;target=null;update();});
 const prev=document.getElementById('library-prev'),more=document.getElementById(mobile?'more':'load-more'),status=document.getElementById(mobile?'library-page':'library-status');
 function update(){
  const albums=mode.value==='albums',edit=!!detail?.can_edit;for(const b of tabs.children){b.classList.toggle('active',b.dataset.mode===mode.value);b.setAttribute('aria-pressed',String(b.dataset.mode===mode.value));}
  back.hidden=!albums||!detail;create.hidden=!albums||!!detail||!writable();rename.hidden=removeAlbum.hidden=add.hidden=!albums||!edit;
  select.hidden=!writable()||albums&&(!detail||!edit);select.textContent=selecting?'退出选择':'选择照片 / 视频';
  heading.textContent=albums?(detail?.name||'我的图集'):target?`选择内容加入「${target.name}」`:'';
  description.textContent=albums?(detail?`${detail.item_count} 项 · ${detail.description||'家庭共享图集'}`:'用图集整理照片与视频，原件保留在时间线。'):'';
  selection.hidden=!selecting;count.textContent=`已选 ${selected.size} 项`;remove.hidden=cover.hidden=!albums||!detail;put.hidden=albums;
  for(const n of area.querySelectorAll('[data-asset-id]')){const eligible=['image','video'].includes(n.dataset.category);n.classList.toggle('collection-selectable',selecting&&eligible);n.classList.toggle('collection-picked',selected.has(n.dataset.assetId));n.setAttribute('aria-label',(selected.has(n.dataset.assetId)?'已选 ':'')+(n.getAttribute('data-caption')||n.textContent));}
  const search=document.getElementById(mobile?'library-search':'search');search.disabled=albums&&!detail;
 }
 // Capture before preview/download handlers, including clicks on a thumbnail's children.
 area.addEventListener('click',event=>{const card=event.target.closest('[data-asset-id]');if(!selecting||!card)return;event.preventDefault();event.stopImmediatePropagation();if(!['image','video'].includes(card.dataset.category))return;const id=card.dataset.assetId;if(selected.has(id))selected.delete(id);else if(selected.size<100)selected.add(id);else notice('每次最多选择100项');update();},true);
 function active(){return mode.value==='albums';}
 function timeline(){if(lastMode!=='timeline'){++generation;selected.clear();selecting=false;}lastMode='timeline';update();}
 function reset(){++generation;detail=null;target=null;selecting=false;selected.clear();history=[''];position=0;next='';for(const dialog of bar.parentNode.querySelectorAll('dialog.collection-dialog'))dialog.close();}
 async function request(action,data={}){
  if(mobile){if(!supported())throw new Error('图集功能需要升级 APK 至0.7和电脑服务至0.4');return nativeCall(action,data);}
  if(action==='albumChange')return post(data.operation==='create'?'albums':`albums/${data.albumId}/${data.operation}`,data.value);
  const query=new URLSearchParams({before:data.before||'',type:data.type||'all',q:data.search||''});return api(action==='albumList'?`albums?${query}`:`albums/${data.albumId}/items?${query}`);
 }
 async function change(operation,value={},id=detail?.id){if(busy)throw new Error('正在保存，请稍候');busy=true;try{return await request('albumChange',{operation,albumId:id||'',value});}finally{busy=false;}}
 async function load(forward=false,previous=false){
  const id=++generation;lastMode='albums';selected.clear();if(!forward&&!previous){history=[''];position=0;next='';}const index=previous?position-1:forward?position+1:0,before=previous?history[index]:forward?next:'';
  prev.disabled=more.disabled=true;more.hidden=false;status.textContent='正在加载…';update();
  try{const r=await request(detail?'albumItems':'albumList',{albumId:detail?.id||'',before:before||'',type:mobile?document.getElementById('library-type').value:state.type,search:document.getElementById(mobile?'library-search':'search').value});if(id!==generation||!active())return;
   position=index;history[index]=before||'';next=r.next||'';if(r.album)detail=r.album;area.replaceChildren();area.classList.remove('timeline');
   for(const item of r.items){
    if(detail){const card=mobile?libraryCard(item):mediaCard(item);card.dataset.caption=item.name;area.append(card);}
    else{const card=make('button','','collection-card'),thumb=make('div','','collection-cover');card.type='button';
     if(item.cover?.preview==='READY'){const image=document.createElement('img');image.loading='lazy';image.alt='';image.src=mobile?`/thumb/${item.cover.id}.jpg`:`./api/v1/assets/${item.cover.id}/thumbnail`;image.onerror=()=>image.replaceWith(make('span','▧'));thumb.append(image);}else thumb.append(make('span','▧'));
     card.append(thumb,make('strong',item.name),make('small',`${item.item_count} 项${item.can_edit?' · 可管理':''}`));card.onclick=()=>{detail=item;selecting=false;load();};area.append(card);}
   }
   if(!r.items.length)area.append(make('p',detail?'图集暂无内容，可添加时间线中的照片或视频。':'还没有图集，可以创建一个。','empty'));
   status.textContent=`第 ${position+1} 页 · ${r.items.length} 项`;update();
  }catch(e){if(id===generation){area.replaceChildren(make('p',e.message,'empty'));status.textContent='加载失败';notice(e);}}
  finally{if(id===generation){prev.disabled=!position;more.disabled=!next;}}
 }
 function dialog(title){const d=make('dialog','','collection-dialog'),body=make('div'),footer=make('div','','collection-actions');d.append(make('h3',title),body,footer);bar.parentNode.append(d);d.showModal();d.addEventListener('close',()=>d.remove(),{once:true});return {d,body,footer};}
 async function confirmDialog(title,text){const {d,body,footer}=dialog(title);body.append(make('p',text));return new Promise(resolve=>{button(footer,'取消',()=>d.close());button(footer,'确定',()=>d.close('yes'));d.addEventListener('close',()=>resolve(d.returnValue==='yes'),{once:true});});}
 function editForm(item){const {d,body,footer}=dialog(item?'编辑图集':'新建图集'),name=make('input'),desc=make('textarea'),message=make('p','','collection-error');name.maxLength=50;name.value=item?.name||'';desc.maxLength=300;desc.value=item?.description||'';name.setAttribute('aria-label','图集名称');desc.setAttribute('aria-label','图集说明');const a=make('label','名称'),b=make('label','说明');a.append(name);b.append(desc);body.append(a,b,message);button(footer,'取消',()=>d.close());const save=button(footer,'保存',async()=>{save.disabled=true;try{const result=await change(item?'edit':'create',{name:name.value.trim(),description:desc.value},item?.id);if(item)detail=result;d.close();await load();}catch(e){message.textContent=e.message;}finally{save.disabled=false;}});name.focus();}
 async function chooseAlbum(){const {d,body,footer}=dialog('加入图集'),list=make('div','','collection-targets'),info=make('p');body.append(info,list);let before='',loading=false;return new Promise(resolve=>{let result=null;const more=button(footer,'更多图集',fetchPage);button(footer,'取消',()=>d.close());d.addEventListener('close',()=>resolve(result),{once:true});async function fetchPage(){if(loading)return;loading=true;more.disabled=true;try{const r=await request('albumList',{before});if(!d.open)return;for(const item of r.items)if(item.can_edit)button(list,item.name,()=>{result=item;d.close();});before=r.next||'';more.hidden=!before;info.textContent=list.children.length?'选择目标图集':'本页没有可管理图集，可继续翻页或先新建图集。';}catch(e){info.textContent=e.message;}finally{loading=false;more.disabled=false;}}fetchPage();});}
 // A new mode always returns to its root. Filters/search keep the open album.
 mode.addEventListener('change',()=>{reset();lastMode=mode.value;update();},true);
 function backAction(){if(selecting){selecting=false;selected.clear();target=null;update();return true;}if(active()&&detail){detail=null;load();return true;}return false;}
 new MutationObserver(update).observe(area,{childList:true,subtree:true});
 update();return {active,load,timeline,reset,back:backAction};
})();
