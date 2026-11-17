'use strict';
globalThis.HomeNasDuplicates=(()=>{
 let dialog=null,serial=0,revision,folder=null,group=null,keep=null,chosen=new Map(),currentScope={},groupPage=0,memberPage=0,busy=false;
 const el=(tag,text='',css='')=>{const n=document.createElement(tag);n.textContent=text;n.className=css;return n;};
 const valid=t=>t===serial&&revision===state.uploadRevision&&!!state.me&&dialog?.open;
 function reset(){++serial;dialog?.close();dialog?.remove();dialog=null;chosen.clear();keep=null;group=null;busy=false;}
 function button(parent,text,fn){const b=el('button',text,'secondary');b.type='button';b.onclick=async()=>{if(busy)return;try{await fn();}catch(e){if(dialog?.open)dialog.querySelector('[role=status]').textContent=e.message;}};parent.append(b);return b;}
 function params(before=''){return new URLSearchParams({...currentScope,before,...(group?{sha256:group.sha256,size:group.size}:{})});}
 function open(where){reset();folder=where;revision=state.uploadRevision;dialog=el('dialog','','duplicates-dialog');dialog.setAttribute('aria-labelledby','duplicates-title');
  const head=el('div','','section-head'),title=el('h2','重复文件');title.id='duplicates-title';head.append(title);button(head,'关闭',reset);dialog.append(head);
  dialog.append(el('p','按文件大小和 SHA256 查找内容完全相同的独立文件。压缩、裁剪、水印及元数据变化后的照片不属于精确重复。','fine'));
  const controls=el('div','','duplicates-controls'),scopeLabel=el('label','范围'),scope=el('select'),typeLabel=el('label','类型'),type=el('select');
  for(const [value,text] of [['','当前账号可见的全部资料'],...(where?[[where.id,'当前文件夹及子目录：'+(where.full_path||where.name)]]:[])]){const o=el('option',text);o.value=value;scope.append(o);}scope.value=where?.id||'';
  for(const [value,text] of [['image','照片'],['video','视频'],['file','其他文件'],['all','全部类型']]){const o=el('option',text);o.value=value;type.append(o);}scopeLabel.append(scope);typeLabel.append(type);controls.append(scopeLabel,typeLabel);dialog.append(controls);
  const content=el('div','','duplicates-content'),status=el('p','','fine');status.setAttribute('role','status');dialog.append(content,status);document.body.append(dialog);dialog.addEventListener('cancel',e=>{e.preventDefault();if(!busy)reset();});dialog.showModal();
  const change=()=>{currentScope={folder_id:scope.value,type:type.value};group=null;keep=null;chosen.clear();groupPage=memberPage=0;return load();};scope.onchange=type.onchange=()=>change().catch(e=>status.textContent=e.message);change().catch(e=>status.textContent=e.message);
 }
 function lock(value){busy=value;if(!dialog)return;for(const n of dialog.querySelectorAll('button,select,input')){if(value){if(n.dataset.wasDisabled===undefined)n.dataset.wasDisabled=String(n.disabled);n.disabled=true;}else if(n.dataset.wasDisabled!==undefined){n.disabled=n.dataset.wasDisabled==='true';delete n.dataset.wasDisabled;}}}
 async function load(before=''){
  const t=++serial,box=dialog.querySelector('.duplicates-content'),status=dialog.querySelector('[role=status]');lock(true);box.replaceChildren();status.textContent='正在查找…';
  try{const data=await api((group?'duplicates/members?':'duplicates?')+params(before),{timeout:90000});if(!valid(t))return;
   status.textContent='结果按当前可见范围实时查询；移入回收站后可以恢复，暂不释放磁盘空间。';
   if(!group){groupPage=data.page_index||0;if(!data.items.length)box.append(el('p','当前范围未发现内容完全相同的文件。'));
    for(const item of data.items){const card=el('article','','duplicate-group');card.append(el('strong',`${item.copies} 份相同文件 · 每份 ${bytes(item.size)}`),el('p',`SHA256：${item.sha256}`,'fine'));button(card,'查看并整理',()=>{group=item;keep=null;chosen.clear();memberPage=0;return load();});box.append(card);}
   }else{memberPage=data.page_index||0;button(box,'返回重复组',()=>{group=null;keep=null;chosen.clear();return load('@page:'+groupPage);});
    box.append(el('p','推荐保留最早入库的一份。不同目录可能面向不同成员；清理会同时隐藏该副本的所有目录和图集引用，关联不会自动转移。请逐项勾选，每次最多20份。','notice'));
    for(const item of data.items){if(!keep&&item.id===data.recommended_id)keep=item;const card=el('article','','duplicate-file');
     if(item.category==='image'&&item.preview==='READY'){const image=el('img');image.src='./api/v1/assets/'+item.id+'/thumbnail';image.alt=item.name;image.loading='lazy';card.append(image);}
     const body=el('div','','duplicate-file-body');body.append(el('strong',item.name),el('p',`${bytes(item.size)} · 入库 ${date(item.created)}${item.id===data.recommended_id?' · 推荐保留':''}`,'fine'));
     for(const f of item.references.folders)body.append(el('p',(f.full_path||f.name)+' · '+({private:'仅所有者',restricted:'指定成员',public:'共享只读',inherit:'继承上级权限',legacy:'兼容共享'}[f.policy]||'按目录授权'),'duplicate-path'));
     body.append(el('p',(item.references.has_album_references?'有图集引用；':'')+(item.references.folders.length>1?'关联多个目录；':'')+(item.references.truncated?'关联过多，本页不能清理；':'')+(!item.can_rename?'无整理权限':'可整理'),'fine'));
     const choices=el('div','','duplicates-controls'),retain=el('label','保留此份'),radio=el('input');radio.type='radio';radio.name='duplicate-keeper';radio.checked=keep?.id===item.id;
     radio.onchange=()=>{keep=item;chosen.delete(item.id);renderChoices();};retain.prepend(radio);choices.append(retain);
     const remove=el('label','移入回收站'),check=el('input');check.type='checkbox';check.dataset.assetId=item.id;check.dataset.eligible=String(item.can_rename&&!item.references.truncated);check.disabled=!item.can_rename||item.references.truncated||item.id===keep?.id;check.checked=chosen.has(item.id);
     check.onchange=()=>{if(check.checked&&chosen.size>=20){check.checked=false;status.textContent='每次最多清理20份；可以分批处理。';return;}if(check.checked)chosen.set(item.id,item);else chosen.delete(item.id);renderChoices();};remove.prepend(check);choices.append(remove);body.append(choices);card.append(body);box.append(card);
    }
    const summary=el('p','','duplicate-selection');box.append(summary);button(box,'确认清理所选副本',clean).classList.add('action-danger');renderChoices();
   }
   const pager=el('div','','pager'),page=group?memberPage:groupPage;const first=button(pager,'首页',()=>load());first.disabled=!page;const prev=button(pager,'上一页',()=>load('@page:'+(page-1)));prev.disabled=!page;pager.append(el('span','第 '+(page+1)+' 页'));const next=button(pager,'下一页',()=>load(data.next));next.disabled=!data.next;box.append(pager);
  }finally{if(valid(t))lock(false);}
 }
 function renderChoices(){if(!dialog)return;for(const check of dialog.querySelectorAll('[data-asset-id]'))check.checked=chosen.has(check.dataset.assetId);
  // A keeper can be switched repeatedly without leaving the formerly retained copy disabled.
  for(const row of dialog.querySelectorAll('.duplicate-file')){const check=row.querySelector('[data-asset-id]');if(!check)continue;const id=check.dataset.assetId;check.disabled=check.dataset.eligible!=='true'||id===keep?.id;row.querySelector('input[type=radio]').checked=id===keep?.id;}
  const summary=dialog.querySelector('.duplicate-selection');if(summary)summary.textContent=`保留：${keep?.name||'请先选择一份'}；已选清理 ${chosen.size} 份（含其他页）。`;
 }
 async function clean(){
  if(!keep||!chosen.size)throw new Error('请选择保留文件，并勾选要清理的副本');
  const t=serial,keeper=keep,items=[...chosen.values()],payload={...currentScope,keep_id:keeper.id,keep_stamp:keeper.references.stamp,remove:items.map(x=>({id:x.id,stamp:x.references.stamp})),confirm_references:true};
  if(!await collectionView.confirm('移入回收站 '+items.length+' 份重复文件？',`保留「${keeper.name}」。所选副本在所有目录和图集中暂时隐藏，关联不会自动转移。可从回收站恢复；本次不会释放磁盘空间。`))return;
  if(!valid(t))return;lock(true);const status=dialog.querySelector('[role=status]');status.textContent='正在重新校验原件并清理，请稍候…';
  try{const result=await post('duplicates/trash',payload,{timeout:90000});if(!valid(t))return;chosen.clear();keep=null;toast('已移入回收站 '+result.count+' 份，可在回收站恢复');await refreshOverview();if(!valid(t))return;await load();}
  finally{if(valid(t))lock(false);}
 }
 return {open,reset};
})();
