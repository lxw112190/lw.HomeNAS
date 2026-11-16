"""Real Chrome + synthetic catalog: icons, scoped search, page jumps and overview."""
from pathlib import Path
import argparse, sqlite3, io, requests
from PIL import Image
from playwright.sync_api import sync_playwright
from smoke import Harness

p=argparse.ArgumentParser();p.add_argument('--exe',type=Path,required=True)
h=Harness(p.parse_args().exe)
try:
 h.start();password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip()
 h.post('login',{'username':'admin','password':password})
 h.post('users',{'username':'reader','display_name':'Reader','role':'viewer','password':'Test12345'},status=201)
 root=h.post('folders',{'name':'搜索范围'},status=201)
 h.post('folders/'+root['id']+'/permissions',{'policy':'public','grants':[],'confirm_access_change':True})
 child=h.post('folders',{'name':'子目录','parent_id':root['id']},status=201)
 secret=h.post('folders',{'name':'私有目录','parent_id':root['id']},status=201)
 h.post('folders/'+secret['id']+'/permissions',{'policy':'private','grants':[],'confirm_access_change':True})
 album=h.post('albums',{'name':'自动封面图集'},status=201)
 h.stop();data=h.home/'数据';(data/'fixture').mkdir();(data/'fixture/synthetic').write_bytes(b'synthetic')
 ids=[f'{i:048x}' for i in range(1,129)]
 with sqlite3.connect(data/'catalog.db') as db:
  for i,ident in enumerate(ids,1):
   photo=i>=126;folder=child['id'] if i<=100 or i in (126,127) else 'default' if i<=120 else secret['id']
   db.execute("INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state,preview) VALUES(?,?,9,'fixture/synthetic',?,?,?,1000,1000,'READY',?)",(ident,f'{i:064x}',f'相册-{i}.png' if photo else f'needle-{i}.txt','image/png' if photo else 'text/plain','image' if photo else 'file','READY' if photo else 'UNAVAILABLE'))
   db.execute('INSERT INTO folder_items(folder_id,asset_id) VALUES(?,?)',(folder,ident))
   if photo:db.execute('INSERT INTO album_items(album_id,asset_id) VALUES(?,?)',(album['id'],ident))
 for ident in ids[-3:]:Image.new('RGB',(24,24),(40,150,90)).save(data/'cache'/f'{ident}.jpg')
 h.start();reader=requests.Session();h.post('login',{'username':'reader','password':'Test12345'},session=reader)
 def get(route,**q):
  r=reader.get(h.url+route,params=q);assert r.status_code==200,r.text;return r.json()
 recursive=get('folders/'+root['id']+'/items',q='needle',before='@last')
 assert len(recursive['items'])==4 and recursive['page_index']==2 and not recursive['next']
 assert all(x['source_folder']==child['id'] for x in recursive['items'])
 assert len(get('assets',q='needle',limit=48,before='@last')['items'])==24
 assert get('albums/'+album['id']+'/items')['album']['cover']['id']==ids[126]
 for route in ['assets','folders','albums','tasks/page','folders/'+root['id']+'/items','albums/'+album['id']+'/items']:
  assert get(route,before='@last')['page_index']>=0
 assert reader.get(h.url+'assets',params={'before':'@page:bad'}).status_code==400
 for kind in ['folder','txt','docx','unknownext']:
  icon=reader.get(h.url+'icons/'+kind);assert icon.status_code==200,icon.text
  assert icon.headers['Content-Type'].startswith('image/png')
  image=Image.open(io.BytesIO(icon.content));assert image.width>=16 and image.height>=16
 with sync_playwright() as pw:
  browser=pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True)
  page=browser.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  page.goto(h.url.replace('api/v1/',''));page.locator('#username').fill('reader');page.locator('#password').fill('Test12345');page.locator('#login-form button.primary').click()
  page.locator('#shell').wait_for(state='visible')
  assert page.locator('#home-photo-count').inner_text()=='2'
  page.screenshot(path=str(h.home/'overview-desktop.png'))
  page.evaluate('navigate("library")');page.locator('.collection-cover img.windows-icon').first.wait_for()
  search=page.locator('#search');assert search.is_enabled();search.fill('needle')
  page.wait_for_function('()=>document.querySelectorAll("#library [data-asset-id]").length===48 && !document.querySelector("#library").inert')
  page.locator('#library-last').click()
  page.wait_for_function('()=>document.querySelectorAll("#library [data-asset-id]").length===24 && !document.querySelector("#library").inert')
  assert page.locator('#library-last').is_disabled() and page.locator('#load-more').is_disabled()
  page.locator('#library-prev').click();page.wait_for_function('()=>document.querySelectorAll("#library [data-asset-id]").length===48 && !document.querySelector("#library").inert')
  page.locator('#library-first').click();page.wait_for_function('()=>document.querySelector("#library-first").disabled && !document.querySelector("#library").inert')
  search.fill('');page.locator('.collection-card').filter(has_text='搜索范围').click()
  assert '所有子目录' in search.get_attribute('placeholder')
  search.fill('needle');page.wait_for_function('()=>document.querySelectorAll("#library [data-asset-id]").length===48 && !document.querySelector("#library").inert')
  page.locator('#library-last').click();page.wait_for_function('()=>document.querySelectorAll("#library [data-asset-id]").length===4 && !document.querySelector("#library").inert')
  search.fill('');page.get_by_role('button',name='主题图集',exact=True).click();page.locator('.collection-card').filter(has_text='自动封面图集').click()
  search.fill('needle');page.wait_for_function('()=>!document.querySelector("#library").inert && document.querySelector("#library").textContent.includes("暂时没有")')
  search.fill('相册');page.wait_for_function('()=>document.querySelectorAll("#library [data-asset-id]").length===2 && !document.querySelector("#library").inert')
  page.set_viewport_size({'width':320,'height':640});page.evaluate('navigate("home")');page.locator('#home-refresh').click()
  assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
  page.screenshot(path=str(h.home/'overview-320.png'));assert not errors,errors;browser.close()
 h.check(True,'Windows shell PNGs, visible-first album cover, global/recursive/album search, last/previous/first navigation and overview at 1440/320px passed')
 print('Evidence:',h.home)
finally:h.stop()
