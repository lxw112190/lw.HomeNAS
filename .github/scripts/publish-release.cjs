const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
function assets(directory, sha, number){
  const manifest=JSON.parse(fs.readFileSync(path.join(directory,'release-manifest.json'),'utf8'));
  if(manifest.commit!==sha || manifest.androidRunNumber!==Number(number))throw new Error('Release manifest belongs to a different CI run or commit.');
  const wanted=[manifest.package,manifest.package+'.sha256',manifest.apk,manifest.apk+'.sha256','release-manifest.json'];
  if(![manifest.package,manifest.apk].every(name=>/^lw\.HomeNAS-[A-Za-z0-9.-]+\.(zip|apk)$/.test(name)))throw new Error('Invalid release file name.');
  const actual=fs.readdirSync(directory).sort();
  if(JSON.stringify(actual)!==JSON.stringify([...wanted].sort()))throw new Error('Unexpected or missing release assets.');
  for(const [name,expected] of [[manifest.package,manifest.packageSha256],[manifest.apk,manifest.apkSha256]]){
    if(!/^[a-f0-9]{64}$/.test(expected)||crypto.createHash('sha256').update(fs.readFileSync(path.join(directory,name))).digest('hex')!==expected)throw new Error('Release artifact hash mismatch.');
    if(fs.readFileSync(path.join(directory,name+'.sha256'),'utf8').trim()!==`${expected}  ${name}`)throw new Error('Release checksum file differs.');
  }
  return {manifest,wanted};
}
async function publish({github,context,core,directory,notes,sha,number}){
  const {manifest,wanted}=assets(directory,sha,number),{owner,repo}=context.repo,tag=`ci-${number}`;
  let release;
  try{release=(await github.rest.repos.getReleaseByTag({owner,repo,tag})).data;}catch(error){if(error.status!==404)throw error;}
  if(release){
    if(release.target_commitish!==sha)throw new Error('Release tag belongs to another commit.');
    if(!release.draft){core.notice(`Already published: ${release.html_url}`);return;}
  }else{
    // A pre-existing unrelated tag must not silently redirect a new release.
    try{const ref=(await github.rest.git.getRef({owner,repo,ref:`tags/${tag}`})).data;if(ref.object.type!=='commit'||ref.object.sha!==sha)throw new Error('Existing tag does not match verified commit.');}catch(error){if(error.status!==404)throw error;}
    release=(await github.rest.repos.createRelease({owner,repo,tag_name:tag,target_commitish:sha,name:`lw.HomeNAS ${manifest.serverVersion} / Android CI ${number}`,body:fs.readFileSync(notes,'utf8'),draft:true,prerelease:true})).data;
  }
  const existing=await github.paginate(github.rest.repos.listReleaseAssets,{owner,repo,release_id:release.id,per_page:100});
  if(existing.some(asset=>!wanted.includes(asset.name)))throw new Error('Draft contains unexpected assets; refusing to publish them.');
  for(const name of wanted){
    // Retry only replaces assets in this workflow's still-private matching draft.
    const previous=existing.find(asset=>asset.name===name);
    if(previous)await github.rest.repos.deleteReleaseAsset({owner,repo,asset_id:previous.id});
    const data=fs.readFileSync(path.join(directory,name));
    await github.rest.repos.uploadReleaseAsset({owner,repo,release_id:release.id,name,data,headers:{'content-type':'application/octet-stream','content-length':data.length}});
  }
  const result=(await github.rest.repos.updateRelease({owner,repo,release_id:release.id,draft:false,prerelease:true,body:fs.readFileSync(notes,'utf8')})).data;
  core.notice(`Published ${result.html_url}`);await core.summary.addHeading('lw.HomeNAS release').addLink('Download full package and APK',result.html_url).write();
}
module.exports=publish;module.exports.assets=assets;
