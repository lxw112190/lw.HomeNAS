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
# Copy source explicitly: never ship local Gradle caches, build outputs or signing/config files.
$androidStage = Join-Path $stage 'android'
New-Item -ItemType Directory -Path (Join-Path $androidStage 'app') -Force | Out-Null
foreach ($file in @('README.md','build.gradle','settings.gradle','gradle.properties','project.example.json')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('android/'+$file)) -Destination $androidStage
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'android/app/build.gradle') -Destination (Join-Path $androidStage 'app')
Copy-Item -LiteralPath (Join-Path $projectRoot 'android/app/src') -Destination (Join-Path $androidStage 'app') -Recurse
New-Item -ItemType Directory -Path (Join-Path $stage 'tools') | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'tools/build-ui-bundle.py') -Destination (Join-Path $stage 'tools')
New-Item -ItemType Directory -Path (Join-Path $stage 'licenses') | Out-Null
foreach ($pair in @(@('third_party/httplib/LICENSE','httplib-LICENSE'), @('third_party/json/LICENSE.MIT','json-LICENSE.MIT'), @('third_party/mbedtls/LICENSE','mbedtls-LICENSE'))) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $pair[0]) -Destination (Join-Path $stage ('licenses/'+$pair[1]))
}
$launch = "@echo off`r`n`"%~dp0NasServer.exe`"`r`npause`r`n"
[IO.File]::WriteAllText((Join-Path $stage '启动服务.cmd'), $launch, [Text.UTF8Encoding]::new($false))
$quickStart = @'
lw.HomeNAS 家庭备份开发预览
运行 启动服务.cmd，保持窗口运行，然后浏览器打开 http://127.0.0.1:8787/ 。
用户名 admin，默认首次随机密码见控制台与 data/initial-admin-password.txt。
测试时可先关闭前台程序/停止服务，运行 deploy/set_admin_password.bat 自选至少10位密码，再启动。
家庭内网HTTP前台入口：deploy/start_console_http.bat。
服务首次安装HTTP：deploy/install_http_service.bat；已有服务切换：deploy/configure_http_service.bat。
HTTP传输未加密。默认服务仍是局域网HTTPS，HTTP仅本机；详见deploy/README.md。
公网IP测试：config/server.json中设置allow_external_hosts为true，http_port按需设为8080，再启动HTTP入口。
网页设置页支持APK/签名资源发布及诊断报告；防火墙脚本见deploy/open_firewall.bat与remove_firewall.bat。
Android源码随包提供；CI配置见GitHub仓库，手机APK由CI生成，不在此Windows包内。
手机地址分开填写IP与端口，可测试连接、导出诊断ZIP；本轮Android尚待CI/真机验证。
管理员可创建普通/只读账号。服务脚本支持安装、启停、查询与卸载，卸载保留数据。
'@
[IO.File]::WriteAllText((Join-Path $stage '开始使用.txt'), $quickStart, [Text.UTF8Encoding]::new($true))
$archive = $stage + '.zip'
Compress-Archive -LiteralPath $stage -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($archive+'.sha256'), ($hash+'  '+[IO.Path]::GetFileName($archive)+"`n"), [Text.UTF8Encoding]::new($false))
Write-Output $archive
Write-Output ('SHA256: '+$hash)
