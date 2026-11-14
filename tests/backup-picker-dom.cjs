// Run the actual APK page/controller against a simulated native bridge.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {JSDOM}=require(path.resolve(process.argv[2]||'tests/dom/node_modules/jsdom'));
const root=path.resolve('android/app/src/main/assets/www');
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
let liveDom;
async function main(){
 let html=fs.readFileSync(path.join(root,'index.html'),'utf8');
 html=html.replace(/<div data-view="([a-z]+)"><\/div>/g,(_,name)=>fs.readFileSync(path.join(root,'views',name+'.html'),'utf8'));
 const dom=liveDom=new JSDOM(html,{runScripts:'outside-only',url:'https://appassets.androidplatform.net/assets/www/index.html'}),w=dom.window,d=w.document,calls=[],errors=[];
 w.scrollTo=()=>{};w.HTMLElement.prototype.scrollIntoView=()=>{};w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};w.PhotoViewer=class {destroy(){}};
 w.addEventListener('error',event=>errors.push(event.message));
 let job={state:'IDLE'},parallel=2,folderId='default',folderName='默认文件夹';const travel='a'.repeat(48);
 const state=()=>({user:'fixture',scope:'fixture:user',canUpload:true,role:'admin',nativeApi:12,version:'1.1.1',backupFolderId:folderId,backupFolderName:folderName,mediaRevision:1,paused:true,mediaAccess:{full:true,any:true},queue:{counts:{},items:[],next:''},transfer:{running:false},enqueue:job,uploadParallel:parallel});
 w.HomeNasNative={postMessage(raw){const request=JSON.parse(raw);calls.push(request);let data={};switch(request.action){
 case 'snapshot':data=state();break;
 case 'mediaDays':data={items:[{day:'2026-11-06',count:205},{day:'2026-11-05',count:1}],next:'2026-11-06',mediaAccess:{full:true,any:true}};break;
 case 'mediaMonths':data={items:[{month:'2026-11',count:5205,bytes:'26025000000'},{month:'2026-10',count:1,bytes:'5000000'}]};break;
 case 'folderList':data={items:[{id:'default',name:'默认文件夹',can_write:true},{id:travel,name:'旅行',can_write:true}],next:''};break;
 case 'backupFolder':folderId=request.data.id;folderName=folderId===travel?'旅行':'默认文件夹';data=state();break;
 case 'mediaItems':data={items:[{id:'one',name:'one.jpg',kind:'image',status:'NEW',size:'5000000'},{id:'two',name:'two.jpg',kind:'image',status:'READY',size:'5000000'},{id:'three',name:'three.jpg',kind:'image',status:'QUEUED',size:'5000000'},{id:'elsewhere',name:'other-folder.jpg',kind:'image',status:'OTHER_FOLDER',size:'5000000'}],next:'more'};break;
 case 'mediaEnqueueStart':job={state:'RUNNING',scanned:100,added:80,skipped:20,folder_id:request.data.folder_id,folder_name:folderName};data=job;break;
 case 'mediaEnqueueCancel':job={state:'STOPPED',scanned:100,added:80,skipped:20,error:'已停止'};data=job;break;
 case 'uploadConcurrency':parallel=request.data.count;data=state();break;
 }queueMicrotask(()=>w.HomeNasNative.onmessage?.({data:JSON.stringify({id:request.id,ok:true,data})}));}};
 w.eval(['picker-selection.js','library-collections.js','app.js'].map(file=>fs.readFileSync(path.join(root,file),'utf8')).join('\n')+';window.renderTransfer=renderTransfer;window.testPicker={choice:pickerChoice,selection:pickerSelection,enqueue:renderEnqueue,page};');await tick();await tick();
 const $=id=>d.getElementById(id);
 await $('choose-media').onclick();assert.equal($('page-picker').hidden,false);assert.equal($('picker-months').querySelectorAll('input').length,2);
 $('picker-all').click();assert.match($('picker-selected').textContent,/5206/);assert(!$('picker-confirm').disabled);
 const november=$('picker-months').querySelector('input');november.checked=false;november.onchange();
 // Restore one day inside the excluded month; do not load the entire month into the DOM.
 const day=$('picker-days').querySelector('input');day.checked=true;day.onchange();
 await $('picker-days').querySelector('button').onclick();await tick();
 const photos=$('picker-days').querySelectorAll('.picker-cell input');assert.equal(photos.length,4);assert(photos[1].disabled);assert(photos[2].disabled);assert(!photos[3].disabled,'Another folder queue cannot disable the current destination selection');assert.match($('picker-days').textContent,/其他文件夹有任务，可在此备份/);
 photos[0].checked=false;photos[0].onchange();
 await $('picker-confirm').onclick();const selection=calls.find(c=>c.action==='mediaEnqueueStart').data;
 assert.equal(selection.all,true);assert(selection.exclude_months.includes('2026-11'));assert(selection.days.includes('2026-11-06'));assert.deepEqual(selection.exclude_ids,['one']);assert.equal(selection.folder_id,'default');assert.equal(selection.only_pending,true);
 assert($('picker-confirm').disabled);assert.equal($('picker-enqueue').hidden,false);assert.match($('picker-enqueue-info').textContent,/已加入 80 项/);
 await $('picker-enqueue-cancel').onclick();assert.equal($('picker-enqueue').hidden,true);assert.match($('error').textContent,/已加入 80 项/);assert(!$('picker-confirm').disabled,'Stopped scan retains selection rules for retry');
 // Use the actual destination dialog, update the native snapshot, then make a NEW selection.
 d.querySelector('nav button[data-page="backup"]').click();const changing=$('backup-folder').onclick();await tick();await [...d.querySelector('dialog.collection-dialog').querySelectorAll('button')].find(b=>b.textContent==='旅行').onclick();await changing;
 assert.match($('backup-folder').textContent,/旅行/);await $('choose-media').onclick();$('picker-all').click();await $('picker-confirm').onclick();const newSelection=calls.filter(c=>c.action==='mediaEnqueueStart').at(-1).data;assert.equal(newSelection.folder_id,travel);assert.equal(newSelection.only_pending,true);assert.match($('picker-target').textContent,/旅行/);assert.match($('picker-enqueue-info').textContent,/保存到「旅行」/);await $('picker-enqueue-cancel').onclick();
 $('upload-parallel').value='3';await $('upload-parallel').onchange();assert(calls.some(c=>c.action==='uploadConcurrency'&&c.data.count===3));
 // Actual transfer renderer preserves per-file phases and presents aggregate speed.
 const multi=state();multi.paused=false;multi.transfer={running:true,completed:10,remaining_items:20,remaining_bytes:'100000000',bytes_per_second:10000000,items:[{name:'one.jpg',phase:'UPLOADING',processed:'2500000',size:'5000000',folder_name:'旅行'},{name:'two.jpg',phase:'HASHING',processed:'1000000',size:'5000000',folder_name:'默认文件夹'}]};w.renderTransfer(multi);
 assert.equal($('transfer-files').children.length,2);assert.match($('transfer-phase').textContent,/本轮完成 10 项/);assert.match($('transfer-speed').textContent,/\/s/);assert.match($('transfer-files').textContent,/计算摘要/);
 assert.match($('transfer-files').textContent,/保存到：旅行/);assert.match($('transfer-files').textContent,/保存到：默认文件夹/);
 // Large explicit selection: single changes and unchanged polls must not walk thumbnail grids.
 const options=$('library-options');assert(options&&!options.open);assert(options.contains(d.querySelector('.library-toolbar')));assert(options.contains(d.querySelector('.collection-actions')));assert(!options.contains(d.querySelector('.collection-tabs')));
 w.testPicker.page('library');options.open=true;w.homeNasBack();assert(!options.open);assert.equal($('page-library').hidden,false);
 w.testPicker.choice.clear();for(let i=0;i<5000;i++)w.testPicker.choice.item('2026-11-06','bulk-'+i,true);
 w.testPicker.selection();assert.match($('picker-selected').textContent,/5000/);
 let gridWalks=0;const grids=[...d.querySelectorAll('.picker-day-grid')];for(const grid of grids){const original=grid.querySelectorAll.bind(grid);grid.querySelectorAll=(selector)=>{gridWalks++;return original(selector);};}
 w.testPicker.choice.item('2026-11-06','bulk-2500',false);w.testPicker.selection('2026-11-06');assert.match($('picker-selected').textContent,/4999/);assert.equal(gridWalks,0,'Single selection must not rescan thumbnails');
 w.testPicker.enqueue({state:'IDLE'});gridWalks=0;for(let i=0;i<100;i++)w.testPicker.enqueue({state:'IDLE'});assert.equal(gridWalks,0,'Unchanged polls must not rescan thumbnails');
 await $('picker-days').querySelector('button').onclick();await tick();assert(d.querySelectorAll('.picker-cell').length>0);await $('picker-days').querySelector('button').onclick();assert.equal(d.querySelectorAll('.picker-cell').length,0);assert.equal(w.testPicker.choice.items.size,4999,'Collapsing thumbnails preserves selection');
 await $('picker-days').querySelector('button').onclick();await tick();await $('picker-days').querySelectorAll('.picker-day-head button')[1].onclick();await tick();assert.equal(d.querySelectorAll('.picker-day-grid')[0].children.length,0,'Opening another day releases the old thumbnails');assert.equal(w.testPicker.choice.items.size,4999);
 assert.equal(errors.length,0,errors.join('\n'));dom.window.close();console.log('APK DOM: month/all selection, exclusions, backed-up/queued states, async enqueue/cancel, concurrency and aggregate/per-file progress passed.');
}
main().catch(error=>{console.error(error);process.exitCode=1;}).finally(()=>liveDom?.window.close());
