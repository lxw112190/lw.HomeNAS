"""Give CI APKs short download names without changing their signed bytes."""
import argparse
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
def apk_name(version,debug=False):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?',version):
        raise ValueError('Invalid APK version')
    return 'HomeNAS-'+version+('-debug' if debug else '')+'.apk'

def rename(root,version):
    plans=[]
    for variant in ['debug','release']:
        folder=root/variant;original=folder/('app-'+variant+'.apk');target=folder/apk_name(version,variant=='debug')
        if not original.exists() and not target.exists():
            if variant=='debug':raise ValueError('Missing Debug APK')
            continue
        if original.exists() and target.exists():raise ValueError('APK target already exists; refusing overwrite')
        metadata=folder/'output-metadata.json';data=json.loads(metadata.read_text(encoding='utf-8'))
        if len(data.get('elements',[]))!=1 or data['elements'][0].get('versionName')!=version:raise ValueError('APK output metadata/version mismatch')
        if data['elements'][0].get('outputFile') not in [original.name,target.name]:raise ValueError('Unexpected APK output name')
        data['elements'][0]['outputFile']=target.name;plans.append((original,target,metadata,data))
    for original,target,metadata,data in plans:
        if original.exists():original.rename(target)
        temporary=metadata.with_suffix('.json.tmp')
        try:temporary.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8');temporary.replace(metadata)
        finally:temporary.unlink(missing_ok=True)
    return [target for _,target,_,_ in plans]

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=ROOT/'android/app/build/outputs/apk');a=p.parse_args()
    for path in rename(a.root,(ROOT/'VERSION').read_text(encoding='utf-8').strip()):print(path)
