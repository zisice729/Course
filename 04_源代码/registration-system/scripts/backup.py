"""Project-owned MySQL backups, without credentials in shell arguments or output."""
from pathlib import Path
import argparse,subprocess,os,json,datetime,hashlib
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--database',choices=['registration','registration_test'],default='registration');p.add_argument('--restore',type=Path);args=p.parse_args()
secrets=json.loads((root/'.runtime/secrets.json').read_text(encoding='utf-8-sig'))
env=os.environ.copy();env['MYSQL_PWD']=secrets['rootPassword']
base=['--host=127.0.0.1','--port=3307','--user=root','--default-character-set=utf8mb4']
tools=root/'.tools/mysql-8.4.11-winx64/bin'
if args.restore:
    # Restores are deliberately limited to the disposable test database.
    if args.database!='registration_test':p.error('restore target must be registration_test; preserve the live demo database')
    file=args.restore.resolve(strict=True)
    if file.suffix!='.sql':p.error('backup must be a .sql file')
    content=file.read_bytes()
    # Our dumps intentionally have no database selection or cross-database operations.
    if b'CREATE DATABASE' in content or b'USE `' in content:p.error('use a project backup without database selection statements')
    with file.open('rb') as source:subprocess.run([str(tools/'mysql.exe'),*base,'--database=registration_test'],stdin=source,env=env,check=True,capture_output=True)
    print('Restored into isolated registration_test. Keep workers stopped until verification.')
else:
    dest=root/'.runtime/backups';dest.mkdir(exist_ok=True)
    file=dest/(args.database+'-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'.sql')
    with file.open('wb') as out:subprocess.run([str(tools/'mysqldump.exe'),*base,'--single-transaction','--routines','--triggers','--set-gtid-purged=OFF','--no-tablespaces','--hex-blob',args.database],stdout=out,stderr=subprocess.PIPE,env=env,check=True)
    digest=hashlib.sha256(file.read_bytes()).hexdigest()
    file.with_suffix('.sha256').write_text(digest+'  '+file.name+'\n',encoding='utf-8')
    print('Backup created:',file.relative_to(root),'SHA256:',digest)
