"""Real EXIF/date paging, private snapshots, QR and offline migration on synthetic data."""
import contextlib
import datetime
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import time
import zipfile
import requests
from PIL import Image
from smoke import Harness, ROOT

h = Harness(ROOT/'build/app/Release/NasServer.exe')


def command(*args, ok=True):
    p = subprocess.run([str(ROOT/'build/app/Release/NasServer.exe'), '--config', str(h.config), *map(str,args)], capture_output=True, timeout=120)
    assert (p.returncode == 0) == ok, p.stdout.decode('utf-8',errors='replace')+p.stderr.decode('utf-8',errors='replace')
    return p


def photo(name, taken=None, modified=1700000000000):
    stream=io.BytesIO();exif=Image.Exif()
    if taken:exif[34665]={36867:taken,36881:'+08:00'}
    Image.new('RGB',(32,24),(len(name)*5%255,40,160)).save(stream,format='JPEG',exif=exif)
    data=stream.getvalue();task,_=h.create(data,name=name,modified=str(modified));h.chunk(task,data,0);return h.finish(task)['asset_id'], data


try:
    h.start();password=(h.home/'数据/initial-admin-password.txt').read_text(encoding='utf-8').strip();h.post('login',{'username':'admin','password':password})
    capture, original=photo('2015-trip.jpg','2015:04:05 12:30:00')
    fallback,_=photo('no-exif.jpg',modified=1550000000000)
    invalid,_=photo('invalid-date.jpg','2019:02:30 12:30:00',modified=1560000000000)
    for _ in range(100):
        rows=h.admin.get(h.url+'assets',params={'order':'timeline'}).json()['items']
        by_id={item['id']:item for item in rows}
        if by_id.get(capture,{}).get('time_source')=='exif':break
        time.sleep(.1)
    expected=int(datetime.datetime(2015,4,5,12,30,tzinfo=datetime.timezone(datetime.timedelta(hours=8))).timestamp()*1000)
    h.check(int(by_id[capture]['taken'])==expected and int(by_id[capture]['timeline_time'])==expected,'actual EXIF capture time and offset override newer file time')
    h.check(by_id[fallback]['time_source']=='file' and by_id[invalid]['time_source']=='file','missing and invalid EXIF dates fall back to file time')
    result=h.admin.get(h.url+'assets',params={'order':'timeline','from':str(expected),'to':str(expected+1000)}).json()
    h.check([r['id'] for r in result['items']]==[capture] and result['timeline_version']==2,'date range uses capture time with exclusive upper bound')
    months=h.admin.get(h.url+'assets/calendar',params={'offset':480}).json()
    h.check(any(x['month']=='2015-04' and x['count']=='1' for x in months['items']),'year/month calendar locates old photos')
    h.check(h.admin.get(h.url+'assets/calendar?offset=99999').status_code==400,'calendar rejects invalid timezone ranges')
    folder=h.post('folders',{'name':'旧照片归档'},status=201);h.post('folders/'+folder['id']+'/add',{'asset_ids':[capture]})
    album=h.post('albums',{'name':'旅行主题'},status=201);h.post('albums/'+album['id']+'/add',{'asset_ids':[capture]})
    info=h.admin.get(h.url+'folders/'+folder['id']).json()
    h.check(info['photo_count']=='1' and info['video_count']=='0' and int(info['total_bytes'])==len(original),'folder statistics reflect active exact file bytes and media counts')
    h.check(requests.get(h.url+'connection').status_code==401,'connection QR requires login')
    choices=h.admin.get(h.url+'connection').json();h.check(choices['credentials_in_qr'] is False and choices['items'],'connection choices contain addresses without passwords or tokens')
    endpoint=choices['items'][0]['endpoint'];qr=h.admin.get(h.url+'connection/qr',params={'endpoint':endpoint})
    h.check(qr.status_code==200 and qr.headers['Content-Type']=='image/svg+xml' and '<path' in qr.text,'server generates a real QR SVG locally')
    h.check(h.admin.get(h.url+'connection/qr',params={'endpoint':'http://attacker.example/'}).status_code==400,'QR cannot encode arbitrary external endpoints')
    h.post('users',{'username':'readonly_backup','password':'test123','display_name':'viewer','role':'viewer'},status=201);viewer=requests.Session();h.post('login',{'username':'readonly_backup','password':'test123'},session=viewer)
    h.post('admin/backups',{},session=viewer,status=403);h.check(True,'only an administrator may create metadata backups')
    pending_data=b'pending-resume-fixture'*900;pending,_=h.create(pending_data,name='pending-upload.bin');pending=h.chunk(pending,pending_data[:4096],0)
    backup=h.post('admin/backups',{},status=201);response=h.admin.get(h.url+'admin/backups/'+backup['id']+'/download');response.raise_for_status()
    extracted=h.home/'下载快照';extracted.mkdir()
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        h.check(archive.testzip() is None and set(archive.namelist())=={'catalog.db','config/server.json','backup.json'},'one-click ZIP contains a consistent catalog, config and manifest, without originals')
        archive.extractall(extracted)
    with contextlib.closing(sqlite3.connect(extracted/'catalog.db')) as db:
        h.check(db.execute('PRAGMA quick_check').fetchone()[0]=='ok' and not db.execute('PRAGMA foreign_key_check').fetchall(),'snapshot database and classification relations are valid')
        h.check(db.execute('SELECT count(*) FROM credentials').fetchone()[0]==0 and db.execute('SELECT count(*) FROM users').fetchone()[0]==2,'snapshot retains accounts but removes login sessions')
    h.check('bootstrap_admin_password' not in json.loads((extracted/'config/server.json').read_text(encoding='utf-8')),'portable configuration omits plaintext bootstrap password')
    destination=h.home/'新硬盘';command('--migrate-to',destination,ok=False);h.check(not destination.exists(),'migration refuses an active library before creating destination')
    h.post('folders/'+folder['id']+'/delete',{})
    h.stop();command('--migrate-to',destination)
    verification=json.loads(command('--verify-library',destination).stdout.decode('utf-8'))
    h.check(verification['ok'] and verification['ready_assets']=='3' and verification['pending_uploads']=='1','full migration verifies every original, database and relation in a new empty directory')
    command('--migrate-to',destination,ok=False);h.check(True,'migration refuses overwriting a nonempty destination')
    restored=h.home/'恢复分类';command('--restore-backup',extracted,'--migrate-to',restored)
    with contextlib.closing(sqlite3.connect(restored/'data/catalog.db')) as db:
        h.check(db.execute('SELECT deleted FROM folders WHERE id=?',(folder['id'],)).fetchone()[0]==0 and db.execute('SELECT count(*) FROM album_items WHERE album_id=?',(album['id'],)).fetchone()[0]==1,'restore snapshot into another empty instance recovers folder and thematic album classifications')
        relative=db.execute('SELECT path FROM assets WHERE id=?',(capture,)).fetchone()[0]
        restored_task=db.execute('SELECT state,error,offset FROM uploads WHERE id=?',(pending['id'],)).fetchone()
        h.check(restored_task==('ERROR','RESTORED_METADATA_RESELECT',0),'metadata-only restoration marks unfinished tasks for reselection without claiming old confirmed bytes')
    h.exe=destination/'NasServer.exe';h.config=destination/'config/server.json';h.start()
    h.check(h.admin.get(h.url+'assets').status_code==401,'migrated instance requires a fresh login')
    h.post('login',{'username':'admin','password':password});h.check(h.admin.get(h.url+'assets/'+capture+'/original').content==original,'migrated executable starts independently and serves exact original bytes')
    continued=h.admin.get(h.url+'uploads/'+pending['id']).json();h.check(continued['offset']=='4096','full migration retains durable upload confirmation')
    continued=h.chunk(continued,pending_data,4096);finished=h.finish(continued);h.check(h.admin.get(h.url+'assets/'+finished['asset_id']+'/original').content==pending_data,'migrated partial upload resumes to exact READY bytes')
    h.stop()
    marker=restored/'migration.json';value=json.loads(marker.read_text(encoding='utf-8'));value['complete']=False;marker.write_text(json.dumps(value),encoding='utf-8')
    refusal=subprocess.run([str(restored/'NasServer.exe'),'--config',str(restored/'config/server.json')],capture_output=True,timeout=20)
    h.check(refusal.returncode!=0 and '迁移未完成' in refusal.stderr.decode('utf-8'),'an incomplete migration cannot be started')
    alternate=subprocess.run([str(ROOT/'build/app/Release/NasServer.exe'),'--config',str(restored/'config/server.json')],capture_output=True,timeout=20)
    h.check(alternate.returncode!=0 and '迁移未完成' in alternate.stderr.decode('utf-8'),'an old executable cannot bypass the incomplete target through --config')
    value['complete']=True;marker.write_text(json.dumps(value),encoding='utf-8')
    copied=restored/'data'/relative;copied.write_bytes(b'broken copy');command('--verify-library',restored,ok=False)
    h.check((h.home/'数据'/relative).read_bytes()==original,'tampered destination fails verification while source originals remain unchanged')
    (h.home/'LIBRARY-UPGRADE-PASS.json').write_text(json.dumps({'checks':h.checks,'count':len(h.checks),'physical_device_tested':False},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Library upgrade:',len(h.checks),'checks; evidence:',h.home)
finally:
    h.stop()
