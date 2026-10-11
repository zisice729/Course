from integration import ROOT,Client,sql,hook,wait,mock,receipts
import subprocess,json,datetime,time
results=[]
def ps(script,*args):
    subprocess.run(['powershell','-ExecutionPolicy','Bypass','-File',str(ROOT/script),*args],cwd=ROOT,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
def record(id,title,cases,evidence):
    results.append(dict(scenarioId=id,title=title,relatedCaseIds=cases,status='PASS',evidence=evidence))
    (ROOT/'tests/results/restart-recovery.json').write_text(json.dumps(dict(executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),database='registration_test',scope='real process termination; expired leases deliberately advanced in isolated DB; recovery observed, not elapsed lease-duration certification',results=results),ensure_ascii=False,indent=2),encoding='utf-8')
    print(id,'PASS',flush=True)
admin=Client('registrar');before=sql('SELECT SUM(enrolled_count) FROM offering_runtime')
mock('fail');hook(admin,'close.beforeCommit','pause')
job=admin.call('POST','/registrar/semesters/2/close-jobs',expected=202)[1]['data']
wait(lambda:admin.data('/test/hooks').get('close.beforeCommit',{}).get('entered'))
token=int(sql(f"SELECT lease_token FROM close_job WHERE id={job['id']}"))
ps('scripts/restart_test.ps1','-StopOnly')
assert sql("SELECT state FROM semester WHERE id=2")=='OPEN' and sql('SELECT COUNT(*) FROM billing_record')=='0' and sql('SELECT SUM(enrolled_count) FROM offering_runtime')==before
sql("UPDATE close_job SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE state='RUNNING';")
ps('scripts/start.ps1','-Profile','test','-Database','registration_test','-BackendPort','8082')
admin.call('GET','/auth/me',expected=401);admin=Client('registrar')
wait(lambda:admin.data(f"/registrar/close-jobs/{job['id']}")['state']=='LOCAL_COMMITTED',40)
assert int(sql(f"SELECT lease_token FROM close_job WHERE id={job['id']}"))>token and sql('SELECT COUNT(*) FROM close_job')=='1'
record('R01','关闭提交前进程终止、原业务回滚及任务租约恢复',['TC-OPS-009','TC-CLOSE-010'],dict(sameJob=True,rollbackObserved=True,tokenAdvanced=True,oldSessionRejected=True))
mock('normal');hook(admin,'billing.beforeAck','pause')
for bill in admin.data('/registrar/billing-records'):admin.call('POST',f"/registrar/billing-records/{bill['id']}/retry",expected=200)
wait(lambda:admin.data('/test/hooks').get('billing.beforeAck',{}).get('entered'),40)
old_receipts=receipts();assert old_receipts
snapshot=sql("SELECT GROUP_CONCAT(payload_hash ORDER BY id) FROM billing_record")
ps('scripts/restart_test.ps1','-StopOnly','-WithBilling')
assert sql("SELECT COUNT(*) FROM billing_outbox WHERE state='SENDING'")!='0'
sql("UPDATE billing_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE state='SENDING'; UPDATE billing_outbox SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE state IN ('PENDING','RETRYING');")
ps('scripts/start.ps1','-Profile','test','-Database','registration_test','-BackendPort','8082')
admin=Client('registrar');wait(lambda:admin.data(f"/registrar/close-jobs/{job['id']}")['state']=='COMPLETE',60)
after=receipts();keys=[r['businessKey'] for r in after];assert len(keys)==len(set(keys))==int(sql('SELECT COUNT(*) FROM billing_record'))
for old in old_receipts:assert next(r for r in after if r['businessKey']==old['businessKey'])==old
assert sql("SELECT GROUP_CONCAT(payload_hash ORDER BY id) FROM billing_record")==snapshot
assert admin.data('/registrar/integrity')==[]
record('R02','计费接收后确认前崩溃、双方重启、持久去重恢复',['TC-OPS-004','TC-BILL-003','TC-BILL-004','TC-BILL-007'],dict(uniqueReceipts=len(keys),snapshotHashUnchanged=True,oldExternalReferencesUnchanged=True,state='COMPLETE'))
again=admin.call('POST','/registrar/semesters/2/close-jobs',expected=202)[1]['data'];assert again['id']==job['id'] and sql('SELECT COUNT(*) FROM close_job')=='1'
record('R03','关闭完成重复请求不再结算',['TC-CLOSE-009','TC-BILL-011'],dict(sameJob=True,noNewBill=True))
