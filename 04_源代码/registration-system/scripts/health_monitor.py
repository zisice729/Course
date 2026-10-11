"""Sample real local service health; incomplete observations never imply seven-day acceptance."""
from pathlib import Path
import urllib.request,time,json,datetime,argparse,math
root=Path(__file__).resolve().parents[1];dest=root/'.runtime/availability.jsonl'
p=argparse.ArgumentParser();p.add_argument('--days',type=float,default=7);p.add_argument('--interval',type=int,default=60);p.add_argument('--summary',action='store_true');p.add_argument('--once',action='store_true');args=p.parse_args()
if args.summary:
 rows=[json.loads(line) for line in dest.read_text(encoding='utf-8').splitlines()] if dest.exists() else []
 if not rows:print(json.dumps(dict(state='NOT_STARTED')));raise SystemExit
 start=datetime.datetime.fromisoformat(rows[0]['at']);elapsed=(datetime.datetime.now(datetime.timezone.utc)-start).total_seconds();expected=max(1,int(elapsed/args.interval)+1);good=sum(r['ok'] for r in rows)
 print(json.dumps(dict(state='OBSERVING' if elapsed<7*86400 else 'WINDOW_ELAPSED_REVIEW_REQUIRED',observedSamples=len(rows),successfulSamples=good,elapsedHours=round(elapsed/3600,3),expectedSamples=expected,unobservedSamples=max(0,expected-len(rows)),conservativeSampleSuccessRatio=good/max(expected,len(rows)),note='sampling ratio, not exact continuous uptime; gaps remain unobserved; no automatic7day PASS'),ensure_ascii=False));raise SystemExit
end=time.monotonic()+args.days*86400
while True:
 start=time.monotonic();ok=False;code=0;error=None
 try:
  with urllib.request.urlopen('http://127.0.0.1:8081/api/v1/health',timeout=5) as res:code=res.status;ok=code==200 and json.load(res)['data']['status']=='UP'
 except Exception as e:error=type(e).__name__
 row=dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),ok=ok,httpStatus=code,elapsedMs=round((time.monotonic()-start)*1000,2),error=error)
 dest.parent.mkdir(exist_ok=True)
 with dest.open('a',encoding='utf-8') as out:out.write(json.dumps(row)+'\n')
 if args.once or time.monotonic()>=end:break
 time.sleep(max(0,args.interval-(time.monotonic()-start)))
