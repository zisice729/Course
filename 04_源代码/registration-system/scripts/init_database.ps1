$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path $PSScriptRoot -Parent
$RuntimeRoot=Join-Path $ProjectRoot '.runtime'
$MysqlRoot=Join-Path $ProjectRoot '.tools/mysql-8.4.11-winx64'
New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null
$SecretsFile=Join-Path $RuntimeRoot 'secrets.json'
if(-not (Test-Path -LiteralPath $SecretsFile)) {
    function New-LocalSecret { $SecretBytes=New-Object byte[] 24; $Generator=[System.Security.Cryptography.RandomNumberGenerator]::Create();try{$Generator.GetBytes($SecretBytes);[Convert]::ToBase64String($SecretBytes)}finally{$Generator.Dispose()} }
    @{dbPassword=(New-LocalSecret);rootPassword=(New-LocalSecret);mockToken=(New-LocalSecret);demoPassword=(New-LocalSecret)} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $SecretsFile
}
$Secrets=Get-Content -Raw -LiteralPath $SecretsFile | ConvertFrom-Json
# MySQL's Windows filesystem routines do not reliably accept Chinese paths.
# Junction aliases keep actual data inside this project without relocating it.
$AliasHash=[System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($ProjectRoot))).Replace('-','').Substring(0,12)
$AliasRoot=Join-Path $env:LOCALAPPDATA ('secd-registration-'+$AliasHash)
New-Item -ItemType Directory -Force -Path $AliasRoot | Out-Null
foreach($Alias in @(@{name='runtime';target=$RuntimeRoot},@{name='mysql';target=$MysqlRoot})){
    $LinkPath=Join-Path $AliasRoot $Alias.name
    if(Test-Path -LiteralPath $LinkPath){
        $Link=Get-Item -LiteralPath $LinkPath
        if($Link.LinkType -ne 'Junction' -or $Link.Target -notcontains $Alias.target){throw "Existing alias points outside this project: $LinkPath"}
    }else{New-Item -ItemType Junction -Path $LinkPath -Target $Alias.target | Out-Null}
}
$MysqlAlias=Join-Path $AliasRoot 'mysql'
$RuntimeAlias=Join-Path $AliasRoot 'runtime'
$DataPath=Join-Path $RuntimeAlias 'mysql-data'
$IniPath=Join-Path $RuntimeAlias 'mysql.ini'
$Config=@"
[mysqld]
basedir=$($MysqlAlias.Replace('\','/'))
datadir=$($DataPath.Replace('\','/'))
port=3307
bind-address=127.0.0.1
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
default-time-zone=+00:00
innodb_buffer_pool_size=128M
log-error=$($RuntimeAlias.Replace('\','/'))/mysql-error.log
"@
[IO.File]::WriteAllText($IniPath,$Config,[Text.UTF8Encoding]::new($false))
$ServerExe=Join-Path $MysqlAlias 'bin/mysqld.exe'
$ClientExe=Join-Path $MysqlAlias 'bin/mysql.exe'
$Fresh=-not (Test-Path -LiteralPath (Join-Path $DataPath 'auto.cnf'))
if($Fresh){ & $ServerExe "--defaults-file=$IniPath" --initialize-insecure; if($LASTEXITCODE -ne 0){throw 'MySQL initialization failed'} }
if(-not (Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue)){
    $ServerProcess=Start-Process -FilePath $ServerExe -ArgumentList "--defaults-file=$IniPath" -WindowStyle Hidden -PassThru
    $ServerProcess.Id | Set-Content -LiteralPath (Join-Path $RuntimeRoot 'mysql.pid')
}
$Ready=$false
for($Attempt=0;$Attempt -lt 40;$Attempt++){
    if(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue){$Ready=$true;break}
    Start-Sleep -Milliseconds 500
}
if(-not $Ready){throw 'MySQL failed to start; see .runtime/mysql-error.log'}
$Provisioned=Join-Path $RuntimeRoot 'database-provisioned'
$env:MYSQL_PWD=if(Test-Path -LiteralPath $Provisioned){$Secrets.rootPassword}else{''}
$Sql=@"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$($Secrets.rootPassword)';
CREATE DATABASE IF NOT EXISTS registration CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS registration_test CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'registration'@'localhost' IDENTIFIED BY '$($Secrets.dbPassword)';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON registration.* TO 'registration'@'localhost';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON registration_test.* TO 'registration'@'localhost';
"@
try { $Sql | & $ClientExe '--host=127.0.0.1' '--port=3307' '--user=root' --default-character-set=utf8mb4; if($LASTEXITCODE -ne 0){throw 'Database provisioning failed'} } finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
Set-Content -LiteralPath $Provisioned -Value 'ready'
Write-Output 'Project MySQL ready on 127.0.0.1:3307; credentials stored only in .runtime/secrets.json'
