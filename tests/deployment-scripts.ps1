$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$fixture=Join-Path $root ('test-results/deployment-'+[Guid]::NewGuid().ToString('N'))
$package=Join-Path $fixture ('space ('+[char]0x6D4B+[char]0x8BD5+')')
$deploy=Join-Path $package 'deploy'
New-Item -ItemType Directory -Path $deploy,(Join-Path $package 'config'),(Join-Path $fixture 'temp') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'deploy/service.ps1'),(Join-Path $root 'deploy/service-native.cs') -Destination $deploy
$utf8=[Text.UTF8Encoding]::new($false)
$checks=[Collections.Generic.List[string]]::new()
function Check([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$checks.Add($Message);Write-Host ('PASS: '+$Message)}
function Reject-ConflictingFlags {
    $process=[Diagnostics.Process]::new()
    $process.StartInfo.FileName='powershell.exe'
    $process.StartInfo.Arguments='-NoProfile -ExecutionPolicy Bypass -File "'+(Join-Path $deploy 'service.ps1')+'" -Action Install -AllowInsecureLan -DisableInsecureLan'
    $process.StartInfo.UseShellExecute=$false;$process.StartInfo.CreateNoWindow=$true
    $process.StartInfo.RedirectStandardOutput=$true;$process.StartInfo.RedirectStandardError=$true
    $process.StartInfo.EnvironmentVariables['TEMP']=Join-Path $fixture 'temp'
    $process.StartInfo.EnvironmentVariables['TMP']=Join-Path $fixture 'temp'
    try{[void]$process.Start();$output=$process.StandardOutput.ReadToEnd()+$process.StandardError.ReadToEnd();$process.WaitForExit();Check ($process.ExitCode -eq 1 -and $output.Contains('not both')) 'Preflight error preserves failure exit code without reaching SCM';return $output}
    finally{$process.Dispose()}
}
$log=Join-Path $deploy 'logs/service-install.log'
$first=Reject-ConflictingFlags
Check ((Test-Path -LiteralPath $log) -and $first.Contains('Deployment log:')) 'Installation log exists and is displayed before any service creation'
$content=[IO.File]::ReadAllText($log)
Check ($content.Contains('stage=Preflight') -and $content.Contains('FAILED:') -and $content.Contains('PowerShell=')) 'Pre-start failure records stage, system environment and error'
Check (-not (Test-Path -LiteralPath (Join-Path $package 'data'))) 'Preflight rejection does not create application data'
[IO.File]::WriteAllText($log,('x' * 1MB),$utf8)
[void](Reject-ConflictingFlags)
Check ((Test-Path -LiteralPath ($log+'.1')) -and (Get-Item -LiteralPath $log).Length -lt 4096) 'Oversized deployment log rotates before appending'
# Block the normal log path, then verify the isolated TEMP fallback.
$blocked=Join-Path $deploy 'logs'
Move-Item -LiteralPath $blocked -Destination (Join-Path $deploy 'retained-logs')
[IO.File]::WriteAllText($blocked,'blocked',$utf8)
$fallback=Reject-ConflictingFlags
$fallbackLog=Join-Path $fixture 'temp/lw.HomeNAS-deploy/service-install.log'
Check ((Test-Path -LiteralPath $fallbackLog) -and $fallback.Contains('lw.HomeNAS-deploy')) 'Unwritable deployment log location falls back to TEMP and reports the path'
# Invalid JSON may contain a bootstrap password: its original parser exception must never be printed.
[IO.File]::WriteAllText((Join-Path $package 'NasServer.exe'),'synthetic placeholder, never executed',$utf8)
[IO.File]::WriteAllText((Join-Path $package 'config/server.json'),'{"bootstrap_admin_password":"NeverLogSentinel" BAD JSON}',$utf8)
$saved=$ErrorActionPreference
try{$ErrorActionPreference='Continue';$output=@(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $deploy 'service.ps1') -Action Install -PlanOnly 2>&1);$code=$LASTEXITCODE}finally{$ErrorActionPreference=$saved}
$text=$output -join "`n"
Check ($code -eq 1 -and $text.Contains('valid UTF-8 JSON') -and -not $text.Contains('NeverLogSentinel')) 'Invalid configuration error does not expose bootstrap credentials'
Add-Type -Path (Join-Path $deploy 'service-native.cs')
$description=[HomeNas.Deployment.NativeService]::DescribeError('CreateServiceW',1057)
Check ($description.Contains('CreateServiceW') -and $description.Contains('Win32 error 1057')) 'Native helper compiles and preserves operation and precise Win32 error code'
# Exercise the actual Windows argument parser through a harmless argv echo executable.
$probe=Join-Path $fixture 'argument probe.exe'
Add-Type -TypeDefinition 'using System; using System.Text; public class ArgumentProbe { public static void Main(string[] args) { foreach(string value in args) Console.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(value))); } }' -OutputAssembly $probe -OutputType ConsoleApplication
$binary='"G:\space ('+[char]0x6D4B+[char]0x8BD5+') & %test%\NasServer.exe" --service --config "G:\space\config\server.json"'
$name='test"quoted\'
$arguments=[HomeNas.Deployment.NativeService]::BuildScCreateArguments($name,$binary,'NT SERVICE\lw.HomeNAS')
$process=[Diagnostics.Process]::new();$process.StartInfo.FileName=$probe;$process.StartInfo.Arguments=$arguments
$process.StartInfo.UseShellExecute=$false;$process.StartInfo.CreateNoWindow=$true;$process.StartInfo.RedirectStandardOutput=$true
try{[void]$process.Start();$echo=$process.StandardOutput.ReadToEnd();$process.WaitForExit();$echoCode=$process.ExitCode}finally{$process.Dispose()}
$actual=@($echo.Trim() -split "`r?`n" | ForEach-Object {[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_))})
$expected=@('create',$name,'binPath=',$binary,'start=','auto','type=','own','error=','normal','obj=','NT SERVICE\lw.HomeNAS','DisplayName=',$name)
Check ($echoCode -eq 0 -and (@(Compare-Object $expected $actual -SyncWindow 0).Count -eq 0)) 'sc.exe arguments preserve Unicode, embedded quotes, trailing slashes and shell characters without a password argument'
# Execute the production PowerShell fallback logic against a test-only native adapter.
Add-Type -TypeDefinition @'
using System; using System.ComponentModel;
namespace HomeNas.TestDeployment {
 public static class NativeService {
  public static int NativeCode, ScCode, ScCalls; public static bool Present; public static string Captured;
  public sealed class Result { public int ExitCode; public string Output="synthetic sc.exe output"; public string Error=""; }
  public static void Create(string name,string path,string account) { if(NativeCode!=0)throw new Win32Exception(NativeCode,"synthetic native failure"); }
  public static bool Exists(string name) { return Present; }
  public static Result CreateWithSc(string name,string path,string account) { ScCalls++; Captured=name+"|"+path+"|"+account; return new Result{ExitCode=ScCode}; }
 }
}
'@
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'deploy/service.ps1'),[ref]$tokens,[ref]$parseErrors)
$function=$ast.Find({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'New-NativeNasService'},$true)
if(-not $function){throw 'Missing production installation function'}
. ([scriptblock]::Create($function.Extent.Text.Replace('HomeNas.Deployment.NativeService','HomeNas.TestDeployment.NativeService')))
$script:messages=[Collections.Generic.List[string]]::new()
function Write-OperationLog([string]$Message){$script:messages.Add($Message)}
$serviceName='lw.HomeNAS';$layout=[pscustomobject]@{BinaryPath=$binary;Account='NT SERVICE\lw.HomeNAS'}
[HomeNas.TestDeployment.NativeService]::NativeCode=0
New-NativeNasService $layout
Check ([HomeNas.TestDeployment.NativeService]::ScCalls -eq 0) 'Native success does not run a second creation command'
[HomeNas.TestDeployment.NativeService]::NativeCode=1783
New-NativeNasService $layout
Check ([HomeNas.TestDeployment.NativeService]::ScCalls -eq 1 -and [HomeNas.TestDeployment.NativeService]::Captured -eq ($serviceName+'|'+$binary+'|'+$layout.Account)) '1783 retries once with identical service name, binary path and virtual account'
[HomeNas.TestDeployment.NativeService]::ScCode=1783
$failure=$null;try{New-NativeNasService $layout}catch{$failure=$_}
Check ($failure -and $failure.Exception.Message.Contains('also failed, exit code 1783') -and ($script:messages -join "`n").Contains('sc.exe exit code=1783')) 'Both native and sc.exe failures remain visible and do not report success'
[HomeNas.TestDeployment.NativeService]::NativeCode=1057
$calls=[HomeNas.TestDeployment.NativeService]::ScCalls
$failure=$null;try{New-NativeNasService $layout}catch{$failure=$_}
Check ($failure -and [HomeNas.TestDeployment.NativeService]::ScCalls -eq $calls) 'Non-1783 native errors are not retried or hidden'
[HomeNas.TestDeployment.NativeService]::NativeCode=1783;[HomeNas.TestDeployment.NativeService]::Present=$true
$failure=$null;try{New-NativeNasService $layout}catch{$failure=$_}
Check ($failure -and $failure.Exception.Message.Contains('service now exists') -and [HomeNas.TestDeployment.NativeService]::ScCalls -eq $calls) 'An existing service after failed RPC prevents any retry or adoption'
$virtualPlan=[HomeNas.Deployment.NativeService]::PlanDisabledProbe($true)
$localPlan=[HomeNas.Deployment.NativeService]::PlanDisabledProbe($false)
Check ($virtualPlan.Name -match '^lw\.HomeNAS\.Probe\.[0-9a-f]{32}\.V$' -and $virtualPlan.Account -eq ('NT SERVICE\'+$virtualPlan.Name) -and $localPlan.Account -eq 'NT AUTHORITY\LocalService' -and $virtualPlan.Name -ne $localPlan.Name) 'Account comparison plans use unique probe names and the corresponding virtual/builtin identities'
Check (-not $virtualPlan.Started -and -not $localPlan.Started -and $virtualPlan.BinaryPath -eq ('"'+(Join-Path ([Environment]::SystemDirectory) 'cmd.exe')+'" /c exit 0')) 'Probe plans never use the NAS executable or request startup'
Copy-Item -LiteralPath (Join-Path $root 'deploy/diagnose-service.ps1') -Destination $deploy
$diagnosticPlan=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $deploy 'diagnose-service.ps1') -ProbeCreation -PlanOnly | ConvertFrom-Json
Check ($LASTEXITCODE -eq 0 -and $diagnosticPlan.ProbeCreation -and $diagnosticPlan.ProbeStartType -eq 'Disabled' -and -not $diagnosticPlan.ProbeStarted -and -not $diagnosticPlan.ExistingNasModified -and -not $diagnosticPlan.SystemPolicyModified) 'Diagnostic PlanOnly describes disabled probes without invoking service creation'
# Restore a writable fixture log directory; never run creation probes on the developer machine.
Remove-Item -LiteralPath (Join-Path $deploy 'logs')
$output=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $deploy 'diagnose-service.ps1')
$reports=@(Get-ChildItem -LiteralPath (Join-Path $deploy 'logs') -Filter 'service-diagnostics-*.json')
$diagnostic=Get-Content -LiteralPath $reports[0].FullName -Raw -Encoding UTF8 | ConvertFrom-Json
Check ($LASTEXITCODE -eq 0 -and $reports.Count -eq 1 -and -not $diagnostic.probeRequested -and @($diagnostic.probes).Count -eq 0 -and @($diagnostic.systemFiles).Count -eq 4) 'Default diagnostics save system metadata with no creation probes'
Write-Output ('Deployment diagnostic checks passed: '+$checks.Count+'. No service, firewall rule or ACL was changed.')
