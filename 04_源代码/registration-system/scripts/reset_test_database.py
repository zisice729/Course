"""Called after the isolated test workers are stopped; target is a fixed literal."""
from pathlib import Path
import os,json,subprocess
root=Path(__file__).resolve().parents[1]
secrets=json.loads((root/'.runtime/secrets.json').read_text(encoding='utf-8-sig'))
env=os.environ.copy();env['MYSQL_PWD']=secrets['rootPassword']
command=[str(root/'.tools/mysql-8.4.11-winx64/bin/mysql.exe'),'--host=127.0.0.1','--port=3307','--user=root','--default-character-set=utf8mb4']
query='DROP DATABASE IF EXISTS registration_test; CREATE DATABASE registration_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'
subprocess.run(command,input=query.encode(),env=env,check=True,capture_output=True)
receipt=(root/'.runtime/billing-registration_test-receipts.sqlite').resolve()
assert receipt.parent==(root/'.runtime').resolve()
receipt.unlink(missing_ok=True)
print('Disposable registration_test fixture reset. Demo database preserved.')
