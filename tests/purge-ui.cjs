const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
class Element{
 constructor(tag,text){this.tagName=tag;this.textContent=text;this.children=[];this.events={};}
 append(...n){this.children.push(...n);}addEventListener(name,fn){this.events[name]=fn;}remove(){}focus(){}showModal(){this.open=true;}
 close(value=''){this.returnValue=value;this.open=false;this.events.close();}
}
const dialogs=[],calls=[],errors=[];const context=vm.createContext({document:{body:{append:d=>dialogs.push(d)}},node:(tag,cls,text)=>new Element(tag,text),post:async(...args)=>{calls.push(args);},loadLibrary:async()=>{},loadRecent:async()=>{},refreshOverview:async()=>{},toast:()=>{},error:msg=>errors.push(msg)});
const source=fs.readFileSync('web/app.js','utf8');vm.runInContext(source.slice(source.indexOf('async function purgeFile('),source.indexOf("$('preview').addEventListener('keydown'")),context);
const item={id:'a'.repeat(48),deleted_at:'123456',name:'synthetic.txt'},trigger={disabled:false};
function open(){const promise=context.purgeFile(item,trigger),dialog=dialogs.at(-1),input=dialog.children[2].children[0],buttons=dialog.children[3].children;return{promise,dialog,input,cancel:buttons[0],erase:buttons[1]};}
(async()=>{
 let ui=open();assert.equal(ui.erase.disabled,true);ui.cancel.onclick();await ui.promise;assert.equal(calls.length,0);
 ui=open();ui.input.value='删除';ui.input.oninput();assert.equal(ui.erase.disabled,true);ui.erase.onclick();await ui.promise;assert.equal(calls.length,0);
 ui=open();ui.input.value='彻底删除';ui.input.oninput();assert.equal(ui.erase.disabled,false);ui.erase.onclick();await ui.promise;
 assert.equal(calls.length,1);assert.equal(calls[0][0],`assets/${item.id}/purge`);assert.equal(calls[0][1].confirm,'DELETE');assert.equal(calls[0][1].deleted_at,item.deleted_at);assert.equal(trigger.disabled,false);
 console.log('Purge UI: cancel, typed confirmation, no premature request, exact asset/deletion record and button recovery passed.');
})().catch(e=>{console.error(e);process.exitCode=1;});
