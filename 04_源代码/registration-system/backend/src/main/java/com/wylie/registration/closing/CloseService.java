package com.wylie.registration.closing;

import com.wylie.registration.auth.Actor;
import com.wylie.registration.catalog.CatalogService;
import com.wylie.registration.registration.Rules;
import com.wylie.registration.shared.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.math.BigDecimal;
import java.util.*;

@Service
public class CloseService {
    private final Db db;private final CatalogService catalog;private final Rules rules;private final Dedup json;private final Gates gates;private final Faults faults;
    private final String owner=UUID.randomUUID().toString();
    public CloseService(Db db,CatalogService catalog,Rules rules,Dedup json,Gates gates,Faults faults){this.db=db;this.catalog=catalog;this.rules=rules;this.json=json;this.gates=gates;this.faults=faults;}
    public Object request(Actor a,long semester){a.require("REGISTRAR");return db.tx(()->{gates.person(a,false);Api.check(Db.num(catalog.context(),"registrationSemesterId")==semester,"仅关闭当前注册学期");
        db.exec("INSERT IGNORE INTO close_job(semester_id) VALUES(?)",semester);var job=db.one("SELECT * FROM close_job WHERE semester_id=? FOR UPDATE",semester);
        if(Set.of("BLOCKED_BUSY","FAILED_LOCAL").contains(job.get("state")))db.exec("UPDATE close_job SET state='REQUESTED',last_error=NULL WHERE id=?",job.get("id"));
        db.audit(a.id(),"CLOSE_REQUESTED",String.valueOf(job.get("id")),"async accepted");return job(Db.num(job,"id"));});}
    public Map<String,Object> job(long id){var row=db.one("SELECT * FROM close_job WHERE id=?",id);if(row==null)throw new Api.Failure(404,"NOT_FOUND","关闭任务不存在");if(row.get("summaryJson")!=null)row.put("summary",json.parse(Db.str(row,"summaryJson")));row.remove("summaryJson");row.remove("leaseOwner");return row;}
    @Scheduled(fixedDelay=1000)
    public void work(){
        var claim=db.tx(()->{var row=db.one("SELECT * FROM close_job WHERE state='REQUESTED' OR (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3)) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");if(row==null)return null;
            db.exec("UPDATE close_job SET state='RUNNING',lease_owner=?,lease_token=lease_token+1,attempt_count=attempt_count+1,lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3)) WHERE id=?",owner,row.get("id"));return db.one("SELECT * FROM close_job WHERE id=?",row.get("id"));});
        if(claim==null)return;
        try{db.tx(()->{apply(claim);return null;});}
        catch(Exception failure){boolean busy=false;for(Throwable t=failure;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException e&&(e.getErrorCode()==3572||e.getErrorCode()==1205||e.getErrorCode()==1213))busy=true;
            String state=busy?"BLOCKED_BUSY":"FAILED_LOCAL",code=busy?"REGISTRATION_BUSY":failure instanceof Api.Failure f?f.code:"CLOSE_FAILED";
            db.tx(()->{db.exec("UPDATE close_job SET state=?,last_error=?,lease_until=NULL WHERE id=? AND lease_owner=? AND lease_token=? AND state='RUNNING'",state,code,claim.get("id"),owner,claim.get("leaseToken"));return null;});
        }
    }
    @SuppressWarnings("unchecked")
    private void apply(Map<String,Object> claim){
        long semester=Db.num(claim,"semesterId"),jobId=Db.num(claim,"id");var term=db.one("SELECT * FROM semester WHERE id=? FOR UPDATE NOWAIT",semester);
        var job=db.one("SELECT * FROM close_job WHERE id=? FOR UPDATE",jobId);if(!owner.equals(job.get("leaseOwner"))||Db.num(job,"leaseToken")!=Db.num(claim,"leaseToken")||!"RUNNING".equals(job.get("state")))return;
        if(!"OPEN".equals(term.get("state")))throw Api.conflict("REGISTRATION_CLOSED","学期已关闭");faults.hit("close.afterGate");
        db.exec("UPDATE semester SET state='CLOSING' WHERE id=?",semester);var offers=catalog.offerings(semester);gates.offerings(offers.stream().map(o->Db.num(o,"id")).toList());
        var active=db.list("SELECT e.* FROM enrollment e JOIN offering o ON o.id=e.offering_id WHERE o.semester_id=? AND e.state='ACTIVE'",semester);
        for(var o:offers){long n=active.stream().filter(e->Db.num(e,"offeringId")==Db.num(o,"id")).count();if(n!=Db.num(o,"enrolledCount"))throw new IllegalStateException("Initial count differs");}
        var schedules=db.list("SELECT sc.* FROM schedule sc JOIN student s ON s.id=sc.student_id JOIN user_account u ON u.student_id=s.id WHERE sc.semester_id=? AND sc.first_submitted_at IS NOT NULL AND sc.state<>'DELETED' AND s.deleted_at IS NULL AND s.status='ENROLLED' AND u.status='ACTIVE' ORDER BY sc.first_submitted_at,sc.student_id",semester);
        List<Leveling.Participant> people=new ArrayList<>();
        for(var sc:schedules){long student=Db.num(sc,"studentId");List<Leveling.Choice> choices=new ArrayList<>();for(var item:(List<Map<String,Object>>)json.parse(Db.str(sc,"submittedChoicesJson")))choices.add(new Leveling.Choice(Db.num(item,"offeringId"),Db.str(item,"choiceType"),(int)Db.num(item,"order")));
            Set<Long> ids=new HashSet<>();active.stream().filter(e->Db.num(e,"studentId")==student).forEach(e->ids.add(Db.num(e,"offeringId")));people.add(new Leveling.Participant(student,Gates.instant(sc.get("firstSubmittedAt")),choices,ids));
        }
        var plan=Leveling.calculate(people,offers,term,(student,o)->{try{rules.prerequisites(student,o);return true;}catch(Api.Failure f){return false;}});
        Map<Long,Map<String,Object>> offerIndex=new HashMap<>();offers.forEach(o->offerIndex.put(Db.num(o,"id"),o));
        for(var e:active){long student=Db.num(e,"studentId"),id=Db.num(e,"offeringId");if(!plan.courses().getOrDefault(student,Set.of()).contains(id)){String reason=plan.reasons().getOrDefault(student,Map.of()).getOrDefault(id,"STUDENT_INACTIVE");db.exec("UPDATE enrollment SET state='CANCELLED',cancel_reason=?,version=version+1 WHERE id=?",reason,e.get("id"));}}
        for(var sc:schedules){long student=Db.num(sc,"studentId");var finalIds=plan.courses().get(student);BigDecimal amount=BigDecimal.ZERO;List<Map<String,Object>> lines=new ArrayList<>();
            db.exec("DELETE FROM schedule_item WHERE schedule_id=?",sc.get("id"));int primaryOrder=0,alternateOrder=0;
            for(long id:finalIds){String origin=plan.origins().get(student).get(id);db.exec("INSERT INTO enrollment(student_id,offering_id,state,origin_type) VALUES(?,?,'ACTIVE',?) ON DUPLICATE KEY UPDATE state='ACTIVE',origin_type=VALUES(origin_type),cancel_reason=NULL,version=version+1",student,id,origin);
                db.exec("INSERT INTO schedule_item(schedule_id,offering_id,choice_type,choice_order,state) VALUES(?,?,?,?,'ENROLLED')",sc.get("id"),id,origin,origin.equals("PRIMARY")?++primaryOrder:++alternateOrder);
                var o=offerIndex.get(id);BigDecimal fee=new BigDecimal(o.get("fee").toString());amount=amount.add(fee);lines.add(Map.of("offeringId",String.valueOf(id),"courseId",String.valueOf(o.get("courseId")),"name",o.get("name"),"fee",fee.setScale(2).toPlainString()));
            }
            db.exec("UPDATE schedule SET state='FINALIZED',version=version+1 WHERE id=?",sc.get("id"));String business="registration:"+semester+":"+student;
            var snapshot=new LinkedHashMap<String,Object>();snapshot.put("businessKey",business);snapshot.put("studentId",String.valueOf(student));snapshot.put("semesterId",String.valueOf(semester));snapshot.put("closeJobId",String.valueOf(jobId));snapshot.put("algorithmVersion","LEVELING_V1_1");snapshot.put("amount",amount.setScale(2).toPlainString());snapshot.put("currency","CNY");snapshot.put("lines",lines);
            String payload=json.json(snapshot);long bill=db.insert("INSERT INTO billing_record(semester_id,student_id,close_job_id,business_key,amount,snapshot_json,payload_hash) VALUES(?,?,?,?,?,?,?)",semester,student,jobId,business,amount,payload,Dedup.hash(payload));db.exec("INSERT INTO billing_outbox(billing_record_id) VALUES(?)",bill);
        }
        for(var o:offers){long id=Db.num(o,"id");long n=plan.courses().values().stream().filter(s->s.contains(id)).count();db.exec("UPDATE offering_runtime SET state=?,enrolled_count=?,version=version+1 WHERE offering_id=?",plan.states().get(id),n,id);}
        faults.hit("close.beforeCommit");db.exec("UPDATE semester SET state='CLOSED' WHERE id=?",semester);
        var summary=Map.of("algorithmVersion","LEVELING_V1_1","studentCount",schedules.size(),"confirmedClasses",plan.states().values().stream().filter("CONFIRMED"::equals).count(),"cancelledClasses",plan.states().values().stream().filter("CANCELLED"::equals).count(),"attempts",plan.attempts(),"sourceVersion",offers.isEmpty()?"none":offers.getFirst().get("sourceVersion"));
        db.exec("UPDATE close_job SET state=?,summary_json=?,lease_until=NULL,last_error=NULL WHERE id=?",schedules.isEmpty()?"COMPLETE":"LOCAL_COMMITTED",json.json(summary),jobId);db.audit(0,"REGISTRATION_CLOSED",String.valueOf(jobId),"LEVELING_V1_1 local committed");
    }
}
