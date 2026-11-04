param([ValidateSet('Release','Debug')][string]$Configuration = 'Release')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& cmake -S $projectRoot -B (Join-Path $projectRoot 'build') -G 'Visual Studio 17 2022' -A x64
if ($LASTEXITCODE -ne 0) { throw 'CMake configure failed' }
& cmake --build (Join-Path $projectRoot 'build') --config $Configuration --parallel 6
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
