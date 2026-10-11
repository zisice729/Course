param([ValidateSet('demo','test')][string]$Profile='demo',[string]$Database='registration',[int]$BackendPort=8081,[switch]$Build)
$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $ProjectRoot
. (Join-Path $PSScriptRoot 'resolve_java.ps1')
$ProjectJdk = Resolve-ProjectJava
$JavaExe = $ProjectJdk.JavaExe
$MavenExe = Join-Path $ProjectRoot '.tools/apache-maven-3.9.11/bin/mvn.cmd'
if (-not (Test-Path -LiteralPath $MavenExe -PathType Leaf)) {
    throw 'Project Maven 3.9.11 is missing. Run: python scripts/bootstrap_tools.py'
}
# A delivery copy must not attach to services from another project directory.
foreach($CheckPort in @(3307,8080,8081,8082,8091,8092,8094,8088)){
    foreach($Listener in @(Get-NetTCPConnection -State Listen -LocalPort $CheckPort -ErrorAction SilentlyContinue)){
        $ListenerProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+$Listener.OwningProcess)
        $AliasHash=[System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($ProjectRoot))).Replace('-','').Substring(0,12)
        $ExpectedAlias='secd-registration-'+$AliasHash
        if($ListenerProcess.CommandLine -notlike ('*'+$ProjectRoot+'*') -and $ListenerProcess.CommandLine -notlike ('*'+$ExpectedAlias+'*')){throw ('Port '+$CheckPort+' is occupied outside this delivery copy; stop that service before starting.')}
    }
}

& (Join-Path $PSScriptRoot 'init_database.ps1')
$Secrets=Get-Content -Raw -LiteralPath '.runtime/secrets.json' | ConvertFrom-Json
$env:DB_PASSWORD=$Secrets.dbPassword
$env:DB_PORT='3307'
$env:DB_NAME=$Database
$env:MOCK_TOKEN=$Secrets.mockToken
$env:DEMO_PASSWORD=$Secrets.demoPassword
$env:APP_PROFILE=$Profile
$env:APP_PORT=[string]$BackendPort
$env:APP_FIXED_CLOCK='2026-10-08T02:00:00Z'
$BillingPort=if($Profile -eq 'test'){8094}else{8092}
$env:BILLING_URL="http://127.0.0.1:$BillingPort"
$NodeRoot=Join-Path $ProjectRoot '.tools/node-v22.23.3-win-x64'
$env:PATH=$NodeRoot+';'+$env:PATH
$Jar=Join-Path $ProjectRoot 'backend/target/registration-0.1.0.jar'
if($Build -or -not (Test-Path -LiteralPath $Jar)){
    foreach($Port in @(8081,8082)){
        if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw 'Stop project backend processes before rebuilding a JAR on Windows.'}
    }
    & $MavenExe -B -f backend/pom.xml package
    if($LASTEXITCODE -ne 0){throw 'Backend build failed'}
}
if(-not (Test-Path -LiteralPath 'frontend/node_modules/vite/bin/vite.js')){Push-Location (Join-Path $ProjectRoot 'frontend');try{& (Join-Path $NodeRoot 'node.exe') (Join-Path $NodeRoot 'node_modules/npm/bin/npm-cli.js') ci;if($LASTEXITCODE -ne 0){throw 'Frontend dependency installation failed'}}finally{Pop-Location}}
$PythonExe=(Get-Command python).Source
foreach($Mock in @(@{kind='catalog';port=8091},@{kind='billing';port=$BillingPort})){
    if(-not (Get-NetTCPConnection -State Listen -LocalPort $Mock.port -ErrorAction SilentlyContinue)){
        $MockScript=Join-Path $ProjectRoot 'mocks/server.py'
        $Process=Start-Process -FilePath $PythonExe -ArgumentList @(('"'+$MockScript+'"'),$Mock.kind,'--port',[string]$Mock.port,'--namespace',$Database) -WindowStyle Hidden -RedirectStandardOutput ".runtime/$($Mock.kind)-$($Mock.port).out.log" -RedirectStandardError ".runtime/$($Mock.kind)-$($Mock.port).err.log" -PassThru
        $Process.Id | Set-Content ".runtime/$($Mock.kind)-$($Mock.port).pid"
    }
}
if(-not (Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction SilentlyContinue)){
    $Process=Start-Process -FilePath $JavaExe -ArgumentList @('-Dfile.encoding=UTF-8','-jar',('"'+$Jar+'"')) -WindowStyle Hidden -RedirectStandardOutput ".runtime/backend-$BackendPort.out.log" -RedirectStandardError ".runtime/backend-$BackendPort.err.log" -PassThru
    $Process.Id | Set-Content ".runtime/backend-$BackendPort.pid"
    @{database=$Database;profile=$Profile;port=$BackendPort} | ConvertTo-Json | Set-Content ".runtime/backend-$BackendPort.config.json"
}
if($BackendPort -eq 8081 -and -not (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)){
    $NodeExe=Join-Path $NodeRoot 'node.exe'
    $Vite=Join-Path $ProjectRoot 'frontend/node_modules/vite/bin/vite.js'
    $Process=Start-Process -FilePath $NodeExe -ArgumentList @(('"'+$Vite+'"'),'--host','127.0.0.1','--port','8080') -WorkingDirectory (Join-Path $ProjectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput '.runtime/frontend.out.log' -RedirectStandardError '.runtime/frontend.err.log' -PassThru
    $Process.Id | Set-Content '.runtime/frontend.pid'
}
$Ready=$false
for($Attempt=0;$Attempt -lt 60;$Attempt++){
    try{$Health=Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/api/v1/health" -TimeoutSec 2;if($Health.data.status -eq 'UP'){$Ready=$true;break}}catch{}
    Start-Sleep -Milliseconds 500
}
if(-not $Ready){throw "Backend not ready; inspect .runtime/backend-$BackendPort logs"}
Write-Output "Backend ready on $BackendPort; browser entry http://127.0.0.1:8080"
Write-Output 'Demo usernames: s1, p1, registrar. Read local password with scripts/show_demo_access.ps1.'
