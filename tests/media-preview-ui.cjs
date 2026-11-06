// Execute production preview logic with a small DOM adapter; no APK/device claim.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
class Element {
 constructor(tag='div',text=''){this.tagName=tag.toUpperCase();this.textContent=text;this.children=[];this.open=false;}
 append(...nodes){this.children.push(...nodes);} replaceChildren(...nodes){this.children=nodes;}
 setAttribute(){} removeAttribute(name){delete this[name];} addEventListener(){}
 querySelectorAll(selector){return this.children.filter(n=>selector.split(',').includes(n.tagName.toLowerCase()));}
 showModal(){this.open=true;} close(){this.open=false;} pause(){this.paused=true;} load(){this.unloaded=true;}
}
const controls=new Map(),get=id=>{if(!controls.has(id))controls.set(id,new Element());return controls.get(id);},calls=[];
const context=vm.createContext({console,document:{createElement:tag=>new Element(tag),querySelectorAll:()=>[]},$:get,element:(tag,text)=>new Element(tag,text),current:{nativeApi:8,scope:'test',nativeMediaPlayback:true},nativeCall:async(action,data)=>{calls.push({action,data});return {};},bytes:String,fileDate:String,renameFile:()=>{},run:async()=>{}});
const source=fs.readFileSync('android/app/src/main/assets/www/app.js','utf8');
vm.runInContext(source.slice(source.indexOf('let previewItem='),source.indexOf('function libraryCard(')),context);
const item={id:'a'.repeat(48),category:'file',mime:'audio/mpeg',size:'1000',name:'synthetic.mp3'};
(async()=>{
 context.item=item;vm.runInContext('previewMedia(item)',context);
 let container=get('media-content').children[0],button=container.children[0];
 assert.equal(button.tagName,'BUTTON');assert.equal(button.textContent,'▶ 播放音频');assert.match(get('media-runtime').textContent,/点击播放/);
 await button.onclick();const play=calls.find(c=>c.action==='playMedia');assert.deepEqual(Object.keys(play.data),['id']);assert.equal(play.data.id,item.id);
 context.current={nativeApi:8,scope:'test',nativeMediaPlayback:false};vm.runInContext('previewMedia(item)',context);
 const audio=get('media-content').children[0];assert.equal(audio.tagName,'AUDIO');assert.equal(audio.controls,true);
 vm.runInContext('closeMedia()',context);assert.equal(audio.paused,true);assert.equal(audio.unloaded,true);assert.equal(audio.src,undefined);
 context.current.nativeMediaPlayback=true;context.item={...item,category:'video',mime:'video/mp4'};vm.runInContext('previewMedia(item)',context);
 assert.equal(get('media-content').children[0].children[0].textContent,'▶ 播放视频');
 context.item={...item,mime:'application/pdf'};vm.runInContext('previewMedia(item)',context);
 assert.equal(get('media-content').children[0].tagName,'P');assert.doesNotMatch(get('media-runtime').textContent,/正在加载/);
 console.log('Media preview UI: old file-category MP3, native video, ID-only bridge, audio cleanup and unsupported-state checks passed.');
})().catch(error=>{console.error(error);process.exitCode=1;});
