const fs=require('node:fs'),assert=require('node:assert/strict'),{JSDOM}=require('./dom/node_modules/jsdom');
const root='android/app/src/main/assets/www/',dom=new JSDOM(fs.readFileSync(root+'views/backup.html','utf8'),{runScripts:'outside-only'}),w=dom.window,d=w.document;
w.$=id=>d.getElementById(id);const source=fs.readFileSync(root+'app.js','utf8');w.eval(source.slice(source.indexOf('function backupTime('),source.indexOf('\nfunction render(s)'))+';window.summary=renderBackupSummary;');
const text=id=>d.getElementById(id).textContent;
try{
 const base={user:'test',canUpload:true,paused:false,auto:false,transfer:{running:false},backupStatus:{}};
 w.summary(base,0,0);assert.match(text('backup-summary-title'),/选择照片/);assert.match(d.body.textContent,/仅统计当前账号已加入队列/);assert.equal(text('backup-last-finished'),'尚无记录');
 w.summary({...base,backupStatus:{result:'COMPLETED',completed:4,last_finished:'1794499200000'}},0,0);assert.match(text('backup-summary-title'),/本轮队列/);assert.match(text('backup-summary-detail'),/4 项/);assert.notEqual(text('backup-last-finished'),'尚无记录');
 for(const [wait,title] of [['NETWORK','等待网络'],['UNMETERED','等待非计费'],['CHARGING','等待充电'],['BATTERY','等待电量'],['NO_ALBUMS','请选择自动']]){w.summary({...base,auto:true,backupStatus:{wait_reason:wait}},3,0);assert(text('backup-summary-title').includes(title),wait);}
 w.summary({...base,paused:true},8,0);assert.match(text('backup-summary-title'),/暂停/);assert.match(text('backup-summary-detail'),/8 项/);
 w.summary({...base,backupStatus:{result:'INTERRUPTED'}},2,0);assert.match(text('backup-summary-title'),/中断/);
 w.summary(base,0,2);assert.match(text('backup-summary-title'),/2 项需要处理/);assert.equal(d.getElementById('backup-show-failed').hidden,false);
 w.summary({...base,transfer:{running:true,stage:'SCANNING',items:[],completed:0}},0,0);assert.match(text('backup-summary-title'),/扫描/);
 for(const [phase,title] of [['HASHING','摘要'],['UPLOADING','上传'],['VERIFYING','校验']]){w.summary({...base,transfer:{running:true,items:[{phase}]}},1,0);assert(text('backup-summary-title').includes(title));}
 w.summary({...base,canUpload:false},0,0);assert.match(text('backup-summary-title'),/只读/);assert.equal(d.getElementById('backup-auto-settings').hidden,true);
 w.summary({user:null},0,0);assert(d.getElementById('backup-summary').hidden);
 console.log('Backup summary DOM: counts scope, history, empty/fallback, scan/hash/upload/verify, policy waits, pause/interruption, failure entry and read-only/logout passed.');
}finally{dom.window.close();}
