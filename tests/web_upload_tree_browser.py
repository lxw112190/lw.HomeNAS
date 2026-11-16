"""Real Chromium UI + isolated NAS: nested selection, shortcuts, logical paths and bytes."""
from pathlib import Path
import argparse
from playwright.sync_api import sync_playwright
from smoke import Harness

parser = argparse.ArgumentParser()
parser.add_argument('--exe', type=Path, required=True)
args = parser.parse_args()
h = Harness(args.exe)
try:
    h.start()
    password = (h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip()
    h.post('login', {'username':'admin', 'password':password})
    target = h.post('folders', {'name':'目录树测试'}, status=201)
    h.post('folders', {'name':'已有子目录', 'parent_id':target['id']}, status=201)
    local = h.home/'合成上传'
    for name, data in [('第一层/第二层/same.txt', b'nested bytes'), ('第一层/same.txt', b'parent bytes'), ('排除目录/skip.txt', b'excluded')]:
        p = local/name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe', headless=True)
        page = browser.new_page(viewport={'width':1100, 'height':800})
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.goto(h.url.replace('api/v1/', ''))
        page.locator('#username').fill('admin')
        page.locator('#password').fill(password)
        page.locator('#login-form button.primary').click()
        page.locator('#shell').wait_for(state='visible')
        page.evaluate('navigate("library")')
        page.locator('.collection-card').filter(has_text='目录树测试').click()
        page.get_by_role('button', name='授权', exact=True).click()
        page.locator('dialog[open]').get_by_role('button', name='取消', exact=True).click()
        page.get_by_role('button', name='上传到当前文件夹', exact=True).click()
        assert page.locator('#upload-folder-name').inner_text() == '目录树测试'
        page.locator('#choose-upload-folder').click()
        dialog = page.locator('dialog[open]')
        dialog.get_by_role('button', name='展开 目录树测试', exact=True).click()
        dialog.locator('.folder-tree-row button').filter(has_text='已有子目录').wait_for()
        dialog.get_by_role('button', name='取消', exact=True).click()
        page.locator('#folder-input').set_input_files(local)
        dialog = page.locator('dialog[open]')
        dialog.locator('summary').filter(has_text='合成上传').click()
        dialog.locator('summary').filter(has_text='排除目录').locator('input').uncheck()
        assert '已选 2 / 3 项' in dialog.inner_text()
        page.set_viewport_size({'width':320, 'height':640})
        assert dialog.evaluate('(d)=>d.getBoundingClientRect().right<=innerWidth')
        page.screenshot(path=str(h.home/'selection-tree-320.png'))
        page.set_viewport_size({'width':640, 'height':320})
        assert dialog.evaluate('(d)=>d.getBoundingClientRect().bottom<=innerHeight')
        page.set_viewport_size({'width':320, 'height':640})
        dialog.get_by_role('button', name='上传所选文件', exact=True).click()
        page.wait_for_function('()=>state.files.length===2 && state.files.every(t=>t.state==="READY")', timeout=60000)
        page.screenshot(path=str(h.home/'upload-tree-320.png'))
        assert not errors, errors
        browser.close()
    def child(parent, name):
        rows = h.admin.get(h.url+'folders', params={'parent_id':parent}).json()['items']
        return next(f for f in rows if f['name']==name)
    base = child(target['id'], '合成上传')
    first = child(base['id'], '第一层')
    second = child(first['id'], '第二层')
    assert not any(f['name']=='排除目录' for f in h.admin.get(h.url+'folders', params={'parent_id':base['id']}).json()['items'])
    for folder, expected in [(first,b'parent bytes'),(second,b'nested bytes')]:
        items = h.admin.get(h.url+'folders/'+folder['id']+'/items').json()['items']
        assert len(items)==1 and items[0]['name']=='same.txt'
        assert h.admin.get(h.url+'assets/'+items[0]['id']+'/original').content==expected
    h.check(True, 'Real Chrome: current-folder/authorization shortcuts, target tree, subtree exclusion, nested same-name originals and 320px dialog passed')
    print('Evidence:', h.home)
finally:
    h.stop()
