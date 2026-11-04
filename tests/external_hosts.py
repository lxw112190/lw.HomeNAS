"""Public/NAT Host tests on an isolated local server; never contacts a public server."""
import argparse
import json
from pathlib import Path
import subprocess
import requests
from smoke import Harness, ROOT


def run(exe):
    h=Harness(exe)
    authority='203.0.113.10:8080'
    headers={'Host':authority,'Origin':'http://'+authority}
    h.extra_args=['--allow-insecure-lan']
    config=json.loads(h.config.read_text(encoding='utf-8'))
    try:
        config.pop('allow_external_hosts',None)
        h.config.write_text(json.dumps(config),encoding='utf-8');h.start()
        response=requests.get(h.url+'server',headers=headers,timeout=10)
        h.check(response.status_code==400 and response.json()['code']=='HOST_REJECTED','missing setting retains local Host restriction')
        h.stop()
        config['allow_external_hosts']=True
        h.config.write_text(json.dumps(config),encoding='utf-8');h.start()
        response=requests.get(h.url+'server',headers=headers,timeout=10)
        h.check(response.status_code==200 and response.json()['insecure_lan'],'explicit external mode accepts public NAT Host')
        h.check(requests.get(h.url.replace('/api/v1/','/'),headers=headers,timeout=10).status_code==200,'public Host can load the actual web page')
        h.check(requests.get(h.url+'server',headers={'Host':'nas.example.test:8080'},timeout=10).status_code==200,'external mode accepts domain without maintaining an allowlist')
        h.check(requests.get(h.url+'me',headers=headers,timeout=10).status_code==401,'public Host still requires login for protected APIs')
        h.admin.headers.update(headers)
        password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip()
        login=h.post('login',{'username':'admin','password':password})
        h.check('token' not in login and login['username']=='admin','public web login succeeds without returning token to JavaScript')
        h.check(h.admin.get(h.url+'me',timeout=10).status_code==200,'public web cookie authenticates the next request')
        payload=b'public NAT host backup roundtrip'
        task,_=h.create(payload,name='public-test.txt');h.chunk(task,payload,0);ready=h.finish(task)
        h.check(h.admin.get(h.url+f'assets/{ready["asset_id"]}/original',timeout=10).content==payload,'public Host upload and original download preserve bytes')
        h.check(h.admin.post(h.url+'login',json={'username':'admin','password':password},headers={'Origin':'http://other.example.test'},timeout=10).status_code==403,'external mode retains same-origin request behavior')
        h.post('logout')
        h.check(h.admin.get(h.url+'me',timeout=10).status_code==401,'public web logout invalidates its cookie session')
        h.stop()
        config['allow_external_hosts']=False
        h.config.write_text(json.dumps(config),encoding='utf-8');h.start()
        h.check(requests.get(h.url+'server',headers=headers,timeout=10).status_code==400,'turning off external mode restores local Host restriction')
        h.stop()
        config['allow_external_hosts']='true'
        h.config.write_text(json.dumps(config),encoding='utf-8')
        rejected=subprocess.run([str(h.exe),'--config',str(h.config)],capture_output=True,timeout=15,creationflags=subprocess.CREATE_NO_WINDOW)
        h.check(rejected.returncode!=0 and b'allow_external_hosts must be true or false' in rejected.stderr,'non-boolean external setting is rejected clearly')
        report={'count':len(h.checks),'checks':h.checks,'scope':'local server with public/DNS Host headers; no real public deployment changed'}
        (h.home/'EXTERNAL-HOSTS-PASS.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('RESULT:',len(h.checks),'checks; evidence:',h.home)
    finally:h.stop()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--exe',type=Path,default=ROOT/'out/Release/NasServer.exe')
    run(parser.parse_args().exe)
