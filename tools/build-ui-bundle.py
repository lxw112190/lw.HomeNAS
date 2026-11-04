"""Sign an Android UI release. Private key stays outside Git and deployment packages."""
from pathlib import Path
import argparse,base64,json,hashlib,zipfile,re
from cryptography.hazmat.primitives import serialization,hashes
from cryptography.hazmat.primitives.asymmetric import padding
p=argparse.ArgumentParser();p.add_argument('--revision',type=int,required=True);p.add_argument('--key',type=Path,default=Path('local-signing/resources-private.pem'));p.add_argument('--output',type=Path,default=Path('deliveries/ui'));a=p.parse_args()
assert a.revision>0
root=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True);archive=a.output/('ui-r'+str(a.revision)+'.zip')
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for f in sorted((root/'android/app/src/main/assets/www').rglob('*')):
  if f.is_file():z.write(f,f.relative_to(root/'android/app/src/main/assets/www').as_posix())
data=archive.read_bytes();manifest={'revision':a.revision,'minNative':1,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()};payload=json.dumps(manifest,separators=(',',':')).encode()
key=serialization.load_pem_private_key(a.key.read_bytes(),password=None)
public=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
trusted=re.search(r'KEY="([^"]+)"',(root/'android/app/src/main/java/com/lw/homenas/ResourceTrust.java').read_text(encoding='utf-8')).group(1)
if public!=trusted:raise SystemExit("Resource signing key does not match the public key embedded in the APK")
envelope={'payload':base64.b64encode(payload).decode(),'signature':base64.b64encode(key.sign(payload,padding.PKCS1v15(),hashes.SHA256())).decode()}
(a.output/'release.json').write_text(json.dumps(envelope,indent=2)+'\n',encoding='utf-8');print(archive);print(a.output/'release.json')
