'use strict';
// Production upload functions with a DOM adapter; real HTTP/browser verified separately.
const fs=require('node:fs'),assert=require('node:assert/strict');
const {JSDOM}=require('./dom/node_modules/jsdom');
const folderA={id:'a'.repeat(48),name:'旅行'},folderB={id:'b'.repeat(48),name:'工作'};
function fixture(){
 const dom=new JSDOM('<strong id="upload-folder-name"></strong><button id="choose-upload-folder"></button><button id="reset-upload-folder"></button><button id="choose-files"></button>',{runScripts:'outside-only',url:'http://localhost/'}),w=dom.window;
 const h={dom,w,calls:[],toasts:[],errors:[],remote:null};
 w.$=id=>w.document.getElementById(id);w.state={me:{user_id:'owner',can_upload:true},uploadRevision:1,uploadFolder:{id:'default',name:'默认文件夹'},files:[],paused:false,active:0};
 w.TextEncoder=TextEncoder;w.Worker=class{terminate(){}};w.bytes=String;w.renderLocal=()=>{};w.navigate=()=>{};w.sleep=async()=>{};
 w.toast=s=>h.toasts.push(s);w.error=s=>h.errors.push(s);w.scheduleTransferRefresh=()=>{};
 w.hashBlob=async()=> 'c'.repeat(64);w.collectionView={chooseFolder:async()=>folderA};
 w.post=async(path,data)=>{h.calls.push({path,data});return h.remote||{id:'task-id',state:'READY',offset:data.size,folder_id:data.folder_id,effective_folder_id:data.folder_id,folder_name:data.folder_id==='default'?'默认文件夹':data.folder_id===folderA.id?folderA.name:folderB.name,folder_defaulted:false};};
 w.api=async()=>{throw Error('Unexpected request');};
 w.eval(fs.readFileSync('web/sha256.js','utf8'));
 const source=fs.readFileSync('web/app.js','utf8');w.eval(source.slice(source.indexOf('function renderUploadDestination('),source.indexOf('const roleNames=')));
 w.kick=()=>{};return h;
}
async function run(){
 const h=fixture(),{w}=h,file=new w.File(['fixture'], '同一张照片.jpg',{lastModified:1700000000000});
 try{
  w.resetUploadDestination();assert.equal(w.$('upload-folder-name').textContent,'默认文件夹');assert(w.$('reset-upload-folder').hidden);
  await w.addFiles([file]);await w.chooseUploadDestination();assert.equal(w.state.uploadFolder.id,folderA.id);assert.equal(w.$('upload-folder-name').textContent,'旅行');assert(!w.$('reset-upload-folder').hidden);
  await w.addFiles([file]);await w.addFiles([file]);assert.equal(w.state.files.length,2,'Same file deduplicates within target, not across targets');
  w.state.uploadFolder={...folderB};await w.addFiles([file]);assert.equal(w.state.files.length,3);
  w.resetUploadDestination();for(const task of w.state.files)await w.uploadFile(task);
  assert.deepEqual(h.calls.map(c=>c.data.folder_id),['default',folderA.id,folderB.id]);assert.equal(new Set(h.calls.map(c=>c.data.client_task_id)).size,3);
  assert.equal(new Set(h.calls.map(c=>c.data.source_key)).size,1,'Source identity stays stable across destinations');
  const original=w.state.files[1],id=original.taskId;await w.uploadFile(original);assert.equal(h.calls.at(-1).data.client_task_id,id);assert.equal(h.calls.at(-1).data.folder_id,folderA.id,'Retry must keep frozen target');
  h.remote={id:'deleted-target',state:'READY',offset:'7',folder_id:folderA.id,effective_folder_id:'default',folder_name:'默认文件夹',folder_defaulted:true};
  await w.uploadFile(original);assert.equal(original.state,'READY');assert.equal(original.folderId,folderA.id);assert.equal(original.effectiveFolderName,'默认文件夹');assert.match(original.folderWarning,/已转存默认/);
  h.remote={...h.remote,folder_defaulted:false};await w.uploadFile(original);assert.equal(original.state,'ERROR');assert.match(original.message,/不一致/);
  h.remote={id:'old-server',state:'READY',offset:'7'};await w.uploadFile(original);assert.equal(original.state,'ERROR');assert.match(original.message,/未确认/);
  h.remote=null;let resolveHash;original.digest=null;w.hashBlob=()=>new Promise(r=>resolveHash=r);const before=h.calls.length,pending=w.uploadFile(original);
  w.state.me={user_id:'different-user',can_upload:true};w.state.uploadRevision++;resolveHash('d'.repeat(64));await pending;
  assert.equal(original.state,'PAUSED');assert.equal(h.calls.length,before,'Old hash result cannot create upload for another identity');
  let resolveFolder;w.collectionView.chooseFolder=()=>new Promise(r=>resolveFolder=r);const choice=w.chooseUploadDestination();w.state.uploadRevision++;w.resetUploadDestination();resolveFolder(folderA);await choice;assert.equal(w.state.uploadFolder.id,'default','Late picker result cannot leak to next login');
 }finally{h.dom.window.close();}
 const batch=fixture();try{
  batch.w.state.uploadFolder={...folderA};batch.w.sleep=async()=>{batch.w.state.uploadFolder={...folderB};};
  await batch.w.addFiles(Array.from({length:205},(_,i)=>new batch.w.File(['x'],'file-'+i+'.txt',{lastModified:1})));
  assert.equal(batch.w.state.files.length,205);assert(batch.w.state.files.every(t=>t.folderId===folderA.id),'Large batch must freeze target before yielding');
 }finally{batch.dom.window.close();}
 console.log('Web upload folders: default/picker, frozen batch, per-folder dedup and task identity, retry, fallback notice, response validation, account-change guards passed.');
}
run().catch(e=>{console.error(e);process.exitCode=1;});
