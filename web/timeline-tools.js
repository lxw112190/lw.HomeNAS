'use strict';
globalThis.HomeNasTimeline=(()=>{
 let root,range={},generation=0,calendarKey='',months=[],shown=false;
 const el=(tag,text='')=>{const n=document.createElement(tag);n.textContent=text;return n;};
 let year,month,from,to,note;
 const mobile=()=>typeof nativeCall==='function';
 const criteria=()=>({type:mobile()?document.getElementById('library-type').value:state.type,search:document.getElementById(mobile()?'library-search':'search').value});
 const session=()=>mobile()?current?.scope:state.me?.user_id;
 function option(select,value,label){const n=el('option',label);n.value=value;select.append(n);}
 function date(value){if(!/^\d{4}-\d{2}-\d{2}$/.test(value))throw Error('请选择有效日期');const d=new Date(value+'T00:00:00');if(!Number.isFinite(d.getTime())||d.getFullYear()!==Number(value.slice(0,4))||d.getMonth()+1!==Number(value.slice(5,7))||d.getDate()!==Number(value.slice(8)))throw Error('日期无效');return d;}
 function reload(){return loadLibrary(false).catch(e=>{note.textContent=e.message;});}
 function apply(){try{const a=date(from.value||'1970-01-01'),b=date(to.value||'2100-12-31');b.setDate(b.getDate()+1);if(a>=b)throw Error('开始日期不能晚于结束日期');const start=from.value?Math.max(0,a.getTime()):0;if(start>=b.getTime())throw Error('日期范围早于可用时间');range={from:String(start),to:String(b.getTime())};note.textContent='已按日期范围筛选；拍摄时间优先，缺失时按文件时间。';reload();}catch(e){note.textContent=e.message;}}
 function setMonth(){if(!year.value)return;const y=Number(year.value),m=month.value?Number(month.value):0;from.value=`${y}-${String(m||1).padStart(2,'0')}-01`;const end=new Date(y,m||12,0);to.value=`${end.getFullYear()}-${String(end.getMonth()+1).padStart(2,'0')}-${String(end.getDate()).padStart(2,'0')}`;apply();}
 function monthOptions(){const previous=month.value;month.replaceChildren();option(month,'','全年');for(const row of months.filter(x=>x.month?.startsWith(year.value+'-')))option(month,row.month.slice(5),Number(row.month.slice(5))+'月 · '+row.count+'项');month.value=previous;if(!month.value)month.value='';}
 async function calendar(){
  if(!shown||!root||!session())return;const c=criteria(),key=JSON.stringify([session(),c]);if(key===calendarKey)return;calendarKey=key;const job=++generation;
  try{const data=mobile()?await nativeCall('timelineCalendar',{...c,offset:-new Date().getTimezoneOffset()}):await api('assets/calendar?'+new URLSearchParams({type:c.type,q:c.search,offset:String(-new Date().getTimezoneOffset())}));if(job!==generation||key!==calendarKey)return;
   months=data.items||[];const before=year.value;year.replaceChildren();option(year,'','定位年份');for(const value of [...new Set(months.map(x=>x.month?.slice(0,4)).filter(Boolean))])option(year,value,value+'年');year.value=before;monthOptions();note.textContent=Number(data.remaining)>0?`正在后台补齐 ${data.remaining} 张旧照片的拍摄时间，可先浏览，稍后刷新。`:'拍摄时间优先；无有效EXIF时使用文件时间。';
  }catch(e){if(job===generation){calendarKey='';note.textContent='年份索引暂不可用：'+e.message;}}
 }
 function init(){
  if(root)return;const area=document.getElementById('library');if(!area)return;root=el('div');root.className='timeline-tools';root.setAttribute('aria-label','照片日期筛选');
  const field=(title,control)=>{const label=el('label',title);label.append(control);root.append(label);};year=el('select');year.id='timeline-year';month=el('select');month.id='timeline-month';from=el('input');to=el('input');from.type=to.type='date';from.id='timeline-from';to.id='timeline-to';field('年份',year);field('月份',month);field('从',from);field('至（含当天）',to);
  const applyButton=el('button','筛选'),clear=el('button','全部日期');applyButton.type=clear.type='button';applyButton.className='action-primary';applyButton.onclick=apply;clear.onclick=()=>{range={};from.value=to.value=year.value=month.value='';note.textContent='已显示全部日期；拍摄时间优先，缺失时按文件时间。';reload();};root.append(applyButton,clear);note=el('p');note.className='timeline-index-note';note.setAttribute('role','status');root.append(note);year.onchange=()=>{month.value='';monthOptions();setMonth();};month.onchange=setMonth;area.before(root);visibility(document.getElementById('library-order').value==='timeline');
 }
 function visibility(value){shown=value;if(root){root.hidden=!value;if(value)calendar();}}
 return {init,query:()=>({...range}),visibility,refresh(){calendarKey='';calendar();},reset(){range={};++generation;calendarKey='';months=[];if(root){from.value=to.value='';year.replaceChildren();month.replaceChildren();}}};
})();
