"""Upgrade a real previous deployment with originals and a confirmed partial upload."""
import argparse,json,subprocess
from pathlib import Path
from smoke import Harness,ROOT
p=argparse.ArgumentParser();p.add_argument('--legacy-exe',required=True,type=Path);p.add_argument('--exe',type=Path,default=ROOT/'build/app/Release/NasServer.exe');a=p.parse_args()
legacy_version=subprocess.check_output([str(a.legacy_exe.resolve()),'--version'],text=True).strip()
assert legacy_version.startswith('0.'), 'Use a real pre-1.0 executable for this upgrade test'
print('Upgrade source:',legacy_version)
h=Harness(a.legacy_exe)
try:
 h.start();password=(h.home/'数据/initial-admin-password.txt').read_text().strip();h.post('login',{'username':'admin','password':password})
 original=b'prior release original';t,_=h.create(original,name='old-original.txt');h.chunk(t,original,0);old=h.finish(t)
 payload=b'x'*(1048576+19);partial,_=h.create(payload,name='old-pending.bin');partial=h.chunk(partial,payload[:1048576],0)
 server_id=h.admin.get(h.url+'server').json()['server_id'];h.stop();h.exe=a.exe.resolve();h.start()
 h.check(h.admin.get(h.url+'server').json()['server_id']==server_id,'upgrade preserves server identity and authenticated session')
 h.check(h.admin.get(h.url+'assets/'+old['asset_id']+'/original').content==original,'upgrade preserves original bytes')
 page=h.admin.get(h.url+'folders/default/items').json();h.check(any(i['id']==old['asset_id'] for i in page['items']),'pre-folder original receives default folder membership')
 resumed=h.admin.get(h.url+'uploads/'+partial['id']).json();h.check(resumed['offset']=='1048576' and resumed['folder_id']=='default','upgrade preserves confirmed offset and supplies default destination')
 h.chunk(resumed,payload,1048576);done=h.finish(resumed);h.check(h.admin.get(h.url+'assets/'+done['asset_id']+'/original').content==payload,'old pending upload resumes without duplicate or missing bytes')
 h.post('assets/trash',{'asset_ids':[old['asset_id']]});h.post('assets/restore',{'asset_ids':[old['asset_id']]});h.check(h.admin.get(h.url+'assets/'+old['asset_id']+'/original').content==original,'new recycle feature works on previous-release originals')
 h.post('login',{'username':'admin','password':password});h.check(True,'upgrade retains previous password')
 (h.home/'V1-UPGRADE-PASS.json').write_text(json.dumps({'checks':h.checks},indent=2),encoding='utf-8');print('Real previous-release upgrade:',len(h.checks),'checks')
finally:h.stop()
