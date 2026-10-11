$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $ProjectRoot
$Config=Get-Content -Raw .runtime/backend-8082.config.json | ConvertFrom-Json
if($Config.profile -ne 'test' -or $Config.database -ne 'registration_test'){throw 'Only the isolated test instance may be stopped/restored'}
foreach($Managed in @('backend-8082','billing-8094')){
    $PidFile=Join-Path $ProjectRoot ".runtime/$Managed.pid"
    if(Test-Path -LiteralPath $PidFile){
        $ManagedId=[int](Get-Content -LiteralPath $PidFile)
        $Process=Get-CimInstance Win32_Process -Filter "ProcessId=$ManagedId" -ErrorAction SilentlyContinue
        if($Process){if($Process.CommandLine -notlike "*$ProjectRoot*"){throw 'PID does not belong to project'};Stop-Process -Id $ManagedId}
        Remove-Item -LiteralPath $PidFile
    }
}
python tests/backup_restore.py
if($LASTEXITCODE -ne 0){throw 'Backup restore check failed'}
& scripts/start.ps1 -Profile test -Database registration_test -BackendPort 8082
