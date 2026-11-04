'use strict';
const $=id=>document.getElementById(id),pendingCalls=new Map();let sequence=0,current=null,cursor='',albumLoaded=false,polling=false;
let queueHistory=[''],queuePosition=0,queueNext='',queueBusy=false;
const labels={QUEUED:'等待上传',HASHING:'计算摘要',UPLOADING:'正在上传',VERIFYING:'电脑校验',COMMITTING:'正在入库',READY:'已备份',ERROR:'需要处理',SOURCE_MISSING:'授权/源文件'};
function nativeCall(action,data={}){
  if(!window.HomeNasNative)return Promise.reject(new Error('请在 Android APK 中使用；普通网页不具备原生备份能力。'));
  return new Promise((resolve,reject)=>{const id=`n${++sequence}`,timer=setTimeout(()=>{pendingCalls.delete(id);reject(new Error('操作仍在处理，请刷新状态；确认进度不会丢失。'));},120000);pendingCalls.set(id,{resolve,reject,timer});window.HomeNasNative.postMessage(JSON.stringify({id,action,data}));});
}
if(window.HomeNasNative)window.HomeNasNative.onmessage=event=>{try{const r=JSON.parse(event.data),p=pendingCalls.get(r.id);if(!p)return;clearTimeout(p.timer);pendingCalls.delete(r.id);r.ok?p.resolve(r.data):p.reject(new Error((r.error||'操作未完成')+(r.incident?` [${r.incident}]`:'')));}catch{showError('手机模块返回了无效响应。');}};
let lastClientLog=0;function clientLog(kind,name,line=0,column=0){if(!window.HomeNasNative||Date.now()-lastClientLog<5000)return;lastClientLog=Date.now();const safe=/^[A-Za-z0-9_.-]{1,80}$/.test(name)?name:'Error';window.HomeNasNative.postMessage(JSON.stringify({id:`log${++sequence}`,action:'clientLog',data:{kind,name:safe,line,column}}));}
window.addEventListener('error',e=>clientLog('error',e.error?.name||'ScriptError',e.lineno,e.colno));window.addEventListener('unhandledrejection',e=>clientLog('rejection',e.reason?.name||'PromiseError'));
function addressPreview(){const mode=$('protocol').value,raw=$('host').value.trim().replace(/：/g,':').replace(/．/g,'.'),port=$('port').value|| (mode==='http'?'8787':'9443');$('certificate-options').hidden=mode!=='https';$('http-note').hidden=mode!=='http';try{const u=new URL(raw.includes('://')?raw:`${mode}://${raw}`);if(!u.port)u.port=port;$('resolved-endpoint').textContent=u.origin;if($('login-server'))$('login-server').textContent=u.origin;}catch{$('resolved-endpoint').textContent='请填写有效的电脑IP和端口';}}
function fillAddress(value){try{const u=new URL(value),explicit=/^https?:\/\/(?:\[[^\]]+\]|[^/:?#]+):(\d+)(?:\/|$)/i.exec(value);$('protocol').value=u.protocol==='https:'?'https':'http';$('host').value=u.hostname;$('port').value=explicit?.[1]||u.port||($('protocol').value==='http'?'8787':'9443');}catch{$('protocol').value='http';$('host').value='192.168.1.100';$('port').value='8787';}addressPreview();}
function pastedAddress(){const raw=$('host').value.trim().replace(/：/g,':').replace(/．/g,'.');try{if(raw.includes('://')){const u=new URL(raw);if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.search||u.hash||!['','/'].includes(u.pathname))return;fillAddress(raw);}else if(/^.+:\d+$/.test(raw)&&!raw.startsWith('[')){const u=new URL(`${$('protocol').value}://${raw}`);$('host').value=u.hostname;$('port').value=raw.match(/:(\d+)$/)[1];}}catch{}addressPreview();}
function connectionData(){pastedAddress();return {host:$('host').value,port:$('port').value,protocol:$('protocol').value,trustCertificate:$('trust-cert').checked};}
function showError(message){$('error').textContent=message;$('error').hidden=false;}
let lastMessage='',messageTimer;
function showMessage(message){if(!message||message===lastMessage)return;lastMessage=message;if(message==='欢迎使用家庭备份')return;clearTimeout(messageTimer);$('message').textContent=message;$('message').hidden=false;messageTimer=setTimeout(()=>$('message').hidden=true,5000);}
function icon(name){const node=document.createElementNS('http://www.w3.org/2000/svg','svg'),use=document.createElementNS('http://www.w3.org/2000/svg','use');node.setAttribute('class','icon');node.setAttribute('aria-hidden','true');use.setAttribute('href','#icon-'+name);node.append(use);return node;}
function placeholder(type){const node=element('div','','placeholder');node.append(icon(type==='video'?'video':type==='image'?'library':'file'));return node;}
function element(tag,text,className=''){const n=document.createElement(tag);n.textContent=text;n.className=className;return n;}
function bytes(s){let n=Number(s);if(n<0)return '待读取';let u=0;const units=['B','KB','MB','GB','TB'];while(n>=1024&&u<4){n/=1024;u++;}return `${n.toFixed(u?1:0)} ${units[u]}`;}
let activePage='login';
function page(name){
 if(!current?.user&&!['login','settings'].includes(name))name='login';
 if(current?.user&&name==='login')name='backup';
 const changed=activePage!==name;activePage=name;
 for(const n of ['login','backup','library','settings','picker'])$(`page-${n}`).hidden=n!==name;
 document.body.dataset.page=name;document.body.classList.toggle('signed-in',!!current?.user);
 $('page-title').textContent={login:'lw.HomeNAS',backup:'备份',library:'资料库',settings:current?.user?'我':'连接设置',picker:'选择照片与视频'}[name];
 $('nav-back').setAttribute('aria-label',name==='picker'?'返回备份':'返回登录');$('bottom-nav').hidden=!current?.user||name==='picker';$('nav-back').hidden=name!=='picker'&&(!!current?.user||name!=='settings');
 document.querySelectorAll('#bottom-nav button').forEach(b=>{b.classList.toggle('active',b.dataset.page===name);b.setAttribute('aria-current',b.dataset.page===name?'page':'false');});
 if(changed){$('error').hidden=true;window.scrollTo(0,0);}
}
function openConnection(){page('settings');$('connection-settings').open=true;}

function render(s){
  if(current?.scope!==s.scope){queueHistory=[''];queuePosition=0;queueNext='';$('queue-filter').value='active';}
  if(s.user&&s.queueCursor!==undefined&&(s.queueCursor!==(queueHistory[queuePosition]||'')||s.queueFilter!==$('queue-filter').value))return;
  if(current?.scope!==s.scope){closeMedia();selectedDays.clear();selectedItems.clear();pickerDays.clear();$('picker-days').replaceChildren();pickerSelection();$('library').replaceChildren();$('albums').replaceChildren();albumLoaded=false;cursor='';$('more').hidden=true;}
  const first=current===null,wasSignedIn=!!current?.user;current=s;if(wasSignedIn!==!!s.user)document.querySelectorAll('.settings-group').forEach(n=>n.open=false);page(first||wasSignedIn!==!!s.user?(s.user?'backup':'login'):activePage);$('login-server').textContent=s.connectionAddress||s.endpoint||$('resolved-endpoint').textContent;$('back-login').hidden=!!s.user;const modern=Number(s.nativeApi||1)>=2;for(const id of ['queue-filter','library-type','library-search','library-search-button'])$(id).hidden=!modern;$('version').textContent=s.version||'';$('connection').textContent=({admin:'管理员',member:'普通用户',viewer:'只读用户'}[s.role]||'');$('profile-name').textContent=s.user||'我的账号';$('library-search-form').hidden=!modern;const capabilities=Number(s.nativeApi||1);$('choose-media').hidden=!s.canUpload||capabilities<3;$('choose').classList.toggle('primary',capabilities<3);$('choose').textContent=capabilities<3?'添加照片 / 文件':'选择其他文件';$('library-order').querySelector('[value=timeline]').disabled=capabilities<3;if(capabilities<3)$('library-order').value='created';
  showMessage(s.message||'');$('access').textContent=s.access||'';$('album-hint').textContent=s.access||'';
  $('logout').hidden=!s.user;document.querySelectorAll('.upload-only').forEach(n=>n.hidden=!s.canUpload);document.querySelectorAll('.session-only').forEach(n=>n.hidden=!s.user);
  if(first){fillAddress(s.connectionAddress||s.endpoint||'http://192.168.1.100:8787');$('username').value=s.user||s.lastUsername||'admin';$('auto').checked=!!s.auto;$('wifi').checked=s.wifiOnly!==false;$('charging').checked=!!s.charging;}
  $('log-session').textContent=`版本 ${s.version||''} · 构建 ${s.build||'local'} · 日志会话 ${s.logSession||'--'}`;
  $('certificate').textContent=s.certificate?`SHA256：${s.certificate}`:'使用系统信任的证书，或导入电脑公开证书。';
  const q=s.queue||{counts:{},items:[]},counts=q.counts||{};$('ready').textContent=counts.READY||0;$('failed').textContent=(counts.ERROR||0)+(counts.SOURCE_MISSING||0);$('pending').textContent=['QUEUED','HASHING','UPLOADING','VERIFYING','COMMITTING'].reduce((n,k)=>n+(counts[k]||0),0);
  const pending=Number($('pending').textContent),failed=Number($('failed').textContent);
  $('backup-status').textContent=pending?(s.paused?'已暂停，进度已保留':`${pending} 项等待或正在备份`):'选择要保存的照片和文件';
  $('start').querySelector('span').textContent=s.paused?'继续备份':'开始备份';$('retry').hidden=!s.canUpload||!failed;
  const visibleItems=modern?(q.items||[]):(q.items||[]).slice(queuePosition*50,queuePosition*50+50);
  queueNext=modern?(q.next||''):((queuePosition+1)*50<(q.items||[]).length?String(queuePosition+1):'');
  $('queue-next').disabled=!queueNext;$('queue-prev').disabled=!queuePosition;$('queue-page').textContent=`第 ${queuePosition+1} 页${modern?'':' · 最近100项'}`;
  $('tasks').replaceChildren();
  for(const task of visibleItems){
    const row=element('div','','task-row'),left=element('div','','task-detail'),mark=element('span','','task-icon');mark.append(icon(/\.(mp4|mov|mkv)$/i.test(task.name)?'video':'file'));
    const name=element('strong',task.name);name.title=task.name;left.append(name,element('small',`${bytes(task.offset)} / ${bytes(task.size)}${task.error?' · '+task.error:''}`));
    const right=element('span',labels[task.state]||task.state,'task-state'+(['ERROR','SOURCE_MISSING'].includes(task.state)?' failed':''));
    const progress=document.createElement('progress');progress.max=100;progress.value=task.state==='READY'?100:Math.min(100,100*Number(task.offset)/Math.max(1,Number(task.size)));progress.setAttribute('aria-label',task.name+'备份进度');left.append(progress);row.append(mark,left,right);$('tasks').append(row);
  }
  if(!visibleItems.length)$('tasks').append(element('p',s.canUpload?'暂无传输任务':'可在资料库浏览共享文件','empty'));

}
async function refresh(){if(polling)return;polling=true;try{render(await nativeCall('snapshot'));}catch(e){showError(e.message);}finally{polling=false;}}
async function run(button,action,data={}){button.disabled=true;$('error').hidden=true;try{const r=await nativeCall(action,data);if(r?.queue)render(r);else await refresh();return r;}catch(e){showError(e.message);throw e;}finally{button.disabled=false;}}
function bind(id,action){$(id).onclick=()=>run($(id),action).catch(()=>{});}
document.querySelectorAll('nav button').forEach(b=>b.onclick=()=>{page(b.dataset.page);if(b.dataset.page==='library'&&current?.user&&!$('library').childNodes.length)loadLibrary(false);});
$('login-settings').onclick=openConnection;$('login-server-button').onclick=openConnection;$('back-login').onclick=()=>page('login');$('nav-back').onclick=()=>page(activePage==='picker'?'backup':'login');
for(const b of document.querySelectorAll('[data-help]'))b.onclick=()=>{if(!$('help-dialog').open)$('help-dialog').showModal();};
for(const b of document.querySelectorAll('[data-close-dialog]'))b.onclick=()=>$(b.dataset.closeDialog).close();
for(const [id,action] of [['choose','chooseFiles'],['permission','permission'],['metadata','metadataPermission'],['start','start'],['pause','pause'],['retry','retry'],['scan','scan'],['logout','logout'],['import-cert','importCertificate'],['clear-cert','clearCertificate']])bind(id,action);
$('refresh').onclick=refresh;
$('host').addEventListener('change',pastedAddress);$('host').addEventListener('input',addressPreview);$('port').addEventListener('input',addressPreview);$('protocol').onchange=()=>{if(['8787','9443',''].includes($('port').value))$('port').value=$('protocol').value==='http'?'8787':'9443';addressPreview();};
$('test-connection').onclick=async()=>{try{const r=await run($('test-connection'),'testConnection',connectionData());if(r.endpoint)fillAddress(r.endpoint);}catch{}};
$('login').onsubmit=async event=>{event.preventDefault();const b=$('login').querySelector('[type=submit]');try{await run(b,'login',{...connectionData(),username:$('username').value,password:$('password').value});$('auto').checked=false;page('backup');}catch{}finally{$('password').value='';}};
$('login').addEventListener('invalid',event=>{showError(['host','port'].includes(event.target.id)?'请填写电脑IP和1至65535之间的端口，默认HTTP为8787。':'请填写用户名和密码。');clientLog('error','FormValidation');},true);
$('view-logs').onclick=async()=>{try{const r=await run($('view-logs'),'diagnostics');$('logs-tail').textContent=r.tail||'尚无日志';$('logs-info').textContent=`会话 ${r.session} · 导出ZIP包含轮转日志及诊断摘要`;$('logs-dialog').showModal();}catch{}};
$('close-logs').onclick=()=>$('logs-dialog').close();bind('export-logs','exportDiagnostics');bind('dialog-export','exportDiagnostics');
$('logout').addEventListener('click',()=>{$('auto').checked=false;});
$('load-albums').onclick=async()=>{try{const r=await run($('load-albums'),'albums');$('albums').replaceChildren();for(const a of r.items){const label=element('label','','check'),check=document.createElement('input');check.type='checkbox';check.value=a.key;check.checked=!!a.selected;label.append(check,element('span',a.name),element('small',`${a.count}项`));$('albums').append(label);}albumLoaded=true;if(!r.items.length)$('albums').append(element('p','暂无可访问相册，请先授权或调整选择范围。','empty'));}catch{}};
$('preferences').onsubmit=async event=>{event.preventDefault();if(!albumLoaded){showError('请先刷新相册并确认要备份的相册，再保存设置。');return;}const b=$('preferences').querySelector('[type=submit]');try{await run(b,'preferences',{albums:Array.from($('albums').querySelectorAll('input:checked'),n=>n.value),auto:$('auto').checked,wifiOnly:$('wifi').checked,charging:$('charging').checked});}catch{}};
let libraryHistory=[''],libraryPosition=0,libraryLoading=false;
function fileDate(value){const time=Number(value);return time>0?new Date(time).toLocaleString('zh-CN'): '未记录';}
function fileDay(value){const time=Number(value);return time>0?new Date(time).toLocaleDateString('zh-CN'):'未知日期';}
let previewItem=null;
function closeMedia(){for(const media of $('media-content').querySelectorAll('video')){media.pause();media.removeAttribute('src');media.load();}$('media-content').replaceChildren();if($('media-dialog').open)$('media-dialog').close();previewItem=null;if(Number(current?.nativeApi||1)>=3)nativeCall('previewClose').catch(()=>{});}
function previewMedia(item){
 closeMedia();previewItem=item;$('media-title').textContent=item.name;$('media-runtime').textContent='';$('media-details').replaceChildren();
 for(const [key,value] of [['大小',bytes(item.size)],['格式',item.mime||item.category],['文件时间',fileDate(item.modified)],['入库时间',fileDate(item.created)],['校验摘要',item.sha256||'未提供']])$('media-details').append(element('dt',key),element('dd',value));
 const modern=Number(current?.nativeApi||1)>=3;let media;
 if(item.category==='image'){media=document.createElement('img');media.alt=item.name;media.src=modern?`/media/${item.id}`:`/thumb/${item.id}.jpg`;media.onload=()=>$('media-runtime').textContent=(modern?'尺寸':'缩略图尺寸')+` ${media.naturalWidth} × ${media.naturalHeight}`;}
 else if(item.category==='video'&&modern){media=document.createElement('video');media.controls=true;media.preload='metadata';media.playsInline=true;media.src=`/media/${item.id}`;media.onloadedmetadata=()=>$('media-runtime').textContent=`${media.videoWidth} × ${media.videoHeight}`+(Number.isFinite(media.duration)?` · ${Math.round(media.duration)} 秒`:'');}
 if(media){media.onerror=()=>$('media-runtime').textContent='当前格式无法预览，可保存原件后打开';$('media-content').append(media);}else $('media-content').append(element('p',item.category==='video'?'视频预览需新版APK；可先保存原件':'可保存原件后打开','caption'));
 $('media-dialog').showModal();
}
$('media-close').onclick=closeMedia;$('media-dialog').addEventListener('cancel',event=>{event.preventDefault();closeMedia();});$('media-save').onclick=()=>{if(previewItem)run($('media-save'),'download',{id:previewItem.id}).catch(()=>{});};
function libraryCard(item){
 const card=element('div','','media'),open=element('button','','media-preview');open.type='button';open.setAttribute('aria-label','预览'+item.name);open.onclick=()=>previewMedia(item);
 if(item.category==='image'&&item.preview==='READY'){const img=document.createElement('img');img.src=`/thumb/${item.id}.jpg`;img.alt=item.name;img.loading='lazy';img.onerror=()=>img.replaceWith(placeholder('image'));open.append(img);}else open.append(placeholder(item.category));
 const name=element('strong',item.name);name.title=item.name;card.append(open,name,element('p',`${bytes(item.size)} · ${item.mime||item.category}`));
 const footer=element('div','','media-footer'),save=element('button','','save-small');save.type='button';save.setAttribute('aria-label','保存'+item.name+'到手机');save.append(icon('update'));save.onclick=()=>run(save,'download',{id:item.id}).catch(()=>{});footer.append(element('span',fileDay(item.modified||item.created)),save);card.append(footer);return card;
}
async function loadLibrary(append,previous=false){
 if(libraryLoading)return;libraryLoading=true;closeMedia();for(const id of ['library-type','library-search-button','library-search','library-prev','more','library-order'])$(id).disabled=true;
 const b=$('load-library'),target=previous?libraryPosition-1:append?libraryPosition+1:0,before=previous?libraryHistory[target]:append?cursor:'';
 try{
  $('library-page').textContent='正在加载…';const r=await run(b,'assets',{before:before||'',type:$('library-type').value,search:$('library-search').value,order:$('library-order').value,pageMode:true});
  if(!append&&!previous)libraryHistory=[''];libraryPosition=target;libraryHistory[target]=before||'';$('library').replaceChildren();$('library').classList.add('timeline');let previousDay='',grid;
  for(const item of r.items){const date=fileDay($('library-order').value==='timeline'?(item.timeline_time||item.modified||item.created):item.created);if(date!==previousDay){const group=element('section','','timeline-day');group.append(element('h2',date,'timeline-heading'));grid=element('div','','timeline-grid');group.append(grid);$('library').append(group);previousDay=date;}grid.append(libraryCard(item));}
  cursor=r.next||'';$('more').hidden=false;$('library-page').textContent=`第 ${libraryPosition+1} 页 · ${r.items.length} 项`;if(!r.items.length)$('library').append(element('p','当前筛选没有文件','empty'));if(append||previous)$('library').scrollIntoView({block:'start'});
 }catch{$('library-page').textContent='加载失败，请刷新重试';}
 finally{libraryLoading=false;for(const id of ['library-type','library-search-button','library-search','library-order'])$(id).disabled=false;$('more').disabled=!cursor;$('library-prev').disabled=!libraryPosition;}
}
$('load-library').onclick=()=>loadLibrary(false);$('more').onclick=()=>loadLibrary(true);
refresh();setInterval(()=>{if(!document.hidden)refresh();},5000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});

$('check-updates').onclick=async()=>{try{const r=await run($('check-updates'),'checkUpdates');$('update-info').textContent=`应用 ${r.apk?r.apk.versionName:'暂无更新'} · 界面 ${r.resourceVersion}${r.ui?'（有可用资源）':''}`;}catch{}};
bind('update-resources','updateResources');bind('install-apk','installApk');$('upload-logs').onclick=()=>run($('upload-logs'),'uploadLogs',{description:$('report-note').value}).catch(()=>{});
nativeCall('snapshot').then(()=>nativeCall('uiReady')).catch(()=>{});


async function queuePage(direction=0,reset=false){if(Number(current?.nativeApi||1)<2){queuePosition=reset?0:Math.max(0,queuePosition+direction);render(current);if(direction)$('tasks').scrollIntoView({block:'start'});return;}if(queueBusy)return;queueBusy=true;const target=reset?0:queuePosition+direction,before=reset?'':direction===1?queueNext:queueHistory[target]||'';try{const result=await nativeCall('queuePage',{before,filter:$('queue-filter').value});if(reset)queueHistory=[''];queuePosition=target;queueHistory[target]=before;render(result);$('queue-prev').disabled=!queuePosition;$('queue-page').textContent=`第 ${queuePosition+1} 页`;if(direction)$('tasks').scrollIntoView({block:'start'});}catch(e){showError(e.message);}finally{queueBusy=false;}}
$('queue-prev').onclick=()=>queuePage(-1);$('queue-next').onclick=()=>queuePage(1);$('queue-filter').onchange=()=>queuePage(0,true);
$('library-prev').onclick=()=>loadLibrary(false,true);$('library-search-form').onsubmit=event=>{event.preventDefault();loadLibrary(false);};$('library-type').onchange=()=>loadLibrary(false);

for(const input of document.querySelectorAll('input[type=password]')){
 const caption=input.labels?.[0]?.textContent.trim()||'密码';input.setAttribute('aria-label',caption);const box=document.createElement('span');box.className='password-field';input.parentNode.insertBefore(box,input);box.append(input);const toggle=document.createElement('button');toggle.type='button';toggle.textContent='显示';toggle.setAttribute('aria-label','显示'+caption);toggle.setAttribute('aria-controls',input.id);toggle.setAttribute('aria-pressed','false');
 toggle.onclick=()=>{const show=input.type==='password';input.type=show?'text':'password';toggle.textContent=show?'隐藏':'显示';toggle.setAttribute('aria-label',(show?'隐藏':'显示')+caption);toggle.setAttribute('aria-pressed',String(show));};box.append(toggle);
}
document.addEventListener('submit',()=>{for(const box of document.querySelectorAll('.password-field')){const input=box.querySelector('input'),button=box.querySelector('button');input.type='password';button.textContent='显示';button.setAttribute('aria-pressed','false');button.setAttribute('aria-label','显示'+input.getAttribute('aria-label'));}},true);

$('library-order').onchange=()=>loadLibrary(false);document.addEventListener('visibilitychange',()=>{if(document.hidden)closeMedia();});

let pickerDays=new Map(),selectedDays=new Set(),selectedItems=new Map(),pickerNext='',pickerBusy=false;
function pickerSelection(){ $('picker-selected').textContent=selectedDays.size||selectedItems.size?`已选 ${selectedDays.size} 天 / ${selectedItems.size} 项`:'未选择';$('picker-confirm').disabled=pickerBusy||!selectedDays.size&&!selectedItems.size; }
function updateDayChecks(date){const group=pickerDays.get(date);if(!group)return;for(const input of group.grid.querySelectorAll('input')){input.checked=input.dataset.ready!=='true'&&(selectedDays.has(date)||selectedItems.has(input.value));input.disabled=input.dataset.ready==='true'||selectedDays.has(date);}}
async function pickerItems(date,more=false){const group=pickerDays.get(date);if(!group||group.loading)return;group.loading=true;group.button.disabled=true;try{const data=await nativeCall('mediaItems',{day:date,before:more?group.next:''});if(!more)group.grid.replaceChildren();
 for(const item of data.items){const cell=element('label','','picker-cell'),image=document.createElement('img'),input=document.createElement('input');image.src=`/local-thumb/${item.id}.jpg`;image.alt=item.name;image.loading='lazy';image.onerror=()=>image.removeAttribute('src');input.type='checkbox';input.value=item.id;input.dataset.ready=String(item.status==='READY');input.setAttribute('aria-label','选择'+item.name);input.onchange=()=>{if(input.checked)selectedItems.set(item.id,date);else selectedItems.delete(item.id);pickerSelection();};
 const label=item.status==='READY'?'已备份':item.status==='UNCONFIRMED'?'待核实':({QUEUED:'已入队',HASHING:'校验中',UPLOADING:'上传中',VERIFYING:'电脑校验',COMMITTING:'入库中',ERROR:'可重试',SOURCE_MISSING:'源文件待确认',MISSING:'可重新备份'}[item.status]||'待备份');cell.append(image,input,element('span',(item.kind==='video'?'视频 · ':'')+label,item.status==='READY'?'backed-up':'unconfirmed'),element('span',`${bytes(item.size)}${item.width?' · '+item.width+'×'+item.height:''}`));cell.title=item.name;group.grid.append(cell);}
 group.next=data.next||'';group.button.textContent=group.next?'更多照片':'刷新照片';updateDayChecks(date);
 }catch(e){showError(e.message);}finally{group.loading=false;group.button.disabled=false;}}
async function loadPicker(more=false){if(pickerBusy)return;pickerBusy=true;pickerSelection();$('picker-refresh').disabled=true;try{if(!more){pickerDays.clear();selectedDays.clear();selectedItems.clear();$('picker-days').replaceChildren();}page('picker');const data=await nativeCall('mediaDays',{before:more?pickerNext:'',reset:!more});$('picker-info').textContent='按文件日期分组；已备份会自动跳过，无法确认时标记待核实。';
 for(const day of data.items){const section=element('section','','picker-day'),head=element('div','','picker-day-head'),label=element('label',''),check=document.createElement('input'),button=element('button','查看','text-button'),grid=element('div','','picker-day-grid');check.type='checkbox';check.setAttribute('aria-label','全选'+day.day);label.append(check,element('span',(day.day==='unknown'?'未知日期':day.day)+` · ${day.count} 项`));head.append(label,button);section.append(head,grid);$('picker-days').append(section);const moreButton=element('button','更多照片','picker-item-more');moreButton.type='button';moreButton.hidden=true;section.append(moreButton);pickerDays.set(day.day,{grid,button:moreButton,next:'',loading:false});
 check.onchange=()=>{if(check.checked){if(selectedDays.size>=30){check.checked=false;showError('一次最多选择30天');return;}selectedDays.add(day.day);for(const [id,date] of selectedItems)if(date===day.day)selectedItems.delete(id);}else selectedDays.delete(day.day);updateDayChecks(day.day);pickerSelection();};button.onclick=()=>{moreButton.hidden=false;pickerItems(day.day);};moreButton.onclick=()=>pickerItems(day.day,!!pickerDays.get(day.day).next);}
 pickerNext=data.next||'';$('picker-more').hidden=!pickerNext;if(!data.items.length&&!more)$('picker-info').textContent='暂无可访问的照片；请先授权相册，或返回使用系统文件选择器。';
 }catch(e){showError(e.message);}finally{pickerBusy=false;$('picker-refresh').disabled=false;pickerSelection();}}
$('choose-media').onclick=()=>loadPicker();$('picker-refresh').onclick=()=>loadPicker();$('picker-more').onclick=()=>loadPicker(true);$('picker-permission').onclick=()=>run($('picker-permission'),'permission').catch(()=>{});
$('picker-confirm').onclick=async()=>{if(pickerBusy)return;pickerBusy=true;pickerSelection();try{const result=await nativeCall('mediaEnqueue',{days:[...selectedDays],ids:[...selectedItems.keys()]});selectedDays.clear();selectedItems.clear();await refresh();page('backup');showMessage(`已加入 ${result.added} 项，跳过已完成或已入队 ${result.skipped} 项`);}catch(e){showError(e.message);}finally{pickerBusy=false;pickerSelection();}};
