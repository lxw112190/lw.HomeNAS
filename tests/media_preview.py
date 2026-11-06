"""Real image metadata/video preview checks against an isolated Windows service."""
import argparse,hashlib,io,json,subprocess,time
from pathlib import Path
import requests
from PIL import Image,TiffImagePlugin
from smoke import Harness,ROOT

p=argparse.ArgumentParser();p.add_argument('--ffmpeg',type=Path,required=True);p.add_argument('--exe',type=Path,default=ROOT/'out/Release/NasServer.exe');args=p.parse_args();h=Harness(args.exe)
try:
 config=json.loads(h.config.read_text(encoding='utf-8'));config['ffmpeg_path']='./missing-ffmpeg.exe';h.config.write_text(json.dumps(config),encoding='utf-8');h.start();password=(h.home/'数据/initial-admin-password.txt').read_text().strip();h.post('login',{'username':'admin','password':password})
 image=Image.new('RGB',(120,60),'#87ad9b');exif=Image.Exif();exif[274]=6;exif[271]='HomeNAS Test';exif[272]='Synthetic Camera';exif[34665]={36867:'2026:11:04 12:34:56',33434:TiffImagePlugin.IFDRational(1,125),33437:TiffImagePlugin.IFDRational(28,10),34855:200,37386:TiffImagePlugin.IFDRational(35,1)}
 out=io.BytesIO();image.save(out,format='JPEG',exif=exif);jpeg=out.getvalue();task,_=h.create(jpeg,name='元数据-测试.jpg');task=h.chunk(task,jpeg,0);task=h.finish(task);ident=task['asset_id'];path=f'assets/{ident}/metadata'
 h.check(requests.get(h.url+path).status_code==401,'metadata requires authentication')
 data=h.admin.get(h.url+path).json();info=data['image'];h.check((info['width'],info['height'])==(60,120) and (info['raw_width'],info['raw_height'])==(120,60),'metadata distinguishes oriented and raw dimensions')
 h.check(info['exif']['taken_at']=='2026:11:04 12:34:56' and info['exif']['camera_model']=='Synthetic Camera','EXIF capture time and camera are read from original')
 h.check(abs(info['exif']['exposure_seconds']-.008)<1e-8 and abs(info['exif']['f_number']-2.8)<1e-8 and info['exif']['iso']==200,'EXIF rational exposure/aperture and ISO match original')
 h.check(data['sha256']==hashlib.sha256(jpeg).hexdigest() and 'path' not in data,'metadata includes original hash without physical path')
 video=h.home/'合成视频.mp4';subprocess.run([str(args.ffmpeg.resolve()),'-hide_banner','-loglevel','error','-f','lavfi','-i','color=c=orange:s=480x270:r=12','-t','1','-c:v','mpeg4','-threads','1','-y',str(video)],check=True,timeout=30)
 payload=video.read_bytes();task,_=h.create(payload,name='合成视频.mp4');task=h.chunk(task,payload,0);task=h.finish(task);vid=task['asset_id'];time.sleep(.6)
 h.check(h.admin.get(h.url+f'assets/{vid}/original').content==payload,'video backup succeeds without FFmpeg')
 ranged=h.admin.get(h.url+f'assets/{vid}/original',headers={'Range':f'bytes=0-{len(payload)-1}'})
 h.check(ranged.status_code==206 and ranged.headers.get('Content-Range')==f'bytes 0-{len(payload)-1}/{len(payload)}' and ranged.content==payload,'native player full-range request has exact 206 bounds')
 ranged=h.admin.get(h.url+f'assets/{vid}/original',headers={'Range':'bytes=2-5'})
 h.check(ranged.status_code==206 and ranged.headers.get('Content-Range')==f'bytes 2-5/{len(payload)}' and ranged.content==payload[2:6],'native seek range returns only the requested bytes')
 h.stop();config['ffmpeg_path']=str(args.ffmpeg.resolve());h.config.write_text(json.dumps(config),encoding='utf-8');h.start()
 for _ in range(300):
  response=h.admin.get(h.url+f'assets/{vid}/thumbnail')
  if response.status_code==200:break
  time.sleep(.1)
 response.raise_for_status();cover=Image.open(io.BytesIO(response.content));h.check(cover.format=='JPEG' and max(cover.size)<=640,'existing video gains bounded JPEG cover after FFmpeg configured')
 h.check(h.admin.get(h.url+f'assets/{vid}/original').content==payload,'video cover never changes original bytes')
 bad=b'not a decodable video';task,_=h.create(bad,name='损坏视频.mkv');task=h.chunk(task,bad,0);task=h.finish(task);badid=task['asset_id'];time.sleep(1)
 row=next(x for x in h.admin.get(h.url+'assets').json()['items'] if x['id']==badid);h.check(row['mime']=='video/x-matroska' and row['category']=='video','MKV is no longer served as octet-stream')
 h.check(h.admin.get(h.url+f'assets/{badid}/original').content==bad and h.admin.get(h.url+f'uploads/{task["id"]}').json()['state']=='READY','cover failure does not fail an admitted original')
 (h.home/'MEDIA-PREVIEW-PASS.json').write_text(json.dumps({'checks':h.checks,'count':len(h.checks)},ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print('Evidence:',h.home)
finally:h.stop()
