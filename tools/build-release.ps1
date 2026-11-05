param(
    [Parameter(Mandatory=$true)][string]$ArtifactDirectory,
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$CiNumber,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{40}$')][string]$CiSha,
    [string]$UiKeyPath
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
function Invoke-ReleasePython { & python.exe @args; if($LASTEXITCODE -ne 0){throw ('Python check failed: '+$args[0])} }
Invoke-ReleasePython @('tools/check-dependencies.py')
Invoke-ReleasePython @('-m','unittest','discover','-s','tests','-p','test_release_*check.py')
Invoke-ReleasePython @('tools/fetch-ffmpeg.py')
Invoke-ReleasePython @('tools/check-android.py')
Invoke-ReleasePython @('-m','unittest','discover','-s','tests','-p','test_android_*check.py')
& (Join-Path $PSScriptRoot 'build.ps1')
if($LASTEXITCODE -ne 0){throw 'Windows Release build failed'}
$exe=Join-Path $root 'build/app/Release/NasServer.exe'
Invoke-ReleasePython @('tests/smoke.py','--exe',$exe)
Invoke-ReleasePython @('tests/albums.py')
Invoke-ReleasePython @('tests/folders.py')
Invoke-ReleasePython @('tests/rename.py')
Invoke-ReleasePython @('tests/timeline.py','--exe',$exe)
& (Join-Path $root 'tests/check-service-scripts.ps1')
if($LASTEXITCODE -ne 0){throw 'Service/firewall plans failed'}
$client=Join-Path $root ('deliveries/client-ci'+$CiNumber)
$prepare=@('tools/prepare-android-release.py','--artifact-directory',[IO.Path]::GetFullPath($ArtifactDirectory),'--output',$client,'--ci-number',[string]$CiNumber,'--ci-sha',$CiSha)
if($UiKeyPath){
    $ui=Join-Path $root ('deliveries/ui-ci'+$CiNumber)
    Invoke-ReleasePython @('tools/build-ui-bundle.py','--revision',[string](1000000L+$CiNumber),'--min-native','7','--key',[IO.Path]::GetFullPath($UiKeyPath),'--output',$ui)
    $prepare+=@('--ui-directory',$ui)
}else{Write-Output 'UI signing secret is absent; package uses the complete UI already embedded in the APK.'}
Invoke-ReleasePython @prepare
$resultFile=Join-Path $root 'deliveries/package-result.json'
& (Join-Path $PSScriptRoot 'package.ps1') -Executable $exe -ClientReleaseDirectory $client -ReleaseLabel ('ci'+$CiNumber) -ResultFile $resultFile
$result=Get-Content -LiteralPath $resultFile -Raw -Encoding UTF8 | ConvertFrom-Json
Invoke-ReleasePython @('tests/package_release.py','--archive',$result.archive)
$assets=Join-Path $root 'deliveries/release-assets'
New-Item -ItemType Directory -Path $assets | Out-Null
Copy-Item -LiteralPath $result.archive,($result.archive+'.sha256') -Destination $assets
$apk=Get-Content -LiteralPath (Join-Path $client 'apk.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$apkName='lw.HomeNAS-'+$apk.versionName+'-ci'+$CiNumber+'-release.apk'
Copy-Item -LiteralPath (Join-Path $client ($apk.sha256+'.apk')) -Destination (Join-Path $assets $apkName)
$utf8=[Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $assets ($apkName+'.sha256')),($apk.sha256+'  '+$apkName+"`n"),$utf8)
$manifest=[ordered]@{serverVersion=$result.version;androidVersion=$apk.versionName;versionCode=$apk.versionCode;androidRunNumber=$CiNumber;commit=$CiSha;package=[IO.Path]::GetFileName($result.archive);packageSha256=$result.sha256;apk=$apkName;apkSha256=$apk.sha256;ui=$(if($UiKeyPath){'signed r'+(1000000L+$CiNumber)}else{'APK builtin'});checks='Windows build, smoke, albums, folders, rename, timeline and package acceptance passed';physicalDeviceTested=$false}
[IO.File]::WriteAllText((Join-Path $assets 'release-manifest.json'),($manifest|ConvertTo-Json -Depth 6),$utf8)
$notes=@"
lw.HomeNAS automatic full package / Android CI $CiNumber

Windows $($result.version), Android Release $($apk.versionName) / code $($apk.versionCode), commit $CiSha.
Includes the fixed-signature APK, Web, deployment scripts and FFmpeg. UI: $($manifest.ui).
Windows package tests and upstream Android CI passed. This does not replace physical-device testing.

Fresh deployment: extract the ZIP, run deploy/import_android.bat, then start the server.
Open http://127.0.0.1:8787/ and sign in with admin / admin123. Change the initial password after login.
Defaults: allow_insecure_lan=true, allow_external_hosts=true. Preserve existing config and data during upgrades.
Switching from an old temporary APK signing key may require a one-time reinstall; preserve pending phone backup work first.

ZIP and APK SHA256 files and release-manifest.json are included.
"@
[IO.File]::WriteAllText((Join-Path $root 'deliveries/release-notes.md'),$notes,$utf8)
Write-Output ('Verified release assets: '+$assets)
