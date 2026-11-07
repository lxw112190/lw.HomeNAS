'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
function fixture(JSDOM,size=145){
 const dom=new JSDOM('<label><select id="library-order"><option value="timeline">时间线</option><option value="folders">文件夹</option><option value="albums">图集</option><option value="trash">回收站</option></select></label><input id="search"><button data-type="all"></button><div id="library"></div><button id="library-prev"></button><button id="load-more"></button><span id="library-status"></span>',{runScripts:'outside-only',url:'http://localhost/'});
 const w=dom.window,d=w.document,folder={id:'default',name:'默认文件夹',can_write:true,is_default:true,item_count:size},destination={id:'f'.repeat(48),name:'归档',can_write:true,item_count:0},album={id:'a'.repeat(48),name:'旅行',can_edit:true,item_count:0};
 const h={w,d,dom,calls:[],errors:[],toasts:[],apiHook:null,postHook:null,rows:Array.from({length:size},(_,i)=>({id:i.toString(16).padStart(48,'0'),name:'照片-'+i+'.jpg',category:'image',can_rename:true,can_restore:true,can_purge:true,deleted_at:'123',modified:'1793750400000',timeline_time:'1793750400000',size:'5000000',mime:'image/jpeg'})),removed:new Set(),members:new Set(),moved:new Set()};
 w.state={me:{user_id:'owner',can_upload:true,admin:true},type:'all',cursor:''};w.$=id=>d.getElementById(id);w.node=(tag,cls,text)=>{const n=d.createElement(tag);n.className=cls||'';if(text!==undefined)n.textContent=text;return n;};w.date=()=> '2026/11/4';w.error=e=>h.errors.push(String(e));w.toast=e=>h.toasts.push(e);w.refreshOverview=async()=>{};w.closePreview=()=>{};w.empty=(n,message)=>n.replaceChildren(w.node('p','empty',message));
 w.mediaCard=w.trashCard=item=>{const n=d.createElement('button');n.dataset.assetId=item.id;n.dataset.category=item.category;n.dataset.item=JSON.stringify(item);n.textContent=item.name;return n;};
 w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(v=''){this.returnValue=v;this.open=false;this.dispatchEvent(new w.Event('close'));};w.HTMLFormElement.prototype.submit=function(){h.exported=JSON.parse(this.querySelector('input').value);};
 const page=(rows,query)=>{const before=query.get('before')||'',visible=rows.filter(x=>!before||x.id>before.slice(6)),limit=Number(query.get('limit')||48),items=visible.slice(0,limit);return {items,next:visible.length>limit?'after:'+items.at(-1).id:''};};
 h.reply=path=>{
  const [route,search='']=path.split('?'),q=new URLSearchParams(search);let rows=h.rows.filter(x=>!h.removed.has(x.id));
  if(q.get('type')&&q.get('type')!=='all')rows=rows.filter(x=>x.category===q.get('type'));if(q.get('q'))rows=rows.filter(x=>x.name.includes(q.get('q')));
  if(route==='assets'||route==='trash')return {...page(rows.map(x=>({...x,in_album:!!q.get('album_id')&&h.members.has(x.id)})),q),from:q.get('from'),to:q.get('to'),membership_album_id:q.get('album_id')};
  if(route==='folders')return {items:[folder,destination],next:''};if(route==='albums')return {items:[album],next:''};
  if(route==='folders/default/items')return {...page(rows.filter(x=>!h.moved.has(x.id)),q),folder};
  if(route===`albums/${album.id}/items`)return {...page(rows.filter(x=>h.members.has(x.id)),q),album};
  if(route===`folders/${destination.id}/items`)return {...page(rows.filter(x=>h.moved.has(x.id)),q),folder:destination};
  throw Error('Unexpected route '+path);
 };
 w.api=async path=>{h.calls.push({method:'GET',path});return h.apiHook?await h.apiHook(path):h.reply(path);};
 w.post=async(path,value)=>{h.calls.push({method:'POST',path,value});if(h.postHook){const result=await h.postHook(path,value);if(result!==undefined)return result;}
  if(path==='folders/move-options')return {selected_count:value.asset_ids.length,items:[folder]};
  if(path==='assets/trash'||path==='assets/restore'||path.endsWith('/purge')){for(const id of value.asset_ids||[path.split('/')[1]])h.removed.add(id);}
  if(path.endsWith('/move'))for(const id of value.asset_ids)h.moved.add(id);
  if(path.endsWith('/add'))for(const id of value.asset_ids)h.members.add(id);
  return {};
 };
 const source=fs.readFileSync('web/app.js','utf8');w.eval(source.slice(source.indexOf('function renderTimeline('),source.indexOf('function metadataText(')));w.eval(source.slice(source.indexOf('let libraryCursors='),source.indexOf('let previewGeneration=')));
 w.eval(fs.readFileSync('web/library-collections.js','utf8')+';window.collectionView=createCollectionView();');
 h.buttons=text=>[...d.querySelectorAll('button')].filter(n=>n.textContent===text&&!n.hidden&&!n.closest('[hidden]'));
 h.click=async text=>{const b=h.buttons(text)[0];assert(b,'Missing button: '+text);assert(!b.disabled,'Disabled button: '+text);await b.onclick();await tick();};
 h.count=()=>Number(d.querySelector('.collection-selection>strong').textContent.match(/\d+/)?.[0]||0);
 h.choose=async text=>{await tick();const b=[...d.querySelector('dialog').querySelectorAll('button')].find(n=>n.textContent===text);assert(b,'Missing dialog choice '+text);await b.onclick();await tick();};
 h.mode=async value=>{d.getElementById('library-order').value=value;d.getElementById('library-order').dispatchEvent(new w.Event('change'));await w.loadLibrary(false);await tick();};
 return h;
}

