param([switch]$IncludeDatabase)
$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path $PSScriptRoot -Parent
$RuntimeRoot=Join-Path $ProjectRoot '.runtime'
foreach($PidFile in Get-ChildItem -LiteralPath $RuntimeRoot -Filter '*.pid'){
    if($PidFile.Name -eq 'mysql.pid' -and -not $IncludeDatabase){continue}
    $ManagedId=[int](Get-Content -LiteralPath $PidFile.FullName)
    $Process=Get-CimInstance Win32_Process -Filter "ProcessId=$ManagedId" -ErrorAction SilentlyContinue
    if(-not $Process){continue}
    $Expected=$Process.CommandLine -like "*$ProjectRoot*" -or ($PidFile.Name -eq 'mysql.pid' -and $Process.CommandLine -like '*secd-registration*')
    if(-not $Expected){throw "PID $ManagedId no longer belongs to this project"}
    Stop-Process -Id $ManagedId
    Remove-Item -LiteralPath $PidFile.FullName
}
Write-Output 'Managed project processes stopped.'
