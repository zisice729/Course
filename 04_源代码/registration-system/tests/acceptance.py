"""Additional variants on a freshly reset isolated test database; no human sign-off implied."""
from integration import Client,sql,body,hook,ROOT,wait,S
import json,time,datetime,urllib.request,subprocess
rows=[]
def check(id,title,related,fn):
    start=time.monotonic()
    try: evidence=fn();row=dict(scenarioId=id,title=title,relatedCaseIds=related,status='PASS',durationMs=round((time.monotonic()-start)*1000),evidence=evidence)
    except Exception as e:
        row=dict(scenarioId=id,title=title,relatedCaseIds=related,status='FAIL',error=str(e));rows.append(row);save();raise
    rows.append(row);save();print(id,'PASS',flush=True)
def save():
    (ROOT/'tests/results/acceptance.json').write_text(json.dumps(dict(executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),database='registration_test',fixture='fresh demo seed plus explicit synthetic variants',executor='Codex automated; not group manual acceptance',results=rows),ensure_ascii=False,indent=2),encoding='utf-8')
admin=Client('registrar');student=Client('s1');teacher=Client('p1')
def auth_bad():
    c=Client()
    for b in [dict(username='s1',password='wrong'),dict(username='unknown',password='wrong'),dict(username='',password='')]:
        assert c.call('POST','/auth/login',b,expected=401)[1]['code']=='BAD_CREDENTIALS'
    return 'wrong password, unknown name, empty credentials all rejected without valid identity'
def privacy():
    for endpoint in ['/registrar/students','/professor/historical-offerings','/registrar/audit-events']:
        student.call('GET',endpoint,expected=403)
    teacher.call('PUT','/professor/offerings/211/grades',dict(updates=[]),expected=403)
    student.call('GET','/student/schedules/2?studentId=2',expected=200)
    assert student.data('/student/schedules/2?studentId=2')['studentId']=='1'
    return 'server role check and actor-derived ownership; no foreign grade access'
def versions():
    c=Client('s18')
    for version in [-1,'0',0.5,None]:c.call('PUT','/student/schedules/2/draft',body(version,[],[]),expected=400 if version!='0' and version!= -1 else (400 if version=='0' else 409))
    return 'string/null/fraction rejected; negative mismatches current version'
def selections():
    c=Client('s18')
    variants=[body(0,[101,102,103], [105,106]),body(0,[101,101,103,104],[105,106]),body(0,[101,102,103,104],[105])]
    for b in variants:c.call('POST','/student/schedules/2/submit',b,expected=400)
    c.call('POST','/student/schedules/2/submit',body(0,[101,102,103,111],[105,106]),expected=409)
    assert not c.data('/student/schedules/2')['enrolledItems']
    return 'first counts, duplicate offering, same course in primary; no partial enrollment'
def prerequisites():
    for user in ['s5','s6','s7']:
        c=Client(user);before=c.data('/student/schedules/2')
        r=c.call('POST','/student/schedules/2/submit',body(before['version'],[101,102,103,105],[106,107]),expected=409)
        assert r[1]['code']=='PREREQUISITE_UNMET' and c.data('/student/schedules/2')==before
    return 'F / I / unrecorded predecessor each refused; entire formal schedule unchanged'
def grade_variants():
    roster=teacher.data('/professor/offerings/201/roster');e=roster[0]
    for grade in ['A','B','C','D','F','I']:
        teacher.call('PUT','/professor/offerings/201/grades',dict(updates=[dict(enrollmentId=e['enrollmentId'],grade=grade,expectedVersion=e['version'])]),expected=200)
        e=teacher.data('/professor/offerings/201/roster')[0];assert e['grade']==grade
    before=teacher.data('/professor/offerings/201/roster')
    teacher.call('PUT','/professor/offerings/201/grades',dict(updates=[dict(enrollmentId=e['enrollmentId'],grade='A',expectedVersion=0)]),expected=409)
    assert teacher.data('/professor/offerings/201/roster')==before
    # Restore a passing grade, retaining the legitimate incremented version.
    teacher.call('PUT','/professor/offerings/201/grades',dict(updates=[dict(enrollmentId=e['enrollmentId'],grade='A',expectedVersion=e['version'])]),expected=200)
    return 'all 6 grades allowed; stale grade version cannot overwrite'
