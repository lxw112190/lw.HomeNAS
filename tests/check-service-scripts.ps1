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
foreach ($file in @('install_service.bat','start_service.bat','stop_service.bat','restart_service.bat','status_service.bat','uninstall_service.bat','start_console.bat')) {
    if (-not (Test-Path -LiteralPath (Join-Path $root ('deploy/'+$file)))) { throw ('Missing script '+$file) }
}
$process = [Diagnostics.Process]::new()
$process.StartInfo.FileName = $plan.Executable
$process.StartInfo.Arguments = ('--service --config "'+$plan.Config+'"')
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.CreateNoWindow = $true
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.RedirectStandardError = $true
[void]$process.Start()
$result = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -eq 0 -or $result -notmatch '1063') { throw 'Service mode must reject non-SCM startup with 1063' }
$process.Dispose()
Write-Output ('Checked '+$files.Count+' PowerShell files, 7 batch entry points, Unicode/spaced path plan, and non-SCM rejection.')
Write-Output 'No service, firewall rule or ACL was installed/changed by these checks.'
