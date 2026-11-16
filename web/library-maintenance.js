'use strict';
globalThis.HomeNasMaintenance=(()=>{
 const page=$('page-settings'),panel=node('section','panel admin-only hidden maintenance-panel');panel.id='library-maintenance';
 const title=node('h2','','资料库健康与维护'),note=node('p','fine','健康检查只报告问题，不修改原件。快照仅包含数据库与分类，不含照片、视频原件；同盘快照不能替代独立备份。'),status=node('p'),actions=node('div','collection-actions'),result=node('div'),schedule=node('form'),history=node('div');
 status.id='health-status';status.setAttribute('role','status');panel.append(title,note,status,actions,result,node('h3','','定时数据库快照'),schedule,node('h3','','操作历史'),history);page.append(panel);
 let serial=0,historyNext='',reportNext='',loading=false,lastPhase='IDLE',reportStamp='';
 function controls(){const active=['RUNNING','PAUSED','INTERRUPTED'].includes(lastPhase);start.disabled=active;pause.disabled=lastPhase!=='RUNNING';resume.disabled=!['PAUSED','INTERRUPTED'].includes(lastPhase);cancel.disabled=!active;rate.disabled=active;}
 const button=(box,label,fn)=>{const b=node('button','secondary',label);b.type='button';b.onclick=async()=>{const rev=state.uploadRevision;b.disabled=true;try{await fn();}catch(e){if(rev===state.uploadRevision)error(e.message);}finally{b.disabled=false;controls();}};box.append(b);return b;};
 const field=(parent,text,input)=>{const label=node('label','',text);label.append(input);parent.append(label);return input;};
 const rate=field(actions,'检查限速 MiB/s',node('input'));rate.type='number';rate.min='1';rate.max='256';rate.value='32';rate.id='health-rate';
 const start=button(actions,'开始检查',()=>command('start',{rate_mib:Number(rate.value)})),pause=button(actions,'暂停检查',()=>command('pause')),resume=button(actions,'继续检查',()=>command('resume')),cancel=button(actions,'取消检查',()=>command('cancel'));
 button(actions,'刷新维护状态',()=>refresh());
 const phase={IDLE:'尚未检查',RUNNING:'检查中',PAUSED:'已暂停',INTERRUPTED:'检查中断，可继续',CANCELLED:'已取消',COMPLETED:'本轮检查完成'};
 const enabled=field(schedule,'启用定时快照',node('input'));enabled.type='checkbox';enabled.id='snapshot-enabled';
 const hours=field(schedule,'间隔（小时）',node('input'));hours.type='number';hours.min='1';hours.max='720';hours.id='snapshot-hours';
 const keep=field(schedule,'保留最近自动快照（份）',node('input'));keep.type='number';keep.min='1';keep.max='100';keep.id='snapshot-keep';
 const scheduleStatus=node('p','fine');schedule.append(scheduleStatus);let scheduleDirty=false;for(const input of [enabled,hours,keep])input.oninput=()=>scheduleDirty=true;
 button(schedule,'保存快照计划',async()=>{if(!await collectionView.confirm('保存快照计划？','仅清理超出保留数量的自动数据库快照；手动快照和所有原件保留。'))return;await post('admin/backup-schedule',{enabled:enabled.checked,interval_hours:Number(hours.value),keep:Number(keep.value)});scheduleDirty=false;await refresh();});
 schedule.onsubmit=e=>e.preventDefault();
 const historyActions=node('div','collection-actions');history.before(historyActions);button(historyActions,'刷新操作历史',()=>loadHistory(true));const moreHistory=button(historyActions,'更多历史',()=>loadHistory(false));
 async function command(action,value={}){await post('admin/health/'+action,value);await refresh();}
 async function loadHistory(reset){const revision=state.uploadRevision;if(!state.me?.admin)return;const before=reset?'':historyNext;if(!reset&&!before)return;const data=await api('admin/history?'+new URLSearchParams({before}));if(revision!==state.uploadRevision)return;if(reset)history.replaceChildren();historyNext=data.next;moreHistory.hidden=!historyNext;
  const names={'asset.trash':'移入回收站','asset.restore':'恢复文件','asset.rename':'文件改名','asset.purge_requested':'永久删除已发起','folder.relocate':'移动目录','folder.permissions':'调整目录权限','folder.create':'创建目录','folder.edit':'编辑目录','folder.delete':'删除空目录','folder.move':'移动文件','folder.add':'加入文件夹','folder.remove':'移到默认文件夹','album.create':'创建图集','album.edit':'编辑图集','album.delete':'删除图集','album.add':'加入图集','album.remove':'移出图集','album.cover':'更改图集封面','backup.created':'创建数据库快照','backup.schedule':'修改快照计划','health.start':'开始健康检查','health.completed':'健康检查完成'};
  for(const item of data.items)history.append(node('p','maintenance-record',`${detailedDate(item.created)} · ${item.actor_name} · ${names[item.action]||item.action}${item.detail?' · '+item.detail:''}${item.object_id?' · 编号 '+item.object_id:''}`));if(!history.children.length)history.append(node('p','fine','暂无操作历史。记录保留最近10000条，不记录密码、令牌或文件内容。'));
 }
 const issues={ORIGINAL_MISSING:'原件缺失',SIZE_MISMATCH:'原件大小不一致',HASH_MISMATCH:'原件摘要不一致',READ_FAILED:'原件读取失败',UNSAFE_PATH:'原件路径异常',FOLDER_LINK_MISSING:'缺少有效目录关联',THUMBNAIL_MISSING:'缩略图缺失'};
 async function loadResults(before='',generation=serial){const revision=state.uploadRevision,data=await api('admin/health/results?'+new URLSearchParams({before}));if(generation!==serial||revision!==state.uploadRevision)return;if(!before)result.replaceChildren();result.querySelector('.more-health')?.remove();for(const item of data.items)result.append(node('p','maintenance-record',`${item.name} · ${issues[item.issue]||item.issue} · ${item.asset_id}`));reportNext=data.next;if(reportNext)button(result,'更多异常',()=>loadResults(reportNext)).classList.add('more-health');
  if(!before&&data.items.length)button(result,'导出本页异常',()=>{const blob=new Blob([JSON.stringify(data.items,null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=node('a');a.href=url;a.download='HomeNAS-health-issues.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
 }
 async function refresh(){if(!state.me?.admin){reset();return;}loading=true;const generation=++serial,revision=state.uploadRevision;try{const data=await api('admin/library-maintenance');if(generation!==serial||revision!==state.uploadRevision)return;panel.classList.remove('hidden');const h=data.health,c=data.counts,s=data.schedule,active=['RUNNING','PAUSED','INTERRUPTED'].includes(h.state);
  status.textContent=`${phase[h.state]||h.state}：${Number(c.total)-Number(c.pending)} / ${c.total} 项，正常 ${c.good}，异常 ${c.issues}，检查期间移除 ${c.skipped}。${h.database==='needs_attention'?'数据库一致性检查发现异常，请备份后处理。':''}${h.finished&&h.finished!=='0'?' 完成于 '+detailedDate(h.finished):''}`;
  lastPhase=h.state;controls();
  if(!scheduleDirty){enabled.checked=s.enabled;hours.value=s.interval_hours;keep.value=s.keep||7;}scheduleStatus.textContent=`${s.enabled?'计划已启用，下次 '+detailedDate(s.next_run):'计划未启用'}；最近自动快照：${detailedDate(s.last_success)}。${s.last_error?'快照生成或保留清理失败，请检查空间和权限。':''}手动快照不自动清理。`;
  const stamp=JSON.stringify([h.started,h.state,c.issues]);const work=[];if(stamp!==reportStamp){reportStamp=stamp;work.push(loadResults('',generation));}if(!history.children.length)work.push(loadHistory(true));await Promise.all(work);
 }finally{if(generation===serial)loading=false;}}
 function reset(){++serial;panel.classList.add('hidden');result.replaceChildren();history.replaceChildren();status.textContent='';scheduleStatus.textContent='';scheduleDirty=false;historyNext=reportNext=reportStamp='';}
 setInterval(()=>{if(state.page==='settings'&&state.me?.admin&&!document.hidden)refresh().catch(()=>{});},5000);
 return {refresh,reset};
})();
