param(
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$CiNumber,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{40}$')][string]$CiSha
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
function Invoke-ReleasePython { & python.exe @args; if($LASTEXITCODE -ne 0){throw ('Python check failed: '+$args[0])} }
Invoke-ReleasePython @('tools/check-workflows.py')
Invoke-ReleasePython @('tools/check-version.py')
Invoke-ReleasePython @('tools/check-dependencies.py')
Invoke-ReleasePython @('-m','unittest','discover','-s','tests','-p','test_release_*check.py')
Invoke-ReleasePython @('tools/fetch-ffmpeg.py')
Invoke-ReleasePython @('tools/check-android.py')
Invoke-ReleasePython @('-m','unittest','discover','-s','tests','-p','test_android_*check.py')
& npm.cmd ci --prefix (Join-Path $root 'tests/dom') --cache (Join-Path $root 'test-results/npm-cache') --ignore-scripts --no-audit --no-fund
if($LASTEXITCODE -ne 0){throw 'DOM test dependencies failed'}
& node (Join-Path $root 'tests/collections-dom.cjs') (Join-Path $root 'tests/dom/node_modules/jsdom')
if($LASTEXITCODE -ne 0){throw 'Collection DOM checks failed'}
& node (Join-Path $root 'tests/web-upload-folders.cjs')
if($LASTEXITCODE -ne 0){throw 'Web upload destination checks failed'}
& node (Join-Path $root 'tests/backup-picker-dom.cjs') (Join-Path $root 'tests/dom/node_modules/jsdom')
if($LASTEXITCODE -ne 0){throw 'APK backup picker DOM checks failed'}
& node (Join-Path $root 'tests/backup-summary-dom.cjs')
if($LASTEXITCODE -ne 0){throw 'APK backup summary DOM checks failed'}
& (Join-Path $PSScriptRoot 'build.ps1')
if($LASTEXITCODE -ne 0){throw 'Windows Release build failed'}
$exe=Join-Path $root 'build/app/Release/NasServer.exe'
Invoke-ReleasePython @('tests/smoke.py','--exe',$exe)
Invoke-ReleasePython @('tests/bundled_clients.py')
Invoke-ReleasePython @('tests/parallel_uploads.py')
Invoke-ReleasePython @('tests/albums.py')
Invoke-ReleasePython @('tests/folders.py')
Invoke-ReleasePython @('tests/recycle.py')
Invoke-ReleasePython @('tests/library_ux.py')
& node (Join-Path $root 'tests/purge-ui.cjs')
if($LASTEXITCODE -ne 0){throw 'Permanent deletion UI confirmation checks failed'}
Invoke-ReleasePython @('tests/rename.py')
Invoke-ReleasePython @('tests/timeline.py','--exe',$exe)
& (Join-Path $root 'tests/check-service-scripts.ps1')
if($LASTEXITCODE -ne 0){throw 'Service/firewall plans failed'}
& (Join-Path $root 'tests/deployment-scripts.ps1')
if($LASTEXITCODE -ne 0){throw 'Deployment/logging checks failed'}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'tests/deployment-scripts.ps1')
if($LASTEXITCODE -ne 0){throw 'Windows PowerShell 5.1 deployment/logging checks failed'}
$resultFile=Join-Path $root 'deliveries/server-package-result.json'
& (Join-Path $PSScriptRoot 'package.ps1') -Executable $exe -ReleaseLabel ('ci'+$CiNumber) -ResultFile $resultFile
$result=Get-Content -LiteralPath $resultFile -Raw -Encoding UTF8 | ConvertFrom-Json
Invoke-ReleasePython @('tools/server-artifact.py','create','--archive',$result.archive,'--output',(Join-Path $root 'deliveries/server-assets'),'--ci-number',[string]$CiNumber,'--ci-sha',$CiSha)
Write-Output 'Tested standalone Windows package ready; final assembly will reuse this executable.'
