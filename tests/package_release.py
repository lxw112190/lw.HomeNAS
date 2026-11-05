"""Exercise the distributable ZIP in a private test directory; never install SCM/firewall rules."""
import argparse,base64,hashlib,io,json,shutil,subprocess,time,zipfile
from pathlib import Path
import requests
from PIL import Image
from smoke import Harness,ROOT


def run(archive):
    with zipfile.ZipFile(archive) as z:
        names=z.namelist();assert len(names)==len(set(names)) and z.testzip() is None
        top={Path(n).parts[0] for n in names};assert len(top)==1
        for name in names:
            parts=Path(name).parts
            assert '..' not in parts and not Path(name).is_absolute()
            assert not any(p in ('data','.git','local-signing','test-results') for p in parts)
            assert not name.lower().endswith(('.jks','.keystore','.pem','.p12','.pfx','catalog.db'))
        h=Harness(ROOT/'build/app/Release/NasServer.exe');clone=h.home/'完整部署'
        z.extractall(clone)  # Only prevalidated paths inside this test's new directory.
    stage=clone/next(iter(top));h.exe=stage/'NasServer.exe';h.config=stage/'config/server.json'
    config=json.loads(h.config.read_text(encoding='utf-8'))
    assert config['allow_external_hosts'] is True and config['allow_insecure_lan'] is True and config['bootstrap_admin_password']=='admin123'
    assert all(not Path(config[k]).is_absolute() for k in ('data_dir','web_dir','ffmpeg_path'))
    config.update(http_port=h.http,https_port=h.https,https_host='127.0.0.1');h.config.write_text(json.dumps(config,ensure_ascii=False),encoding='utf-8')
    expected=json.loads((stage/'client-release/apk.json').read_text(encoding='utf-8'));script=stage/'deploy/import_android.ps1'
    def import_apk(ok=True,plan=False):
        args=['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(script)]+(['-PlanOnly'] if plan else [])
        p=subprocess.run(args,capture_output=True);assert (p.returncode==0)==ok,p.stderr[:1000];return p
    try:
        baseline=json.loads((stage/'android/signing-certificate.json').read_text(encoding='utf-8'));verification=json.loads((stage/'client-release/verification.json').read_text(encoding='utf-8'))
        h.check(baseline['certificate_sha256']==verification['certificate_sha256'],'fixed public signing baseline is included')
        h.check(json.loads(import_apk(plan=True).stdout)['VersionCode']==expected['versionCode'],'offline import plan accepts bundled APK')
        import_apk();import_apk();h.check(True,'offline import is repeatable in a Unicode path')
        h.start();h.post('login',{'username':'admin','password':'admin123'});h.check(h.admin.get(h.url+'me').status_code==200,'fresh deployment uses documented admin password')
        h.check(requests.get(h.url+'server').json()['insecure_lan'] is True,'plain startup follows LAN HTTP default')
        h.check(requests.get(h.url+'server',headers={'Host':'public.example:8080'}).status_code==200,'external Host default is enabled')
        latest=requests.get(h.url+'client/latest').json();h.check(latest['apk']==expected,'public APK catalog matches verified metadata')
        apk=requests.get(h.url+'client/packages/'+expected['sha256']+'.apk').content
        h.check(len(apk)==int(expected['size']) and hashlib.sha256(apk).hexdigest()==expected['sha256'],'public APK download preserves size and hash')
        if (stage/'client-release/ui.json').exists():
            manifest=json.loads(base64.b64decode(latest['ui']['payload']));ui=requests.get(h.url+'client/packages/'+manifest['sha256']+'.zip').content
            h.check(hashlib.sha256(ui).hexdigest()==manifest['sha256'],'signed UI download matches catalog')
        else:h.check(latest['ui'] is None,'complete APK builtin UI needs no separate signing secret')
        h.check(b'id="android-download"' in requests.get(h.url.removesuffix('api/v1/')+'views/login.html').content,'login page includes APK download entry')
        ffmpeg=(h.config.parent/config['ffmpeg_path']).resolve();h.check(ffmpeg.is_file() and ffmpeg.is_relative_to(stage.resolve()),'FFmpeg resolves through relative config inside package')
        sample=h.home/'封面验收.mp4';subprocess.run([str(ffmpeg),'-v','error','-f','lavfi','-i','color=c=orange:s=320x180:r=12','-t','1','-c:v','mpeg4','-threads','1','-y',str(sample)],check=True,timeout=30)
        payload=sample.read_bytes();task,_=h.create(payload,name=sample.name);h.chunk(task,payload,0);task=h.finish(task);asset=task['asset_id']
        for _ in range(200):
            response=h.admin.get(h.url+f'assets/{asset}/thumbnail')
            if response.status_code==200:break
            time.sleep(.1)
        response.raise_for_status();picture=Image.open(io.BytesIO(response.content));h.check(picture.format=='JPEG' and max(picture.size)<=640,'bundled FFmpeg creates a real video cover')
        album=h.post('albums',{'name':'完整包图集验收'},status=201);path='albums/'+album['id'];h.post(path+'/add',{'asset_ids':[asset]});h.post(path+'/cover',{'asset_id':asset})
        detail=h.admin.get(h.url+path+'/items').json();h.check(detail['album']['cover']['id']==asset and len(detail['items'])==1,'album membership and video cover work')
        h.post(path+'/delete');h.check(h.admin.get(h.url+f'assets/{asset}/original').content==payload,'album deletion preserves original bytes')
        import_apk(False);h.check(True,'offline import refuses a running instance')
        h.post('password',{'old_password':'admin123','new_password':'changed123'});h.stop();import_apk();h.start()
        h.post('login',{'username':'admin','password':'admin123'},session=requests.Session(),status=401);h.post('login',{'username':'admin','password':'changed123'});h.check(True,'password survives reimport and restart')
        report={'checks':h.checks,'count':len(h.checks),'archive':archive.name,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'physical_device_tested':False}
        (ROOT/'test-results/package-release-pass.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print('Package acceptance:',len(h.checks),'checks; evidence:',h.home)
    finally:h.stop()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--archive',required=True,type=Path);run(p.parse_args().archive.resolve())
