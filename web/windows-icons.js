'use strict';
globalThis.HomeNasIcons={icon(name='',folder=false){
 const image=document.createElement('img');image.className='windows-icon';image.alt='';image.loading='lazy';
 const extension=String(name).split('.').pop().toLowerCase(),kind=folder?'folder':String(name).includes('.')&&/^[a-z0-9]{1,16}$/.test(extension)?extension:'file';
 image.src='./api/v1/icons/'+kind;
 image.onerror=()=>{image.onerror=null;if(kind!=='file'&&!folder)image.src='./api/v1/icons/file';};return image;
}};
