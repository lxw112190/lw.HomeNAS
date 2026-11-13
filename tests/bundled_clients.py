"""Real isolated executable/startup/downloads. Synthetic APK/UI bytes are never installed."""
import base64,hashlib,json,shutil,requests
from smoke import Harness,ROOT

h=Harness(ROOT/'build/app/Release/NasServer.exe')
program=h.home/'程序 中文';program.mkdir();shutil.copy2(h.exe,program/'NasServer.exe');h.exe=program/'NasServer.exe'
source=program/'client-release';source.mkdir()
def bundle(code,data=None,ui=False):
 data=data or ('synthetic client '+str(code)).encode();digest=hashlib.sha256(data).hexdigest()
 metadata={'sha256':digest,'size':str(len(data))}
 if ui:
  metadata.update(revision=code,minNative=13)
  result={'payload':base64.b64encode(json.dumps(metadata).encode()).decode(),'signature':'fixture-not-a-real-signature'}
 else:result={**metadata,'versionCode':code,'versionName':'test-'+str(code),'notes':'synthetic, not installable'}
 (source/(digest+('.zip' if ui else '.apk'))).write_bytes(data)
 (source/('ui.json' if ui else 'apk.json')).write_text(json.dumps(result),encoding='utf-8')
 return result,data
def catalog():return requests.get(h.url+'client/latest',timeout=5).json()
def restart():h.stop();h.start()
try:
 h.start();h.check(catalog()=={'apk':None,'ui':None},'empty client-release does not block NAS')
 h.post('login',{'username':'admin','password':(h.home/'数据/initial-admin-password.txt').read_text().strip()})
 h.stop();first,data=bundle(1);ui,_=bundle(10,ui=True);h.start()
 h.check(catalog()=={'apk':first,'ui':ui},'startup discovers APK and optional UI from executable directory, independent of working directory')
 download=requests.get(h.url+'client/packages/'+first['sha256']+'.apk')
 h.check(download.content==data,'anonymous APK download is byte-identical after plain startup')
 h.check('HomeNAS-test-1.apk' in download.headers['Content-Disposition'],'APK download uses a short versioned filename')
 path=h.home/'数据/maintenance/apk.json';mtime=path.stat().st_mtime_ns;restart()
 h.check(path.stat().st_mtime_ns==mtime,'unchanged startup skips metadata publication')
 h.stop();edited=dict(first,notes='new release notes, same APK');(source/'apk.json').write_text(json.dumps(edited));h.start();h.check(catalog()['apk']==first,'same APK version preserves existing published metadata')
 h.stop();second,_=bundle(2);h.start();h.check(catalog()['apk']==second,'newer bundled APK automatically replaces catalog')
 h.stop();bundle(1);h.start();h.check(catalog()['apk']==second,'older bundled APK never downgrades current catalog')
 h.stop();bundle(2,b'different content same version');h.start();h.check(catalog()['apk']==second,'same-version different-content bundle is rejected while NAS stays available')
 h.stop();bad,_=bundle(3);(source/(bad['sha256']+'.apk')).write_bytes(b'tampered');h.start()
 h.check(catalog()['apk']==second,'corrupt bundle preserves published APK and NAS availability')
 h.stop();(source/'apk.json').write_text('{broken');h.start();h.check(catalog()['apk']==second,'malformed manifest does not prevent service startup')
 h.stop();bad,_=bundle(3);(source/(bad['sha256']+'.apk')).unlink();h.start();h.check(catalog()['apk']==second,'incomplete copy does not replace valid catalog')
 h.check(h.admin.delete(h.url+'admin/client/apk').status_code==200,'administrator withdraws APK')
 h.stop();bundle(2);h.start();h.check(catalog()['apk'] is None,'withdrawal survives restart with original bundled APK')
 h.stop();path.write_text(json.dumps(second));h.start();h.check(catalog()['apk'] is None,'durable withdrawal intent hides catalog left by interrupted deletion')
 h.stop();third,_=bundle(3);h.start();h.check(catalog()['apk']==third,'strictly newer bundled version may publish after withdrawal')
 web_data=b'web-published-newer';upload=h.admin.post(h.url+'admin/client/apk/blob',data=web_data).json()
 web={**upload,'versionCode':9,'versionName':'web-test','notes':'manual'};h.post('admin/client/apk/publish',web);restart()
 h.check(catalog()['apk']==web,'administrator-published newer APK survives old deployment bundle')
 h.admin.delete(h.url+'admin/client/apk');h.stop();bundle(4);h.start();h.check(catalog()['apk'] is None,'withdrawal high-water mark also blocks intermediate old versions')
 h.post('admin/client/apk/publish',web);restart();h.check(catalog()['apk']==web,'explicit manual republish clears withdrawal and survives restart')
 h.admin.delete(h.url+'admin/client/ui');restart();h.check(catalog()['ui'] is None,'UI withdrawal also survives restart')
 h.stop();new_ui,_=bundle(11,ui=True);h.start();h.check(catalog()['ui']==new_ui,'newer signed-envelope UI revision can be auto-published')
 h.stop();invalid=json.loads(base64.b64decode(new_ui['payload']));invalid.update(revision=12,minNative=999);new_ui['payload']=base64.b64encode(json.dumps(invalid).encode()).decode();(source/'ui.json').write_text(json.dumps(new_ui));h.start()
 h.check(catalog()['ui']!=new_ui,'unsupported UI protocol preserves existing UI catalog')
 h.check(h.admin.get(h.url+'me').status_code==200 and h.admin.get(h.url+'assets').json()['items']==[],'account/session and unrelated library survive all startup imports')
 h.check(not list((h.home/'数据/maintenance').glob('*.tmp')) and not list((h.home/'数据/maintenance/packages').glob('*.part')),'no incomplete temporary publication files remain')
 (h.home/'BUNDLED-CLIENTS-PASS.json').write_text(json.dumps({'checks':h.checks,'count':len(h.checks),'scope':'synthetic startup/download regression; not APK installation or signature verification'},ensure_ascii=False,indent=2),encoding='utf-8')
 print('Evidence:',h.home)
finally:h.stop()