async function run(JSDOM){
 let checks=0;
 const check=(condition,message)=>{assert(condition,message);checks++;};
 {
  const h=fixture(JSDOM);await h.w.loadLibrary(false);await h.click('选择文件');await h.click('全选本页');check(h.count()===48,'page one selects 48');
  await h.w.loadLibrary(true);h.d.querySelector('[data-asset-id]').click();await tick();check(h.count()===49,'next page adds to retained selection');check(/本页 1 项 · 其他页 48 项/.test(h.d.querySelector('.selection-info').textContent),'selection summary separates current and other pages');
  await h.w.loadLibrary(false,true);check(h.d.querySelectorAll('.collection-picked').length===48,'returning page restores every checkmark');await h.click('取消本页选择');check(h.count()===1,'deselect current page preserves other page');
  await h.click('查看已选');check(/照片-48/.test(h.d.querySelector('.selection-review').textContent),'review lists off-page selection');await h.choose('取消此项');check(h.count()===0,'off-page item can be removed from selection');await h.choose('完成');
  await h.click('全选本页');h.w.state.type='video';await h.w.loadLibrary(false);check(h.count()===0,'type change clears cross-page selection');check(!h.d.querySelector('[data-asset-id]'),'type query renders no stale images');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM);await h.w.loadLibrary(false);await h.click('选择文件');await h.click('全选本页');
  let resolve;h.apiHook=path=>path.includes('before=after')?new Promise(r=>resolve=()=>r(h.reply(path))):h.reply(path);
  const old=h.w.loadLibrary(true);h.w.state.type='video';await h.w.loadLibrary(false);resolve();await old;await tick();check(h.count()===0&&!h.d.querySelector('[data-asset-id]'),'late page response cannot overwrite a newer filter');check(h.d.getElementById('library-status').textContent.includes('第 1 页'),'late page does not overwrite page number');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM);await h.mode('folders');await h.d.querySelector('.collection-card').onclick();await h.click('选择文件');await h.click('全选本页');await h.w.loadLibrary(true);h.d.querySelector('[data-asset-id]').click();await tick();check(h.count()===49,'folder selection survives paging');
  const moving=h.click('移动到文件夹');await h.choose('归档');await h.choose('确定');await moving;check(h.moved.size===49&&h.count()===0,'folder move uses exact cross-page selection');check(h.d.getElementById('library-status').textContent.includes('第 2 页'),'successful move keeps nonempty current page');check(!h.calls.some(c=>c.path==='folders/move-options'),'known source folder needs no extra source picker');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM,60);await h.w.loadLibrary(false);await h.w.loadLibrary(true);await h.click('选择文件');await h.click('全选本页');const pending=h.click('移入回收站');await h.choose('确定');await pending;
  check(h.d.getElementById('library-status').textContent.includes('第 1 页')&&h.d.querySelectorAll('[data-asset-id]').length===48,'deleting final page falls back to a populated page');check(h.d.getElementById('library-prev').disabled,'batch completion cannot incorrectly re-enable previous page at page one');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM,60);await h.mode('folders');await h.d.querySelector('.collection-card').onclick();await h.w.loadLibrary(true);await h.click('选择文件');await h.click('全选本页');const pending=h.click('移入回收站');await h.choose('确定');await pending;
  check(h.d.getElementById('library-status').textContent.includes('第 1 页')&&h.d.getElementById('library-prev').disabled,'folder final-page deletion also restores the correct disabled previous button');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM);await h.w.loadLibrary(false);await h.click('选择文件');await h.click('全选本页');let resolve;h.apiHook=path=>new Promise(r=>resolve=()=>r(h.reply(path)));const pending=h.w.loadLibrary(true);await h.click('取消选择');resolve();await pending;
  check(h.count()===0&&h.d.getElementById('library-status').textContent.includes('第 2 页'),'cancelling selection during paging still permits the requested page to finish');check(h.d.querySelector('[data-asset-id]').dataset.assetId===h.rows[48].id,'cancel selection never leaves stale cards under a new page context');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM,205);await h.w.loadLibrary(false);await h.click('全选这一天');let n=0;h.postHook=(path)=>{if(path==='assets/trash'&&++n===2)throw Error('模拟第二批失败');};
  const pending=h.click('移入回收站');await h.choose('确定');await pending;check(h.count()===105&&h.removed.size===100,'partial failure retains all 105 unconfirmed items');check(h.errors.some(e=>e.includes('100 / 205')&&e.includes('105')),'failure reports precise completed and remaining counts');
  h.postHook=null;const retry=h.click('移入回收站');await h.choose('确定');await retry;const posts=h.calls.filter(c=>c.path==='assets/trash');check(posts.length===4&&posts[2].value.asset_ids.length===100&&posts[3].value.asset_ids.length===5,'retry submits only remaining 100 + 5');check(h.removed.size===205&&h.count()===0,'retry completes without resubmitting confirmed first batch');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM,55);h.rows[0].category='file';await h.w.loadLibrary(false);await h.click('选择文件');h.d.querySelector('[data-asset-id]').click();await h.w.loadLibrary(true);h.d.querySelector('[data-asset-id]').click();await tick();await h.click('加入图集');
  check(!h.d.querySelector('dialog')&&h.errors.some(e=>e.includes('非照片/视频')),'album validation inspects off-page files before any write');check(!h.calls.some(c=>c.path.endsWith('/add')),'mixed selection never causes a partial album add');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM,205);await h.mode('albums');await h.d.querySelector('.collection-card').onclick();await h.click('添加照片 / 视频');await tick();await h.click('全选这一天');let count=0;h.postHook=path=>{if(path.endsWith('/add')&&++count===2)throw Error('第二批失败');};await h.click('加入图集');
  check(h.count()===105&&h.members.size===100,'album add retains only failed/remainder across all pages');h.postHook=null;await h.click('加入图集');check(h.members.size===205&&h.count()===0,'album retry completes all remaining members');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM);await h.w.loadLibrary(false);await h.click('选择文件');await h.click('全选本页');h.d.getElementById('search').value='不匹配';await h.w.loadLibrary(false);check(h.count()===0&&!h.d.querySelector('[data-asset-id]'),'search cannot retain hidden selections from earlier results');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM);await h.w.loadLibrary(false);await h.click('全选这一天');let resolve;h.apiHook=path=>new Promise(r=>resolve=()=>r(h.reply(path)));const pending=h.click('全选这一天');await tick();await h.click('取消选择');resolve();await pending;check(h.count()===0,'cancelled asynchronous day result cannot repopulate selection');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM,1005);await h.w.loadLibrary(false);await h.click('全选这一天');check(h.count()===1000&&h.errors.some(e=>e.includes('1000')),'1000 item boundary stops instead of silently selecting incomplete whole day');check(!h.d.getElementById('load-more').disabled,'day selection limit restores normal paging controls');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM);await h.w.loadLibrary(false);await h.click('选择文件');h.d.querySelector('[data-asset-id]').click();const pending=h.click('移入回收站');await tick();h.w.state.me.user_id='other';h.w.collectionView.reset();await pending;check(!h.calls.some(c=>c.path==='assets/trash'),'account reset closes confirmation without a write');h.dom.window.close();
 }
 {
  const h=fixture(JSDOM);await h.mode('folders');await h.d.querySelector('.collection-card').onclick();let fail=true;h.apiHook=path=>{if(fail&&path.includes('before=after'))throw Error('分页网络失败');return h.reply(path);};await h.w.loadLibrary(true);check(h.d.getElementById('library-status').textContent==='加载失败','collection reports failed page load');fail=false;await h.w.loadLibrary(true);check(h.d.getElementById('library-status').textContent.includes('第 2 页'),'failed collection page does not advance cursor history');h.dom.window.close();
 }
 console.log('Library pagination DOM:',checks,'checks passed (production controller + production Web pager).');
 return checks;
}
module.exports={run};
if(require.main===module){const {JSDOM}=require(require('node:path').resolve(process.argv[2]));run(JSDOM).catch(e=>{console.error(e);process.exitCode=1;});}
