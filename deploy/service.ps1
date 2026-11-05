param(
    [ValidateSet('Install','Configure','Start','Stop','Restart','Status','Uninstall','Console','AdminPassword')][string]$Action = 'Status',
    [switch]$AllowInsecureLan,
    [switch]$DisableInsecureLan,
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
$serviceName = 'lw.HomeNAS'
$packageRoot = Split-Path -Parent $PSScriptRoot
$httpArguments=if($AllowInsecureLan){' --allow-insecure-lan'}elseif($DisableInsecureLan){' --no-allow-insecure-lan'}else{''}
$operationLog = $null
$stage = 'Preflight'

function Initialize-OperationLog {
    # No transcript: Console/AdminPassword may display a password. Log only explicit diagnostics.
    foreach($directory in @((Join-Path $PSScriptRoot 'logs'),(Join-Path ([IO.Path]::GetTempPath()) 'lw.HomeNAS-deploy'))) {
        try {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
            $path=Join-Path $directory 'service-install.log'
            if((Test-Path -LiteralPath $path) -and (Get-Item -LiteralPath $path).Length -ge 1MB) {
                if(Test-Path -LiteralPath ($path+'.3')){Remove-Item -LiteralPath ($path+'.3') -Force}
                foreach($number in 2,1){if(Test-Path -LiteralPath ($path+'.'+$number)){Move-Item -LiteralPath ($path+'.'+$number) -Destination ($path+'.'+($number+1)) -Force}}
                Move-Item -LiteralPath $path -Destination ($path+'.1') -Force
            }
            [IO.File]::AppendAllText($path,([DateTime]::UtcNow.ToString('o')+' BEGIN action='+$Action+"`r`n"),[Text.UTF8Encoding]::new($false))
            $script:operationLog=$path
            Write-Host ('Deployment log: '+$path)
            return
        }catch{continue}
    }
    Write-Warning 'Cannot write deployment log to deploy/logs or the temporary folder.'
}
function Write-OperationLog([string]$Message) {
    if($operationLog) {
        try{[IO.File]::AppendAllText($operationLog,([DateTime]::UtcNow.ToString('o')+' stage='+$stage+' '+$Message+"`r`n"),[Text.UTF8Encoding]::new($false))}
        catch{Write-Warning 'Cannot append deployment log.'}
    }
}

function Get-Layout {
    $candidates = @((Join-Path $packageRoot 'NasServer.exe'), (Join-Path $packageRoot 'out/Release/NasServer.exe'), (Join-Path $packageRoot 'build/app/Release/NasServer.exe'))
    $exe = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $exe) { throw 'NasServer.exe not found. Build Release x64 or extract the complete deployment package.' }
    $exe = [IO.Path]::GetFullPath($exe)
    $runtimeRoot = Split-Path -Parent $exe
    $configPath = Join-Path $runtimeRoot 'config/server.json'
    # JSON parser exceptions can quote the entire config, including bootstrap credentials.
    try{$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json}
    catch{throw 'Cannot read config/server.json. Check that it exists and contains valid UTF-8 JSON.'}
    if($config.PSObject.Properties.Name -contains 'allow_insecure_lan' -and $config.allow_insecure_lan -isnot [bool]){throw 'allow_insecure_lan must be true or false.'}
    $effectiveHttp=if($AllowInsecureLan){$true}elseif($DisableInsecureLan){$false}elseif($config.PSObject.Properties.Name -contains 'allow_insecure_lan'){[bool]$config.allow_insecure_lan}else{$true}
    $configRoot = Split-Path -Parent $configPath
    $dataPath = [IO.Path]::GetFullPath(([IO.Path]::Combine($configRoot,[string]$config.data_dir))).TrimEnd('\')
    $webPath = [IO.Path]::GetFullPath(([IO.Path]::Combine($configRoot,[string]$config.web_dir))).TrimEnd('\')
    $driveRoot = [IO.Path]::GetPathRoot($dataPath).TrimEnd('\')
    $windowsRoot = [Environment]::GetFolderPath('Windows').TrimEnd('\')
    if ($dataPath -eq $driveRoot -or $dataPath -eq $runtimeRoot.TrimEnd('\') -or $dataPath -eq $windowsRoot -or $dataPath.StartsWith($windowsRoot+'\',[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unsafe data_dir: choose a dedicated data folder, not a drive root, application root or Windows directory.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $webPath 'index.html'))) { throw 'Web resources not found.' }
    [pscustomobject]@{
        Service = $serviceName
        Executable = $exe
        Config = $configPath
        RuntimeRoot = $runtimeRoot
        Data = $dataPath
        Web = $webPath
        Account = "NT SERVICE\$serviceName"
        BinaryPath = ('"'+$exe+'" --service --config "'+$configPath+'"'+$httpArguments)
        AllowInsecureLan = $effectiveHttp
        HttpUrl = ('http://127.0.0.1:'+[int]$config.http_port+'/')
    }
}
function Require-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Please right-click the batch file and select Run as administrator.' }
}
function Get-NasService { Get-Service -Name $serviceName -ErrorAction SilentlyContinue }
function Wait-State([string]$Expected) {
    $service = Get-NasService
    if (-not $service) { throw 'Service not installed.' }
    try { $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::$Expected, [TimeSpan]::FromSeconds(60)) }
    finally { $service.Dispose() }
}
function Stop-NasService {
    $service = Get-NasService
    if (-not $service) { throw 'Service not installed.' }
    try {
        $service.Refresh()
        if ($service.Status -eq 'Stopped') { return }
        if ($service.Status -ne 'StopPending') { Stop-Service -Name $serviceName -ErrorAction Stop }
    } finally { $service.Dispose() }
    Wait-State 'Stopped'
}
function Start-NasService {
    $service = Get-NasService
    if (-not $service) { throw 'Service not installed. Run install-service.bat first.' }
    try {
        $service.Refresh()
        if ($service.Status -eq 'Running') { return }
        if ($service.Status -ne 'StartPending') { Start-Service -Name $serviceName -ErrorAction Stop }
    } finally { $service.Dispose() }
    Wait-State 'Running'
}
function Grant-Directory([string]$Path,[string]$Rights,[string]$Sid) {
    & icacls.exe $Path /grant ('*'+$Sid+':(OI)(CI)'+$Rights) | Out-Host
    if ($LASTEXITCODE -ne 0) { throw ('Cannot grant service access to '+$Path) }
}
function New-NativeNasService($Layout) {
    if(-not ('HomeNas.Deployment.NativeService' -as [type])) {
        Add-Type -Path (Join-Path $PSScriptRoot 'service-native.cs')
    }
    try {
        [HomeNas.Deployment.NativeService]::Create($serviceName,$Layout.BinaryPath,$Layout.Account)
    } catch {
        $nativeFailure=$_.Exception.GetBaseException()
        if($nativeFailure -isnot [ComponentModel.Win32Exception] -or $nativeFailure.NativeErrorCode -ne 1783){throw}
        Write-OperationLog ('Native creation failed: '+$nativeFailure.Message)
        # A failed RPC may have reached SCM. Never adopt, overwrite or delete an existing service.
        if([HomeNas.Deployment.NativeService]::Exists($serviceName)) {
            throw 'Native creation returned 1783 but the service now exists. Refusing to retry; inspect its configuration before continuing.'
        }
        Write-Host 'CreateServiceW returned 1783. Trying Windows sc.exe once with the same virtual account.'
        $script:stage='CreateServiceScExe'
        $result=[HomeNas.Deployment.NativeService]::CreateWithSc($serviceName,$Layout.BinaryPath,$Layout.Account)
        Write-OperationLog ('sc.exe exit code='+$result.ExitCode)
        foreach($line in @($result.Output,$result.Error)) {
            if($line){Write-Host $line;Write-OperationLog ('sc.exe: '+$line.Trim())}
        }
        if($result.ExitCode -ne 0) {
            throw ('Native CreateServiceW failed with 1783; Windows sc.exe also failed, exit code '+$result.ExitCode+'. Check Windows service/RPC policies and protection software logs; no account or firewall setting was changed.')
        }
    }
}
try {
    if(-not $PlanOnly -and $Action -notin @('Console','AdminPassword')) {
        Initialize-OperationLog
        Write-OperationLog ('OS='+[Environment]::OSVersion.VersionString+'; PowerShell='+$PSVersionTable.PSVersion+'; Process64Bit='+[Environment]::Is64BitProcess)
    }
    if($AllowInsecureLan -and $DisableInsecureLan){throw 'Choose AllowInsecureLan or DisableInsecureLan, not both.'}
    if ($PlanOnly) { Get-Layout | ConvertTo-Json -Depth 3; exit 0 }
    if ($Action -eq 'AdminPassword') {
        $layout = Get-Layout
        # Inherit the console directly instead of PowerShell 5.1 decoding native output through a pipe.
        $process = Start-Process -FilePath $layout.Executable -ArgumentList ('--config "'+$layout.Config+'" --initialize-admin') -NoNewWindow -Wait -PassThru
        try { exit $process.ExitCode } finally { $process.Dispose() }
    }
    if ($Action -eq 'Console') {
        $layout = Get-Layout
        $arguments = '--config "'+$layout.Config+'"'
        $arguments += $httpArguments
        $process = Start-Process -FilePath $layout.Executable -ArgumentList $arguments -NoNewWindow -Wait -PassThru
        try { exit $process.ExitCode } finally { $process.Dispose() }
    }
    if ($Action -eq 'Status') {
        $service = Get-NasService
        if (-not $service) { Write-Host 'lw.HomeNAS is not installed.'; exit 0 }
        $service | Format-List Name,DisplayName,Status,StartType
        Get-CimInstance -ClassName Win32_Service -Filter "Name='$serviceName'" | Select-Object PathName,StartName,ProcessId,ExitCode | Format-List
        $service.Dispose(); exit 0
    }
    Require-Administrator
    Write-OperationLog 'Administrator=True'
    switch ($Action) {
        'Install' {
            $layout = Get-Layout
            if (Get-NasService) { throw 'Service already exists; use start/stop/restart/status. This installer will not delete or overwrite an existing service.' }
            Write-OperationLog ('Executable='+$layout.Executable+'; Account='+$layout.Account+'; Config='+$layout.Config+'; Data='+$layout.Data+'; LAN HTTP='+$layout.AllowInsecureLan)
            New-Item -ItemType Directory -Path $layout.Data -Force | Out-Null
            $stage='CreateServiceW'
            New-NativeNasService $layout
            Write-OperationLog 'Service created; subsequent failures leave it installed for inspection.'
            $stage='ServiceSid'
            & sc.exe sidtype $serviceName unrestricted | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Unable to enable the service SID.' }
            $stage='RecoveryPolicy'
            & sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/15000 | Out-Host
            if($LASTEXITCODE -ne 0){throw 'Unable to configure service restart recovery.'}
            & sc.exe failureflag $serviceName 1 | Out-Host
            if($LASTEXITCODE -ne 0){throw 'Unable to enable service failure recovery.'}
            $sid = ([Security.Principal.NTAccount]::new($layout.Account)).Translate([Security.Principal.SecurityIdentifier]).Value
            $stage='DirectoryPermissions'
            Grant-Directory $layout.RuntimeRoot 'RX' $sid
            if (-not $layout.Web.StartsWith($layout.RuntimeRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { Grant-Directory $layout.Web 'RX' $sid }
            Grant-Directory $layout.Data 'M' $sid
            $stage='StartService'
            Start-NasService
            Write-OperationLog 'Service running'
            Write-Host ('Installed and running: '+$layout.HttpUrl)
            Write-Host ('Initial username: admin; initial password file: '+(Join-Path $layout.Data 'initial-admin-password.txt'))
            Write-Host ('Service log: '+(Join-Path $layout.Data 'logs/service.log'))
        }
        'Start' { Start-NasService; Write-Host 'lw.HomeNAS is running.' }
        'Configure' {
            $layout = Get-Layout
            $existing = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
            if (-not $existing) { throw 'Service not installed. Use install-service.bat first.' }
            $expectedPrefix = '"'+$layout.Executable+'"'
            if (-not $existing.PathName.StartsWith($expectedPrefix+' ',[StringComparison]::OrdinalIgnoreCase)) { throw 'The installed service points to another application path. Configure it from the matching installation directory.' }
            $wasRunning = $existing.State -ne 'Stopped'
            Stop-NasService
            $changed = Invoke-CimMethod -InputObject $existing -MethodName Change -Arguments @{PathName=$layout.BinaryPath}
            if ($changed.ReturnValue -ne 0) { throw ('Service configuration failed, code '+$changed.ReturnValue) }
            Write-Host ('LAN HTTP enabled: '+$layout.AllowInsecureLan)
            if ($wasRunning) { Start-NasService; Write-Host 'Configuration applied and service restarted.' }
            else { Write-Host 'Configuration applied. Service remains stopped; run start_service.bat when ready.' }
        }
        'Stop' { Stop-NasService; Write-Host 'lw.HomeNAS is stopped; data retained.' }
        'Restart' { Stop-NasService; Start-NasService; Write-Host 'lw.HomeNAS restarted.' }
        'Uninstall' {
            if (-not (Get-NasService)) { Write-Host 'Service is not installed.'; exit 0 }
            Stop-NasService
            $removed = Invoke-CimMethod -InputObject (Get-CimInstance Win32_Service -Filter "Name='$serviceName'") -MethodName Delete
            if ($removed.ReturnValue -ne 0) { throw ('Delete service failed, code '+$removed.ReturnValue) }
            Write-Host 'Service uninstalled. Application files, certificates and all data were retained.'
        }
    }
    Write-OperationLog 'Action completed'
} catch {
    $failure=$_.Exception.GetBaseException()
    Write-OperationLog ('FAILED: '+$failure.Message)
    Write-Error -Message $failure.Message -ErrorAction Continue
    if($operationLog){Write-Host ('Deployment log: '+$operationLog)}
    Write-Host ('Failed stage: '+$stage)
    Write-Host 'If creation failed, NasServer.exe has not started and service.log will not exist.'
    Write-Host 'Check deploy/logs/service-install.log and Windows Event Viewer. For startup failures also check data/logs/service.log.'
    exit 1
}
