param([string]$Executable,[string]$ClientReleaseDirectory,[string]$ReleaseLabel,[string]$ResultFile)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Executable) { $Executable = Join-Path $projectRoot 'out/Release/NasServer.exe' }
if (-not (Test-Path -LiteralPath $Executable)) { throw '请先生成 Release x64' }
$version = (& $Executable --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^\d+\.\d+\.\d+(-[a-z0-9.-]+)?$') { throw '无法读取程序版本' }
if($ClientReleaseDirectory){
    $clientVersion=(Get-Content -LiteralPath (Join-Path $ClientReleaseDirectory 'apk.json') -Raw -Encoding UTF8 | ConvertFrom-Json).versionName
    if($clientVersion -ne $version){throw 'APK and server release versions differ. Build both from the same versioned source commit.'}
}
$label=if($ReleaseLabel){if($ReleaseLabel -notmatch '^ci[1-9][0-9]*$'){throw 'ReleaseLabel must be ci followed by a positive run number'};$ReleaseLabel}else{Get-Date -Format 'yyyyMMdd-HHmmss'}
$name = 'lw.HomeNAS-' + $version + '-' + $label + '-win-x64'
$stage = Join-Path $projectRoot ('deliveries/' + $name)
if (Test-Path -LiteralPath $stage) { throw '打包目标已存在' }
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -LiteralPath $Executable -Destination (Join-Path $stage 'NasServer.exe')
if($ClientReleaseDirectory){ Copy-Item -LiteralPath $ClientReleaseDirectory -Destination (Join-Path $stage 'client-release') -Recurse }
foreach ($folder in @('web','config')) { Copy-Item -LiteralPath (Join-Path $projectRoot $folder) -Destination $stage -Recurse }
# Explicit deployment allowlist: never ship local installation logs or unknown files.
$deployStage=Join-Path $stage 'deploy'
New-Item -ItemType Directory -Path $deployStage | Out-Null
foreach($file in @('install-service.bat','restart-service.bat','start-service.bat','stop-service.bat','uninstall-service.bat','open_firewall.bat','remove_firewall.bat','service.ps1','service-native.cs','diagnose-service.ps1','firewall.ps1','import_android.ps1','README.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('deploy/'+$file)) -Destination $deployStage
}
# User-requested deployment defaults; source/runtime configs and existing accounts are untouched.
$deploymentConfigFile=Join-Path $stage 'config/server.json'
$deploymentConfig=Get-Content -LiteralPath $deploymentConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
$deploymentConfig.allow_external_hosts=$true
$deploymentConfig | Add-Member -NotePropertyName allow_insecure_lan -NotePropertyValue $true -Force
$deploymentConfig | Add-Member -NotePropertyName bootstrap_admin_password -NotePropertyValue 'admin123' -Force
[IO.File]::WriteAllText($deploymentConfigFile,($deploymentConfig | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
foreach ($file in @('README.md','VERSION','LICENSE','THIRD-PARTY-NOTICES.md')) { Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $stage }
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs') -Destination $stage -Recurse
# Copy source explicitly: never ship local Gradle caches, build outputs or signing/config files.
$androidStage = Join-Path $stage 'android'
New-Item -ItemType Directory -Path (Join-Path $androidStage 'app') -Force | Out-Null
foreach ($file in @('README.md','build.gradle','settings.gradle','gradle.properties','project.example.json','signing-certificate.json')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('android/'+$file)) -Destination $androidStage
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'android/app/build.gradle') -Destination (Join-Path $androidStage 'app')
Copy-Item -LiteralPath (Join-Path $projectRoot 'android/app/src') -Destination (Join-Path $androidStage 'app') -Recurse
New-Item -ItemType Directory -Path (Join-Path $stage 'tools') | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'tools/build-ui-bundle.py') -Destination (Join-Path $stage 'tools')
Copy-Item -LiteralPath (Join-Path $projectRoot 'tools/android_ui_policy.py') -Destination (Join-Path $stage 'tools')
if(Test-Path -LiteralPath (Join-Path $projectRoot 'tools/ffmpeg/ffmpeg.exe')){Copy-Item -LiteralPath (Join-Path $projectRoot 'tools/ffmpeg') -Destination (Join-Path $stage 'tools') -Recurse}
New-Item -ItemType Directory -Path (Join-Path $stage 'licenses') | Out-Null
foreach ($pair in @(@('third_party/httplib/LICENSE','httplib-LICENSE'), @('third_party/json/LICENSE.MIT','json-LICENSE.MIT'), @('third_party/mbedtls/LICENSE','mbedtls-LICENSE'))) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $pair[0]) -Destination (Join-Path $stage ('licenses/'+$pair[1]))
}
$launch = "@echo off`r`n`"%~dp0NasServer.exe`"`r`npause`r`n"
[IO.File]::WriteAllText((Join-Path $stage '启动服务.cmd'), $launch, [Text.UTF8Encoding]::new($false))
$quickStart = @'
lw.HomeNAS 家庭备份开发预览
运行 启动服务.cmd，保持窗口运行，然后浏览器打开 http://127.0.0.1:8787/ 。
全新部署默认用户名 admin、初始密码 admin123；登录后在“存储与设置”修改密码。已有data中的账号密码不会重置。
deploy只保留7个批处理：install-service、start-service、stop-service、restart-service、uninstall-service、open_firewall、remove_firewall。请右键以管理员身份运行。
首次使用运行deploy/install-service.bat，安装后自动启动。安装统一读取config/server.json，无需区分HTTP安装脚本。
默认allow_insecure_lan为true，直接启动即可使用局域网HTTP；HTTPS同时保留。设为false并移除强制开启参数可恢复HTTP仅本机。
本包allow_external_hosts默认为true；http_port按需设为8080，再启动HTTP入口。
网页设置页支持APK/签名资源发布及诊断报告；进阶PowerShell维护命令见deploy/README.md。
安装失败日志：deploy/logs/service-install.log；程序启动后的运行日志：data/logs/service.log。不要将日志发到公开仓库。
Android源码随包提供；CI配置见GitHub仓库，手机APK由CI生成，不在此Windows包内。
手机地址分开填写IP与端口，可测试连接、导出诊断ZIP；Android真机功能仍需验证。
管理员可创建普通/只读账号。服务脚本支持安装、启停、查询与卸载，卸载保留数据。
'@
if($ClientReleaseDirectory){
    $apkMetadata=Get-Content -LiteralPath (Join-Path $ClientReleaseDirectory 'apk.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $quickStart=$quickStart.Replace('手机APK由CI生成，不在此Windows包内。',('本包附带CI APK '+$apkMetadata.versionName+' (versionCode '+$apkMetadata.versionCode+')：先停止NAS，使用PowerShell运行deploy/import_android.ps1导入，再启动即可网页下载。'))
    if(Test-Path -LiteralPath (Join-Path $ClientReleaseDirectory 'ui.json')){$quickStart+="`r`n附带签名页面资源，由导入脚本一起发布；手机检查更新、下载资源更新后重新打开，可使用最新界面。"}
    $quickStart+="`r`n安装/升级前请阅读docs中的对应CI发布说明；不同签名的Debug APK不能覆盖安装，勿盲目卸载旧版。"
}
if(Test-Path -LiteralPath (Join-Path $stage 'tools/ffmpeg/ffmpeg.exe')){$quickStart+="`r`n已包含FFmpeg，视频封面开箱可用；使用config/server.json内的相对路径，不需要配置系统PATH。封面不等于整段视频兼容转码。"}

[IO.File]::WriteAllText((Join-Path $stage '开始使用.txt'), $quickStart, [Text.UTF8Encoding]::new($true))
$archive = $stage + '.zip'
Compress-Archive -LiteralPath $stage -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($archive+'.sha256'), ($hash+'  '+[IO.Path]::GetFileName($archive)+"`n"), [Text.UTF8Encoding]::new($false))
Write-Output $archive
if($ResultFile){[IO.File]::WriteAllText([IO.Path]::GetFullPath($ResultFile),([pscustomobject]@{archive=$archive;stage=$stage;sha256=$hash;version=$version}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))}
Write-Output ('SHA256: '+$hash)
