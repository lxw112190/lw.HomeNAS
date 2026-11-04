"""End-to-end checks against an isolated real Windows server, including crash recovery."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import time
import uuid
import requests

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class Harness:
    def __init__(self, executable):
        self.exe = executable.resolve()
        self.home = ROOT / 'test-results' / ('端到端-' + uuid.uuid4().hex[:10])
        self.home.mkdir(parents=True)
        self.http, self.https = free_port(), free_port()
        self.url = f'http://127.0.0.1:{self.http}/api/v1/'
        config = json.loads((ROOT / 'config/server.json').read_text(encoding='utf-8'))
        web = self.exe.parent/'web'
        if not (web/'index.html').is_file(): web = ROOT/'web'
        config.update(http_port=self.http, https_port=self.https, https_host='127.0.0.1',
                      data_dir='./数据', web_dir=str(web), chunk_bytes=1048576,
                      minimum_free_bytes=0, maximum_file_bytes=16 * 1024**3)
        self.config = self.home / 'server.json'
        self.config.write_text(json.dumps(config, ensure_ascii=False), encoding='utf-8')
        self.process = None
        self.log = None
        self.checks = []
        self.admin = requests.Session()

    def check(self, condition, label):
        if not condition:
            raise AssertionError(label)
        self.checks.append(label)
        print('PASS:', label, flush=True)

    def start(self):
        self.log = (self.home / ('server-' + str(time.time_ns()) + '.log')).open('wb')
        self.process = subprocess.Popen([str(self.exe), '--config', str(self.config), *getattr(self, 'extra_args', [])], cwd=self.home,
                                        stdout=self.log, stderr=subprocess.STDOUT,
                                        creationflags=subprocess.CREATE_NO_WINDOW)
        for _ in range(150):
            if self.process.poll() is not None:
                self.log.flush()
                raise RuntimeError('Server exited; inspect ' + str(self.home))
            try:
                if requests.get(self.url + 'server', timeout=.5).status_code == 200:
                    return
            except requests.RequestException:
                pass
            time.sleep(.1)
        raise TimeoutError('Server did not start')

    def stop(self):
        if self.process and self.process.poll() is None:
            # Deliberate abrupt termination of this test's own process validates WAL/recovery.
            self.process.terminate()
            self.process.wait(timeout=15)
        if self.log:
            self.log.close()

    def post(self, path, value=None, session=None, status=200):
        response = (session or self.admin).post(self.url + path, json={} if value is None else value, timeout=20)
        if response.status_code != status:
            raise AssertionError(f'{path}: {response.status_code}: {response.text[:300]}')
        return response.json()

    def create(self, content, name='中文照片.jpg', session=None, **overrides):
        value = dict(client_task_id=uuid.uuid4().hex, name=name, size=str(len(content)),
                     sha256=hashlib.sha256(content).hexdigest(), source_key=uuid.uuid4().hex,
                     album='测试', modified='1793750400000')
        value.update(overrides)
        return self.post('uploads', value, session=session, status=201), value

    def chunk(self, task, content, offset, session=None, digest=None, status=200):
        block = content[offset:offset+1048576]
        response = (session or self.admin).put(self.url + f'uploads/{task["id"]}/data', data=block,
                    headers={'Content-Type': 'application/octet-stream', 'X-Upload-Offset': str(offset),
                             'X-Chunk-SHA256': digest or hashlib.sha256(block).hexdigest()}, timeout=20)
        if response.status_code != status:
            raise AssertionError(f'chunk: {response.status_code}: {response.text[:300]}')
        return response.json()

    def finish(self, task, expected='READY'):
        self.post(f'uploads/{task["id"]}/complete', status=202)
        for _ in range(150):
            result = self.admin.get(self.url + f'uploads/{task["id"]}', timeout=10).json()
            if result['state'] in ('READY', 'ERROR'):
                self.check(result['state'] == expected, f'complete -> {expected}')
                return result
            time.sleep(.1)
        raise TimeoutError('Verification timed out')

    def run(self):
        self.start()
        try:
            for resource in ['', 'app.js', 'style.css', 'sha256.js', 'hash-worker.js']:
                response=requests.get(self.url.replace('/api/v1/','/')+resource,timeout=10)
                self.check(response.status_code==200 and len(response.content)>50,'bundled web resource: '+(resource or 'index.html'))
            self.check(requests.get(self.url + 'assets').status_code == 401, 'anonymous media access rejected')
            password = (self.home / '数据/initial-admin-password.txt').read_text().strip()
            self.post('login', {'username': 'admin', 'password': password})
            self.check(self.admin.get(self.url + 'me').json()['admin'], 'administrator login')
            cert = self.home / '数据/server-cert.pem'
            secure = requests.get(f'https://127.0.0.1:{self.https}/api/v1/server', verify=str(cert), timeout=10)
            self.check(secure.status_code == 200 and secure.json()['https'], 'HTTPS with certificate and hostname verification')
            hostile = self.admin.post(self.url + 'users', json={}, headers={'Origin': 'https://evil.example'})
            self.check(hostile.status_code == 403, 'cross-origin mutation rejected')
            self.check(requests.get(self.url + 'server', headers={'Host': 'evil.example'}).status_code == 400, 'unrecognized Host rejected')
            self.check(requests.get(self.url.replace('/api/v1/', '/') + '../data/catalog.db').status_code in (401,404), 'data files not statically served')
            self.post('users/administrator', {'enabled': False}, status=409)
            self.post('users/administrator', {'role': 'member'}, status=409)
            self.check(self.admin.get(self.url+'me').status_code==200,'last administrator cannot be disabled or demoted')
            member_password='member-test-password-2026'
            member=self.post('users', {'username':'family_member','display_name':'家庭成员','role':'member','password':member_password},status=201)
            self.check('salt' not in member and 'password_hash' not in member,'user creation does not return password material')
            self.post('users', {'username':'FAMILY_MEMBER','display_name':'重复账号','role':'member','password':member_password},status=409)
            self.post('users', {'username':'bad/name','display_name':'非法用户名','role':'member','password':member_password},status=400)
            self.post('users', {'username':'bad_role','display_name':'非法角色','role':'owner','password':member_password},status=400)
            self.post('users', {'username':'weak_password','display_name':'弱密码','role':'member','password':'123'},status=400)
            phone=requests.Session()
            login=self.post('login', {'username':'FAMILY_MEMBER','password':member_password},session=phone)
            self.check(login['role']=='member' and login['can_upload'],'username/password member login is case-insensitive')
            self.check('token' not in login,'browser login keeps token in HttpOnly cookie')
            self.check(any(c.name=='nas_session' and 'HttpOnly' in c._rest for c in phone.cookies),'member session cookie is HttpOnly')
            self.check(phone.get(self.url+'users').status_code==403,'member cannot list accounts')
            self.post('users',{},session=phone,status=403)
            self.post('users/'+member['id'], {'role':'admin'},session=phone,status=403)
            self.check(self.admin.post(self.url+'pair',json={}).status_code==404,'pair-code redemption endpoint removed')
            self.check(self.admin.post(self.url+'pairing',json={}).status_code==404,'pair-code creation endpoint removed')
            reader_password='reader-test-password-2026'
            reader=self.post('users', {'username':'family_reader','display_name':'只读成员','role':'viewer','password':reader_password},status=201)
            readonly=requests.Session()
            self.post('login',{'username':'family_reader','password':reader_password},session=readonly)
            self.check(not readonly.get(self.url+'me').json()['can_upload'],'read-only account exposes no upload capability')
            self.post('uploads',{},session=readonly,status=403)
            self.post('users',{},session=readonly,status=403)
            content = os.urandom(3 * 1048576 + 173)
            task, request = self.create(content)
            same = self.post('uploads', request, status=201)
            self.check(same['id'] == task['id'], 'create upload is idempotent')
            conflict = dict(request, sha256='f'*64)
            self.post('uploads', conflict, status=409)
            self.chunk(task, content, 0, digest='0'*64, status=422)
            self.check(self.admin.get(self.url + f'uploads/{task["id"]}').json()['offset'] == '0', 'bad chunk leaves confirmed offset unchanged')
            task = self.chunk(task, content, 0)
            self.chunk(task, content, 0, status=409)
            self.check(task['offset'] == '1048576', 'confirmed offset is persisted')
            # Simulate an interrupted write that extended the file without a DB acknowledgment.
            self.stop()
            part = self.home / '数据/storage/uploads' / (task['id'] + '.part')
            with part.open('ab') as f:
                f.write(b'UNACKNOWLEDGED-TAIL')
            self.start()
            self.check(part.stat().st_size == 1048576, 'restart truncates unacknowledged tail')
            self.check(self.admin.get(self.url + 'me').status_code == 200, 'login session survives server restart')
            while int(task['offset']) < len(content):
                task = self.chunk(task, content, int(task['offset']))
            result = self.finish(task)
            asset = result['asset_id']
            downloaded = self.admin.get(self.url + f'assets/{asset}/original', timeout=20)
            self.check(downloaded.content == content, 'downloaded original byte-identical after restart and resume')
            ranged = self.admin.get(self.url + f'assets/{asset}/original', headers={'Range': 'bytes=100-299'})
            self.check(ranged.status_code == 206 and ranged.content == content[100:300], 'Range download byte-identical')
            self.check('Content-Range' in ranged.headers, 'Range response includes Content-Range')
            again = self.post(f'uploads/{task["id"]}/complete', status=202)
            self.check(again['asset_id'] == asset, 'complete retry returns same asset')
            duplicate, _ = self.create(content, name='相同内容-换个名字.jpg', session=phone)
            self.check(duplicate['state'] == 'READY' and duplicate['asset_id'] == asset, 'same content from second account reuses shared original')
            altered = os.urandom(8192)
            separate, _ = self.create(altered, name='中文照片.jpg')
            separate = self.chunk(separate, altered, 0)
            separate = self.finish(separate)
            self.check(separate['asset_id'] != asset, 'same name different content retains both')
            self.check(phone.get(self.url + f'uploads/{task["id"]}').status_code == 403, 'member cannot inspect another account upload')
            empty, _ = self.create(b'', name='空白文档.txt')
            empty = self.finish(empty)
            self.check(self.admin.get(self.url + f'assets/{empty["asset_id"]}/original').content == b'', 'zero-byte file roundtrip')
            # Real image exercises the actual Windows codec and thumbnail pipeline.
            from PIL import Image
            image = self.home / '样本.png'
            Image.new('RGB', (800, 400), (32, 130, 95)).save(image)
            png = image.read_bytes()
            picture, _ = self.create(png, name='预览方向.png')
            picture = self.chunk(picture, png, 0)
            picture = self.finish(picture)
            for _ in range(100):
                thumb = self.admin.get(self.url + f'assets/{picture["asset_id"]}/thumbnail')
                if thumb.status_code == 200:
                    break
                time.sleep(.1)
            import io
            thumbnail = Image.open(io.BytesIO(thumb.content))
            self.check(thumbnail.size == (640, 320), 'WIC thumbnail size and aspect ratio')
            from PIL import ImageDraw, ImageOps
            for orientation in range(1, 9):
                source = Image.new('RGB', (80, 48))
                draw = ImageDraw.Draw(source)
                for box, color in [((0,0,39,23),'red'),((40,0,79,23),'green'),((0,24,39,47),'blue'),((40,24,79,47),'yellow')]:
                    draw.rectangle(box, fill=color)
                exif = Image.Exif(); exif[274] = orientation
                source_file = self.home / ('orientation-' + str(orientation) + '.jpg')
                source.save(source_file, quality=98, exif=exif)
                payload = source_file.read_bytes()
                oriented, _ = self.create(payload, name=source_file.name)
                oriented = self.chunk(oriented, payload, 0)
                oriented = self.finish(oriented)
                for _ in range(100):
                    response = self.admin.get(self.url + f'assets/{oriented["asset_id"]}/thumbnail')
                    if response.status_code == 200: break
                    time.sleep(.05)
                actual = Image.open(io.BytesIO(response.content)).convert('RGB')
                expected = ImageOps.exif_transpose(Image.open(source_file)).convert('RGB')
                equal = actual.size == expected.size
                if equal:
                    for x,y in [(5,5),(actual.width-6,5),(5,actual.height-6),(actual.width-6,actual.height-6)]:
                        equal &= max(abs(a-b) for a,b in zip(actual.getpixel((x,y)),expected.getpixel((x,y)))) < 35
                self.check(equal, f'EXIF orientation {orientation} matches Pillow reference')
            # Inject a real COMMITTING crash point: file moved but final DB state not updated.
            recovery_bytes = os.urandom(13017)
            interrupted, _ = self.create(recovery_bytes, name='入库恢复.bin')
            interrupted = self.chunk(interrupted, recovery_bytes, 0)
            self.stop()
            recovered_asset = uuid.uuid4().hex + uuid.uuid4().hex[:16]
            relative = 'storage/originals/recovery/' + recovered_asset + '.bin'
            target = self.home / '数据' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            (self.home/'数据/storage/uploads'/(interrupted['id']+'.part')).rename(target)
            with sqlite3.connect(self.home/'数据/catalog.db') as db:
                db.execute("INSERT INTO assets(id,sha256,size,path,name,mime,category,created,modified,state) VALUES(?,?,?,?,?,?,?,?,?,'COMMITTING')",
                           (recovered_asset,hashlib.sha256(recovery_bytes).hexdigest(),len(recovery_bytes),relative,'入库恢复.bin','application/octet-stream','file',int(time.time()*1000),0))
                db.execute("UPDATE uploads SET state='COMMITTING',asset_id=? WHERE id=?",(recovered_asset,interrupted['id']))
            self.start()
            for _ in range(100):
                recovered = self.admin.get(self.url+f'uploads/{interrupted["id"]}').json()
                if recovered['state'] in ('READY','ERROR'): break
                time.sleep(.05)
            self.check(recovered['state']=='READY' and recovered['asset_id']==recovered_asset,'COMMITTING crash after rename is recovered')
            self.check(self.admin.get(self.url+f'assets/{recovered_asset}/original').content==recovery_bytes,'recovered commit downloads byte-identically')
            # Removing a server original must not result in a false successful dedup response.
            target.unlink()
            _, same_request = self.create(b'repair-probe', name='保留测试.bin')
            missing_request = dict(client_task_id=interrupted['client_task_id'],name=interrupted['name'],size=interrupted['size'],sha256=interrupted['sha256'],source_key='recovery-probe')
            missing = self.post('uploads',missing_request,status=201)
            self.check(missing['state']=='ERROR' and missing['error']=='ORIGINAL_MISSING','reselect does not claim success for missing original')
            large, _ = self.create(b'', name='大于4GiB.mp4', size=str(5*1024**3), sha256='a'*64)
            self.check(large['size'] == str(5*1024**3), 'upload protocol preserves size beyond 32-bit')
            self.post(f'uploads/{large["id"]}/complete', status=409)
            cancelled = self.post(f'uploads/{large["id"]}/cancel')
            self.check(cancelled['state']=='CANCELLED','cancel releases unfinished large upload')
            self.check(self.post(f'uploads/{large["id"]}/cancel')['state']=='CANCELLED','cancel retry is idempotent')
            self.post(f'uploads/{task["id"]}/cancel',status=409)
            self.check(not (self.home/'数据/storage/uploads'/(large['id']+'.part')).exists(),'cancel removes only its temporary file')
            self.check(readonly.get(self.url+f'assets/{asset}/original').content==content,'read-only account can download shared original')
            self.post(f'uploads/{task["id"]}/complete',session=readonly,status=403)
            self.post(f'uploads/{large["id"]}/cancel',session=readonly,status=403)
            self.chunk(task,content,0,session=readonly,status=403)
            self.check('data_dir' not in readonly.get(self.url+'overview').json(),'non-admin overview hides physical storage path')
            self.post('users/'+member['id'],{'enabled':False})
            self.check(phone.get(self.url+'assets').status_code==401,'disabled account session immediately loses access')
            self.post('login',{'username':'family_member','password':member_password},session=requests.Session(),status=401)
            self.check(self.admin.get(self.url+f'assets/{asset}/original').content==content,'disabling account retains originals')
            self.post('users/'+member['id'],{'enabled':True})
            self.post('login',{'username':'family_member','password':member_password},session=phone)
            new_password='member-reset-password-2026'
            self.post('users/'+member['id'],{'new_password':new_password})
            self.check(phone.get(self.url+'me').status_code==401,'admin password reset revokes previous sessions')
            self.post('login',{'username':'family_member','password':member_password},session=requests.Session(),status=401)
            self.post('login',{'username':'family_member','password':new_password},session=phone)
            self.post('users/'+member['id'],{'role':'viewer'})
            self.check(phone.get(self.url+'me').status_code==401,'role change revokes old sessions')
            self.post('login',{'username':'family_member','password':new_password},session=phone)
            self.post('uploads',{},session=phone,status=403)
            self.check(phone.get(self.url+'me').json()['role']=='viewer','demoted member is read-only on new login')
            self.post('users/'+member['id'],{'role':'member'})
            self.post('login',{'username':'family_member','password':new_password},session=phone)
            other_session=requests.Session()
            self.post('login',{'username':'family_member','password':new_password},session=other_session)
            own_password='self-changed-password-2026'
            self.post('password',{'old_password':new_password,'new_password':own_password},session=phone)
            self.check(phone.get(self.url+'me').status_code==401 and other_session.get(self.url+'me').status_code==401,'self password change invalidates all sessions of that account')
            native=self.post('login',{'username':'family_member','password':own_password,'client':'native'},session=phone)
            self.check('token' in native and len(native['token'])==64,'native login can obtain bearer token')
            bearer=requests.get(self.url+'me',headers={'Authorization':'Bearer '+native['token']})
            self.check(bearer.status_code==200 and bearer.json()['username']=='family_member','native bearer authentication')
            self.check(all('salt' not in x and 'password_hash' not in x for x in self.admin.get(self.url+'users').json()),'account list does not disclose password hashes')
            self.stop()
            self.start()
            self.check(self.admin.get(self.url + f'assets/{asset}/original').content == content, 'committed originals survive second abrupt restart')
            report = {'checks': self.checks, 'count': len(self.checks), 'scope': 'Windows x64 username/password roles and HTTP/TLS backup recovery; no physical Android verification'}
            (self.home / 'PASS.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            print('RESULT:', len(self.checks), 'checks; evidence:', self.home)
        finally:
            self.stop()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--exe', type=Path, default=ROOT / 'out/Release/NasServer.exe')
    args = parser.parse_args()
    Harness(args.exe).run()
