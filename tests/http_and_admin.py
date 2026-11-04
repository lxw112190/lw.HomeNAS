"""Real Windows binary checks, confined to a generated data directory; never installs a service."""
import json
import sqlite3
import subprocess
from pathlib import Path
import requests
from smoke import Harness, ROOT


def initialize(h, first, second=None):
    return subprocess.run([str(h.exe), '--config', str(h.config), '--initialize-admin'],
                          input=(first+'\n'+(first if second is None else second)+'\n').encode('utf-8'),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          creationflags=subprocess.CREATE_NO_WINDOW, timeout=30)


def run():
    h=Harness(ROOT/'out/Release/NasServer.exe')
    password='LocalTest1234'
    changed='NextLocal1234'
    try:
        result=initialize(h,password)
        h.check(result.returncode==0 and password.encode() not in result.stdout,'offline initialization accepts a chosen password without printing it')
        h.check(not (h.home/'数据/initial-admin-password.txt').exists(),'chosen password is not saved as an initial password text file')
        h.extra_args=['--allow-insecure-lan'];h.start()
        h.admin.headers['X-NAS-Trace']='testlogs-H42'
        h.check(requests.get(h.url+'server').json()['insecure_lan'] is True,'explicit HTTP LAN mode is reported by the real server')
        h.post('login',{'username':'admin','password':password})
        payload=b'HTTP LAN preserve original test'
        task,_=h.create(payload,name='http-original.txt');task=h.chunk(task,payload,0);ready=h.finish(task)
        member=h.post('users',{'username':'family_test','display_name':'Test member','role':'member','password':'MemberTest1234'},status=201)
        denied=initialize(h,changed)
        h.check(denied.returncode!=0,'offline password tool refuses a live data directory')
        h.stop()
        invalid=initialize(h,'short')
        h.check(invalid.returncode!=0,'offline tool retains the ordinary password length rule')
        mismatched=initialize(h,changed,'Different1234')
        h.check(mismatched.returncode!=0,'mismatched confirmation does not update the administrator')
        result=initialize(h,changed)
        h.check(result.returncode==0,'offline reset succeeds after stopping the server')
        h.start()
        h.check(h.admin.get(h.url+'me').status_code==401,'offline reset revokes previous administrator sessions')
        h.post('login',{'username':'admin','password':password},session=requests.Session(),status=401)
        h.post('login',{'username':'admin','password':changed})
        h.check(h.admin.get(h.url+f'assets/{ready["asset_id"]}/original').content==payload,'HTTP original survives administrator reset')
        members=h.admin.get(h.url+'users').json()
        h.check(any(x['id']==member['id'] for x in members),'reset preserves other accounts')
        h.post('login',{'username':'family_test','password':'MemberTest1234'},session=requests.Session())
        h.stop()
        logs=(h.home/'数据/logs/requests.log').read_text(encoding='utf-8')
        h.check('trace=testlogs-H42' in logs,'server request logs preserve the phone trace identifier')
        h.check(all(secret not in logs for secret in (password,changed,'MemberTest1234')),'server logs omit login and reset passwords')
        report={'count':len(h.checks),'checks':h.checks,'scope':'native console HTTP and offline local admin maintenance; no SCM installed'}
        (h.home/'HTTP-ADMIN-PASS.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print('RESULT:',len(h.checks),'checks;',h.home)
    finally:h.stop()


if __name__=='__main__':run()
