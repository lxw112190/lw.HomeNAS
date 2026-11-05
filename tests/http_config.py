"""Config and CLI precedence against real isolated listeners; no service/firewall mutation."""
import json,subprocess
import requests,psutil
from smoke import Harness,ROOT

h=Harness(ROOT/'out/Release/NasServer.exe')
base=json.loads(h.config.read_text(encoding='utf-8'))
try:
 cases=[(True,[],True),(False,[],False),(False,['--allow-insecure-lan'],True),
        (True,['--no-allow-insecure-lan'],False),(None,[],True),
        (True,['--allow-insecure-lan','--no-allow-insecure-lan'],False),
        (False,['--no-allow-insecure-lan','--allow-insecure-lan'],True)]
 for configured,args,expected in cases:
  config=dict(base)
  if configured is None:config.pop('allow_insecure_lan',None)
  else:config['allow_insecure_lan']=configured
  h.config.write_text(json.dumps(config),encoding='utf-8');h.extra_args=args;h.start()
  label=f'config={configured}, cli={args}'
  try:
   h.check(requests.get(h.url+'server',timeout=10).json()['insecure_lan']==expected,'HTTP mode: '+label)
   process=psutil.Process(h.process.pid);connections=getattr(process,'net_connections',None) or process.connections
   listeners=[c for c in connections(kind='tcp') if c.status==psutil.CONN_LISTEN and c.laddr.port==h.http]
   h.check(len(listeners)==1 and listeners[0].laddr.ip==('0.0.0.0' if expected else '127.0.0.1'),'physical HTTP bind address: '+label)
  finally:h.stop()
 config=dict(base);config['allow_insecure_lan']=True;h.config.write_text(json.dumps(config),encoding='utf-8');h.extra_args=[];h.start()
 h.check(requests.get(f'https://127.0.0.1:{h.https}/api/v1/server',verify=str(h.home/'数据/server-cert.pem'),timeout=10).json()['https'],'HTTPS remains available with LAN HTTP enabled');h.stop()
 for invalid in ['true',1,None,[]]:
  config=dict(base);config['allow_insecure_lan']=invalid;h.config.write_text(json.dumps(config),encoding='utf-8')
  p=subprocess.run([str(h.exe),'--config',str(h.config)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=10,creationflags=subprocess.CREATE_NO_WINDOW)
  h.check(p.returncode!=0 and b'allow_insecure_lan must be true or false' in p.stdout,'invalid HTTP config type rejected: '+repr(invalid))
 (h.home/'HTTP-CONFIG-PASS.json').write_text(json.dumps({'count':len(h.checks),'checks':h.checks},ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
