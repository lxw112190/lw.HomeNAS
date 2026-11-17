'use strict';
/* Folder destinations and thematic albums share browsing controls, not storage ownership. */
function createCollectionView(){
 const mobile=typeof nativeCall==='function',area=document.getElementById('library'),mode=document.getElementById('library-order');
 const make=(tag,text='',css='')=>{const n=document.createElement(tag);n.textContent=text;n.className=css;return n;};
 const notice=e=>mobile?showError(e.message||e):error(e.message||e),writable=()=>mobile?!!current?.canUpload:!!state.me?.can_upload;
 const scope=()=>mobile?current?.scope:state.me?.user_id;
 let detail=null,target=null,selected=new Map(),dayBusy=false,selecting=false,generation=0,history=[''],position=0,next='',busy=false;
 let selectionKey='',pageLoading=false,pageTicket=null,loadGeneration=0,dayJob=0,pagerPrevious=false,pagerNext=false,extraTools=false;
 const filter=()=>({type:mobile?document.getElementById('library-type').value:state.type,search:document.getElementById(mobile?'library-search':'search').value.trim(),sort:mobile?'':document.getElementById('library-sort')?.value||''});
 const contextKey=()=>JSON.stringify([scope(),mode.value,detail?.id||'',target?.kind||'',target?.id||'',filter().type,filter().search.trim(),filter().sort,globalThis.HomeNasTimeline?.query?.()||{}]);
 function context(){const key=contextKey();if(key!==selectionKey){const count=selected.size;++generation;selected.clear();selectionKey=key;history=[''];position=0;next='';pagerPrevious=pagerNext=false;if(count&&!mobile)toast('筛选或位置已改变，已清除 '+count+' 项选择');}return {key,generation,scope:scope()};}
 const matches=t=>!!t&&t.generation===generation&&t.scope===scope()&&t.key===contextKey();
 function ticket(){context();return {...context(),ids:[...selected.keys()]};}
 function checkSelection(t){if(!matches(t)||t.ids.length!==selected.size||t.ids.some(id=>!selected.has(id)))throw new Error('选择范围已改变，请重新确认操作');}
 function loading(value,t){if(value){if(t&&!matches(t))return;pageTicket=t;}else{if(t&&pageTicket!==t)return;pageTicket=null;}pageLoading=value;area.inert=value;area.setAttribute('aria-busy',String(value));update();}
 function paging(previous,next){pagerPrevious=!!previous;pagerNext=!!next;update();}
 const kind=()=>mode.value==='folders'?'folders':mode.value==='albums'?'albums':target?.kind||'albums';
 const title=k=>k==='folders'?'文件夹':'图集',eligible=n=>!(target?.kind==='albums'&&JSON.parse(n.dataset.item||'{}').in_album)&&(mode.value==='trash'||kind()==='folders'||mode.value==='timeline'&&!target||['image','video'].includes(n.dataset.category));
 const bar=make('div','','collection-tools'),tabs=make('div','','collection-tabs'),actions=make('div','','collection-actions'),heading=make('strong'),description=make('span','','collection-description'),selection=make('div','','collection-selection');
 area.before(bar,selection);const breadcrumb=make('nav','','collection-breadcrumb');breadcrumb.setAttribute('aria-label','当前位置');bar.append(tabs,breadcrumb,actions,heading,description);selection.setAttribute('aria-label','跨页文件选择与操作');mode.hidden=true;mode.closest('label')?.classList.add('collection-mode-source');tabs.setAttribute('role','group');tabs.setAttribute('aria-label','资料库展示');
 const button=(parent,text,fn)=>{const b=make('button',text,'secondary');b.type='button';b.onclick=async()=>{if(b.disabled)return;if(busy){notice('正在执行批量操作，请稍候');return;}b.disabled=true;try{await fn();}catch(e){notice(e);}finally{b.disabled=false;update();}};parent.append(b);return b;};
 for(const [value,label] of [['folders','文件夹'],['timeline','时间线'],['albums','主题图集'],...(!mobile?[['trash','回收站']]:[])])button(tabs,label,()=>{if(mode.value===value)return;mode.value=value;mode.dispatchEvent(new Event('change',{bubbles:true}));if(mobile)syncChoices();}).dataset.mode=value;
 const back=button(actions,'返回',()=>upFolder()),create=button(actions,'新建',()=>editForm(null,kind())),rename=button(actions,'编辑',()=>editForm(detail,kind()));
 const add=button(actions,'添加内容',()=>{target={...detail,kind:kind()};detail=null;mode.value='timeline';selecting=true;selected.clear();loadLibrary(false);update();if(mobile)syncChoices();});
 const erase=button(actions,'删除',async()=>{const k=kind();if(await confirmDialog('删除'+title(k)+'？',k==='folders'?'其中的文件归入默认文件夹，原件保留。':'只删除图集及其关联，原件保留。')){await change(k,'delete',{},detail.id);detail=null;selecting=false;selected.clear();await load();}});
 const sub=button(actions,'新建子文件夹',()=>editForm(null,'folders')),policy=button(actions,'目录权限',()=>policyForm()),relocate=button(actions,'移动目录',()=>relocateFolder());
 const duplicates=mobile?null:button(actions,'重复文件',()=>globalThis.HomeNasDuplicates.open(mode.value==='folders'?detail:null));
 const moreTools=button(actions,'更多整理',()=>{extraTools=!extraTools;update();});
 const uploadHere=mobile?null:button(actions,'上传到当前文件夹',()=>openUploadToFolder(detail));
 const grantHere=mobile?null:button(actions,'授权',()=>policyForm());
 const promote=mobile?null:button(actions,'升级文件夹',()=>promoteFolder()),demote=mobile?null:button(actions,'降级文件夹',()=>demoteFolder());
 const autoCover=button(actions,'恢复自动封面',async()=>{const t=context();if(!await confirmDialog('恢复自动封面？','将使用图集内第一张有权查看的照片。'))return;if(!matches(t))return;await change('albums','cover',{asset_id:''},detail.id);await load();});
 const select=button(actions,'选择文件',()=>{++generation;selecting=!selecting;selected.clear();context();update();});
 const refresh=button(actions,'刷新列表',()=>{globalThis.HomeNasTimeline?.refresh();return active()?load(false,false,true):loadLibrary(false,false,true);});
 const count=make('strong'),selectionInfo=make('span','','selection-info');count.setAttribute('role','status');selection.append(count,selectionInfo);
 const pageSelect=button(selection,'全选本页',()=>{const nodes=[...area.querySelectorAll('[data-asset-id]')].filter(eligible),all=nodes.length&&nodes.every(n=>selected.has(n.dataset.assetId));for(const n of nodes){if(all)selected.delete(n.dataset.assetId);else pick(n);}update();});
 const clear=button(selection,'清除选择',()=>{++generation;selected.clear();update();}),review=button(selection,'查看已选',reviewSelection);
 const putFolder=button(selection,'加入文件夹',()=>put('folders')),putAlbum=button(selection,'加入图集',()=>put('albums'));
 const move=button(selection,'移动到文件夹',moveSelection);
 const remove=button(selection,'移出',async()=>{ensureSelected();const t=ticket(),k=kind(),id=detail.id;if(await confirmDialog('移出所选 '+t.ids.length+' 项？',k==='folders'?'所选文件归入默认文件夹，原件保留。':'只移出当前图集，原件保留在时间线中。')){checkSelection(t);await change(k,'remove',{asset_ids:t.ids,confirm_access_change:true},id);await reload();}});
 const trash=mobile?null:button(selection,'移入回收站',async()=>{ensureSelected();const t=ticket();if(!await confirmDialog('移入回收站 '+t.ids.length+' 项？',selectionNote()+'所有成员将暂时看不到这些文件，可以在回收站恢复。'))return;checkSelection(t);await batchPost('assets/trash');});
 const restore=mobile?null:button(selection,'批量恢复',async()=>{ensureSelected();const t=ticket();if(await confirmDialog('恢复所选 '+t.ids.length+' 项？',selectionNote()+'恢复后回到原来的时间线、文件夹和图集。')){checkSelection(t);await batchPost('assets/restore');}});
 const purge=mobile?null:button(selection,'彻底删除',purgeSelected);
 const download=mobile?null:button(selection,'导出 ZIP',exportSelected);
 const cover=button(selection,'设为封面',async()=>{if(selected.size!==1)throw new Error('请选择一项作为封面');await change('albums','cover',{asset_id:[...selected.keys()][0]},detail.id);selected.clear();await load();});
 const cancel=button(selection,'取消选择',cancelSelection);
 function cancelSelection(){const hadTarget=!!target;if(!pageLoading||hadTarget)++generation;++dayJob;selected.clear();selecting=false;target=null;dayBusy=false;update();if(hadTarget)return loadLibrary(false);}
 function visibleSelected(){return [...area.querySelectorAll('[data-asset-id]')].filter(n=>selected.has(n.dataset.assetId)).length;}
 function selectionNote(){const here=visibleSelected();return `共 ${selected.size} 项（本页 ${here} 项，其他页 ${selected.size-here} 项）。`;}
 function reviewSelection(){
  ensureSelected();const {d,body,footer}=dialog('已选文件 · 可逐项取消'),list=make('div','','selection-review'),info=make('p');body.append(info,list);let page=0;
  const previous=button(footer,'上一组',()=>{page--;render();}),more=button(footer,'下一组',()=>{page++;render();});button(footer,'完成',()=>d.close());
  function render(){const items=[...selected.values()];page=Math.max(0,Math.min(page,Math.ceil(items.length/20)-1));info.textContent=selectionNote();list.replaceChildren();for(const item of items.slice(page*20,page*20+20)){const row=make('div','','selection-review-row'),name=make('span',item.name||item.id),remove=button(row,'取消此项',()=>{selected.delete(item.id);update();render();});name.title=item.name||item.id;row.prepend(name);remove.classList.add('action-warning');list.append(row);}previous.disabled=!page;more.disabled=(page+1)*20>=items.length;if(!items.length)list.append(make('p','没有已选文件。'));}render();
 }

 function pick(n){if(!eligible(n))return;const item=JSON.parse(n.dataset.item||'{}');if(!item.id)return;if(selected.size>=1000&&!selected.has(item.id)){notice('每次最多1000项，请分批选择');return;}selected.set(item.id,item);}
 function decorateDays(){for(const group of area.querySelectorAll('.timeline-day')){const head=group.querySelector('.timeline-heading');if(!head||head.querySelector('.day-select'))continue;const b=button(head,'全选这一天',()=>selectDay(group));b.classList.add('day-select');}}
 async function selectDay(group){
  if(dayBusy||busy||pageLoading)return;context();const t=context(),job=++dayJob,criteria=filter(),album=target?.kind==='albums'?target.id:'';dayBusy=true;
  try{const first=group.querySelector('[data-item]');if(!first)return;const sample=JSON.parse(first.dataset.item),start=new Date(Number(sample.timeline_time||sample.modified||sample.created));if(!Number.isFinite(start.getTime()))throw new Error('无法识别日期，请按本页选择');start.setHours(0,0,0,0);const end=new Date(start);end.setDate(end.getDate()+1);selecting=true;update();let cursor='',total=0;const cursors=new Set();
   do{const data={before:cursor,...criteria,order:'timeline',selectionOnly:true,from:String(start.getTime()),to:String(end.getTime()),...(album?{album_id:album}:{})};const r=mobile?await nativeCall('assets',data):await api('assets?'+new URLSearchParams({...data,q:data.search,limit:'100'}));if(!matches(t))return;if(r.from!==data.from||r.to!==data.to||album&&r.membership_album_id!==album)throw new Error('按天选择和图集标记需要电脑服务1.0.2，请先升级电脑端');
    for(const item of r.items){if(album&&(item.in_album||!['image','video'].includes(item.category)))continue;if(selected.size>=1000&&!selected.has(item.id))throw new Error('已保留1000项选择，请先分批操作');selected.set(item.id,item);total++;}
    cursor=r.next||'';if(cursor&&cursors.has(cursor))throw new Error('服务器分页游标重复，已停止选择，请刷新');cursors.add(cursor);count.textContent='正在选择：'+total+' 项';
   }while(cursor);
  }finally{if(job===dayJob){dayBusy=false;update();}}
 }
 async function reload(){if(active())await load(false,false,true);else await loadLibrary(false,false,true);if(!mobile)refreshOverview().catch(notice);update();}
 async function batchPost(path){
  ensureSelected();if(busy)throw new Error('正在执行操作');const t=ticket(),ids=t.ids;busy=true;update();let done=0;
  try{for(let at=0;at<ids.length;at+=100){if(!matches(t))throw new Error('选择范围或登录身份已改变');const chunk=ids.slice(at,at+100);await post(path,{asset_ids:chunk});if(!matches(t))return;chunk.forEach(id=>selected.delete(id));done+=chunk.length;count.textContent='已完成 '+done+' / '+ids.length;}await reload();if(!mobile)toast('已完成 '+done+' 项');}
  catch(e){if(matches(t)){if(done)await reload().catch(()=>{});throw new Error('已确认完成 '+done+' / '+ids.length+' 项；剩余 '+selected.size+' 项保留选中，可重试。'+e.message);}}
  finally{busy=false;update();}
 }
 async function purgeSelected(){ensureSelected();const t=ticket(),items=[...selected.values()];if(!await confirmDialog('彻底删除 '+items.length+' 项？',selectionNote()+'电脑原件和缩略图将永久删除，所有成员均无法恢复。手机本地文件保留，再次备份可能重新上传。',true))return;checkSelection(t);if(busy)throw new Error('正在执行操作');busy=true;update();let done=0;try{for(const item of items){if(!matches(t))return;await post('assets/'+item.id+'/purge',{confirm:'DELETE',deleted_at:String(item.deleted_at)});if(!matches(t))return;selected.delete(item.id);done++;count.textContent='已删除 '+done+' / '+items.length;}await reload();}catch(e){if(matches(t)){if(done)await reload().catch(()=>{});throw new Error('已确认删除 '+done+' 项；剩余 '+selected.size+' 项保留选中，请核对后重试：'+e.message);}}finally{busy=false;update();}}
 function exportSelected(){ensureSelected();const form=make('form'),input=make('input');form.method='POST';form.action='./api/v1/assets/export';form.target='_blank';form.setAttribute('rel','noopener');input.type='hidden';input.name='selection';input.value=JSON.stringify({asset_ids:[...selected.keys()]});form.append(input);document.body.append(form);form.submit();form.remove();toast('已提交 ZIP 导出，请查看浏览器下载');}
 for(const b of [create,add,putFolder,putAlbum])b.classList.add('action-primary');for(const b of [move,rename,cover])b.classList.add('action-edit');remove.classList.add('action-warning');for(const b of [erase,trash,purge])if(b)b.classList.add('action-danger');if(restore)restore.classList.add('action-restore');if(download)download.classList.add('action-export');
 const prev=document.getElementById('library-prev'),more=document.getElementById(mobile?'more':'load-more'),status=document.getElementById(mobile?'library-page':'library-status');
 function folderStats(item){const n=Number(item.total_bytes||0),unit=n>=1073741824?'GB':n>=1048576?'MB':'KB',scale=unit==='GB'?1073741824:unit==='MB'?1048576:1024;return `${(n/scale).toFixed(1)} ${unit} · 照片 ${item.photo_count||0} · 视频 ${item.video_count||0}`;}
 function policyLabel(item){return ({private:'仅所有者',restricted:'指定成员',public:item.parent_id?'父级范围内只读':'所有账号只读',inherit:'继承父目录',legacy:'兼容共享'})[item.policy]||'以服务器权限为准';}
 function active(){return ['folders','albums'].includes(mode.value);}
 function ensureSelected(){context();if(!selected.size)throw new Error('请先选择文件');if(selected.size>1000)throw new Error('每次最多选择1000项');}
 function update(){if(duplicates)duplicates.hidden=mode.value==='trash'||!!target;sub.hidden=!(mode.value==='folders'&&detail?.can_manage&&Number(detail.depth||1)<16);policy.hidden=relocate.hidden=!(mode.value==='folders'&&detail?.can_manage&&!detail.is_default);globalThis.HomeNasTimeline?.visibility?.(mode.value==='timeline');const k=kind(),on=active(),edit=!!detail?.can_edit,manage=k==='folders'?!!detail?.can_write:edit;for(const n of area.querySelectorAll('[data-item]'))if(selected.has(n.dataset.assetId))selected.set(n.dataset.assetId,JSON.parse(n.dataset.item));
  if(uploadHere)uploadHere.hidden=!(mode.value==='folders'&&detail?.can_write&&writable());if(grantHere)grantHere.hidden=!(mode.value==='folders'&&detail?.can_manage&&!detail.is_default);if(!mobile)policy.hidden=true;
  if(promote){promote.hidden=demote.hidden=!(mode.value==='folders'&&detail?.can_manage&&!detail.is_default);promote.title=detail?.parent_id?'移到父目录的上一级，子目录一起移动':'当前已是一级文件夹';demote.title='移入一个有管理权限的同级文件夹，子目录一起移动';}
  for(const b of tabs.children){b.hidden=b.dataset.mode==='trash'&&!writable();b.classList.toggle('active',b.dataset.mode===mode.value);b.setAttribute('aria-pressed',String(b.dataset.mode===mode.value));}
  const sortBox=document.getElementById('library-sort');if(sortBox){sortBox.disabled=busy||dayBusy||mode.value==='trash'||on&&!detail&&!filter().search.trim();sortBox.title=sortBox.disabled?'文件排序用于目录/图集内部、搜索和时间线':'对当前全部结果排序';}
  autoCover.hidden=!on||k!=='albums'||!detail||!edit;
  back.textContent='返回'+title(k);back.hidden=!on||!detail;create.textContent='新建'+title(k);create.hidden=!on||!!detail||!writable();rename.textContent='编辑'+title(k);erase.textContent='删除'+title(k);rename.hidden=erase.hidden=!on||!edit;add.hidden=!on||!manage;add.textContent=k==='folders'?'添加文件':'添加照片 / 视频';
  select.hidden=(mode.value==='trash'&&!writable())||on&&!detail;select.textContent=selecting?'退出选择':k==='albums'&&on?'选择照片 / 视频':'选择文件';
  breadcrumb.replaceChildren();const root=button(breadcrumb,'资料库',()=>{detail=null;target=null;mode.value='folders';mode.dispatchEvent(new Event('change',{bubbles:true}));});if(on&&detail){if(k==='folders'&&detail.breadcrumbs?.length){for(const part of detail.breadcrumbs){breadcrumb.append(make('span',' / '));if(part.id===detail.id)breadcrumb.append(make('span',part.name));else button(breadcrumb,part.name,async()=>{detail={id:part.id};selected.clear();selecting=false;await load();});}}else breadcrumb.append(make('span',' / '),make('span',detail.name));}else breadcrumb.append(make('span',' / '+(mode.value==='folders'?'文件夹':mode.value==='timeline'?'时间线':mode.value==='albums'?'主题图集':'回收站')));
  heading.textContent=on?(detail?.name||'家庭'+title(k)):target?`选择内容加入「${target.name}」`:'';
  description.textContent=on?(detail?`${detail.stats_scope?'本层 ':''}${detail.item_count} 项${detail.child_count!==undefined?' · 子目录 '+detail.child_count:''}${detail.total_bytes!==undefined?' · '+folderStats(detail):''} · ${detail.description||(k==='folders'?policyLabel(detail):'主题图集')}${detail.owner_name?' · '+detail.owner_name:''}`:k==='folders'?'只显示有权访问的目录；新建一级目录默认私有，子目录继承。':'主题图集只保留照片引用，适合跨文件夹制作专题；日常整理请用文件夹。'):'';
  if(on&&!detail&&filter().search.trim()){heading.textContent='全局搜索结果';description.textContent='搜索所有有权访问的文件夹中的文件。';}
  moreTools.hidden=on||mode.value==='trash'||!!target||!writable();moreTools.textContent=extraTools?'收起整理':'更多整理';
  selection.hidden=!selecting;count.textContent=`已选 ${selected.size} 项`;selectionInfo.textContent=`本页 ${visibleSelected()} 项 · 其他页 ${selected.size-visibleSelected()} 项${dayBusy?' · 正在选择整天…':busy?' · 正在处理…':''}`;
  const nodes=[...area.querySelectorAll('[data-asset-id]')].filter(eligible);pageSelect.textContent=nodes.length&&nodes.every(n=>selected.has(n.dataset.assetId))?'取消本页选择':'全选本页';pageSelect.disabled=!nodes.length;clear.disabled=review.disabled=!selected.size;
  const inTrash=mode.value==='trash';if(trash){trash.hidden=inTrash||!writable()||!!target;trash.disabled=!selected.size||[...selected.values()].some(x=>!x.can_rename);trash.title=trash.disabled?'请选择有权删除的文件':'将所选项目移入回收站';restore.hidden=!inTrash;restore.disabled=!selected.size||[...selected.values()].some(x=>x.can_restore===false);restore.title=restore.disabled?'请选择可恢复项目，取消正在清理的项目':'恢复所选项目';purge.hidden=!inTrash||!(mobile?false:state.me?.admin);purge.disabled=!selected.size;download.hidden=inTrash;download.disabled=!selected.size;}remove.hidden=!on||!detail||k==='folders'&&detail.is_default;remove.textContent=k==='folders'?'移到默认文件夹':'移出图集';cover.hidden=!on||!detail||k!=='albums';move.hidden=inTrash||!!target||!writable()||on&&(!detail||k==='folders'&&!manage);putFolder.hidden=inTrash||!writable()||on||!!target&&target.kind!=='folders';putAlbum.hidden=inTrash||!writable()||on||!extraTools&&target?.kind!=='albums';remove.hidden=remove.hidden||!manage||k==='folders'&&!!filter().search.trim();cover.hidden=cover.hidden||!manage;
  for(const b of [putFolder,putAlbum,move,remove])b.disabled=!selected.size;cover.disabled=selected.size!==1;refresh.hidden=on&&!detail;
  const locked=busy||dayBusy||pageLoading;for(const b of [...bar.querySelectorAll('button'),...selection.querySelectorAll('button')]){if(locked)b.disabled=true;else if(b.dataset.operationLocked==='true'){b.disabled=false;}b.dataset.operationLocked=String(locked);}
  // Reapply per-control constraints after releasing an operation lock.
  if(!locked){pageSelect.disabled=!nodes.length;clear.disabled=review.disabled=!selected.size;for(const b of [putFolder,putAlbum,move,remove])b.disabled=!selected.size;cover.disabled=selected.size!==1;if(trash){trash.disabled=!selected.size||[...selected.values()].some(x=>!x.can_rename);restore.disabled=!selected.size||[...selected.values()].some(x=>x.can_restore===false);purge.disabled=download.disabled=!selected.size;}}
  cancel.disabled=busy;for(const b of area.querySelectorAll('.day-select'))b.disabled=locked;
  if(promote){promote.disabled=locked||!detail?.parent_id;demote.disabled=locked;}
  prev.disabled=locked||!pagerPrevious;more.disabled=locked||!pagerNext;const firstButton=document.getElementById('library-first'),lastButton=document.getElementById('library-last');if(firstButton)firstButton.disabled=prev.disabled;if(lastButton)lastButton.disabled=more.disabled;for(const input of document.querySelectorAll(mobile?'#library-type,#library-search,#library-search-button':'[data-type],#search'))input.disabled=busy||dayBusy;
  for(const n of area.querySelectorAll('[data-asset-id]')){n.classList.toggle('collection-selectable',selecting&&eligible(n));n.classList.toggle('collection-picked',selected.has(n.dataset.assetId));const added=!!target&&JSON.parse(n.dataset.item||'{}').in_album;n.classList.toggle('collection-added',added);if(added&&!n.querySelector('.already-added'))n.append(make('span','已添加','already-added'));const badge=n.querySelector('.already-added');if(badge)badge.hidden=!added;n.setAttribute('aria-label',(selected.has(n.dataset.assetId)?'已选 ':'')+(n.dataset.caption||n.textContent));}
  const searchBox=document.getElementById(mobile?'library-search':'search');searchBox.disabled=busy||dayBusy;searchBox.placeholder=on&&detail?(k==='folders'?'搜索此文件夹及所有子目录':'搜索此图集内的文件名'):'全局搜索文件名';if(mobile&&activePage==='library')document.getElementById('nav-back').setAttribute('aria-label',backLabel());
 }
 area.addEventListener('click',event=>{const n=event.target.closest('[data-asset-id]');if(!selecting||!n)return;event.preventDefault();event.stopImmediatePropagation();if(busy||dayBusy||pageLoading||!eligible(n))return;if(selected.has(n.dataset.assetId))selected.delete(n.dataset.assetId);else pick(n);update();},true);
 async function request(k,action,data={}){const session=scope();let result;
  if(mobile){if(Number(current?.nativeApi||0)<(k==='folders'?7:4))throw new Error('请升级APK和电脑服务后使用'+title(k));const prefix=k==='folders'?'folder':'album';result=await nativeCall(prefix+action,{...data,[prefix+'Id']:data.id||''});}
  else{if(action==='Change')result=await post(data.operation==='create'?k:`${k}/${data.id}/${data.operation}`,data.value);else{const query=new URLSearchParams({before:data.before||'',type:data.type||'all',q:data.search||'',sort:data.sort||''});if(data.parent_id!==undefined)query.set('parent_id',data.parent_id);result=await api(action==='List'?`${k}?${query}`:`${k}/${data.id}/items?${query}`);}}
  if(scope()!==session)throw new Error('登录身份已改变');return result;
 }
 async function change(k,operation,value={},id=''){
  if(busy)throw new Error('正在保存，请稍候');const t=context();busy=true;update();let done=0;
  try{if(!value.asset_ids)return await request(k,'Change',{operation,id,value});let result;for(let at=0;at<value.asset_ids.length;at+=100){if(!matches(t))throw new Error('选择范围或登录身份已改变');const chunk=value.asset_ids.slice(at,at+100);result=await request(k,'Change',{operation,id,value:{...value,asset_ids:chunk}});if(!matches(t))return;done+=chunk.length;chunk.forEach(id=>selected.delete(id));count.textContent='已完成 '+done+' / '+value.asset_ids.length;}return result;}
  catch(e){if(matches(t)){if(done)await reload().catch(()=>{});throw new Error('已确认完成 '+done+' 项；剩余 '+selected.size+' 项保留选中。'+e.message);}}
  finally{busy=false;update();}
 }
 async function load(forward=false,previous=false,keepPage=false,jump=''){
  const t=context(),sequence=++loadGeneration,k=kind(),criteria=filter(),containerId=detail?.id||'',globalSearch=!containerId&&!!criteria.search.trim();
  let index=Math.max(0,previous?position-1:forward?position+1:keepPage?position:0),before=jump==='last'?'@last':previous?(history[index]??'@page:'+index):forward?next:keepPage?(history[index]??'@page:'+index):'';
  if(forward&&!next||previous&&!position)return;loading(true,t);more.hidden=false;status.textContent='正在加载…';
  try{
   const r=globalSearch?(mobile?await nativeCall('assets',{...criteria,before:before||'',order:'created'}):await api('assets?'+new URLSearchParams({type:criteria.type,q:criteria.search.trim(),sort:criteria.sort,before:before||'',limit:'48'}))):await request(k,containerId?'Items':'List',{id:containerId,before:before||'',...criteria,...(k==='folders'&&!containerId?{parent_id:''}:{})});
   if(sequence!==loadGeneration||!matches(t)||mode.value!==k)return;
   if(keepPage&&index>0&&!r.items.length)return load();
   if(Number.isInteger(r.page_index))index=r.page_index;
   if(!forward&&!previous&&!keepPage)history=[''];position=index;history[index]=before||'';next=r.next||'';
   if(r.folder||r.album)detail=r.folder||r.album;area.replaceChildren();area.classList.remove('timeline');if(k==='folders'&&detail&&!criteria.search.trim()&&r.children)renderChildren(r.children,context());
   for(const item of r.items){
    if(detail||globalSearch){if(detail&&k==='folders'&&!criteria.search.trim())item.source_folder=detail.id;const n=mobile?libraryCard(item):mediaCard(item);n.dataset.caption=item.name;area.append(n);}
    else{
     const n=button(area,'',()=>{detail=item;selecting=false;selected.clear();return load();});n.className='collection-card';
     const thumb=make('div','','collection-cover');
     if(k==='albums'&&item.cover?.preview==='READY'){const image=document.createElement('img');image.loading='lazy';image.alt='';image.src=mobile?`/thumb/${item.cover.id}.jpg`:`./api/v1/assets/${item.cover.id}/thumbnail`;image.onerror=()=>image.replaceWith(make('span','▧'));thumb.append(image);}
     else thumb.append(!mobile&&globalThis.HomeNasIcons?HomeNasIcons.icon('',k==='folders'):make('span',k==='folders'?'📁':'▧','collection-symbol'));
     n.append(thumb,make('strong',item.full_path||item.name),make('small',`${item.item_count} 项${k==='folders'&&item.total_bytes!==undefined?' · '+folderStats(item):''}${item.owner_name?' · '+item.owner_name:''}${k==='folders'&&item.policy?' · '+policyLabel(item):''}${item.can_edit?' · 可管理':''}`));
    }
   }
   if(!r.items.length)area.append(make('p',detail?'这里暂时没有文件。':'可以新建'+title(k)+'。','empty'));
   status.textContent=`第 ${position+1} 页 · ${r.items.length} 项`;update();
  }catch(e){if(sequence===loadGeneration&&matches(t)){area.replaceChildren(make('p',e.message+'，请点“刷新列表”重试。','empty'));status.textContent='加载失败';notice(e);}}
  finally{if(sequence===loadGeneration){loading(false,t);paging(!!position,!!next);}}
 }
 async function put(k){
  ensureSelected();const t=ticket();
  if(k==='albums'&&[...selected.values()].some(item=>!['image','video'].includes(item.category)))throw new Error('所选项目中含其他页的非照片/视频文件，请在“查看已选”中取消，或改用文件夹');
  const destination=target?.kind===k?target:await chooseCollection(k);if(!destination)return;checkSelection(t);
  if(k==='folders'&&!await confirmDialog('添加到文件夹？','文件若引用多个目录，按权限交集控制访问；添加私有引用可能让其他成员无法查看。'))return;await change(k,'add',{asset_ids:t.ids,confirm_access_change:true},destination.id);
  if(!matches(t))return;target=null;selected.clear();selecting=false;mode.value=k;detail=destination;if(mobile)syncChoices();await load();
 }
 async function moveSelection(){
  ensureSelected();const t=ticket();let source=active()&&kind()==='folders'&&!filter().search.trim()?detail:null;
  if(!source){
   let options;try{options=mobile?await nativeCall('folderMoveOptions',{asset_ids:t.ids}):await post('folders/move-options',{asset_ids:t.ids});}catch(e){if(e.status===404)throw new Error('请升级电脑服务后使用跨页移动，或进入来源文件夹移动');throw e;}
   checkSelection(t);if(Number(options.selected_count)!==t.ids.length)throw new Error('电脑返回的移动范围不一致，请刷新后重试');
   if(!options.items.length)throw new Error('所选文件没有共同且可管理的来源文件夹。可以用“加入文件夹”归档，或分别进入来源文件夹移动。');
   source=options.items.length===1?options.items[0]:await chooseCollection('folders',options.items);
  }
  if(!source)return;checkSelection(t);const destination=await chooseCollection('folders',null,source.id);if(!destination)return;checkSelection(t);
  if(!await confirmDialog('批量移动 '+t.ids.length+' 项？',selectionNote()+'从「'+source.name+'」移到「'+destination.name+'」，其他关联保留，文件有效权限取全部目录的交集，可能改变哪些人能查看。'))return;
  checkSelection(t);await change('folders','move',{asset_ids:t.ids,target_id:destination.id,confirm_access_change:true},source.id);if(!matches(t))return;await reload();if(!mobile&&matches(t))toast('已移动 '+t.ids.length+' 项到「'+destination.name+'」');
 }
 async function upFolder(){const parent=kind()==='folders'?detail?.parent_id:'';detail=parent?{id:parent}:null;selected.clear();selecting=false;await load();}
 function renderChildren(result,t){const box=make('div','','folder-children');area.append(box);const append=r=>{for(const child of r.items||[]){const b=button(box,'▣ '+child.name,async()=>{detail=child;selected.clear();selecting=false;await load();});if(!mobile&&globalThis.HomeNasIcons){b.textContent=child.name;b.prepend(HomeNasIcons.icon('',true));}}if(r.next){const next=button(box,'更多子目录',async()=>{const page=await request('folders','List',{before:r.next,parent_id:detail.id});if(!matches(t))return;next.remove();append(page);});}};append(result);}
 async function policyForm(){const t=ticket(),id=detail.id;const info=mobile?await nativeCall('folderPolicy',{id}):await api('folders/'+id+'/permissions');if(!matches(t))return;const {d,body,footer}=dialog('目录权限'),modeInput=make('select');modeInput.setAttribute('aria-label','权限模式');for(const [value,label] of [['private','仅目录所有者'],['restricted','指定成员'],['public',info.parent_id?'父级可见成员只读':'所有账号只读'],...(info.parent_id?[['inherit','继承父目录']]:[])]){const o=make('option',label);o.value=value;modeInput.append(o);}modeInput.value=info.policy==='legacy'?'public':info.policy;body.append(make('p','子目录授权不能超过父目录。文件引用多个目录时，取这些目录权限的交集。服务器管理员始终可以管理全部资料。'),modeInput);const list=make('div'),rows=[];for(const person of info.people||[]){if(person.id===info.owner_id||person.role==='admin')continue;const label=make('label',person.display_name+' · '+person.username),select=make('select');for(const [value,text] of [[0,'不授权'],[1,'只读'],[2,'可整理'],[3,'管理']]){if(person.role==='viewer'&&value>1)continue;const o=make('option',text);o.value=value;select.append(o);}select.value=(info.grants||[]).find(g=>g.user_id===person.id)?.level||'0';label.append(select);list.append(label);rows.push({person,select});}const policyError=make('p','','collection-error');policyError.setAttribute('role','alert');body.append(list,policyError);modeInput.onchange=()=>list.hidden=modeInput.value!=='restricted';modeInput.onchange();button(footer,'取消',()=>d.close());button(footer,'保存权限',async()=>{try{policyError.textContent='';if(!matches(t))throw Error('位置已改变');if(!await confirmDialog('确认修改目录权限？','影响所有下级目录、文件引用和正在上传的任务。被撤销权限的任务停止，确认进度保留。'))return;const value={policy:modeInput.value,grants:rows.filter(r=>Number(r.select.value)>0).map(r=>({user_id:r.person.id,level:Number(r.select.value)})),confirm_access_change:true};detail=mobile?await nativeCall('folderPolicy',{id,value}):await post('folders/'+id+'/permissions',value);d.close();await load();}catch(e){policyError.textContent=e.message;}});}
 async function relocateFolder(){const t=ticket(),source=detail,destination=await chooseCollection('folders',null,source.id,true);if(!destination||!matches(t))return;if(!destination.can_manage)throw Error('需要目标父目录的管理权限');if(!mobile)return changeFolderLevel(source,destination,t,'移动');if(!await confirmDialog('移动整个目录？','下级目录会一起移动，并继承目标父目录的权限上限。请核对新位置的可见范围。'))return;if(!matches(t))return;detail=await change('folders','relocate',{parent_id:destination.id,confirm_access_change:true},source.id);await load();}
 async function changeFolderLevel(source,destination,t,operation){
  if(!matches(t))return;if(!destination.can_manage)throw Error('需要目标父目录的管理权限');
  const path=destination.full_path||destination.name,impact=await api('folders/'+source.id+'/relocation-impact',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({parent_id:destination.id})});if(!matches(t))return;const summary='涉及 '+impact.folders+' 个目录；'+(impact.changes.length?impact.changes.map(x=>x.name+'：可见目录 '+x.before.readable+'→'+x.after.readable+'，可整理 '+x.before.writable+'→'+x.after.writable).join('；'):'未发现成员权限级别变化')+(impact.truncated?'（仅预览前200个账号）':'');
  if(!await confirmDialog(operation+'「'+source.name+'」？','目标位置：'+path+'。'+summary+'。所有下级目录一起移动，继承目标父目录的权限上限，可能改变哪些成员能查看；移到资料库根目录时，原来继承权限的文件夹会改为私有。最多支持16层目录。'))return;
  if(!matches(t))return;const result=await change('folders','relocate',{parent_id:destination.id,confirm_access_change:true},source.id);if(!matches(t))return;detail=result;selected.clear();selecting=false;await load();if(!mobile)toast('已'+operation+'到「'+path+'」');
 }
 async function promoteFolder(){
  const t=ticket(),source=detail;if(!source?.can_manage||source.is_default)throw Error('需要当前文件夹的管理权限');if(!source.parent_id)throw Error('当前已是一级文件夹');
  const parent=await api('folders/'+source.parent_id);if(!matches(t))return;
  const destination=parent.parent_id?await api('folders/'+parent.parent_id):{id:'',name:'资料库根目录',can_manage:true};
  return changeFolderLevel(source,destination,t,'升级');
 }
 async function demoteFolder(){
  const t=ticket(),source=detail;if(!source?.can_manage||source.is_default)throw Error('需要当前文件夹的管理权限');
  const candidates=[],seen=new Set();let before='';
  do{const r=await request('folders','List',{parent_id:source.parent_id||'',before});if(!matches(t))return;
   candidates.push(...r.items.filter(f=>f.id!==source.id&&!f.is_default&&f.id!=='default'&&f.can_manage));before=r.next||'';
   if(before&&seen.has(before))throw Error('目录分页重复，请刷新后重试');seen.add(before);
  }while(before);
  if(!candidates.length)throw Error('没有可作为父目录的同级文件夹，请先新建同级文件夹或取得其管理权限');
  const destination=await chooseFolderTree(candidates,source.id,false,{manageOnly:true,title:'降级：选择作为父目录的同级文件夹'});if(!destination||!matches(t))return;
  return changeFolderLevel(source,destination,t,'降级');
 }
 function dialog(text){const d=make('dialog','','collection-dialog'),body=make('div'),footer=make('div','','collection-actions');d.append(make('h3',text),body,footer);document.body.append(d);d.showModal();d.addEventListener('close',()=>d.remove(),{once:true});return {d,body,footer};}
 async function confirmDialog(text,note,typed=false){const {d,body,footer}=dialog(text);body.append(make('p',note));const input=make('input');if(typed){const label=make('label','输入“彻底删除”确认');input.maxLength=4;label.append(input);body.append(label);}return new Promise(resolve=>{const cancel=button(footer,'取消',()=>d.close()),ok=button(footer,typed?'彻底删除':'确定',()=>{if(!typed||input.value==='彻底删除')d.close('yes');});ok.classList.add(typed||/删除|回收站/.test(text)?'action-danger':'action-primary');if(typed){ok.disabled=true;input.oninput=()=>{ok.disabled=input.value!=='彻底删除';};}cancel.focus();d.addEventListener('close',()=>resolve(d.returnValue==='yes'&&(!typed||input.value==='彻底删除')),{once:true});});}
 function editForm(item,k,onSave,parentFolder=detail){const {d,body,footer}=dialog((item?'编辑':'新建')+title(k)),name=make('input'),desc=make('textarea'),message=make('p','','collection-error');name.maxLength=50;name.value=item?.name||'';desc.maxLength=300;desc.value=item?.description||'';name.setAttribute('aria-label',title(k)+'名称');desc.setAttribute('aria-label',title(k)+'说明');const a=make('label','名称'),b=make('label','说明');a.append(name);b.append(desc);body.append(a,b,...(k==='folders'&&!item?[make('p','保存位置：'+(parentFolder?.full_path||parentFolder?.name||'资料库根目录'))]:[]),message);button(footer,'取消',()=>d.close());const save=button(footer,'保存',async()=>{save.disabled=true;try{const result=await change(k,item?'edit':'create',{name:name.value.trim(),description:desc.value,...(k==='folders'&&!item?{parent_id:parentFolder?.id||''}:{})},item?.id||'');if(!d.open)return;d.close();if(onSave)onSave(result);else{if(item)detail=result;await load();}}catch(e){message.textContent=e.message;}finally{save.disabled=false;}});save.classList.add('action-primary');name.focus();}
 async function chooseCollection(k,candidates=null,excludeId='',rootChoice=false){if(k==='folders')return chooseFolderTree(candidates,excludeId,rootChoice);const {d,body,footer}=dialog('选择'+(candidates?'来源':'目标')+title(k)),list=make('div','','collection-targets'),info=make('p');body.append(info,list);let before='',loading=false;return new Promise(resolve=>{let result=null;const finish=item=>{if(!d.open)return;result=item;d.close();};const more=button(footer,'更多'+title(k),fetchPage);const newTarget=button(footer,'新建'+title(k),()=>editForm(null,k,finish));newTarget.classList.add('action-primary');newTarget.hidden=!!candidates||rootChoice;if(rootChoice&&k==='folders')button(footer,'资料库根目录',()=>finish({id:'',name:'资料库根目录',can_manage:true}));button(footer,'取消',()=>d.close());d.addEventListener('close',()=>resolve(result),{once:true});async function fetchPage(){if(loading)return;loading=true;more.disabled=true;try{const r=candidates?{items:candidates,next:''}:await request(k,'List',{before});if(!d.open)return;for(const item of r.items)if(item.id!==excludeId&&!(k==='folders'&&excludeId&&item.breadcrumbs?.some(a=>a.id===excludeId))&&(k==='folders'?(rootChoice?item.can_manage:item.can_write):item.can_edit))button(list,(item.full_path||item.name)+(item.owner_name?' · '+item.owner_name:''),()=>finish(item));before=r.next||'';more.hidden=!before;info.textContent=list.children.length?'选择'+(candidates?'来源':'目标')+title(k):'可以新建'+title(k)+'，或继续翻页。';}catch(e){info.textContent=e.message;}finally{loading=false;more.disabled=false;}}fetchPage();});}
 async function chooseFolderTree(candidates=null,excludeId='',rootChoice=false,options={}){
  const {d,body,footer}=dialog(options.title||(candidates?'选择来源文件夹':'选择目标文件夹')),tree=make('div','','folder-tree'),info=make('p');tree.setAttribute('role','group');tree.setAttribute('aria-label','文件夹目录树');body.append(info,tree);const session=scope();
  return new Promise(resolve=>{let result=null;const finish=item=>{if(!d.open||scope()!==session)return;result=item;d.close();};
   if(!candidates&&!rootChoice)button(footer,'新建文件夹',()=>editForm(null,'folders',finish,null));
   if(rootChoice)button(footer,'资料库根目录',()=>finish({id:'',name:'资料库根目录',can_manage:true}));
   button(footer,'取消',()=>d.close());d.addEventListener('close',()=>resolve(result),{once:true});
   async function page(parent,box,before=''){
    const loading=make('p','正在加载目录…');box.append(loading);
    try{const r=candidates?{items:candidates,next:''}:await request('folders','List',{parent_id:parent,before});if(!d.open||scope()!==session)return;loading.remove();
     for(const item of r.items){if(item.id===excludeId||excludeId&&item.breadcrumbs?.some(a=>a.id===excludeId))continue;
      const branch=make('div','','folder-tree-branch'),row=make('div','','folder-tree-row'),children=make('div','','folder-tree-children');children.hidden=true;box.append(branch);branch.append(row,children);
      let loaded=false;const expand=button(row,'▸',async()=>{children.hidden=!children.hidden;expand.textContent=children.hidden?'▸':'▾';expand.setAttribute('aria-expanded',String(!children.hidden));if(!loaded){loaded=true;await page(item.id,children);}});expand.setAttribute('aria-label','展开 '+item.name);expand.setAttribute('aria-expanded','false');expand.hidden=!!candidates||Number(item.child_count)===0;
      const choose=button(row,(candidates?item.full_path||item.name:item.name)+(item.owner_name?' · '+item.owner_name:''),()=>finish(item));choose.disabled=!(rootChoice||options.manageOnly?item.can_manage:item.can_write);choose.title=item.full_path||item.name;if(!mobile&&globalThis.HomeNasIcons)choose.prepend(HomeNasIcons.icon('',true));
     }
     if(r.next){const next=button(box,'更多目录',async()=>{next.remove();await page(parent,box,r.next);});}
     if(!r.items.length)box.append(make('p','没有可见子目录','caption'));info.textContent='展开多级目录后，点击可写文件夹的名称选择。';
    }catch(e){loading.remove();const retry=button(box,'重试加载目录',()=>{retry.remove();return page(parent,box,before);});info.textContent=e.message;}
   }page('',tree);
  });
 }
 function reset(){extraTools=false;++generation;++loadGeneration;++dayJob;detail=target=null;selecting=dayBusy=false;selectionKey='';selected.clear();history=[''];position=0;next='';pagerPrevious=pagerNext=false;loading(false);for(const d of document.querySelectorAll('dialog.collection-dialog'))d.close();}
 function timeline(){const t=context();decorateDays();update();return t;}
 function backAction(){if(selecting){cancelSelection();return true;}if(active()&&detail){upFolder();return true;}return false;}
 function backLabel(){return active()&&detail?'返回'+title(kind()):'返回备份';}
 mode.addEventListener('change',()=>{reset();update();},true);new MutationObserver(()=>{decorateDays();update();}).observe(area,{childList:true,subtree:true});update();
 async function moveAsset(item){const t=context();const members=await api(`assets/${item.id}/folders`);const sources=members.items.filter(f=>f.can_write);if(!sources.length)throw new Error('当前没有可管理的来源文件夹');const source=item.source_folder?sources.find(f=>f.id===item.source_folder):sources.length===1?sources[0]:await chooseCollection('folders',sources);if(!source)return;const destination=await chooseCollection('folders');if(!destination||!matches(t))return;if(!await confirmDialog('移动文件？','从「'+source.name+'」移到「'+destination.name+'」，可能改变可见成员；其他关联和原件保留。'))return;if(!matches(t))return;await change('folders','move',{asset_ids:[item.id],target_id:destination.id,confirm_access_change:true},source.id);closePreview();await loadLibrary(false);toast('已移动到「'+destination.name+'」');}
 return {matches,loading,paging,query:()=>target?.kind==='albums'?{album_id:target.id}:{},confirm:confirmDialog,moveAsset,active,load,timeline,reset,back:backAction,backLabel,openFolder:async id=>{detail={id};target=null;selected.clear();selecting=false;mode.value='folders';document.getElementById(mobile?'library-search':'search').value='';if(!mobile)navigate('library');else await load();},chooseFolder:()=>chooseFolderTree()};
}
