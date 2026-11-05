'use strict';
/* Shared by the Web and APK pages. Original media and download URLs are untouched. */
class PhotoViewer {
 constructor(host,image){
  this.host=host;this.image=image;this.scale=1;this.x=0;this.y=0;this.pointers=new Map();
  this.stage=document.createElement('div');this.stage.className='photo-stage';this.stage.tabIndex=0;this.stage.setAttribute('aria-label','图片缩放区域，可拖动、滚轮或双指缩放');
  image.draggable=false;image.classList.add('zoom-image');this.stage.append(image);
  const bar=document.createElement('div');bar.className='photo-controls';bar.setAttribute('role','group');bar.setAttribute('aria-label','图片缩放');
  const button=(text,label,action)=>{const b=document.createElement('button');b.type='button';b.textContent=text;b.setAttribute('aria-label',label);b.onclick=action;bar.append(b);return b;};
  this.minus=button('−','缩小图片',()=>this.zoom(this.scale/1.4));this.label=document.createElement('output');this.label.setAttribute('aria-label','缩放倍率');bar.append(this.label);
  this.plus=button('+','放大图片',()=>this.zoom(this.scale*1.4));button('还原','还原图片',()=>this.reset());button('原始大小','按原始像素查看',()=>this.zoom(this.originalScale||1));
  host.replaceChildren(this.stage,bar);host.classList.add('photo-viewer');
  this.loaded=()=>this.resize();image.addEventListener('load',this.loaded);this.observer=new ResizeObserver(()=>this.resize());this.observer.observe(this.stage);
  this.stage.onwheel=e=>{e.preventDefault();this.zoom(this.scale*(e.deltaY<0?1.15:1/1.15),e.clientX,e.clientY);};
  this.stage.ondblclick=()=>this.scale>1?this.reset():this.zoom(2);
  this.stage.onkeydown=e=>{if(e.key==='+'||e.key==='='){e.preventDefault();this.zoom(this.scale*1.4);}else if(e.key==='-'){e.preventDefault();this.zoom(this.scale/1.4);}else if(e.key==='0'){e.preventDefault();this.reset();}};
  this.stage.onpointerdown=e=>{if(e.pointerType==='mouse'&&e.button!==0)return;this.stage.setPointerCapture(e.pointerId);this.pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});this.gesture();};
  this.stage.onpointermove=e=>{const old=this.pointers.get(e.pointerId);if(!old)return;this.pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(this.pointers.size===2){const p=[...this.pointers.values()];this.zoom(this.pinchScale*Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y)/Math.max(1,this.pinchDistance));}else if(this.pointers.size===1){this.x+=e.clientX-old.x;this.y+=e.clientY-old.y;this.draw();}};
  this.stage.onpointerup=this.stage.onpointercancel=this.stage.onlostpointercapture=e=>{this.pointers.delete(e.pointerId);this.gesture();};
  this.resize();
 }
 gesture(){if(this.pointers.size===2){const p=[...this.pointers.values()];this.pinchDistance=Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y);this.pinchScale=this.scale;}}
 resize(){const w=this.stage.clientWidth,h=this.stage.clientHeight,iw=this.image.naturalWidth,ih=this.image.naturalHeight;if(!w||!h||!iw||!ih)return;const fit=Math.min(w/iw,h/ih,1);this.width=iw*fit;this.height=ih*fit;this.originalScale=1/fit;this.maximum=Math.max(8,this.originalScale);this.image.style.width=this.width+'px';this.image.style.height=this.height+'px';this.draw();}
 zoom(scale,clientX,clientY){const old=this.scale;this.scale=Math.max(1,Math.min(this.maximum||8,scale));if(Number.isFinite(clientX)&&Number.isFinite(clientY)){const r=this.stage.getBoundingClientRect(),x=clientX-r.left-r.width/2,y=clientY-r.top-r.height/2;this.x=x-(x-this.x)*this.scale/old;this.y=y-(y-this.y)*this.scale/old;}this.draw();}
 reset(){this.scale=1;this.x=this.y=0;this.draw();}
 draw(){const maxX=Math.max(0,((this.width||0)*this.scale-this.stage.clientWidth)/2),maxY=Math.max(0,((this.height||0)*this.scale-this.stage.clientHeight)/2);this.x=Math.max(-maxX,Math.min(maxX,this.x));this.y=Math.max(-maxY,Math.min(maxY,this.y));this.image.style.transform=`translate(-50%,-50%) translate(${this.x}px,${this.y}px) scale(${this.scale})`;this.label.textContent=Math.round(this.scale*100)+'%';this.minus.disabled=this.scale<=1;this.plus.disabled=this.scale>=(this.maximum||8);this.stage.classList.toggle('is-zoomed',this.scale>1);}
 destroy(){this.observer.disconnect();this.image.removeEventListener('load',this.loaded);this.pointers.clear();this.host.classList.remove('photo-viewer');}
}
