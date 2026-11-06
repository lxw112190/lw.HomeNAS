param([switch]$PlanOnly)
$ErrorActionPreference='Stop'
$packageRoot=Split-Path -Parent $PSScriptRoot
$layoutOutput=& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'service.ps1') -PlanOnly
if($LASTEXITCODE -ne 0){throw 'Cannot resolve application layout; ensure NasServer.exe, config and web folders are complete.'}
$layout=$layoutOutput | ConvertFrom-Json
function Get-ApkHash([string]$Path){
    $algorithm=[Security.Cryptography.SHA256]::Create();$stream=[IO.File]::OpenRead($Path)
    try{return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$algorithm.Dispose()}
}
$source=Join-Path $packageRoot 'client-release' 
$metadata=Get-Content -LiteralPath (Join-Path $source 'apk.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if([string]$metadata.sha256 -notmatch '^[a-f0-9]{64}$' -or [long]$metadata.versionCode -lt 1){throw 'Invalid APK release metadata'}
$apk=Join-Path $source ([string]$metadata.sha256+'.apk')
if((Get-ApkHash $apk) -ne $metadata.sha256 -or (Get-Item -LiteralPath $apk).Length -ne [long]$metadata.size){throw 'APK checksum or size does not match release metadata'}
# Optional signed page resources. The APK independently verifies the RSA signature.
$ui=$null;$uiManifest=$null;$uiArchive=$null
if(Test-Path -LiteralPath (Join-Path $source 'ui.json')){
    $ui=Get-Content -LiteralPath (Join-Path $source 'ui.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $uiManifest=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($ui.payload)) | ConvertFrom-Json
    if([string]$uiManifest.sha256 -notmatch '^[a-f0-9]{64}$' -or [long]$uiManifest.revision -lt 1 -or [long]$uiManifest.minNative -lt 1 -or [long]$uiManifest.minNative -gt 11){throw 'Invalid UI release metadata'}
    $uiArchive=Join-Path $source ([string]$uiManifest.sha256+'.zip')
    if((Get-ApkHash $uiArchive) -ne $uiManifest.sha256 -or (Get-Item -LiteralPath $uiArchive).Length -ne [long]$uiManifest.size){throw 'UI checksum or size does not match release metadata'}
}
if($PlanOnly){[pscustomobject]@{Version=$metadata.versionName;VersionCode=$metadata.versionCode;Source=$apk;Data=$layout.Data;SHA256=$metadata.sha256;UIRevision=$(if($uiManifest){$uiManifest.revision}else{$null})} | ConvertTo-Json;exit 0}
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
    if($ui){
        $uiCatalog=Join-Path $root 'ui.json';$publish=$true
        if(Test-Path -LiteralPath $uiCatalog){
            $existing=Get-Content -LiteralPath $uiCatalog -Raw -Encoding UTF8 | ConvertFrom-Json
            $existingManifest=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($existing.payload)) | ConvertFrom-Json
            if([long]$existingManifest.revision -eq [long]$uiManifest.revision -and $existingManifest.sha256 -ne $uiManifest.sha256){throw 'This UI revision already exists with different content. Publish a higher revision.'}
            if([long]$existingManifest.revision -gt [long]$uiManifest.revision){$publish=$false;Write-Host 'A newer UI release was retained.'}
        }
        if($publish){
            $uiTarget=Join-Path $packages ([string]$uiManifest.sha256+'.zip')
            if(Test-Path -LiteralPath $uiTarget){if((Get-ApkHash $uiTarget) -ne $uiManifest.sha256){throw 'Existing UI package is inconsistent'}}else{Copy-Item -LiteralPath $uiArchive -Destination $uiTarget}
            $temporary=Join-Path $root (([Guid]::NewGuid().ToString('N'))+'.json.tmp')
            [IO.File]::WriteAllText($temporary,($ui | ConvertTo-Json -Depth 8),$encoding)
            if(Test-Path -LiteralPath $uiCatalog){$backup=Join-Path $root (([Guid]::NewGuid().ToString('N'))+'.previous');[IO.File]::Replace($temporary,$uiCatalog,$backup);Remove-Item -LiteralPath $backup}else{[IO.File]::Move($temporary,$uiCatalog)}
            Write-Host ('Imported signed UI revision '+$uiManifest.revision+'. On the phone, check updates and download resources, then reopen the app.')
        }
    }
    Write-Host ('Imported Android '+$metadata.versionName+' (versionCode '+$metadata.versionCode+').')
    Write-Host 'Start NAS and open its login page. The Android download button is available without login.'
    Write-Host 'Accounts, originals and upload progress were retained.'
}finally{if($temporary -and (Test-Path -LiteralPath $temporary)){Remove-Item -LiteralPath $temporary};if($guard){$guard.Dispose()}}
