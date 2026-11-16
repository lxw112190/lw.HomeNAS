module.exports=async function tagCommit(github,owner,repo,tag){
 let object=(await github.rest.git.getRef({owner,repo,ref:`tags/${tag}`})).data.object;
 for(let depth=0;object.type==='tag'&&depth<8;depth++)object=(await github.rest.git.getTag({owner,repo,tag_sha:object.sha})).data.object;
 if(object.type!=='commit'||!/^[a-f0-9]{40}$/.test(object.sha))throw Error('Tag must resolve to a commit.');
 return object.sha;
};
