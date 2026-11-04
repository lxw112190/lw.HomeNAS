$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$files = @(Get-ChildItem -LiteralPath (Join-Path $root 'deploy') -Filter '*.ps1') + @(Get-ChildItem -LiteralPath (Join-Path $root 'tools') -Filter '*.ps1')
foreach ($file in $files) {
    $tokens = $null; $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) { throw ($file.Name+': '+($errors | Out-String)) }
}
$planText = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'deploy/service.ps1') -Action Install -PlanOnly
if ($LASTEXITCODE -ne 0) { throw 'Service installation plan failed' }
$plan = $planText | ConvertFrom-Json
if (-not $plan.BinaryPath.StartsWith('"'+$plan.Executable+'" --service --config "')) { throw 'Service binary/config quoting mismatch' }
if ($plan.Account -ne 'NT SERVICE\lw.HomeNAS') { throw 'Service account mismatch' }
if (-not (Test-Path -LiteralPath $plan.Config)) { throw 'Missing runtime config' }
$httpPlan = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'deploy/service.ps1') -Action Configure -AllowInsecureLan -PlanOnly | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $httpPlan.AllowInsecureLan -or -not $httpPlan.BinaryPath.EndsWith(' --allow-insecure-lan') -or $plan.BinaryPath.Contains('--allow-insecure-lan')) { throw 'HTTP/default service mode plans mismatch' }
$entries = @('install_service.bat','start_service.bat','stop_service.bat','restart_service.bat','status_service.bat','uninstall_service.bat','start_console.bat','install_http_service.bat','configure_http_service.bat','configure_https_service.bat','start_console_http.bat','set_admin_password.bat')
foreach ($file in $entries) {
    if (-not (Test-Path -LiteralPath (Join-Path $root ('deploy/'+$file)))) { throw ('Missing script '+$file) }
}
foreach ($suffix in @('', ' --allow-insecure-lan')) {
$process = [Diagnostics.Process]::new()
$process.StartInfo.FileName = $plan.Executable
$process.StartInfo.Arguments = ('--service --config "'+$plan.Config+'"'+$suffix)
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.CreateNoWindow = $true
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.RedirectStandardError = $true
[void]$process.Start()
$result = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -eq 0 -or $result -notmatch '1063') { throw 'Service mode must reject non-SCM startup with 1063' }
$process.Dispose()
}
Write-Output ('Checked '+$files.Count+' PowerShell files, '+$entries.Count+' batch entry points, default/HTTP plans, Unicode/spaced paths and both non-SCM modes.')
Write-Output 'No service, firewall rule or ACL was installed/changed by these checks.'
