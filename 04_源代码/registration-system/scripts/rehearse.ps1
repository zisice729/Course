param([switch]$ResetFixture)
$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $ProjectRoot
if($ResetFixture){
    python scripts/backup.py --database registration_test
    if($LASTEXITCODE -ne 0){throw 'Backup failed; refusing test reset'}
    & scripts/reset_test.ps1
}else{& scripts/start.ps1 -Profile test -Database registration_test -BackendPort 8082}
$NodeRoot=Join-Path $ProjectRoot '.tools/node-v22.23.3-win-x64'
$env:VITE_API_TARGET='http://127.0.0.1:8082'
if(-not (Get-NetTCPConnection -State Listen -LocalPort 8088 -ErrorAction SilentlyContinue)){
    $Vite=Join-Path $ProjectRoot 'frontend/node_modules/vite/bin/vite.js'
    $Process=Start-Process -FilePath (Join-Path $NodeRoot 'node.exe') -ArgumentList @(('"'+$Vite+'"'),'--host','127.0.0.1','--port','8088','--strictPort') -WorkingDirectory (Join-Path $ProjectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput '.runtime/frontend-rehearsal.out.log' -RedirectStandardError '.runtime/frontend-rehearsal.err.log' -PassThru
    $Process.Id | Set-Content .runtime/frontend-rehearsal.pid
}
Write-Output 'Isolated rehearsal: http://127.0.0.1:8088 ; DB registration_test only. Normal demo8080 unchanged.'
