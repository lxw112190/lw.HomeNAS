"""One release version; reject divergent server, CMake and APK version sources."""
import re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
version=(ROOT/'VERSION').read_text(encoding='utf-8').strip()
assert re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?',version),'Invalid VERSION'
for file,pattern in [('src/core.hpp',r'Version = "([^"]+)"'),('CMakeLists.txt',r'project\(lw_HomeNAS VERSION ([^ ]+)'),('android/app/build.gradle',r"versionName '([^']+)'")]:
 actual=re.search(pattern,(ROOT/file).read_text(encoding='utf-8')).group(1)
 assert actual==version,(file,actual,version)
print('Release version sources agree:',version)

protocols=[]
for file,pattern in [
 ('android/app/src/main/java/com/lw/homenas/MainActivity.java',r'put\("nativeApi",(\d+)\)'),
 ('android/app/src/main/java/com/lw/homenas/ClientMaintenance.java',r'put\("nativeApi",(\d+)\)'),
 ('src/maintenance.hpp',r'number\(manifest,"minNative"\)>(\d+)'),
 ('deploy/import_android.ps1',r'uiManifest.minNative -gt (\d+)'),
 ('tools/build-ui-bundle.py',r"min-native'.*?default=(\d+)"),
 ('tools/build-release.ps1',r"'--min-native','(\d+)'"),
]:
 value=int(re.search(pattern,(ROOT/file).read_text(encoding='utf-8-sig')).group(1));protocols.append(value)
assert len(set(protocols))==1,('Native/resource protocol bounds differ',protocols)
print('Native/resource protocol sources agree:',protocols[0])
