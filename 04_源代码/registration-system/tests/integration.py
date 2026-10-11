"""Actual HTTP / MySQL integration checks against the isolated test instance."""
from pathlib import Path
import urllib.request,urllib.error,http.cookiejar,json,uuid,time,concurrent.futures,threading,subprocess,os,datetime

ROOT=Path(__file__).resolve().parents[1];S=json.loads((ROOT/'.runtime/secrets.json').read_text(encoding='utf-8-sig'))
BASE='http://127.0.0.1:8082/api/v1';RESULTS=ROOT/'tests/results';RESULTS.mkdir(exist_ok=True)
results=[]
class Client:
    def __init__(self,name=None,password=None):
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf=''
        self.refresh()
        if name:self.call('POST','/auth/login',dict(username=name,password=password or S['demoPassword']),expected=200);self.refresh()
    def refresh(self):self.csrf=self.call('GET','/auth/csrf',expected=200)[1]['data']['token']
    def call(self,method,path,body=None,key=None,expected=None,csrf=True):
        headers={'Content-Type':'application/json'}
        if method!='GET':
            headers['Idempotency-Key']=key or uuid.uuid4().hex
            if csrf:headers['X-CSRF-TOKEN']=self.csrf
        req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
        try:
            with self.opener.open(req,timeout=30) as res:code=res.status;raw=res.read()
        except urllib.error.HTTPError as e:code=e.code;raw=e.read()
        data=json.loads(raw) if raw else None
        if expected is not None:assert code==expected,(method,path,code,data)
        return code,data
    def data(self,path):return self.call('GET',path,expected=200)[1]['data']
def sql(query):
    env=os.environ.copy();env['MYSQL_PWD']=S['dbPassword']
    exe=ROOT/'.tools/mysql-8.4.11-winx64/bin/mysql.exe'
    r=subprocess.run([str(exe),'--host=127.0.0.1','--port=3307','--user=registration','--database=registration_test','--batch','--skip-column-names','--default-character-set=utf8mb4'],input=query,text=True,encoding='utf-8',capture_output=True,env=env)
    assert r.returncode==0,r.stderr
    return r.stdout.strip()
def case(case_id,title,fn):
    start=time.monotonic()
    try:detail=fn();row=dict(caseId=case_id,title=title,status='PASS',durationMs=round((time.monotonic()-start)*1000),evidence=detail)
    except Exception as e:
        row=dict(caseId=case_id,title=title,status='FAIL',durationMs=round((time.monotonic()-start)*1000),error=str(e));results.append(row);save();raise
    results.append(row);save();print(case_id,title,'PASS',flush=True)
def save():
    (RESULTS/'integration.json').write_text(json.dumps(dict(executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),base=BASE,database='registration_test',results=results),ensure_ascii=False,indent=2),encoding='utf-8')
def body(version,main,alt):return dict(expectedVersion=version,choiceItems=[dict(offeringId=str(o),choiceType=t,order=i+1) for t,items in [('PRIMARY',main),('ALTERNATE',alt)] for i,o in enumerate(items)])
def mock(mode):
    req=urllib.request.Request('http://127.0.0.1:8094/control',data=json.dumps(dict(mode=mode)).encode(),headers={'Content-Type':'application/json','X-Mock-Token':S['mockToken']},method='POST');urllib.request.urlopen(req).close()
def receipts():
    req=urllib.request.Request('http://127.0.0.1:8094/receipts',headers={'X-Mock-Token':S['mockToken']});return json.load(urllib.request.urlopen(req))
def wait(fn,timeout=35):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(.3)
    raise AssertionError('Condition did not converge')
