$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $ProjectRoot
$PidFile=Join-Path $ProjectRoot '.runtime/availability-monitor.pid'
if(Test-Path -LiteralPath $PidFile){
    $ManagedId=[int](Get-Content -LiteralPath $PidFile)
    $Process=Get-CimInstance Win32_Process -Filter "ProcessId=$ManagedId" -ErrorAction SilentlyContinue
    if($Process){if($Process.CommandLine -notlike "*$ProjectRoot*"){throw 'PID reused by unrelated process'};Write-Output 'Existing observation left running';exit}
}
$Script=Join-Path $PSScriptRoot 'health_monitor.py'
$Process=Start-Process -FilePath (Get-Command python).Source -ArgumentList @(('"'+$Script+'"'),'--days','7','--interval','60') -WindowStyle Hidden -RedirectStandardOutput '.runtime/availability.out.log' -RedirectStandardError '.runtime/availability.err.log' -PassThru
$Process.Id | Set-Content -LiteralPath $PidFile
Write-Output 'Seven-day observation started; completion pending. View actual records using health_monitor.py --summary.'
