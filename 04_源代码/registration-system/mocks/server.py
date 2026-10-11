"""Read-only catalog and durable idempotent Billing mocks. Standard library only."""
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import json, sqlite3, hashlib, time, threading, argparse, uuid

ROOT=Path(__file__).resolve().parents[1]
RUNTIME=ROOT/'.runtime';RUNTIME.mkdir(exist_ok=True)
SECRETS=json.loads((RUNTIME/'secrets.json').read_text(encoding='utf-8-sig'))

def catalog():
    names=['程序设计基础','离散数学','计算机组成原理','数据库原理','软件工程','计算机网络','操作系统','算法设计','Web应用开发','软件测试','人机交互']
    courses=[dict(id=str(i+1),name=n,department='计算机学院',prerequisites=['1'] if i==4 else []) for i,n in enumerate(names)]
    offers=[]
    for i in range(12):
        course=i+1 if i<10 else 1 if i==10 else 11
        day=i%5+1;start='08:00:00' if i<5 or i==10 else '10:00:00' if i<10 else '14:00:00'
        end={'08:00:00':'09:30:00','10:00:00':'11:30:00','14:00:00':'15:30:00'}[start]
        offers.append(dict(id=str(101+i),courseId=str(course),meetings=[dict(weekday=day,startTime=start,endTime=end,firstDate='2026-09-01',lastDate='2026-12-31',weekMask=None)]))
    return dict(version='demo-autumn-v1',courses=courses,offerings=offers)

class MockServer(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,port,kind,namespace='demo'):
        super().__init__(('127.0.0.1',port),Handler);self.kind=kind;self.mode='normal';self.delay=0;self.lock=threading.Lock()
        self.db=RUNTIME/(kind+'-'+namespace+'-receipts.sqlite')
        if kind=='billing':
            with sqlite3.connect(self.db) as c:c.execute('CREATE TABLE IF NOT EXISTS receipt(business_key TEXT PRIMARY KEY,hash TEXT NOT NULL,external_ref TEXT NOT NULL,payload TEXT NOT NULL,received_at REAL NOT NULL)')
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,status,data):
        b=json.dumps(data,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json;charset=utf-8');self.send_header('Content-Length',str(len(b)));self.end_headers()
        try:self.wfile.write(b)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    def authorized(self):return self.headers.get('X-Mock-Token')==SECRETS['mockToken']
    def do_GET(self):
        if self.path=='/health':return self.reply(200,dict(kind=self.server.kind,mode=self.server.mode))
        if self.path=='/snapshot' and self.server.kind=='catalog':
            if self.server.mode=='fail':return self.reply(503,dict(error='unavailable'))
            time.sleep(self.server.delay);return self.reply(200,catalog())
        if self.path=='/receipts' and self.server.kind=='billing':
            if not self.authorized():return self.reply(403,{})
            with sqlite3.connect(self.server.db) as c:rows=c.execute('SELECT business_key,hash,external_ref FROM receipt ORDER BY business_key').fetchall()
            return self.reply(200,[dict(businessKey=k,payloadHash=h,externalRef=r) for k,h,r in rows])
        self.reply(404,{})
    def do_POST(self):
        if not self.authorized():return self.reply(403,{})
        try:
            size=int(self.headers.get('Content-Length',0))
            if size>1024*1024:return self.reply(413,{})
            body=json.loads(self.rfile.read(size))
        except Exception:return self.reply(400,{})
        if self.path=='/control':
            mode=body.get('mode','normal')
            if mode not in ['normal','fail','drop-response','slow']:return self.reply(400,{})
            self.server.mode=mode;self.server.delay=max(0,min(float(body.get('delay',0)),15));return self.reply(200,dict(mode=mode,delay=self.server.delay))
        if self.path=='/transactions' and self.server.kind=='billing':
            if self.server.mode=='fail':return self.reply(500,dict(error='billing_unavailable'))
            time.sleep(self.server.delay)
            key=body.get('businessKey');payload=json.dumps(body,ensure_ascii=False,sort_keys=True,separators=(',',':'))
            digest=hashlib.sha256(payload.encode()).hexdigest()
            if key!=self.headers.get('Idempotency-Key') or digest!=self.headers.get('X-Payload-Hash'):return self.reply(409,dict(error='payload_mismatch'))
            with self.server.lock,sqlite3.connect(self.server.db) as c:
                old=c.execute('SELECT hash,external_ref FROM receipt WHERE business_key=?',(key,)).fetchone()
                if old and old[0]!=digest:return self.reply(409,dict(error='business_key_conflict'))
                ref=old[1] if old else 'MOCK-'+uuid.uuid4().hex[:16]
                if not old:c.execute('INSERT INTO receipt VALUES(?,?,?,?,?)',(key,digest,ref,payload,time.time()))
            if self.server.mode=='drop-response':self.close_connection=True;return
            return self.reply(200 if old else 201,dict(businessKey=key,externalRef=ref))
        self.reply(404,{})

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('kind',choices=['catalog','billing']);p.add_argument('--port',type=int);p.add_argument('--namespace',default='demo');args=p.parse_args()
    if not args.namespace.replace('_','').isalnum():p.error('namespace must be alphanumeric')
    MockServer(args.port or (8091 if args.kind=='catalog' else 8092),args.kind,args.namespace).serve_forever()
