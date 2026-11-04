param([switch]$PlanOnly)
$ErrorActionPreference='Stop'
$packageRoot=Split-Path -Parent $PSScriptRoot
$layout=(& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'service.ps1') -PlanOnly | ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot resolve application layout'}
function Get-ApkHash([string]$Path){
    $algorithm=[Security.Cryptography.SHA256]::Create();$stream=[IO.File]::OpenRead($Path)
    try{return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$algorithm.Dispose()}
}
$source=Join-Path $packageRoot 'client-release' 
$metadata=Get-Content -LiteralPath (Join-Path $source 'apk.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if([string]$metadata.sha256 -notmatch '^[a-f0-9]{64}$' -or [long]$metadata.versionCode -lt 1){throw 'Invalid APK release metadata'}
$apk=Join-Path $source ([string]$metadata.sha256+'.apk')
if((Get-ApkHash $apk) -ne $metadata.sha256 -or (Get-Item -LiteralPath $apk).Length -ne [long]$metadata.size){throw 'APK checksum or size does not match release metadata'}
if($PlanOnly){[pscustomobject]@{Version=$metadata.versionName;VersionCode=$metadata.versionCode;Source=$apk;Data=$layout.Data;SHA256=$metadata.sha256} | ConvertTo-Json;exit 0}
New-Item -ItemType Directory -Path $layout.Data -Force | Out-Null
$guard=$null;$temporary=$null
try{
    try{$guard=[IO.File]::Open((Join-Path $layout.Data 'instance.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}catch{throw 'Stop the NAS console program or Windows service before importing the APK.'}
    $root=Join-Path $layout.Data 'maintenance'
    $packages=Join-Path $root 'packages';New-Item -ItemType Directory -Path $packages -Force | Out-Null
    $target=Join-Path $packages ([string]$metadata.sha256+'.apk')
    if(Test-Path -LiteralPath $target){if((Get-ApkHash $target) -ne $metadata.sha256){throw 'Existing package content is inconsistent'}}else{Copy-Item -LiteralPath $apk -Destination $target}
    $temporary=Join-Path $root (([Guid]::NewGuid().ToString('N'))+'.json.tmp')
    $encoding=New-Object System.Text.UTF8Encoding -ArgumentList $false
    [IO.File]::WriteAllText($temporary,($metadata | ConvertTo-Json -Depth 8),$encoding)
    $catalog=Join-Path $root 'apk.json'
    if(Test-Path -LiteralPath $catalog){$backup=Join-Path $root (([Guid]::NewGuid().ToString('N'))+'.previous');[IO.File]::Replace($temporary,$catalog,$backup);Remove-Item -LiteralPath $backup}else{[IO.File]::Move($temporary,$catalog)}
    Write-Host ('Imported Android '+$metadata.versionName+' (versionCode '+$metadata.versionCode+').')
    Write-Host 'Start NAS and open its login page. The Android download button is available without login.'
    Write-Host 'Accounts, originals and upload progress were retained.'
}finally{if($temporary -and (Test-Path -LiteralPath $temporary)){Remove-Item -LiteralPath $temporary};if($guard){$guard.Dispose()}}