def run():
    anonymous=Client();student=Client('s1');professor=Client('p1');admin=Client('registrar')
    case('TC-AUTH-001','三角色登录',lambda: [student.data('/auth/me')['role'],professor.data('/auth/me')['role'],admin.data('/auth/me')['role']])
    case('TC-AUTH-002','错误凭据拒绝',lambda: anonymous.call('POST','/auth/login',dict(username='missing',password='bad'),expected=401)[1]['code'])
    case('TC-SEC-002','真实CSRF过滤链拒绝',lambda: student.call('PUT','/student/schedules/2/draft',body(0,[101,102,103,104],[105,106]),expected=403,csrf=False)[1]['code'])
    case('TC-SEC-001','角色越权拒绝',lambda: student.call('GET','/registrar/students',expected=403)[1]['code'])
    case('TC-CAT-001','冻结目录字段和ID字符串',lambda: check_catalog(student))
    case('TC-API-003','分页及非法排序',lambda: check_paging(student))
    case('TC-REG-001','草稿不占名额',lambda: draft(student))
    case('TC-REG-003','首次4+2正式提交',lambda: submit(student))
    case('TC-REG-016','成功重放与异体冲突',lambda: idempotent(student))
    case('TC-REG-015','旧版本拒绝',lambda: student.call('POST','/student/schedules/2/submit',body(0,[101,102,103,104],[105,106]),expected=409)[1]['code'])
    case('TC-REG-006','先修F拒绝且全量回滚',lambda: prereq())
    case('TC-CON-001','真实MySQL最后一席竞争',lambda: last_seat(student))
    case('TC-DB-008','占位后异常全部回滚',lambda: rollback(admin))
    case('TC-GRADE-001','历史名单合法成绩保存',lambda: grade(professor))
    case('TC-GRADE-003','null不清除已有成绩',lambda: grade_null(professor))
    case('TC-GRADE-002','非法成绩整批拒绝',lambda: grade_invalid(professor))
    case('TC-PEOPLE-001','人员账号原子创建',lambda: person(admin))
    case('TC-PEOPLE-004','当前关联拒绝删除',lambda: admin.call('DELETE','/registrar/students/2?expectedVersion=0',expected=409)[1]['code'])
    case('TC-REG-RECREATE','删除、草稿、重建仍须首次4+2',lambda: recreate())
    case('TC-API-INTEGER','版本和选择顺序拒绝小数',lambda: integer_validation())
    case('TC-TEACH-ATOMIC','授课集合原子修改与其他教师占用拒绝',lambda: assignment())
    case('TC-GRADE-ROLLBACK','成绩批量中途失败回滚',lambda: grade_rollback(admin,professor))
    case('TC-CON-006','普通写持锁时关闭任务BLOCKED_BUSY',lambda: close_busy(admin))
    case('TC-CLOSE-010','本地关闭失败业务全部回滚',lambda: close_rollback(admin))
    case('TC-CLOSE-001','本地原子关闭和最终不变量',lambda: close_normal(admin))
    case('TC-BILL-003','远端接收丢响应持久去重',lambda: billing_recover(admin))
    case('TC-GRADE-007','关闭后仍可历史评分',lambda: grade(professor))
    case('TC-REG-014','关闭后正式写拒绝',lambda: student.call('POST','/student/schedules/2/submit',body(student.data('/student/schedules/2')['version'],[101,102,103,104],[105,106]),expected=409)[1]['code'])
    case('TC-AUTH-003','退出后会话失效',lambda: logout(student))
    print('Integration checks:',len(results),'PASS',flush=True)
def check_catalog(c):
    rows=c.data('/catalog/offerings?size=100')['items'];assert len(rows)==12 and all(isinstance(o['id'],str) for o in rows);assert rows[0]['enrolledCount']==9;return dict(classes=len(rows),firstId=rows[0]['id'],snapshot=rows[0]['sourceVersion'])
def check_paging(c):
    assert c.data('/catalog/offerings')['size']==20
    c.call('GET','/catalog/offerings?size=101',expected=400);c.call('GET','/catalog/offerings?sort=drop',expected=400);return 'default20,max100,sort whitelist'
def draft(c):
    version=c.data('/student/schedules/2')['version'];c.call('PUT','/student/schedules/2/draft',body(version,[101,102,103,104],[105,106]),expected=200);assert sql("SELECT enrolled_count FROM offering_runtime WHERE offering_id=101")=="9";return 'count=9; ACTIVE unchanged'
def submit(c):
    v=c.data('/student/schedules/2')['version'];c.call('POST','/student/schedules/2/submit',body(v,[101,102,103,104],[105,106]),key='initial-submit',expected=200);assert len(c.data('/student/schedules/2')['enrolledItems'])==4;assert sql("SELECT enrolled_count FROM offering_runtime WHERE offering_id=101")=="10";return '4 ACTIVE; alternatives do not occupy seats'
def idempotent(c):
    original=body(1,[101,102,103,104],[105,106]);r=c.call('POST','/student/schedules/2/submit',original,key='initial-submit',expected=200);assert r[1]['data']['version']==2
    c.call('POST','/student/schedules/2/submit',body(2,[101,102,103,104],[106,105]),key='initial-submit',expected=409);return 'same key/body replay before version; changed body409'
def prereq():
    c=Client('s5');v=c.data('/student/schedules/2')['version'];before=sql("SELECT GROUP_CONCAT(offering_id ORDER BY offering_id) FROM enrollment WHERE student_id=5 AND state='ACTIVE'")
    r=c.call('POST','/student/schedules/2/submit',body(v,[101,102,103,105],[106,107]),expected=409);assert r[1]['code']=='PREREQUISITE_UNMET';assert before==sql("SELECT GROUP_CONCAT(offering_id ORDER BY offering_id) FROM enrollment WHERE student_id=5 AND state='ACTIVE'");return 'F fails; old ACTIVE intact'
