"""Read-only smoke load; does not represent the required 500/2000 mixed workload."""
import concurrent.futures,time,json,datetime
from integration import Client,ROOT
USERS=20;ITERATIONS=15
clients=[Client('s'+str(i+1)) for i in range(USERS)]
def worker(client):
    rows=[]
    for i in range(ITERATIONS):
        path=['/catalog/offerings?size=20','/student/schedules/2','/student/report-card'][i%3]
        start=time.perf_counter();status,_=client.call('GET',path)
        rows.append(dict(path=path,status=status,elapsedMs=round((time.perf_counter()-start)*1000,2)))
    return rows
start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(USERS) as pool:rows=[r for group in pool.map(worker,clients) for r in group]
values=sorted(r['elapsedMs'] for r in rows)
report=dict(executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),profile='single instance; 20 simultaneous sessions; read-only; no think time; no warm-up',virtualUsers=USERS,requests=len(rows),durationSeconds=round(time.perf_counter()-start,3),successful=sum(r['status']==200 for r in rows),p50Ms=values[len(values)//2],p95Ms=values[int(len(values)*.95)-1],maxMs=max(values),within120SecondsSuccessRatio=sum(r['status']==200 and r['elapsedMs']<=120000 for r in rows)/len(rows),samples=rows)
(ROOT/'tests/results/read-load.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print({k:v for k,v in report.items() if k!='samples'})
assert report['successful']==len(rows)
