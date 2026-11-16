"""Real Chromium and isolated NAS: promote/demote a nonempty subtree."""
from pathlib import Path
import argparse
from playwright.sync_api import sync_playwright
from smoke import Harness

parser = argparse.ArgumentParser()
parser.add_argument('--exe', type=Path, required=True)
h = Harness(parser.parse_args().exe)
try:
    h.start()
    password = (h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip()
    h.post('login', {'username':'admin', 'password':password})
    a = h.post('folders', {'name':'层级A'}, status=201)
    b = h.post('folders', {'name':'层级B','parent_id':a['id']}, status=201)
    c = h.post('folders', {'name':'子目录C','parent_id':b['id']}, status=201)
    payload = b'folder promotion retains original bytes'
    task, _ = h.create(payload, name='fixture.txt', folder_id=c['id'])
    h.chunk(task, payload, 0)
    asset = h.finish(task)['asset_id']
    def info(folder):
        return h.admin.get(h.url+'folders/'+folder['id']).json()
    def check_tree(parent_b, parent_c):
        assert info(b)['parent_id'] == parent_b
        assert info(c)['parent_id'] == parent_c
        assert h.admin.get(h.url+'assets/'+asset+'/original').content == payload
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe', headless=True)
        page = browser.new_page(viewport={'width':1200,'height':900})
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.goto(h.url.replace('api/v1/', ''))
        page.locator('#username').fill('admin')
        page.locator('#password').fill(password)
        page.locator('#login-form button.primary').click()
        page.locator('#shell').wait_for(state='visible')
        page.evaluate('navigate("library")')
        page.locator('.collection-card').filter(has_text='层级A').click()
        page.locator('#library').get_by_role('button',name='层级B',exact=True).click()
        promote = page.get_by_role('button',name='升级文件夹',exact=True)
        demote = page.get_by_role('button',name='降级文件夹',exact=True)
        promote.click()
        dialog = page.locator('dialog[open]')
        assert '资料库根目录' in dialog.inner_text()
        dialog.get_by_role('button',name='取消',exact=True).click()
        check_tree(a['id'], b['id'])
        promote.click()
        dialog.get_by_role('button',name='确定',exact=True).click()
        page.wait_for_function('()=>document.querySelector(".collection-breadcrumb").textContent.includes("层级B") && !document.querySelector(".collection-breadcrumb").textContent.includes("层级A")')
        assert promote.is_disabled(), 'A root folder cannot be promoted again'
        check_tree('', b['id'])
        demote.click()
        assert '选择作为父目录的同级文件夹' in dialog.inner_text()
        assert not dialog.locator('.folder-tree-row button').filter(has_text='子目录C').count()
        assert not dialog.locator('.folder-tree-row button').filter(has_text='默认文件夹').count()
        dialog.locator('.folder-tree-row button').filter(has_text='层级A').click()
        assert '目标位置：层级A' in dialog.inner_text()
        dialog.get_by_role('button',name='确定',exact=True).click()
        page.wait_for_function('()=>document.querySelector(".collection-breadcrumb").textContent.includes("层级A") && !document.querySelector("#library").inert')
        check_tree(a['id'], b['id'])
        page.locator('#library').get_by_role('button',name='子目录C',exact=True).click()
        promote.click()
        assert '目标位置：层级A' in dialog.inner_text()
        dialog.get_by_role('button',name='确定',exact=True).click()
        page.wait_for_function('()=>!document.querySelector(".collection-breadcrumb").textContent.includes("层级B") && !document.querySelector("#library").inert')
        check_tree(a['id'], a['id'])
        # A target name conflict must leave the original hierarchy and bytes intact.
        conflict = h.post('folders', {'name':'子目录C'}, status=201)
        promote.click()
        dialog.get_by_role('button',name='确定',exact=True).click()
        page.locator('#global-error:not(.hidden)').wait_for()
        check_tree(a['id'], a['id'])
        assert info(conflict)['parent_id'] == ''
        page.set_viewport_size({'width':320,'height':640})
        assert demote.bounding_box()['x'] + demote.bounding_box()['width'] <= 320
        page.screenshot(path=str(h.home/'folder-levels-320.png'))
        assert not errors, errors
        browser.close()
    h.check(True, 'Chrome folder levels: cancel, root promotion, sibling demotion, grandparent promotion, descendant and byte preservation, conflict rollback, narrow toolbar passed')
    print('Evidence:', h.home)
finally:
    h.stop()
