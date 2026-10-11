"""Short ramped capacity evidence, not the full 2min+10min formal mixed acceptance."""
from integration import ROOT,Client,sql,body
import concurrent.futures,json,time,datetime,threading,collections
STAGES=[50,100,500];DURATION=30;THINK=10
sql("UPDATE offering_meeting SET weekday=MOD(offering_id-20001,4)+1 WHERE offering_id BETWEEN 20001 AND 21200;")
sql("INSERT INTO student(id,name,birth_date,ssn,status) SELECT 1000+n,CONCAT('Synthetic student ',n),'2004-01-01',LPAD(600000000+n,9,'0'),'ENROLLED' FROM (WITH RECURSIVE n AS(SELECT 1 n UNION ALL SELECT n+1 FROM n WHERE n<500) SELECT n FROM n)t;")
sql("INSERT INTO user_account(username,password_hash,role,student_id,must_change_password) SELECT CONCAT('s',s.id),a.password_hash,'STUDENT',s.id,FALSE FROM student s CROSS JOIN user_account a WHERE s.id BETWEEN 1001 AND 1500 AND a.username='s1';")
sql('INSERT INTO schedule(student_id,semester_id) SELECT id,2 FROM student WHERE id BETWEEN 1001 AND 1500;')
print('Synthetic500 fixture ready; logging in500 independent sessions',flush=True)
with concurrent.futures.ThreadPoolExecutor(20) as pool:clients=list(pool.map(lambda n:Client('s'+str(1001+n)),range(500)))
versions=[0]*500;stages=[]
for users in STAGES:
    barrier=threading.Barrier(users);samples=[];lock=threading.Lock()
    def worker(n):
        c=clients[n];barrier.wait();end=time.monotonic()+DURATION;step=0;local=[]
        while time.monotonic()<end:
            k=(n+step)%20;start=time.perf_counter();method='GET';operation='catalog'
            try:
                if k<8:path=f'/catalog/offerings?size=20&page={1+n%61}';status,data=c.call('GET',path)
                elif k<12:operation='schedule';status,data=c.call('GET','/student/schedules/2')
                elif k<14:operation='report';status,data=c.call('GET','/student/report-card')
                else:
                    operation='submit' if k<17 else 'draft';method='POST' if operation=='submit' else 'PUT'
                    first=20001+4*(n%300);main=list(range(first,first+4));alt=[20001+(4*(n%300)+4)%1200,20001+(4*(n%300)+5)%1200]
                    status,data=c.call(method,'/student/schedules/2/'+operation,body(versions[n],main,alt))
                    if status==200:versions[n]=data['data']['version']
                row=dict(user=1001+n,operation=operation,method=method,status=status,elapsedMs=round((time.perf_counter()-start)*1000,2),traceId=data.get('traceId') if isinstance(data,dict) else None)
            except Exception as e:row=dict(user=1001+n,operation=operation,method=method,status=0,elapsedMs=round((time.perf_counter()-start)*1000,2),error=type(e).__name__)
            local.append(row);step+=1;time.sleep(min(THINK,max(0,end-time.monotonic())))
        with lock:samples.extend(local)
    started=time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(users) as pool:list(pool.map(worker,range(users)))
    times=sorted(r['elapsedMs'] for r in samples);counts=collections.Counter(r['operation'] for r in samples)
    stage=dict(virtualUsers=users,durationSeconds=round(time.monotonic()-started,3),requests=len(samples),success=sum(r['status']==200 for r in samples),errors=sum(r['status']!=200 for r in samples),p95Ms=times[int(len(times)*.95)-1],maxMs=max(times),operationCounts=dict(counts),successWithin120SecondsRatio=sum(r['status']==200 and r['elapsedMs']<=120000 for r in samples)/len(samples),samples=samples)
    stages.append(stage)
    report=dict(executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),database='registration_test',topology='one Java application; MySQL pool20; loopback',fixture='500 synthetic students; 600 extra courses; total1212 offerings',thinkSeconds=THINK,scope='student-only70%read30%write; 30second stages; no dedicated120second warm-up; not formal500/2000 full-workload acceptance',stages=stages)
    (ROOT/'tests/results/mixed-load.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print({k:v for k,v in stage.items() if k!='samples'},flush=True)
    assert stage['errors']==0,'Ramp stopped on observed failures; retain evidence'
assert sql("SELECT COUNT(*) FROM offering_runtime WHERE enrolled_count>capacity OR enrolled_count<0")=='0'
assert sql("SELECT COUNT(*) FROM offering_runtime r LEFT JOIN (SELECT offering_id,COUNT(*) n FROM enrollment WHERE state='ACTIVE' GROUP BY offering_id)e ON e.offering_id=r.offering_id WHERE r.enrolled_count<>COALESCE(e.n,0)")=='0'
print('Post-load enrollment counts and capacity invariant PASS',flush=True)
