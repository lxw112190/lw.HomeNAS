param(
    [ValidateSet('Install','Start','Stop','Restart','Status','Uninstall','Console')][string]$Action = 'Status',
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
$serviceName = 'lw.HomeNAS'
$packageRoot = Split-Path -Parent $PSScriptRoot

function Get-Layout {
    $candidates = @((Join-Path $packageRoot 'NasServer.exe'), (Join-Path $packageRoot 'out/Release/NasServer.exe'), (Join-Path $packageRoot 'build/app/Release/NasServer.exe'))
    $exe = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $exe) { throw 'NasServer.exe not found. Build Release x64 or extract the complete deployment package.' }
    $exe = [IO.Path]::GetFullPath($exe)
    $runtimeRoot = Split-Path -Parent $exe
    $configPath = Join-Path $runtimeRoot 'config/server.json'
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
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
        BinaryPath = ('"'+$exe+'" --service --config "'+$configPath+'"')
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
    if (-not $service) { throw 'Service not installed. Run install_service.bat first.' }
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
try {
    if ($PlanOnly) { Get-Layout | ConvertTo-Json -Depth 3; exit 0 }
    if ($Action -eq 'Console') {
        $layout = Get-Layout
        & $layout.Executable --config $layout.Config
        exit $LASTEXITCODE
    }
    if ($Action -eq 'Status') {
        $service = Get-NasService
        if (-not $service) { Write-Host 'lw.HomeNAS is not installed.'; exit 0 }
        $service | Format-List Name,DisplayName,Status,StartType
        Get-CimInstance -ClassName Win32_Service -Filter "Name='$serviceName'" | Select-Object PathName,StartName,ProcessId,ExitCode | Format-List
        $service.Dispose(); exit 0
    }
    Require-Administrator
    switch ($Action) {
        'Install' {
            $layout = Get-Layout
            if (Get-NasService) { throw 'Service already exists; use start/stop/restart/status. This installer will not delete or overwrite an existing service.' }
            New-Item -ItemType Directory -Path $layout.Data -Force | Out-Null
            # Structured CIM argument preserves embedded quotes and Unicode paths.
            # Omit StartPassword: virtual service accounts require a NULL password.
            $created = Invoke-CimMethod -ClassName Win32_Service -MethodName Create -Arguments @{
                Name=$serviceName;DisplayName='lw.HomeNAS';PathName=$layout.BinaryPath;
                ServiceType=[byte]16;ErrorControl=[byte]1;StartMode='Automatic';
                DesktopInteract=$false;StartName=$layout.Account
            }
            if ($created.ReturnValue -ne 0) { throw ('Create service failed, Win32_Service code '+$created.ReturnValue) }
            & sc.exe sidtype $serviceName unrestricted | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Unable to enable the service SID.' }
            $sid = ([Security.Principal.NTAccount]::new($layout.Account)).Translate([Security.Principal.SecurityIdentifier]).Value
            Grant-Directory $layout.RuntimeRoot 'RX' $sid
            if (-not $layout.Web.StartsWith($layout.RuntimeRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { Grant-Directory $layout.Web 'RX' $sid }
            Grant-Directory $layout.Data 'M' $sid
            Start-NasService
            Write-Host ('Installed and running: '+$layout.HttpUrl)
            Write-Host ('Initial username: admin; initial password file: '+(Join-Path $layout.Data 'initial-admin-password.txt'))
            Write-Host ('Service log: '+(Join-Path $layout.Data 'logs/service.log'))
        }
        'Start' { Start-NasService; Write-Host 'lw.HomeNAS is running.' }
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
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    Write-Host 'Check status_service.bat, Windows Event Viewer, port conflicts, and data/logs/service.log.'
    exit 1
}
