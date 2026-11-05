param([switch]$ProbeCreation,[switch]$PlanOnly)
$ErrorActionPreference='Stop'
if($PlanOnly){
    [pscustomobject]@{ProbeCreation=$ProbeCreation.IsPresent;ProbeStartType='Disabled';ProbeStarted=$false;ProbeAccounts=@('NT SERVICE\<unique probe name>','NT AUTHORITY\LocalService');ExistingNasModified=$false;SystemPolicyModified=$false} | ConvertTo-Json
    exit 0
}
$report=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');os=[Environment]::OSVersion.VersionString;powerShell=$PSVersionTable.PSVersion.ToString();process64Bit=[Environment]::Is64BitProcess;probeRequested=$ProbeCreation.IsPresent;services=@();systemFiles=@();scmEvents=@();probes=@();errors=@()}
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$report.administrator=([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
foreach($name in @('RpcSs','DcomLaunch','RpcEptMapper','Winmgmt','BFE','MpsSvc')) {
    try{$service=Get-Service -Name $name;try{$report.services+=@{name=$name;status=$service.Status.ToString();startType=$service.StartType.ToString()}}finally{$service.Dispose()}}
    catch{$report.errors+=('Cannot query service '+$name)}
}
foreach($name in @('advapi32.dll','sechost.dll','rpcrt4.dll','sc.exe')) {
    try{$file=Get-Item -LiteralPath (Join-Path ([Environment]::SystemDirectory) $name);$report.systemFiles+=@{name=$name;version=$file.VersionInfo.FileVersion}}
    catch{$report.errors+=('Cannot inspect system file '+$name)}
}
try{
    $events=Get-WinEvent -FilterHashtable @{LogName='System';ProviderName='Service Control Manager';Level=2,3;StartTime=(Get-Date).AddHours(-2)} -MaxEvents 12 -ErrorAction Stop
    foreach($event in $events){$report.scmEvents+=@{utc=$event.TimeCreated.ToUniversalTime().ToString('o');id=$event.Id;message=$event.Message}}
}catch{$report.errors+='No recent SCM warning/error events were available, or access was denied.'}
$exitCode=0
if($ProbeCreation) {
    if(-not $report.administrator){$report.errors+='Creation probes require an Administrator PowerShell. No probe was executed.';$exitCode=1}
    else {
        try {
            Add-Type -Path (Join-Path $PSScriptRoot 'service-native.cs')
            foreach($virtual in @($true,$false)){
                $probe=[HomeNas.Deployment.NativeService]::ProbeDisabledService($virtual)
                $report.probes+=$probe
                Write-Host ('Probe account='+$probe.Account+' create='+$probe.CreateCode+' deleted='+$probe.Deleted+' cleanup='+$probe.DeleteCode)
                if($probe.CreateCode -ne 0 -or -not $probe.Deleted){$exitCode=1}
            }
        }catch{$report.errors+=('Probe failed: '+$_.Exception.GetBaseException().Message);$exitCode=1}
    }
}
# Explicit metadata only. Do not collect config, NAS media, credentials or protection-software settings.
$saved=$false
foreach($directory in @((Join-Path $PSScriptRoot 'logs'),(Join-Path ([IO.Path]::GetTempPath()) 'lw.HomeNAS-deploy'))) {
    try {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $path=Join-Path $directory ('service-diagnostics-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)+'.json')
        [IO.File]::WriteAllText($path,($report|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
        Write-Host ('Diagnostic report: '+$path)
        $saved=$true;break
    }catch{continue}
}
if(-not $saved){Write-Error 'Cannot save diagnostic report.';exit 1}
if(-not $ProbeCreation){Write-Host 'Read-only report complete. No service, ACL, firewall or policy was changed.'}
else{Write-Host 'Probes were disabled and never started. Existing lw.HomeNAS, data, ACL, firewall and policies were not changed. Check cleanup results in the report.'}
exit $exitCode
