param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'web') -Destination $Destination -Recurse -Force
$deployDestination=Join-Path $Destination 'deploy'
New-Item -ItemType Directory -Path $deployDestination -Force | Out-Null
foreach($file in @('install-service.bat','restart-service.bat','start-service.bat','stop-service.bat','uninstall-service.bat','open_firewall.bat','remove_firewall.bat','service.ps1','service-native.cs','diagnose-service.ps1','firewall.ps1','import_android.ps1','README.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('deploy/'+$file)) -Destination $deployDestination -Force
}
$ffmpeg=Join-Path $projectRoot 'tools/ffmpeg'
if(Test-Path -LiteralPath (Join-Path $ffmpeg 'ffmpeg.exe')){New-Item -ItemType Directory -Path (Join-Path $Destination 'tools') -Force | Out-Null;Copy-Item -LiteralPath $ffmpeg -Destination (Join-Path $Destination 'tools') -Recurse -Force}
$configDestination = Join-Path $Destination 'config'
New-Item -ItemType Directory -Path $configDestination -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $configDestination 'server.json'))) {
    Copy-Item -LiteralPath (Join-Path $projectRoot 'config/server.json') -Destination $configDestination
}
