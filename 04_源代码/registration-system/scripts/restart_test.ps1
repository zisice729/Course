param([switch]$WithBilling,[switch]$StopOnly)
$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $ProjectRoot
$Config=Get-Content -Raw .runtime/backend-8082.config.json | ConvertFrom-Json
if($Config.profile -ne 'test' -or $Config.database -ne 'registration_test'){throw 'Refusing to stop a non-test instance'}
$ManagedNames=@('backend-8082')
if($WithBilling){$ManagedNames+=@('billing-8094')}
foreach($Managed in $ManagedNames){
    $PidFile=Join-Path $ProjectRoot ".runtime/$Managed.pid"
    if(Test-Path -LiteralPath $PidFile){
        $ManagedId=[int](Get-Content -LiteralPath $PidFile)
        $Process=Get-CimInstance Win32_Process -Filter "ProcessId=$ManagedId" -ErrorAction SilentlyContinue
        if($Process){if($Process.CommandLine -notlike "*$ProjectRoot*"){throw 'PID not owned by project'};Stop-Process -Id $ManagedId}
        Remove-Item -LiteralPath $PidFile
    }
}
if(-not $StopOnly){& scripts/start.ps1 -Profile test -Database registration_test -BackendPort 8082}
