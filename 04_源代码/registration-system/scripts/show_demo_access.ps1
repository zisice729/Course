$ProjectRoot=Split-Path $PSScriptRoot -Parent
$Secrets=Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot '.runtime/secrets.json') | ConvertFrom-Json
Write-Output 'Browser: http://127.0.0.1:8080'
Write-Output 'Student s1; professor p1; registrar registrar'
Write-Output "Demo password (local only): $($Secrets.demoPassword)"
