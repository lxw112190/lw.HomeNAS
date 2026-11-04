param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'web') -Destination $Destination -Recurse -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy') -Destination $Destination -Recurse -Force
$configDestination = Join-Path $Destination 'config'
New-Item -ItemType Directory -Path $configDestination -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $configDestination 'server.json'))) {
    Copy-Item -LiteralPath (Join-Path $projectRoot 'config/server.json') -Destination $configDestination
}
