/* Load only this application's fixed, local page fragments before binding events. */
'use strict';
(async()=>{
 try {
  const slots=[...document.querySelectorAll('[data-view]')];
  const pages=await Promise.all(slots.map(async slot=>{
   const response=await fetch(`./views/${slot.dataset.view}.html`,{cache:'no-cache',credentials:'same-origin'});
   if(!response.ok)throw new Error('PageLoadError');
   return await response.text();
  }));
  slots.forEach((slot,index)=>slot.innerHTML=pages[index]);
  for(const file of ['library-collections.js','photo-viewer.js','app.js'])await new Promise((resolve,reject)=>{const script=document.createElement('script');script.src='./'+file;script.onload=resolve;script.onerror=reject;document.head.append(script);});
 } catch {fail();}
 function fail(){document.body.replaceChildren();const p=document.createElement('p');p.textContent='页面资源加载失败，请重新打开应用；网页请检查服务中的 web 文件夹是否完整。';document.body.append(p);}
})();
