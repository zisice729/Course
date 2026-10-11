# Recreate only the disposable test fixture. The demonstration database is never a target.
$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $ProjectRoot
foreach($Managed in @('backend-8082','billing-8094')){
    $PidFile=Join-Path $ProjectRoot ".runtime/$Managed.pid"
    if(Test-Path -LiteralPath $PidFile){
        $ManagedId=[int](Get-Content -LiteralPath $PidFile)
        $Process=Get-CimInstance Win32_Process -Filter "ProcessId=$ManagedId" -ErrorAction SilentlyContinue
        if($Process){
            if($Process.CommandLine -notlike "*$ProjectRoot*"){throw 'Test PID is not owned by this project'}
            if($Managed -eq 'backend-8082'){
                $Config=Get-Content -Raw .runtime/backend-8082.config.json | ConvertFrom-Json
                if($Config.profile -ne 'test' -or $Config.database -ne 'registration_test'){throw 'Refusing to reset a non-test instance'}
            }
            Stop-Process -Id $ManagedId
        }
        Remove-Item -LiteralPath $PidFile
    }
}
python scripts/reset_test_database.py
if($LASTEXITCODE -ne 0){throw 'Test reset failed'}
& (Join-Path $PSScriptRoot 'start.ps1') -Profile test -Database registration_test -BackendPort 8082
