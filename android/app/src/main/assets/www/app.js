'use strict';
const $=id=>document.getElementById(id),pendingCalls=new Map();let sequence=0,current=null,cursor='',albumLoaded=false,polling=false;
const labels={QUEUED:'等待上传',HASHING:'计算摘要',UPLOADING:'正在上传',VERIFYING:'电脑校验',COMMITTING:'正在入库',READY:'已备份',ERROR:'需要处理',SOURCE_MISSING:'授权/源文件'};
function nativeCall(action,data={}){
  if(!window.HomeNasNative)return Promise.reject(new Error('请在 Android APK 中使用；普通网页不具备原生备份能力。'));
  return new Promise((resolve,reject)=>{const id=`n${++sequence}`,timer=setTimeout(()=>{pendingCalls.delete(id);reject(new Error('操作仍在处理，请刷新状态；确认进度不会丢失。'));},120000);pendingCalls.set(id,{resolve,reject,timer});window.HomeNasNative.postMessage(JSON.stringify({id,action,data}));});
}
if(window.HomeNasNative)window.HomeNasNative.onmessage=event=>{try{const r=JSON.parse(event.data),p=pendingCalls.get(r.id);if(!p)return;clearTimeout(p.timer);pendingCalls.delete(r.id);r.ok?p.resolve(r.data):p.reject(new Error(r.error||'操作未完成'));}catch{showError('手机模块返回了无效响应。');}};
function showError(message){$('error').textContent=message;$('error').hidden=false;}
function element(tag,text,className=''){const n=document.createElement(tag);n.textContent=text;n.className=className;return n;}
function bytes(s){let n=Number(s);if(n<0)return '待读取';let u=0;const units=['B','KB','MB','GB','TB'];while(n>=1024&&u<4){n/=1024;u++;}return `${n.toFixed(u?1:0)} ${units[u]}`;}
function page(name){for(const n of ['backup','library','settings'])$(`page-${n}`).hidden=n!==name;document.querySelectorAll('nav button').forEach(b=>b.classList.toggle('active',b.dataset.page===name));window.scrollTo(0,0);}
function render(s){
  if(current?.scope!==s.scope){$('library').replaceChildren();$('albums').replaceChildren();albumLoaded=false;cursor='';$('more').hidden=true;}
  const first=current===null;current=s;$('version').textContent=s.version||'';$('connection').textContent=s.user?`${s.user} · ${{admin:'管理员',member:'普通用户',viewer:'只读用户'}[s.role]||s.role}${s.allowHttp?' · 明文联调':''}`:'尚未登录 · 先连接家庭电脑';
  $('message').textContent=s.message||'';$('access').textContent=s.access||'';$('album-hint').textContent=s.access||'';
  $('signed-out').hidden=!!s.user;$('logout').hidden=!s.user;document.querySelectorAll('.upload-only').forEach(n=>n.hidden=!s.canUpload);
  if(first){$('endpoint').value=s.endpoint||'';$('username').value=s.user||s.lastUsername||'admin';$('allow-http').checked=!!s.allowHttp;$('auto').checked=!!s.auto;$('wifi').checked=s.wifiOnly!==false;$('charging').checked=!!s.charging;}
  $('certificate').textContent=s.certificate?`SHA256：${s.certificate}`:'使用系统信任的证书，或导入电脑公开证书。';
  const q=s.queue||{counts:{},items:[]},counts=q.counts||{};$('ready').textContent=counts.READY||0;$('failed').textContent=(counts.ERROR||0)+(counts.SOURCE_MISSING||0);$('pending').textContent=['QUEUED','HASHING','UPLOADING','VERIFYING','COMMITTING'].reduce((n,k)=>n+(counts[k]||0),0);
  $('tasks').replaceChildren();for(const task of q.items||[]){const row=element('div',''),left=element('div',''),right=element('span',labels[task.state]||task.state,'task-state');left.append(element('strong',task.name),element('small',`${bytes(task.offset)} / ${bytes(task.size)}${task.error?' · '+task.error:''}`));const p=document.createElement('progress');p.max=100;p.value=task.state==='READY'?100:Math.min(100,100*Number(task.offset)/Math.max(1,Number(task.size)));left.append(p);row.append(left,right);$('tasks').append(row);}if(!q.items?.length)$('tasks').append(element('p',s.user?'尚未选择文件；只读账号可浏览资料库。':'请先登录。','empty'));
}
async function refresh(){if(polling)return;polling=true;try{render(await nativeCall('snapshot'));}catch(e){showError(e.message);}finally{polling=false;}}
async function run(button,action,data={}){button.disabled=true;$('error').hidden=true;try{const r=await nativeCall(action,data);if(r?.queue)render(r);else await refresh();return r;}catch(e){showError(e.message);throw e;}finally{button.disabled=false;}}
function bind(id,action){$(id).onclick=()=>run($(id),action).catch(()=>{});}
document.querySelectorAll('nav button').forEach(b=>b.onclick=()=>{page(b.dataset.page);if(b.dataset.page==='library'&&current?.user&&!$('library').childNodes.length)loadLibrary(false);});
$('go-connect').onclick=()=>page('settings');
for(const [id,action] of [['choose','chooseFiles'],['permission','permission'],['metadata','metadataPermission'],['start','start'],['pause','pause'],['retry','retry'],['scan','scan'],['logout','logout'],['import-cert','importCertificate'],['clear-cert','clearCertificate']])bind(id,action);
$('refresh').onclick=refresh;
$('login').onsubmit=async event=>{event.preventDefault();const b=$('login').querySelector('[type=submit]');try{await run(b,'login',{endpoint:$('endpoint').value,username:$('username').value,password:$('password').value,allowHttp:$('allow-http').checked,trustCertificate:$('trust-cert').checked});$('auto').checked=false;page('backup');}catch{}finally{$('password').value='';}};
$('logout').addEventListener('click',()=>{$('auto').checked=false;});
$('load-albums').onclick=async()=>{try{const r=await run($('load-albums'),'albums');$('albums').replaceChildren();for(const a of r.items){const label=element('label','','check'),check=document.createElement('input');check.type='checkbox';check.value=a.key;check.checked=!!a.selected;label.append(check,element('span',a.name),element('small',`${a.count}项`));$('albums').append(label);}albumLoaded=true;if(!r.items.length)$('albums').append(element('p','暂无可访问相册，请先授权或调整选择范围。','empty'));}catch{}};
$('preferences').onsubmit=async event=>{event.preventDefault();if(!albumLoaded){showError('请先刷新相册并确认要备份的相册，再保存设置。');return;}const b=$('preferences').querySelector('[type=submit]');try{await run(b,'preferences',{albums:Array.from($('albums').querySelectorAll('input:checked'),n=>n.value),auto:$('auto').checked,wifiOnly:$('wifi').checked,charging:$('charging').checked});}catch{}};
async function loadLibrary(append){const b=append?$('more'):$('load-library');try{const r=await run(b,'assets',{before:append?cursor:''});if(!append)$('library').replaceChildren();for(const item of r.items){const card=element('div','','media');if(item.category==='image'&&item.preview==='READY'){const img=document.createElement('img');img.src=`/thumb/${item.id}.jpg`;img.alt=item.name;img.loading='lazy';img.onerror=()=>img.replaceWith(element('div','▧','placeholder'));card.append(img);}else card.append(element('div',item.category==='video'?'▷':'▤','placeholder'));card.append(element('strong',item.name),element('p',bytes(item.size),'subtle'));const save=element('button','保存原件到手机');save.onclick=()=>run(save,'download',{id:item.id}).catch(()=>{});card.append(save);$('library').append(card);}cursor=r.next||'';$('more').hidden=!cursor;if(!$('library').childNodes.length)$('library').append(element('p','电脑资料库暂时没有文件。','empty'));}catch{}}
$('load-library').onclick=()=>loadLibrary(false);$('more').onclick=()=>loadLibrary(true);
refresh();setInterval(()=>{if(!document.hidden)refresh();},3000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
