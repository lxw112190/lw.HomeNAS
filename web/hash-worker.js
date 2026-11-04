importScripts('./sha256.js');
self.onmessage=async function(event){
  try{const file=event.data.file,hash=new NasSha256();for(let offset=0;offset<file.size;offset+=8388608){hash.update(await file.slice(offset,offset+8388608).arrayBuffer());self.postMessage({progress:Math.min(file.size,offset+8388608)});}self.postMessage({digest:hash.digestHex()});}
  catch(error){self.postMessage({error:error.message});}
};
