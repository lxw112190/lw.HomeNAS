param([ValidateSet('Open','Remove','Status')][string]$Action='Status',[switch]$PlanOnly)
$ErrorActionPreference='Stop'
$layout=(& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'service.ps1') -PlanOnly | ConvertFrom-Json)
if($LASTEXITCODE -ne 0){throw 'Cannot resolve application layout'}
$config=Get-Content -LiteralPath $layout.Config -Raw -Encoding UTF8 | ConvertFrom-Json
$ports=@([int]$config.http_port,[int]$config.https_port)
foreach($port in $ports){if($port -lt 1024 -or $port -gt 65535){throw 'Invalid configured port'}}
$sha=[Security.Cryptography.SHA256]::Create()
try{$tag=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($layout.Executable.ToLowerInvariant())))).Replace('-','').Substring(0,12)}finally{$sha.Dispose()}
$name='lw.HomeNAS-'+$tag
if($PlanOnly){[pscustomobject]@{Action=$Action;Name=$name;Program=$layout.Executable;Protocol='TCP';Ports=$ports;Profile='Any'} | ConvertTo-Json;exit 0}
if($Action -eq 'Status'){Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue | Format-List Name,Enabled,Direction,Action;exit 0}
$principal=New-Object Security.Principal.WindowsPrincipal -ArgumentList ([Security.Principal.WindowsIdentity]::GetCurrent())
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Right-click the batch file and Run as administrator.'}
Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
if($Action -eq 'Open'){
    New-NetFirewallRule -Name $name -DisplayName $name -Group 'lw.HomeNAS managed' -Direction Inbound -Action Allow -Enabled True -Program $layout.Executable -Protocol TCP -LocalPort $ports -Profile Any | Out-Host
    Write-Host ('Opened configured TCP ports: '+($ports -join ', '))
}else{Write-Host 'Removed only the firewall rule owned by this application path.'}
