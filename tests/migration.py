"""Exercise a real v0.1 database with the v0.1 executable, then upgrade to v0.2."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import requests
from smoke import Harness, ROOT


def run(legacy, current):
    version=subprocess.check_output([str(legacy.resolve()),'--version'],text=True).strip()
    if not version.startswith('0.1.'):
        raise RuntimeError('A real 0.1 executable is required for this migration test')
    h=Harness(legacy)
    # The v0.1 executable rejects config fields introduced after its release.
    config=json.loads(h.config.read_text(encoding='utf-8'))
    config.pop('allow_external_hosts',None)
    config.pop('allow_insecure_lan',None)
    config.pop('bootstrap_admin_password',None)
    config.pop('ffmpeg_path',None)
    h.config.write_text(json.dumps(config,ensure_ascii=False),encoding='utf-8')
    h.start()
    try:
        initial=(h.home/'数据/initial-admin-password.txt').read_text().strip()
        h.post('login',{'password':initial})
        password='legacy-migration-password-2026'
        h.post('password',{'old_password':initial,'new_password':password})
        h.post('login',{'password':password})
        identity=requests.get(h.url+'server').json()['server_id']
        certificate=hashlib.sha256((h.home/'数据/server-cert.pem').read_bytes()).hexdigest()
        completed=b'old-admin-original'*1000
        ready,_=h.create(completed,name='旧版已完成.bin')
        ready=h.chunk(ready,completed,0)
        ready=h.finish(ready)
        code=h.post('pairing')['code']
        old_phone=requests.Session()
        h.post('pair',{'name':'旧版配对手机','code':code},session=old_phone)
        partial=b'legacy-phone-resume'*150000
        task,creation=h.create(partial,name='旧版手机待续传.bin',session=old_phone)
        task=h.chunk(task,partial,0,session=old_phone)
        offset=task['offset']
        h.stop()
        h.exe=current.resolve()
        h.start()
        h.check(h.admin.get(h.url+'me').status_code==401,'upgrade invalidates legacy admin sessions')
        h.check(old_phone.get(h.url+'me').status_code==401,'upgrade invalidates legacy paired device tokens')
        h.post('login',{'username':'admin','password':password})
        h.check(requests.get(h.url+'server').json()['server_id']==identity,'server identity preserved across account migration')
        h.check(hashlib.sha256((h.home/'数据/server-cert.pem').read_bytes()).hexdigest()==certificate,'TLS identity preserved across migration')
        h.check(not (h.home/'数据/initial-admin-password.txt').exists(),'upgrade does not reset custom administrator password')
        h.check(h.admin.get(h.url+f'assets/{ready["asset_id"]}/original').content==completed,'legacy completed original retained')
        resumed=h.post('uploads',creation,status=201)
        h.check(resumed['id']==task['id'] and resumed['offset']==offset,'legacy paired-device task ID and acknowledged offset retained under admin')
        while int(resumed['offset'])<len(partial):resumed=h.chunk(resumed,partial,int(resumed['offset']))
        resumed=h.finish(resumed)
        h.check(h.admin.get(h.url+f'assets/{resumed["asset_id"]}/original').content==partial,'legacy partial upload resumes and downloads byte-identically')
        with sqlite3.connect(h.home/'数据/catalog.db') as db:
            h.check(db.execute('PRAGMA user_version').fetchone()[0]==2,'schema upgraded to version 2')
            h.check(db.execute("SELECT COUNT(*) FROM credentials c LEFT JOIN users u ON u.id=c.device_id WHERE u.id IS NULL").fetchone()[0]==0,'no active orphan legacy credentials')
        h.stop();h.start()
        h.check(h.admin.get(h.url+'me').status_code==200,'new account sessions survive subsequent restarts')
        report={'count':len(h.checks),'checks':h.checks,'legacy_version':version,'scope':'real v0.1 app creates fixture; v0.2 upgrades isolated data directory'}
        (h.home/'MIGRATION-PASS.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print('MIGRATION RESULT:',len(h.checks),'checks;',h.home)
    finally:h.stop()


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--legacy-exe',required=True,type=Path)
    p.add_argument('--exe',type=Path,default=ROOT/'out/Release/NasServer.exe')
    args=p.parse_args();run(args.legacy_exe,args.exe)
