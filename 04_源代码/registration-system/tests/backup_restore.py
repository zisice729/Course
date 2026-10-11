"""Round trip only registration_test. Workers must be stopped by the PS wrapper."""
from integration import ROOT,sql
import subprocess,json,datetime,hashlib,sys
tables=['student','professor','user_account','semester','catalog_snapshot','offering','schedule','schedule_item','enrollment','grade','close_job','billing_record','billing_outbox','request_dedup','audit_event']
def snapshot():
    counts={t:int(sql('SELECT COUNT(*) FROM '+t)) for t in tables}
    fingerprint=sql("SELECT SHA2(GROUP_CONCAT(CONCAT(student_id,':',offering_id,':',state) ORDER BY student_id,offering_id SEPARATOR '|'),256) FROM enrollment")
    return dict(counts=counts,enrollmentFingerprint=fingerprint)
before=snapshot();subprocess.run([sys.executable,str(ROOT/'scripts/backup.py'),'--database','registration_test'],check=True,capture_output=True)
backup=max((ROOT/'.runtime/backups').glob('registration_test-*.sql'),key=lambda x:x.stat().st_mtime)
digest=hashlib.sha256(backup.read_bytes()).hexdigest();assert digest==backup.with_suffix('.sha256').read_text().split()[0]
subprocess.run([sys.executable,str(ROOT/'scripts/backup.py'),'--database','registration_test','--restore',str(backup)],check=True,capture_output=True)
after=snapshot();assert before==after
assert sql("SELECT COUNT(*) FROM offering_runtime r LEFT JOIN (SELECT offering_id,COUNT(*) n FROM enrollment WHERE state='ACTIVE' GROUP BY offering_id)e ON e.offering_id=r.offering_id WHERE r.enrolled_count<>COALESCE(e.n,0)")=='0'
report=dict(executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),status='PASS',database='registration_test',backupSha256=digest,before=before,after=after,countsConsistent=True,scope='SQL row-count and enrollment fingerprint round trip; not a timed RTO/RPO drill')
(ROOT/'tests/results/backup-restore.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print('Backup/restore round trip PASS; demo database untouched.')