def last_seat(c):
    v=c.data('/student/schedules/2')['version'];c.call('POST','/student/schedules/2/submit',body(v,[102,103,104],[105,106]),expected=200)
    contenders=[Client('s13'),Client('s14')];barrier=threading.Barrier(2)
    def compete(client):barrier.wait();return client.call('POST','/student/schedules/2/submit',body(0,[101,106,110,112],[107,108]))
    with concurrent.futures.ThreadPoolExecutor(2) as p:responses=list(p.map(compete,contenders))
    assert sorted(r[0] for r in responses)==[200,409],responses
    assert next(r[1]['code'] for r in responses if r[0]==409)=='COURSE_FULL'
    assert sql("SELECT enrolled_count FROM offering_runtime WHERE offering_id=101")=="10"
    assert sql("SELECT COUNT(*) FROM enrollment WHERE offering_id=101 AND state='ACTIVE'")=="10"
    return dict(http=[r[0] for r in responses],count=10,active=10)
def hook(admin,point,mode):admin.call('POST','/test/hooks',dict(point=point,mode=mode),expected=200)
def rollback(admin):
    c=Client('s15');hook(admin,'registration.afterEnroll','fail');before=sql("SELECT SUM(enrolled_count) FROM offering_runtime")
    try:c.call('POST','/student/schedules/2/submit',body(0,[105,107,108,109],[106,110]),expected=500)
    finally:hook(admin,'registration.afterEnroll','release')
    assert sql("SELECT SUM(enrolled_count) FROM offering_runtime")==before;assert not c.data('/student/schedules/2')['enrolledItems'];return 'all counts and new ACTIVE rolled back'
def grade(c):
    rows=c.data('/professor/offerings/201/roster');r=rows[0];c.call('PUT','/professor/offerings/201/grades',dict(updates=[dict(enrollmentId=r['enrollmentId'],grade='B',expectedVersion=r['version'])]),expected=200);assert c.data('/professor/offerings/201/roster')[0]['grade']=='B';return 'own historical class updated'
def grade_null(c):
    r=c.data('/professor/offerings/201/roster')[0];c.call('PUT','/professor/offerings/201/grades',dict(updates=[dict(enrollmentId=r['enrollmentId'],grade=None,expectedVersion=r['version'])]),expected=200);assert c.data('/professor/offerings/201/roster')[0]['grade']=='B';return 'null skips existing B'
def grade_invalid(c):
    r=c.data('/professor/offerings/201/roster')[0];c.call('PUT','/professor/offerings/201/grades',dict(updates=[dict(enrollmentId=r['enrollmentId'],grade='E',expectedVersion=r['version'])]),expected=400);return 'E rejected'
def person(admin):
    fields=dict(name='集成测试学生',birthDate='2004-01-01',ssn='999-88-7777',status='ENROLLED',graduationDate=None)
    result=admin.call('POST','/registrar/students',fields,key='person-once',expected=201)[1]['data'];assert result['credentialDelivery']['mustChangePassword'];temporary=result['credentialDelivery']['temporaryPassword']
    replay=admin.call('POST','/registrar/students',fields,key='person-once',expected=201)[1]['data'];assert 'credentialDelivery' not in replay and replay['id']==result['id']
    created=Client(result['username'],temporary);created.call('GET','/catalog/offerings',expected=403)
    created.call('POST','/auth/password',dict(oldPassword=temporary,newPassword='IntegrationPassword2026!'),expected=200);created.call('GET','/auth/me',expected=401)
    admin.call('DELETE',f"/registrar/students/{result['id']}?expectedVersion=0",expected=204);admin.call('DELETE',f"/registrar/students/{result['id']}?expectedVersion=0",expected=204)
    return dict(id=result['id'],username=result['username'],oneTimeDelivery=True,logicalDeleted=True)
def close_busy(admin):
    c=Client('s16');hook(admin,'registration.afterGate','pause')
    with concurrent.futures.ThreadPoolExecutor(1) as pool:
        future=pool.submit(c.call,'PUT','/student/schedules/2/draft',body(0,[],[]))
        wait(lambda:admin.data('/test/hooks').get('registration.afterGate',{}).get('entered'))
        job=admin.call('POST','/registrar/semesters/2/close-jobs',expected=202)[1]['data'];job_id=job['id']
        wait(lambda:admin.data(f'/registrar/close-jobs/{job_id}')['state']=='BLOCKED_BUSY')
        hook(admin,'registration.afterGate','release');assert future.result()[0]==200
    assert sql("SELECT state FROM semester WHERE id=2")=='OPEN';return '202 accepted; worker BLOCKED_BUSY; registration proceeds'
