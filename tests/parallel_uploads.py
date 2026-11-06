"""Three independent senders resume and verify synthetic 5MiB JPEGs on an isolated NAS."""
from smoke import Harness, ROOT
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import os
import threading
import time
import requests
from PIL import Image

h = Harness(ROOT/'build/app/Release/NasServer.exe')
try:
    h.start()
    h.post('login', {'username':'admin','password':(h.home/'数据/initial-admin-password.txt').read_text().strip()})
    sessions=[]
    for _ in range(3):
        session=requests.Session();session.cookies.update(h.admin.cookies);sessions.append(session)
    base=io.BytesIO();Image.new('RGB',(64,64),(40,110,70)).save(base,format='JPEG')
    payloads=[base.getvalue()+os.urandom(5*1024*1024-len(base.getvalue())) for _ in range(3)]
    barrier=threading.Barrier(3)
    def send(index):
        session=sessions[index];payload=payloads[index]
        task,creation=h.create(payload,name=f'parallel-fixture-{index}.jpg',session=session)
        barrier.wait(timeout=30)
        for offset in (0,1048576):
            task=h.chunk(task,payload,offset,session=session)
        # Simulate a fresh sender after pause: repeat the same idempotent creation.
        resumed=h.post('uploads',creation,session=session,status=201)
        assert resumed['id']==task['id'] and int(resumed['offset'])==2097152
        for offset in range(int(resumed['offset']),len(payload),1048576):
            resumed=h.chunk(resumed,payload,offset,session=session)
        h.post(f'uploads/{resumed["id"]}/complete',session=session,status=202)
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            result=session.get(h.url+f'uploads/{resumed["id"]}',timeout=10).json()
            if result['state']=='READY':
                data=session.get(h.url+'assets/'+result['asset_id']+'/original',timeout=15).content
                assert data==payload and hashlib.sha256(data).hexdigest()==creation['sha256']
                return result
            assert result['state']!='ERROR',result
            time.sleep(.1)
        raise TimeoutError('Concurrent verification did not finish')
    with ThreadPoolExecutor(max_workers=3) as pool:
        results=list(pool.map(send,range(3)))
    h.check(len({row['asset_id'] for row in results})==3,'three parallel 5MiB photos resume independently and preserve exact originals')
    h.check(all(int(row['offset'])==5*1024*1024 and row['state']=='READY' for row in results),'all files become complete only after server READY')
    h.stop();h.start()
    h.check(all(h.admin.get(h.url+'assets/'+row['asset_id']+'/original',timeout=15).content==payloads[i] for i,row in enumerate(results)),'parallel originals and confirmations survive isolated server restart')
    print('Parallel upload integration:',len(h.checks),'checks (local loopback, not phone speed measurements)')
finally:
    h.stop()
