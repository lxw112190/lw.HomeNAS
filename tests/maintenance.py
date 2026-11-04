"""Isolated maintenance endpoint roundtrips; synthetic APK bytes are never installed."""
from smoke import Harness,ROOT
import requests,json,hashlib,io,zipfile,base64,argparse
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--exe',type=Path,default=ROOT/'out/Release/NasServer.exe');h=Harness(parser.parse_args().exe)
try:
 h.start();password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip();h.post('login',{'username':'admin','password':password})
 catalog=requests.get(h.url+'client/latest').json();h.check(catalog=={'apk':None,'ui':None},'unpublished download state is explicit and public')
 fake=b'PK synthetic APK fixture, not installable';route=h.url+'admin/client/apk/blob'
 h.check(requests.post(route,data=fake).status_code==401,'anonymous package publishing is rejected')
 blob=h.admin.post(route,data=fake).json();h.check(blob['sha256']==hashlib.sha256(fake).hexdigest(),'package upload is hashed by real server')
 h.post('admin/client/apk/publish',{**blob,'versionCode':6,'versionName':'test-only','notes':'fixture'})
 latest=requests.get(h.url+'client/latest').json()['apk'];h.check(latest['versionCode']==6,'published APK metadata is publicly readable')
 h.check(requests.get(h.url+'client/packages/'+blob['sha256']+'.apk').content==fake,'web APK download is byte-identical before login')
 envelope=json.loads((ROOT/'deliveries/ui/release.json').read_text());revision=json.loads(base64.b64decode(envelope['payload']))['revision'];ui=(ROOT/f'deliveries/ui/ui-r{revision}.zip').read_bytes()
 h.check(h.admin.post(h.url+'admin/client/ui/blob',data=ui).status_code==200,'signed UI archive upload succeeds')
 h.post('admin/client/ui/publish',envelope);h.check(requests.get(h.url+'client/latest').json()['ui']==envelope,'resource signature envelope is preserved unchanged')
 archive=io.BytesIO()
 with zipfile.ZipFile(archive,'w') as z:z.writestr('diagnostics.json','{"test":true}')
 report=h.admin.post(h.url+'diagnostics',data=archive.getvalue(),headers={'X-NAS-Note-Base64':base64.b64encode('上传问题测试'.encode()).decode()});h.check(report.status_code==201,'diagnostic ZIP receives report identifier')
 h.check(report.json()['description']=='上传问题测试','Unicode problem description survives upload');
 ident=report.json()['id'];h.check(requests.get(h.url+'admin/diagnostics').status_code==401,'diagnostic reports are not public')
 h.check(any(x['id']==ident for x in h.admin.get(h.url+'admin/diagnostics').json()),'administrator can list uploaded reports')
 h.check(h.admin.get(h.url+'admin/diagnostics/'+ident).content==archive.getvalue(),'diagnostic ZIP download preserves bytes')
 h.check(h.admin.get(h.url+'assets').json()['items']==[],'diagnostic logs do not enter shared media library')
 h.post('users',{'username':'support_viewer','display_name':'viewer','role':'viewer','password':'ViewerOnly1234'},status=201)
 viewer=requests.Session();h.post('login',{'username':'support_viewer','password':'ViewerOnly1234'},session=viewer)
 h.check(viewer.get(h.url+'admin/diagnostics').status_code==403 and viewer.post(route,data=fake).status_code==403,'non-admin cannot read reports or publish packages')
 h.check(h.admin.delete(h.url+'admin/diagnostics/'+ident).status_code==200,'administrator can delete report')
 h.check(h.admin.delete(h.url+'admin/client/apk').status_code==200 and requests.get(h.url+'client/latest').json()['apk'] is None,'administrator can withdraw APK release')
 (h.home/'MAINTENANCE-PASS.json').write_text(json.dumps({'count':len(h.checks),'checks':h.checks},indent=2),encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