def close_rollback(admin):
    before=sql("SELECT SUM(enrolled_count) FROM offering_runtime");hook(admin,'close.beforeCommit','fail')
    try:
        job=admin.call('POST','/registrar/semesters/2/close-jobs',expected=202)[1]['data'];wait(lambda:admin.data(f"/registrar/close-jobs/{job['id']}")['state']=='FAILED_LOCAL')
    finally:hook(admin,'close.beforeCommit','release')
    assert sql("SELECT state FROM semester WHERE id=2")=='OPEN';assert sql("SELECT COUNT(*) FROM billing_record")=='0';assert sql("SELECT SUM(enrolled_count) FROM offering_runtime")==before;return 'OPEN, old counts, no committed billing'
def close_normal(admin):
    mock('drop-response');job=admin.call('POST','/registrar/semesters/2/close-jobs',expected=202)[1]['data'];final=wait(lambda:(j if (j:=admin.data(f"/registrar/close-jobs/{job['id']}"))['state']=='LOCAL_COMMITTED' else None))
    assert sql("SELECT state FROM semester WHERE id=2")=='CLOSED';assert admin.data('/registrar/integrity')==[]
    assert sql("SELECT COUNT(*) FROM offering_runtime WHERE semester_id=2 AND state='CONFIRMED' AND enrolled_count NOT BETWEEN 3 AND 10")=='0'
    assert sql("SELECT COUNT(*) FROM enrollment e JOIN offering_runtime r ON r.offering_id=e.offering_id WHERE e.state='ACTIVE' AND r.state='CANCELLED'")=='0'
    return final['summary']
def billing_recover(admin):
    wait(lambda:len(receipts())>=1);mock('normal')
    for b in admin.data('/registrar/billing-records'):admin.call('POST',f"/registrar/billing-records/{b['id']}/retry",expected=200)
    job=admin.data('/registrar/close-jobs')[0];wait(lambda:admin.data(f"/registrar/close-jobs/{job['id']}")['state']=='COMPLETE',60)
    received=receipts();keys=[r['businessKey'] for r in received];assert len(keys)==len(set(keys));assert len(keys)==int(sql('SELECT COUNT(*) FROM billing_record'));assert sql("SELECT COUNT(*) FROM billing_outbox WHERE state<>'SENT'")=='0';return dict(uniqueReceipts=len(keys),localBills=len(keys),state='COMPLETE')
def logout(c):c.call('POST','/auth/logout',expected=204);c.call('GET','/auth/me',expected=401);return 'old session rejected'
def recreate():
    c=Client('s17');main=[106,107,108,109];alt=[110,112]
    c.call('POST','/student/schedules/2/submit',body(0,main,alt),expected=200)
    c.call('DELETE','/student/schedules/2',dict(expectedVersion=1),expected=204)
    c.call('PUT','/student/schedules/2/draft',body(2,main[:3],alt),expected=200)
    c.call('POST','/student/schedules/2/submit',body(3,main[:3],alt),expected=400)
    assert not c.data('/student/schedules/2')['enrolledItems']
    c.call('POST','/student/schedules/2/submit',body(3,main,alt),expected=200)
    return 'tombstone retains ordering time; draft cannot bypass 4+2 rebuild'

def integer_validation():
    c=Client('s18');b=body(0,[106,107,108,109],[110,112]);b['expectedVersion']=0.5
    c.call('PUT','/student/schedules/2/draft',b,expected=400)
    b['expectedVersion']=0;b['choiceItems'][0]['order']=1.5
    c.call('PUT','/student/schedules/2/draft',b,expected=400)
    return 'fractional expectedVersion/order rejected'

def assignment():
    c=Client('p3');before=c.data('/professor/teaching-assignments/2')
    c.call('PUT','/professor/teaching-assignments/2',dict(expectedVersion=before['version'],offeringIds=['111','101']),expected=409)
    assert c.data('/professor/teaching-assignments/2')==before
    c.call('PUT','/professor/teaching-assignments/2',dict(expectedVersion=before['version'],offeringIds=['111','112']),expected=200)
    c.call('PUT','/professor/teaching-assignments/2',dict(expectedVersion=before['version']+1,offeringIds=['111']),expected=200)
    return 'conflict preserves prior assignments; unassigned offering can be added/released'

def grade_rollback(admin,c):
    rows=c.data('/professor/offerings/201/roster');updates=[dict(enrollmentId=r['enrollmentId'],grade='D',expectedVersion=r['version']) for r in rows[:2]]
    hook(admin,'grade.afterFirst','fail')
    try:c.call('PUT','/professor/offerings/201/grades',dict(updates=updates),expected=500)
    finally:hook(admin,'grade.afterFirst','release')
    assert c.data('/professor/offerings/201/roster')==rows
    return 'both grade values and versions unchanged'

if __name__=='__main__':run()
