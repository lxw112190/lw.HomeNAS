const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const select=require('../.github/scripts/select-release.cjs'),publish=require('../.github/scripts/publish-release.cjs');
const sha='a'.repeat(40),other='b'.repeat(40);let checks=0;
function fixture(change={}){
 const run={id:123,run_number:13,name:'Android APK',path:'.github/workflows/android.yml',head_branch:'main',head_repository:{full_name:'owner/repo'},event:'push',status:'completed',conclusion:'success',head_sha:sha,...change};
 const jobs=[{name:'apk',conclusion:'success'},{name:'emulator',conclusion:'success'}],artifacts=[{name:'lw.HomeNAS-Android-13',expired:false,workflow_run:{head_sha:sha}}],outputs={};
 const github={rest:{actions:{getWorkflowRun:async()=>({data:run}),listWorkflowRuns:async()=>({data:{workflow_runs:[run]}}),listJobsForWorkflowRun:()=>{},listWorkflowRunArtifacts:()=>{}},repos:{getContent:async()=>({data:{type:'file',encoding:'base64',content:Buffer.from('1.0.0\n').toString('base64')}}),getReleaseByTag:async()=>{throw {status:404};}}}};
 github.paginate=async fn=>fn===github.rest.actions.listJobsForWorkflowRun?jobs:artifacts;
 const context={repo:{owner:'owner',repo:'repo'},eventName:'workflow_run',payload:{workflow_run:{id:123}}};
 const core={getInput:()=>'',setOutput:(name,value)=>outputs[name]=value,notice:()=>{}};
 return {github,context,core,run,jobs,artifacts,outputs};
}
async function rejection(value,message){await assert.rejects(select(value),message);checks++;}
(async()=>{
 let f=fixture();await select(f);assert.equal(f.outputs.sha,sha);assert.equal(f.outputs.publish,'true');assert.equal(f.outputs.artifact_name,'lw.HomeNAS-Android-13');checks++;
 f=fixture();f.context.eventName='workflow_dispatch';f.core.getInput=n=>n==='publish'?'false':'';await select(f);assert.equal(f.outputs.publish,'false');checks++;
 await rejection(fixture({event:'pull_request'}),/trusted/);
 await rejection(fixture({head_branch:'feature'}),/trusted/);
 await rejection(fixture({head_repository:{full_name:'attacker/repo'}}),/trusted/);
 f=fixture();f.context.eventName='workflow_dispatch';f.core.getInput=n=>n==='android_run_id'?'999999999999999999999':'';await rejection(f,/safe integer/);
 f=fixture();f.jobs[1].conclusion='skipped';await rejection(f,/both pass/);
 f=fixture();f.artifacts[0].workflow_run.head_sha=other;await rejection(f,/selected commit/);
 f=fixture();f.artifacts[0].expired=true;await rejection(f,/unexpired/);
 f=fixture();f.github.rest.repos.getReleaseByTag=async()=>({data:{target_commitish:other,draft:false}});await select(f);assert.equal(f.outputs.publish,'false');assert.equal(f.outputs.already,'false');checks++;
 f=fixture();f.github.rest.repos.getReleaseByTag=async()=>({data:{target_commitish:sha,draft:false,html_url:'published'}});await select(f);assert.equal(f.outputs.publish,'false');checks++;
 f=fixture();f.artifacts[0].expired=true;f.github.rest.repos.getReleaseByTag=async()=>({data:{target_commitish:sha,draft:false,html_url:'published'}});await rejection(f,/unexpired/);
 f=fixture();f.github.rest.repos.getContent=async()=>({data:{type:'file',encoding:'base64',content:Buffer.from('../bad').toString('base64')}});await rejection(f,/semantic VERSION/);
 f=fixture();f.github.rest.repos.getReleaseByTag=async()=>({data:{target_commitish:other,draft:true}});await rejection(f,/different commit/);
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'homenas-release-'));
 try{
  const hash=data=>crypto.createHash('sha256').update(data).digest('hex');
  const manifest={commit:sha,androidRunNumber:13,serverVersion:'1.0.0',package:'lw.HomeNAS-ci13.zip',apk:'HomeNAS-1.0.0.apk',packageSha256:hash('zip'),apkSha256:hash('apk')};
  for(const [name,data] of [[manifest.package,'zip'],[manifest.apk,'apk']]){fs.writeFileSync(path.join(directory,name),data);fs.writeFileSync(path.join(directory,name+'.sha256'),`${hash(data)}  ${name}\n`);}
  fs.writeFileSync(path.join(directory,'release-manifest.json'),JSON.stringify(manifest));const notes=path.join(directory,'..',path.basename(directory)+'-notes.md');fs.writeFileSync(notes,'test release');
  function publishing(fail=false,existing){
   const calls=[];let publicRelease=false;
   const github={rest:{repos:{getReleaseByTag:async()=>{if(existing)return {data:existing};throw {status:404};},createRelease:async input=>{assert.equal(input.draft,true);assert.equal(input.prerelease,false);assert.equal(input.tag_name,'v1.0.0');calls.push('draft');return {data:{id:1,draft:true}};},listReleaseAssets:()=>{},deleteReleaseAsset:async()=>{throw Error('unexpected delete');},uploadReleaseAsset:async input=>{if(fail)throw Error('upload failed');calls.push(input.name);},updateRelease:async input=>{assert.equal(calls.length,existing?5:6);assert.equal(input.draft,false);assert.equal(input.prerelease,false);publicRelease=true;return {data:{html_url:'published'}};}},git:{getRef:async()=>{throw {status:404};}}},paginate:async()=>[]};
   const core={notice:()=>{},summary:{addHeading(){return this;},addLink(){return this;},async write(){}}};
   return {github,core,context:{repo:{owner:'owner',repo:'repo'}},directory,notes,sha,number:'13',calls,isPublic:()=>publicRelease};
  }
  let p=publishing();await publish(p);assert.equal(p.isPublic(),true);checks++;
  p=publishing(true);await assert.rejects(publish(p),/upload failed/);assert.equal(p.isPublic(),false);checks++;
  p=publishing(false,{id:1,target_commitish:sha,draft:false});await publish(p);assert.equal(p.calls.length,0);checks++;
  p=publishing(false,{id:1,target_commitish:other,draft:false});await assert.rejects(publish(p),/another commit/);checks++;
  p=publishing(false,{id:1,target_commitish:sha,draft:true});await publish(p);assert.equal(p.isPublic(),true);checks++;
  p=publishing(false,{id:1,target_commitish:sha,draft:true});p.github.paginate=async()=>[{name:'private.pem'}];await assert.rejects(publish(p),/unexpected assets/);assert.equal(p.isPublic(),false);checks++;
  fs.writeFileSync(path.join(directory,'private.pem'),'never publish');assert.throws(()=>publish.assets(directory,sha,13),/Unexpected/);fs.unlinkSync(path.join(directory,'private.pem'));checks++;
  fs.writeFileSync(path.join(directory,manifest.apk),'tampered');assert.throws(()=>publish.assets(directory,sha,13),/hash mismatch/);checks++;
  fs.unlinkSync(notes);
 }finally{const resolved=path.resolve(directory);assert(resolved.startsWith(path.resolve(os.tmpdir())+path.sep)&&path.basename(resolved).startsWith('homenas-release-'));fs.rmSync(resolved,{recursive:true,force:true});}
 console.log(`Release workflow: ${checks} checks passed; no GitHub requests were made.`);
})().catch(error=>{console.error(error);process.exitCode=1;});