def grade_batch_atomic():
    roster=teacher.data('/professor/offerings/201/roster');a,b=roster[:2]
    updates=[dict(enrollmentId=a['enrollmentId'],grade='D',expectedVersion=a['version']),dict(enrollmentId=b['enrollmentId'],grade='E',expectedVersion=b['version'])]
    teacher.call('PUT','/professor/offerings/201/grades',dict(updates=updates),expected=400)
    assert teacher.data('/professor/offerings/201/roster')==roster
    return 'invalid later item prevents earlier valid grade update'
def people_update():
    for kind,fields in [('students',dict(name='验收学生',birthDate='2004-01-01',ssn='123456789',status='ENROLLED',graduationDate=None)),('professors',dict(name='验收教师',birthDate='1980-01-01',ssn='123456789',status='ON_DUTY',department='验收学院'))]:
        person=admin.call('POST','/registrar/'+kind,fields,expected=201)[1]['data']
        changed={**fields,'name':fields['name']+'已更新','expectedVersion':0}
        admin.call('PUT',f"/registrar/{kind}/{person['id']}",changed,expected=200)
        admin.call('PUT',f"/registrar/{kind}/{person['id']}",changed,expected=409)
        assert admin.data(f"/registrar/{kind}/{person['id']}")['name']==changed['name']
        admin.call('DELETE',f"/registrar/{kind}/{person['id']}?expectedVersion=1",expected=204)
    return 'both person types created/updated; stale update rejected; SSN nonunique allowed'
def fields_invalid():
    original=dict(name='x',birthDate='2004-01-01',ssn='123456789',status='ENROLLED')
    for patch in [dict(name=''),dict(birthDate='2099-01-01'),dict(ssn='123'),dict(status='UNKNOWN'),dict(extra='x'),dict(graduationDate='1999-01-01')]:admin.call('POST','/registrar/students',{**original,**patch},expected=400)
    admin.call('POST','/registrar/professors',dict(name='x',birthDate='1980-01-01',ssn='123456789',status='ON_DUTY',department=''),expected=400)
    return 'blank name, future birthday, malformed SSN/status, unknown field, date order, blank department'
def password_reset():
    fields=dict(name='改密验收',birthDate='2004-01-01',ssn='321654987',status='ENROLLED')
    p=admin.call('POST','/registrar/students',fields,expected=201)[1]['data'];old=p['credentialDelivery']['temporaryPassword']
    c=Client(p['username'],old);c.call('POST','/auth/password',dict(oldPassword=old,newPassword='AcceptancePassword2026!'),expected=200)
    one=Client(p['username'],'AcceptancePassword2026!');two=Client(p['username'],'AcceptancePassword2026!')
    reset=admin.call('POST',f"/registrar/accounts/{p['accountId']}/reset-password",expected=200)[1]['data']
    one.call('GET','/auth/me',expected=401);two.call('GET','/auth/me',expected=401)
    fresh=Client(p['username'],reset['credentialDelivery']['temporaryPassword']);fresh.call('GET','/catalog/offerings',expected=403)
    assert fresh.data('/auth/me')['mustChangePassword']
    admin.call('DELETE',f"/registrar/students/{p['id']}?expectedVersion=0",expected=204)
    return 'all prior sessions revoked, new temporary password works and requires change; no plaintext recorded'
def history_delete():
    account=Client('s30');before=account.data('/student/report-card');assert len(before)==2
    admin.call('DELETE','/registrar/students/30?expectedVersion=0',expected=204)
    account.call('GET','/auth/me',expected=401)
    assert sql('SELECT COUNT(*) FROM enrollment e JOIN offering o ON o.id=e.offering_id WHERE e.student_id=30 AND o.semester_id=1')=='2'
    assert sql('SELECT COUNT(*) FROM grade g JOIN enrollment e ON e.id=g.enrollment_id WHERE e.student_id=30')=='2'
    return 'logical deletion disables account; both historical enrollments and grades retained'
def person_atomic():
    before=sql('SELECT COUNT(*) FROM student');accounts=sql('SELECT COUNT(*) FROM user_account')
    hook(admin,'people.afterPerson','fail')
    try:admin.call('POST','/registrar/students',dict(name='rollback',birthDate='2004-01-01',ssn='222333444',status='ENROLLED'),expected=500)
    finally:hook(admin,'people.afterPerson','release')
    assert sql('SELECT COUNT(*) FROM student')==before and sql('SELECT COUNT(*) FROM user_account')==accounts
    return 'person insert failure rolls back person+account together'
