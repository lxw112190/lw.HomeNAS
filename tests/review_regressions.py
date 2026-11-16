"""Regression checks against an isolated synthetic server and real Chrome."""
from pathlib import Path
import sys, json, requests, argparse
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tests'))
from smoke import Harness
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--exe',type=Path,required=True)
h = Harness(parser.parse_args().exe)
results = {}
try:
    h.start()
    password = (h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip()
    h.post('login', {'username':'admin','password':password})
    h.post('users', {'username':'member','display_name':'Member','role':'member','password':'Test12345'}, status=201)
    member = requests.Session()
    h.post('login', {'username':'member','password':'Test12345'}, session=member)
    private = h.post('folders', {'name':'Private audit fixture'}, status=201)
    dest = h.post('folders', {'name':'Move destination'}, status=201)
    def upload(name, content, folder='default'):
        task, _ = h.create(content, name=name, folder_id=folder)
        h.chunk(task, content, 0)
        return h.finish(task)['asset_id']
    public_ids = [upload('IMG_001.txt', b'one'), upload('IMGX001.txt', b'two')]
    private_id = upload('PRIVATE-audit-only.txt', b'secret-fixture', private['id'])
    trash_id = upload('trash-fixture.txt', b'trash-fixture')
    h.post('assets/trash', {'asset_ids':[trash_id]})
    results['literal_search'] = [x['name'] for x in h.admin.get(h.url+'assets', params={'q':'IMG_001'}).json()['items']]
    results['trash_scope'] = {'overview':member.get(h.url+'overview').json()['trash'], 'list_count':len(member.get(h.url+'trash').json()['items'])}
    duplicate, _ = h.create(b'trash-fixture', name='trash-fixture.txt')
    results['trashed_reupload'] = {'state':duplicate['state'], 'same_asset':duplicate['asset_id']==trash_id, 'original_status':h.admin.get(h.url+'assets/'+trash_id+'/original').status_code}
    h.create(b'waiting', name='member-active-task.txt', session=member)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe', headless=True)
        page = browser.new_page()
        page.goto(h.url.replace('api/v1/',''))
        page.locator('#username').fill('admin'); page.locator('#password').fill(password)
        page.locator('#login-form button.primary').click()
        page.wait_for_function('()=>state.me?.admin && document.querySelectorAll("#recent [data-item]").length===3')
        # Actual production preview -> single-file move -> actual folder picker.
        item = h.admin.get(h.url+'assets', params={'q':'IMG_001'}).json()['items'][0]
        page.evaluate('(item)=>preview(item)', item)
        page.locator('#move-file').click()
        page.locator('.folder-tree-row button').filter(has_text='Move destination').click()
        with page.expect_response(lambda r: r.url.endswith('/move') and r.request.method=='POST') as response:
            page.get_by_role('button',name='确定',exact=True).click()
        results['single_move'] = {'status':response.value.status, 'response':response.value.json(), 'body':response.value.request.post_data_json}
        page.evaluate('closePreview()')
        # Delay a genuine response after it was authenticated for the admin.
        page.evaluate('''() => {
            window.savedApi=api; window.heldReady=false; window.releaseHeld=null;
            let first=true;
            api=async (...args)=>{const data=await savedApi(...args);if(args[0]==='assets' && first){first=false;window.heldReady=true;await new Promise(resolve=>window.releaseHeld=resolve);}return data;};
            window.oldRecent=loadRecent();
        }''')
        page.wait_for_function('()=>heldReady')
        page.evaluate('''async()=>{await post('logout');showAuth();await post('login',{username:'member',password:'Test12345'});await entered();}''')
        page.wait_for_function('()=>state.me?.username === "member" && !document.querySelector("#recent").textContent.includes("PRIVATE-audit")')
        results['late_recent'] = page.evaluate('async()=>{releaseHeld();await oldRecent;api=savedApi;return {account:state.me.username,leaked_name:document.querySelector("#recent").innerText};}')
        results['late_recent']['private_api_status'] = member.get(h.url+'assets/'+private_id+'/original').status_code
        assert 'PRIVATE-audit' not in results['late_recent']['leaked_name']
        # Slow pending request makes change-filter request return before sending.
        page.evaluate('async()=>{await refreshTasks(true);}')
        page.evaluate('''()=>{
            window.taskRequests=[];window.heldReady=false;let first=true;
            api=async(...args)=>{if(args[0].startsWith('tasks/page?'))taskRequests.push(args[0]);const data=await savedApi(...args);if(args[0].startsWith('tasks/page?')&&first){first=false;heldReady=true;await new Promise(resolve=>releaseHeld=resolve);}return data;};
            window.oldTaskRequest=refreshTasks();
        }''')
        page.wait_for_function('()=>heldReady')
        page.evaluate('''()=>{const input=document.querySelector('#remote-filter');input.value='failed';input.dispatchEvent(new Event('change'));}''')
        page.evaluate('async()=>{releaseHeld();await oldTaskRequest;api=savedApi;}')
        page.wait_for_function('()=>!taskBusy')
        results['task_filter'] = {'filter':page.locator('#remote-filter').input_value(),'requests':page.evaluate('taskRequests'),'visible':page.locator('#server-tasks').inner_text()}
        # Same name, length and modified time in distinct local files: second is skipped before hashing.
        page.evaluate('''async()=>{
            window.savedKick=kick;kick=()=>{};state.files=[];
            await addFiles([new File(['AAAA'],'same.txt',{lastModified:1700000000000})]);
            await addFiles([new File(['BBBB'],'same.txt',{lastModified:1700000000000})]);
        }''')
        results['queue_identity_collision'] = page.evaluate('({count:state.files.length,message:document.querySelector("#toast").textContent})')
        assert results['queue_identity_collision']['count']==2
        page.evaluate('async()=>{for(const task of state.files)await uploadFile(task);}')
        assert page.evaluate('state.files.every(t=>t.state==="READY")')
        uploaded=member.get(h.url+'assets',params={'q':'same.txt'}).json()['items']
        assert {member.get(h.url+'assets/'+item['id']+'/original').content for item in uploaded}=={b'AAAA',b'BBBB'}
        page.evaluate('''async()=>{state.files=[];await addFiles([new File(['trash-fixture'],'trash-fixture.txt',{lastModified:1700000000000})]);await uploadFile(state.files[0]);}''')
        results['trashed_reupload']['browser'] = page.evaluate('({state:state.files[0].state,message:state.files[0].message})')
        # A valid 3-level folder hierarchy exceeds the obsolete album text bound.
        page.evaluate('''async()=>{
            state.files=[];state.nextCandidate=0;
            const target=await post('folders',{name:'Long path fixture'});state.uploadFolder={id:target.id,name:target.name};
            const f=new File(['long-path-data'],'long-path.txt',{lastModified:1700000000000});
            const segment='目录'.repeat(22);Object.defineProperty(f,'webkitRelativePath',{value:[segment,segment,segment,f.name].join('/')});
            await addFiles([f],{preserveFolders:true});
            await uploadFile(state.files[0]);
        }''')
        results['long_folder_upload'] = page.evaluate('({state:state.files[0]?.state,message:state.files[0]?.message,pathBytes:new TextEncoder().encode(state.files[0]?.file.webkitRelativePath).length})')
        assert results['long_folder_upload']['state']=='READY',results['long_folder_upload']
        assert results['trashed_reupload']['browser']['state']=='TRASHED'
        assert results['single_move']['status']==200
        assert results['single_move']['body']['confirm_access_change'] is True
        assert results['literal_search']==['IMG_001.txt']
        assert results['trash_scope']['overview']['files']=='0' and results['trash_scope']['list_count']==0
        assert any('filter=failed' in q for q in results['task_filter']['requests'])
        assert 'member-active-task' not in results['task_filter']['visible']
        # Real browser grid/list and maintenance layout on desktop and narrow viewport.
        page.evaluate('async()=>{await post("logout");showAuth();}')
        page.locator('#username').fill('admin');page.locator('#password').fill(password);page.locator('#login-form button.primary').click()
        page.wait_for_function('()=>state.me?.admin && state.page==="home"')
        page.evaluate('navigate("settings")');page.locator('#library-maintenance').wait_for(state='visible')
        page.get_by_role('button',name='开始检查',exact=True).click()
        page.wait_for_function('()=>document.querySelector("#health-status").textContent.includes("本轮检查完成")',timeout=20000)
        page.locator('#snapshot-enabled').check();page.locator('#snapshot-hours').fill('24');page.locator('#snapshot-keep').fill('3')
        page.get_by_role('button',name='保存快照计划',exact=True).click()
        with page.expect_response(lambda r:r.url.endswith('/backup-schedule') and r.request.method=='POST') as saved_schedule:
            page.get_by_role('button',name='确定',exact=True).click()
        assert saved_schedule.value.status==200 and saved_schedule.value.json()['keep']==3
        page.evaluate('document.querySelector("#toast").classList.add("hidden")')
        page.locator('#library-maintenance').screenshot(path=str(h.home/'maintenance-desktop.png'))
        page.set_viewport_size({'width':320,'height':700})
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        page.locator('#library-maintenance').screenshot(path=str(h.home/'maintenance-320.png'))
        browser.close()
    (h.home/'review-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS: eight review regressions, actual original bytes and maintenance layouts')
    print('Evidence:', h.home)
finally:
    h.stop()
