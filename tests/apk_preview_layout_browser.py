"""Render production APK preview assets in Chrome; this is not a device/APK test."""
from pathlib import Path
from playwright.sync_api import sync_playwright
from PIL import Image
import io

root=Path(__file__).resolve().parents[1];assets=root/'android/app/src/main/assets/www'
output=root/'test-results/apk-filename-layout';output.mkdir(parents=True,exist_ok=True)
source=(assets/'app.js').read_text(encoding='utf-8')
preview=source[source.index('let previewItem='):source.index('function libraryCard(')]
name='2026年家庭旅行拍摄记录_'+('很长的原始文件名称_Abc123_'*12)+'.jpg'
with sync_playwright() as pw:
 browser=pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True)
 page=browser.new_page();page.route('http://preview.test/**',lambda route:route.fulfill(body='<html><head><meta charset="utf-8"></head><body></body></html>',content_type='text/html'))
 image=io.BytesIO();Image.new('RGB',(800,600),(45,115,90)).save(image,format='PNG')
 page.route('http://preview.test/media/**',lambda route:route.fulfill(body=image.getvalue(),content_type='image/png'))
 page.goto('http://preview.test/')
 page.set_content('<meta name="viewport" content="width=device-width,initial-scale=1"><style>'+ (assets/'style.css').read_text(encoding='utf-8')+'</style>'+ (assets/'views/dialogs.html').read_text(encoding='utf-8'))
 page.add_script_tag(content="const $=id=>document.getElementById(id);const element=(tag,text='',css='')=>{const n=document.createElement(tag);n.textContent=text;n.className=css;return n;};let current={nativeApi:7,scope:'fixture'};const nativeCall=async()=>({});const bytes=String,fileDate=String;const renameFile=()=>{},run=async()=>{};")
 page.add_script_tag(content=(assets/'photo-viewer.js').read_text(encoding='utf-8'))
 page.add_script_tag(content=preview)
 for width,height in [(320,640),(640,320),(1024,768)]:
  page.set_viewport_size({'width':width,'height':height})
  page.evaluate('(name)=>previewMedia({id:"a".repeat(48),name,category:"image",mime:"image/jpeg",size:"1234",can_rename:true})',name)
  page.locator('#media-metadata summary').click()
  filename=page.locator('#media-details dd').first
  assert filename.inner_text()==name
  assert filename.evaluate('(n)=>n.scrollWidth<=n.clientWidth+1'), 'Filename must wrap without clipping'
  assert page.locator('#media-title').evaluate('(n)=>n.getBoundingClientRect().right<=document.querySelector("#media-close").getBoundingClientRect().left')
  for selector in ['#media-close','#media-save','#media-prev','#media-next']:
   box=page.locator(selector).bounding_box();assert box and box['x']>=0 and box['y']>=0 and box['x']+box['width']<=width+1 and box['y']+box['height']<=height+1,(selector,box,width,height)
  assert filename.evaluate('(n)=>getComputedStyle(n).userSelect')=='text'
  page.screenshot(path=str(output/f'preview-{width}.png'))
 browser.close()
print('APK production page layout: full filename wraps/selects; header, close and actions fit 320px, landscape and desktop. No Android device claim.')