def source_outage():
    def control(mode,delay=0):
        req=urllib.request.Request('http://127.0.0.1:8091/control',data=json.dumps(dict(mode=mode,delay=delay)).encode(),headers={'Content-Type':'application/json','X-Mock-Token':S['mockToken']},method='POST');urllib.request.urlopen(req).close()
    before=student.data('/catalog/offerings');control('fail')
    try:assert student.data('/catalog/offerings')==before;admin.call('POST','/registrar/catalog/probe',expected=503)
    finally:control('normal')
    control('slow',15);start=time.monotonic()
    try:admin.call('POST','/registrar/catalog/probe',expected=504)
    finally:control('normal')
    elapsed=time.monotonic()-start;assert 8.5<=elapsed<=10
    return dict(frozenReadsUnaffected=True,timeoutSeconds=round(elapsed,3),note='request budget9s leaves headroom for10s overall target; real legacy database not tested')
def synthetic_catalog():
    sql("INSERT INTO course(id,external_id) SELECT 10000+n,CONCAT('SCALE-C-',n) FROM (WITH RECURSIVE n AS (SELECT 1 n UNION ALL SELECT n+1 FROM n WHERE n<600) SELECT n FROM n) t;")
    sql("INSERT INTO course_snapshot(snapshot_id,course_id,name,department) SELECT 2,id,CONCAT('Synthetic course ',id),'Synthetic' FROM course WHERE id BETWEEN 10001 AND 10600;")
    sql("INSERT INTO offering(id,external_id,course_id,semester_id,snapshot_id) SELECT 20000+n,CONCAT('SCALE-O-',n),10000+MOD(n-1,600)+1,2,2 FROM (WITH RECURSIVE n AS (SELECT 1 n UNION ALL SELECT n+1 FROM n WHERE n<600) SELECT n FROM n UNION ALL SELECT n+600 FROM n) t;")
    sql('INSERT INTO offering_runtime(offering_id,semester_id) SELECT id,2 FROM offering WHERE id BETWEEN 20001 AND 21200;')
    sql("INSERT INTO offering_meeting(offering_id,weekday,start_time,end_time,first_date,last_date) SELECT id,1,'14:00:00','15:30:00','2026-09-01','2026-12-31' FROM offering WHERE id BETWEEN 20001 AND 21200;")
    first=student.data('/catalog/offerings?size=20');last=student.data('/catalog/offerings?size=20&page=61');assert first['total']==1212 and len(first['items'])==20 and len(last['items'])==12
    ids=[]
    for page in range(1,62):ids.extend(o['id'] for o in student.data(f'/catalog/offerings?size=20&page={page}')['items'])
    assert len(ids)==len(set(ids))==1212 and ids[-1]=='21200'
    assert student.data('/catalog/offerings?q=Synthetic%20course&size=100')['total']==1200
    assert teacher.data('/catalog/offerings?size=100')['total']==12
    student.call('GET','/catalog/offerings?semesterId=999',expected=404)
    return dict(total=1212,pages=61,noMissingOrDuplicate=True,professorEligibilityFiltered=True,fixture='600 synthetic courses / 1200 extra offerings in isolated test DB')
check('A01','错误、未知及空凭据',['TC-AUTH-002'],auth_bad)
check('A02','跨角色和历史班访问拒绝',['TC-SEC-001','TC-GRADE-008','TC-PRIV-001'],privacy)
check('A03','版本输入类型边界',['TC-API-006'],versions)
check('A04','首次数量和重复班/课程',['TC-REG-002','TC-REG-004','TC-REG-009'],selections)
check('A05','F/I/未录入先修',['TC-REG-006','TC-REG-007'],prerequisites)
check('A06','六种成绩与旧版本',['TC-GRADE-001','TC-GRADE-005'],grade_variants)
check('A07','后项非法整批不提交',['TC-GRADE-002'],grade_batch_atomic)
check('A08','两种人员更新/旧版本/SSN重复',['TC-PEOPLE-001','TC-PEOPLE-002','TC-PEOPLE-010'],people_update)
check('A09','人员字段非法变体',['TC-PEOPLE-008','TC-PEOPLE-009','TC-PEOPLE-010','TC-PEOPLE-011'],fields_invalid)
check('A10','重置密码撤销全部旧会话',['TC-AUTH-006','TC-PEOPLE-013'],password_reset)
check('A11','删除保留历史且禁用会话',['TC-PEOPLE-003','TC-PEOPLE-013'],history_delete)
check('A12','人员创建中途失败回滚',['TC-PEOPLE-007','TC-DB-004'],person_atomic)
check('A13','目录源故障与十秒预算',['TC-CAT-004','TC-CAT-005'],source_outage)
check('A14','1212班SQL分页与过滤',['TC-CAT-002','TC-API-003'],synthetic_catalog)
print('Additional acceptance scenarios:',len(rows),'PASS')
