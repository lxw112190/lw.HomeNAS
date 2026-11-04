"""Deployment bootstrap affects only a fresh data directory, never an existing account."""
import json,requests
from smoke import Harness,ROOT
h=Harness(ROOT/'out/Release/NasServer.exe')
try:
 config=json.loads(h.config.read_text(encoding='utf-8'));config.update(bootstrap_admin_password='admin123',allow_external_hosts=True);h.config.write_text(json.dumps(config),encoding='utf-8');h.start()
 h.post('login',{'username':'admin','password':'admin123'});h.check(h.admin.get(h.url+'me').status_code==200,'fresh deployment accepts public bootstrap password')
 h.check(h.admin.get(h.url+'server',headers={'Host':'public.example:8080'}).status_code==200,'deployment external Host flag is effective')
 h.post('password',{'old_password':'admin123','new_password':'changed123'});h.stop();h.start();h.post('login',{'username':'admin','password':'admin123'},session=requests.Session(),status=401);h.post('login',{'username':'admin','password':'changed123'});h.check(True,'configured bootstrap cannot reset an existing password on restart')
 h.check(not (h.home/'数据/initial-admin-password.txt').exists(),'password change removes initial password file')
 (h.home/'BOOTSTRAP-PASS.json').write_text(json.dumps({'checks':h.checks}),encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
