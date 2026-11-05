param([switch]$Install)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$administrator=([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if($Install -and -not $administrator){throw 'Real SCM test requires an elevated Administrator PowerShell. No service was changed.'}
$fixture=Join-Path $root ('test-results/http-service-'+[Guid]::NewGuid().ToString('N'))
$package=Join-Path $fixture (([string][char]0x6D4B)+[char]0x8BD5+' HTTP (space)')
New-Item -ItemType Directory -Path $package -Force | Out-Null
$exe=Join-Path $root 'build/app/Release/NasServer.exe'
if(-not (Test-Path -LiteralPath $exe)){throw 'Build the CMake Release target first.'}
Copy-Item -LiteralPath $exe -Destination (Join-Path $package 'NasServer.exe')
Copy-Item -LiteralPath (Join-Path $root 'deploy'),(Join-Path $root 'web') -Destination $package -Recurse
New-Item -ItemType Directory -Path (Join-Path $package 'config'),(Join-Path $package 'data') | Out-Null
$config=Get-Content -LiteralPath (Join-Path $root 'config/server.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$listeners=@()
try{
    foreach($number in 1..2){$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$listener.Start();$listeners+=,$listener}
    $config.http_port=$listeners[0].LocalEndpoint.Port;$config.https_port=$listeners[1].LocalEndpoint.Port
}finally{foreach($listener in $listeners){$listener.Stop()}}
$config.https_host='127.0.0.1';$config.allow_insecure_lan=$false
$config.data_dir='../data';$config.web_dir='../web';$config.minimum_free_bytes=0
$utf8=[Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $package 'config/server.json'),($config|ConvertTo-Json),$utf8)
$sentinel=Join-Path $package 'data/keep-original.txt'
[IO.File]::WriteAllText($sentinel,'synthetic original retained by uninstall',$utf8)
$checks=[Collections.Generic.List[string]]::new()
function Check([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$checks.Add($Message);Write-Output ('PASS: '+$Message)}
function Invoke-Entry([string]$Action,[string[]]$Flags=@(),[int]$Expected=0){
    $savedPreference=$ErrorActionPreference
    try{$ErrorActionPreference='Continue';$output=@(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $package 'deploy/service.ps1') -Action $Action @Flags 2>&1);$code=$LASTEXITCODE}
    finally{$ErrorActionPreference=$savedPreference}
    if($code -ne $Expected){throw ('Action '+$Action+' exited '+$code+': '+($output -join "`n"))}
    return ($output -join "`n")
}
function Invoke-HttpBatch([int]$Expected){
    $process=[Diagnostics.Process]::new()
    $process.StartInfo.FileName=$env:ComSpec
    $process.StartInfo.Arguments='/d /c ""'+(Join-Path $package 'deploy/install-service.bat')+'""'
    $process.StartInfo.WorkingDirectory=$env:SystemRoot
    $process.StartInfo.UseShellExecute=$false;$process.StartInfo.CreateNoWindow=$true
    $process.StartInfo.RedirectStandardInput=$true;$process.StartInfo.RedirectStandardOutput=$true;$process.StartInfo.RedirectStandardError=$true
    try{
        [void]$process.Start();$process.StandardInput.WriteLine();$process.StandardInput.Close()
        $output=$process.StandardOutput.ReadToEnd()+$process.StandardError.ReadToEnd();$process.WaitForExit()
        if($process.ExitCode -ne $Expected){throw ('HTTP batch exited '+$process.ExitCode+': '+$output)}
        return $output
    }finally{$process.Dispose()}
}
function Service(){Get-CimInstance Win32_Service -Filter "Name='lw.HomeNAS'"}
function Is-Owned($Service){return $Service -and $Service.PathName -eq ('"'+(Join-Path $package 'NasServer.exe')+'" --service --config "'+(Join-Path $package 'config/server.json')+'"')}
function Http-Identity(){(Invoke-RestMethod -Uri ('http://127.0.0.1:'+$config.http_port+'/api/v1/server') -TimeoutSec 10).server_id}
$attempted=$false;$failure=$null
try{
    $plan=Invoke-Entry Install @('-AllowInsecureLan','-PlanOnly') | ConvertFrom-Json
    Check ($plan.AllowInsecureLan -and $plan.BinaryPath.EndsWith(' --allow-insecure-lan')) 'HTTP Install explicitly overrides false configuration'
    Check ($plan.Config -eq (Join-Path $package 'config/server.json') -and $plan.Data -eq (Join-Path $package 'data')) 'Unicode spaced package paths resolve relative to the config'
    $default=Invoke-Entry Install @('-PlanOnly') | ConvertFrom-Json
    Check (-not $default.AllowInsecureLan -and -not $default.BinaryPath.EndsWith(' --allow-insecure-lan')) 'Default installation follows false configuration'
    $https=Invoke-Entry Configure @('-DisableInsecureLan','-PlanOnly') | ConvertFrom-Json
    Check (-not $https.AllowInsecureLan -and $https.BinaryPath.EndsWith(' --no-allow-insecure-lan')) 'HTTPS configuration explicitly disables LAN HTTP'
    $invalid=Invoke-Entry Install @('-AllowInsecureLan','-DisableInsecureLan','-PlanOnly') 1
    Check ($invalid.Contains('not both')) 'Conflicting mode flags are rejected'
    if(-not $Install){
        if(-not $administrator){
            $rejected=Invoke-HttpBatch 1
            Check ($rejected.Contains('Run as administrator')) 'Actual HTTP batch preserves non-admin failure exit code'
            Check (-not (Test-Path -LiteralPath (Join-Path $package 'data/catalog.db'))) 'Rejected non-admin installation does not initialize data'
        }
    }else{
        if(Service){throw 'lw.HomeNAS already exists. Refusing to alter an existing service; stop is not enough. Use an isolated test machine.'}
        $config.allow_insecure_lan=$true
        [IO.File]::WriteAllText((Join-Path $package 'config/server.json'),($config|ConvertTo-Json),$utf8)
        $attempted=$true
        [void](Invoke-HttpBatch 0)
        $service=Service
        Check ((Is-Owned $service) -and $service.State -eq 'Running' -and $service.StartName -eq 'NT SERVICE\lw.HomeNAS') 'Actual batch creates and starts the isolated virtual-account HTTP service'
        $serverId=Http-Identity
        Check (-not [string]::IsNullOrEmpty($serverId)) 'SCM-started HTTP service answers its API'
        $duplicate=Invoke-HttpBatch 1
        Check ($duplicate.Contains('Service already exists') -and (Is-Owned (Service))) 'Duplicate installation refuses to overwrite the running service'
        [void](Invoke-Entry Status)
        [void](Invoke-Entry Restart)
        Check ((Http-Identity) -eq $serverId) 'Restart preserves the server identity and HTTP availability'
        [void](Invoke-Entry Stop)
        Check ((Service).State -eq 'Stopped') 'Stop waits for the SCM Stopped state'
        [void](Invoke-Entry Start)
        Check ((Http-Identity) -eq $serverId) 'Start restores the same HTTP service'
        Check (Test-Path -LiteralPath (Join-Path $package 'data/logs/service.log')) 'Service writes the expected relative log file'
    }
}catch{$failure=$_}
finally{
    if($attempted){
        try{
            $service=Service
            if($service){if(-not (Is-Owned $service)){throw 'Service path changed; refusing to clean up a service not owned by this test.'};[void](Invoke-Entry Uninstall)}
            $deadline=[DateTime]::UtcNow.AddSeconds(15)
            while((Service) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 200}
            Check (-not (Service)) 'Uninstall removes the test service'
            Check ([IO.File]::ReadAllText($sentinel) -eq 'synthetic original retained by uninstall') 'Uninstall retains original data'
        }catch{if(-not $failure){$failure=$_}else{Write-Warning $_.Exception.Message}}
    }
    $report=[ordered]@{passed=($null -eq $failure);realScm=$Install.IsPresent;administrator=$administrator;checks=@($checks);package=$package;firewallModified=$false;failure=$(if($failure){$failure.Exception.Message}else{$null})}
    [IO.File]::WriteAllText((Join-Path $fixture 'result.json'),($report|ConvertTo-Json -Depth 4),$utf8)
    Write-Output ('Evidence: '+(Join-Path $fixture 'result.json'))
}
if($failure){throw $failure}
Write-Output ('HTTP service checks passed: '+$checks.Count+'; actual SCM installation: '+$Install.IsPresent)
