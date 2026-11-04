param([string]$Executable)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Executable) { $Executable = Join-Path $projectRoot 'out/Release/NasServer.exe' }
if (-not (Test-Path -LiteralPath $Executable)) { throw '请先生成 Release x64' }
$version = (& $Executable --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^\d+\.\d+\.\d+(-[a-z0-9.-]+)?$') { throw '无法读取程序版本' }
$name = 'lw.HomeNAS-' + $version + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-win-x64'
$stage = Join-Path $projectRoot ('deliveries/' + $name)
if (Test-Path -LiteralPath $stage) { throw '打包目标已存在' }
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -LiteralPath $Executable -Destination (Join-Path $stage 'NasServer.exe')
foreach ($folder in @('web','config','deploy')) { Copy-Item -LiteralPath (Join-Path $projectRoot $folder) -Destination $stage -Recurse }
foreach ($file in @('README.md','LICENSE','THIRD-PARTY-NOTICES.md')) { Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $stage }
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs') -Destination $stage -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'android') -Destination $stage -Recurse
New-Item -ItemType Directory -Path (Join-Path $stage 'licenses') | Out-Null
foreach ($pair in @(@('third_party/httplib/LICENSE','httplib-LICENSE'), @('third_party/json/LICENSE.MIT','json-LICENSE.MIT'), @('third_party/mbedtls/LICENSE','mbedtls-LICENSE'))) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $pair[0]) -Destination (Join-Path $stage ('licenses/'+$pair[1]))
}
$launch = "@echo off`r`nchcp 65001 >nul`r`n`"%~dp0NasServer.exe`"`r`npause`r`n"
[IO.File]::WriteAllText((Join-Path $stage '启动服务.cmd'), $launch, [Text.UTF8Encoding]::new($false))
$quickStart = @'
lw.HomeNAS 账号权限版开发预览
运行 启动服务.cmd，保持窗口运行，然后浏览器打开 http://127.0.0.1:8787/ 。
用户名 admin，首次密码见控制台与 data/initial-admin-password.txt。
管理员可在用户管理中创建普通用户或只读用户。手机自动备份与真机APK尚未接入。
服务批处理位于 deploy：安装、启动、停止、重启、查询与卸载。卸载保留数据。
'@
[IO.File]::WriteAllText((Join-Path $stage '开始使用.txt'), $quickStart, [Text.UTF8Encoding]::new($true))
$archive = $stage + '.zip'
Compress-Archive -LiteralPath $stage -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($archive+'.sha256'), ($hash+'  '+[IO.Path]::GetFileName($archive)+"`n"), [Text.UTF8Encoding]::new($false))
Write-Output $archive
Write-Output ('SHA256: '+$hash)
