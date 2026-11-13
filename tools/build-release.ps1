param(
    [Parameter(Mandatory=$true)][string]$ArtifactDirectory,
    [Parameter(Mandatory=$true)][string]$ServerArtifactDirectory,
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$CiNumber,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{40}$')][string]$CiSha,
    [string]$UiKeyPath
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
function Invoke-ReleasePython { & python.exe @args; if($LASTEXITCODE -ne 0){throw ('Python check failed: '+$args[0])} }
Invoke-ReleasePython @('tools/check-version.py')
Invoke-ReleasePython @('tools/server-artifact.py','restore','--artifact-directory',[IO.Path]::GetFullPath($ServerArtifactDirectory),'--ci-number',[string]$CiNumber,'--ci-sha',$CiSha)
Invoke-ReleasePython @('tools/fetch-ffmpeg.py','--verify-only')
$serverBuild=Get-Content -LiteralPath (Join-Path $ServerArtifactDirectory 'server-build.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$exe=Join-Path $root 'build/app/Release/NasServer.exe'
$client=Join-Path $root ('deliveries/client-ci'+$CiNumber)
$prepare=@('tools/prepare-android-release.py','--artifact-directory',[IO.Path]::GetFullPath($ArtifactDirectory),'--output',$client,'--ci-number',[string]$CiNumber,'--ci-sha',$CiSha)
if($UiKeyPath){
    $ui=Join-Path $root ('deliveries/ui-ci'+$CiNumber)
    Invoke-ReleasePython @('tools/build-ui-bundle.py','--revision',[string](1000000L+$CiNumber),'--min-native','14','--key',[IO.Path]::GetFullPath($UiKeyPath),'--output',$ui)
    $prepare+=@('--ui-directory',$ui)
}else{Write-Output 'UI signing secret is absent; package uses the complete UI already embedded in the APK.'}
Invoke-ReleasePython @prepare
$resultFile=Join-Path $root 'deliveries/package-result.json'
& (Join-Path $PSScriptRoot 'package.ps1') -Executable $exe -ClientReleaseDirectory $client -ReleaseLabel ('ci'+$CiNumber) -ResultFile $resultFile
$result=Get-Content -LiteralPath $resultFile -Raw -Encoding UTF8 | ConvertFrom-Json
Invoke-ReleasePython @('tests/package_release.py','--archive',$result.archive,'--expected-executable-sha256',$serverBuild.executableSha256)
$assets=Join-Path $root 'deliveries/release-assets'
New-Item -ItemType Directory -Path $assets | Out-Null
Copy-Item -LiteralPath $result.archive,($result.archive+'.sha256') -Destination $assets
$apk=Get-Content -LiteralPath (Join-Path $client 'apk.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$apkName='HomeNAS-'+$apk.versionName+'.apk'
Copy-Item -LiteralPath (Join-Path $client ($apk.sha256+'.apk')) -Destination (Join-Path $assets $apkName)
$utf8=[Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $assets ($apkName+'.sha256')),($apk.sha256+'  '+$apkName+"`n"),$utf8)
$manifest=[ordered]@{serverVersion=$result.version;androidVersion=$apk.versionName;versionCode=$apk.versionCode;androidRunNumber=$CiNumber;commit=$CiSha;serverArchiveSha256=$serverBuild.archiveSha256;serverExecutableSha256=$serverBuild.executableSha256;package=[IO.Path]::GetFileName($result.archive);packageSha256=$result.sha256;apk=$apkName;apkSha256=$apk.sha256;ui=$(if($UiKeyPath){'signed r'+(1000000L+$CiNumber)}else{'APK builtin'});checks='Windows build, data and preview regressions, EXIF timeline, metadata backup, offline migration and full ZIP acceptance passed';physicalDeviceTested=$false}
[IO.File]::WriteAllText((Join-Path $assets 'release-manifest.json'),($manifest|ConvertTo-Json -Depth 6),$utf8)
$notes=@"
lw.HomeNAS automatic full package / CI $CiNumber

Windows $($result.version), Android Release $($apk.versionName) / code $($apk.versionCode), commit $CiSha.
v1.3.1: Web timeline filters now use aligned 40px controls, consistent field spacing, a clear primary action and responsive rows. The phone connection address selector also gets consistent styling and bounded long-address display. APK version code advances to 24 with the existing signing identity; native protocol remains 14.
v1.3 highlights: EXIF capture-time timeline with year/month/date navigation; consistent metadata snapshots and verified offline library migration; folders-first organization with statistics and batch moves; phone QR setup, recent servers and actionable connection checks. Chinese and English READMEs are included.
Includes the fixed-signature APK, Web, deployment scripts and FFmpeg. UI: $($manifest.ui).
APK and Windows built in parallel from the same commit. Android emulator and full ZIP acceptance passed; the final ZIP reuses the exact independently tested Windows executable. This does not replace physical-device testing.

Fresh deployment: extract the ZIP, then run deploy/install-service.bat as administrator (or NasServer.exe in the foreground). Bundled client-release APK/UI files are validated and published automatically at startup; download the APK from the login page.
Open http://127.0.0.1:8787/ and sign in with admin / admin123. Change the initial password after login.
Defaults: allow_insecure_lan=true, allow_external_hosts=true. Preserve existing config and data during upgrades.
Switching from an old temporary APK signing key may require a one-time reinstall; preserve pending phone backup work first.

ZIP and APK SHA256 files and release-manifest.json are included.
"@
[IO.File]::WriteAllText((Join-Path $root 'deliveries/release-notes.md'),$notes,$utf8)
Write-Output ('Verified release assets: '+$assets)
