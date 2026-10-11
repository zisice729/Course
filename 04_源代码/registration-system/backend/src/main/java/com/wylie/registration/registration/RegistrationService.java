package com.wylie.registration.registration;

import com.wylie.registration.auth.Actor;
import com.wylie.registration.catalog.CatalogService;
import com.wylie.registration.shared.*;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.util.*;

@Service
public class RegistrationService {
    private final Db db;private final CatalogService catalog;private final Gates gates;private final Rules rules;private final Dedup dedup;private final Settings settings;private final Faults faults;
    public RegistrationService(Db db,CatalogService catalog,Gates gates,Rules rules,Dedup dedup,Settings settings,Faults faults){this.db=db;this.catalog=catalog;this.gates=gates;this.rules=rules;this.dedup=dedup;this.settings=settings;this.faults=faults;}
    public Map<String,Object> view(Actor a,long semester){a.require("STUDENT");if(db.one("SELECT id FROM schedule WHERE student_id=? AND semester_id=?",a.studentId(),semester)==null)throw new Api.Failure(404,"SCHEDULE_NOT_FOUND","课表尚未创建");return viewStudent(a.studentId(),semester);}
    public Map<String,Object> viewStudent(long student,long semester){
        var row=db.one("SELECT * FROM schedule WHERE student_id=? AND semester_id=?",student,semester);
        Map<String,Object> result=new LinkedHashMap<>();result.put("studentId",student);result.put("semesterId",semester);result.put("version",row==null?0:row.get("version"));result.put("state",row==null?"DRAFT":row.get("state"));result.put("firstSubmittedAt",row==null?null:row.get("firstSubmittedAt"));
        result.put("draftItems",row==null?List.of():db.list("SELECT i.offering_id,i.choice_type,i.choice_order,i.state,i.cancel_reason,c.name FROM schedule_item i JOIN offering o ON o.id=i.offering_id JOIN course_snapshot c ON c.snapshot_id=o.snapshot_id AND c.course_id=o.course_id WHERE i.schedule_id=? ORDER BY i.choice_type DESC,i.choice_order",row.get("id")));
        result.put("enrolledItems",db.list("SELECT e.id enrollment_id,e.offering_id,e.origin_type,e.state,c.name,r.fee FROM enrollment e JOIN offering o ON o.id=e.offering_id JOIN course_snapshot c ON c.snapshot_id=o.snapshot_id AND c.course_id=o.course_id JOIN offering_runtime r ON r.offering_id=o.id WHERE e.student_id=? AND o.semester_id=? AND e.state='ACTIVE' ORDER BY o.id",student,semester));
        result.put("cancelledItems",db.list("SELECT e.offering_id,e.cancel_reason,c.name FROM enrollment e JOIN offering o ON o.id=e.offering_id JOIN course_snapshot c ON c.snapshot_id=o.snapshot_id AND c.course_id=o.course_id WHERE e.student_id=? AND o.semester_id=? AND e.state='CANCELLED'",student,semester));return result;
    }
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> choices(Map<String,Object> body,long semester){
        Api.check(body.keySet().stream().allMatch(Set.of("expectedVersion","choiceItems")::contains),"包含不支持的字段");
        Api.check(body.get("choiceItems") instanceof List<?>,"choiceItems必须为数组");var raw=(List<?>)body.get("choiceItems");Api.check(raw.size()<=6,"最多4主选2备选");
        Set<Long> ids=new HashSet<>();Set<String> slots=new HashSet<>();int primary=0,alternate=0;List<Map<String,Object>> result=new ArrayList<>();
        for(Object item:raw){Api.check(item instanceof Map<?,?>,"选择项格式错误");var map=(Map<String,Object>)item;
            Api.check(map.keySet().equals(Set.of("offeringId","choiceType","order")),"选择项字段不正确");Api.check(map.get("offeringId") instanceof String,"offeringId须为字符串");
            long id=id(map.get("offeringId"));String type=Objects.toString(map.get("choiceType"),"");Api.check(Set.of("PRIMARY","ALTERNATE").contains(type),"选择类型不正确");Api.check(map.get("order") instanceof Integer,"order必须为整数");int order=((Number)map.get("order")).intValue();
            Api.check(order>=1&&order<=(type.equals("PRIMARY")?4:2)&&ids.add(id)&&slots.add(type+order),"班级或顺序重复/越界");
            var offering=catalog.offering(id);Api.check(Db.num(offering,"semesterId")==semester,"班级不属于当前学期");
            if(type.equals("PRIMARY"))primary++;else alternate++;
            result.add(Map.of("offeringId",id,"choiceType",type,"order",order));
        }
        for(int i=1;i<=primary;i++)Api.check(slots.contains("PRIMARY"+i),"主选顺序须连续");for(int i=1;i<=alternate;i++)Api.check(slots.contains("ALTERNATE"+i),"备选顺序须连续");
        return result;
    }
    public static long id(Object value){try{String s=Objects.toString(value,"");Api.check(s.matches("[1-9][0-9]{0,18}"),"ID格式不正确");return Long.parseLong(s);}catch(NumberFormatException e){throw Api.bad("ID超出范围");}}
    private Map<String,Object> lockSchedule(long student,long semester){
        db.exec("INSERT IGNORE INTO schedule(student_id,semester_id) VALUES(?,?)",student,semester);
        return db.one("SELECT * FROM schedule WHERE student_id=? AND semester_id=? FOR UPDATE",student,semester);
    }
    public Object write(Actor a,long semester,Map<String,Object> body,String key,boolean submit){
        a.require("STUDENT");var items=choices(body,semester);String op=(submit?"submit:":"draft:")+semester;
        return db.tx(()->{
            var term=gates.semester(semester,true);gates.person(a,true);var schedule=lockSchedule(a.studentId(),semester);Object prior=dedup.existing(a.id(),op,key,body);if(prior!=null)return prior;
            boolean first=schedule.get("lastSubmittedAt")==null||"DELETED".equals(schedule.get("state"));gates.window(term,first);gates.version(schedule,body.get("expectedVersion"));faults.hit("registration.afterGate");
            List<Map<String,Object>> targets=items.stream().filter(i->"PRIMARY".equals(i.get("choiceType"))).map(i->catalog.offering(Db.num(i,"offeringId"))).toList();
            var old=db.list("SELECT e.offering_id FROM enrollment e JOIN offering o ON o.id=e.offering_id WHERE e.student_id=? AND o.semester_id=? AND e.state='ACTIVE'",a.studentId(),semester);
            if(submit){
                if(first)Api.check(targets.size()==4&&items.size()==6,"首次及删除后重建须4主选2备选");
                Set<Long> lockIds=new HashSet<>();old.forEach(r->lockIds.add(Db.num(r,"offeringId")));targets.forEach(r->lockIds.add(Db.num(r,"id")));gates.offerings(lockIds);
                // Refresh runtime fields after acquiring row locks; static metadata remains frozen.
                targets=targets.stream().map(o->catalog.offering(Db.num(o,"id"))).toList();
                for(var target:targets){if(!"OPEN".equals(target.get("state")))throw Api.conflict("OFFERING_CLOSED","班级不可注册");rules.prerequisites(a.studentId(),target);}
                rules.noConflicts(targets,term,true);
                Set<Long> targetIds=new HashSet<>();targets.forEach(t->targetIds.add(Db.num(t,"id")));Set<Long> oldIds=new HashSet<>();old.forEach(r->oldIds.add(Db.num(r,"offeringId")));
                for(long id:oldIds)if(!targetIds.contains(id)){db.exec("UPDATE enrollment SET state='WITHDRAWN',version=version+1,cancel_reason=NULL WHERE student_id=? AND offering_id=? AND state='ACTIVE'",a.studentId(),id);db.exec("UPDATE offering_runtime SET enrolled_count=enrolled_count-1,version=version+1 WHERE offering_id=?",id);}
                faults.hit("registration.afterWithdraw");
                for(long id:targetIds)if(!oldIds.contains(id)){
                    if(db.exec("UPDATE offering_runtime SET enrolled_count=enrolled_count+1,version=version+1 WHERE offering_id=? AND enrolled_count<capacity",id)!=1)throw Api.conflict("COURSE_FULL","所选班级已满员");
                    db.exec("INSERT INTO enrollment(student_id,offering_id,state,origin_type) VALUES(?,?,'ACTIVE','PRIMARY') ON DUPLICATE KEY UPDATE state='ACTIVE',origin_type='PRIMARY',cancel_reason=NULL,version=version+1",a.studentId(),id);
                }
                faults.hit("registration.afterEnroll");
                db.exec("UPDATE schedule SET state='SUBMITTED',submitted_choices_json=?,first_submitted_at=COALESCE(first_submitted_at,?),last_submitted_at=?,version=version+1 WHERE id=?",dedup.json(items),Timestamp.from(settings.now()),Timestamp.from(settings.now()),schedule.get("id"));
            }else db.exec("UPDATE schedule SET state=IF(state='DELETED','DRAFT',state),version=version+1 WHERE id=?",schedule.get("id"));
            db.exec("DELETE FROM schedule_item WHERE schedule_id=?",schedule.get("id"));
            Set<Long> enrolled=new HashSet<>();if(submit)targets.forEach(o->enrolled.add(Db.num(o,"id")));else old.forEach(o->enrolled.add(Db.num(o,"offeringId")));
            for(var i:items)db.exec("INSERT INTO schedule_item(schedule_id,offering_id,choice_type,choice_order,state) VALUES(?,?,?,?,?)",schedule.get("id"),i.get("offeringId"),i.get("choiceType"),i.get("order"),enrolled.contains(Db.num(i,"offeringId"))?"ENROLLED":"SELECTED");
            var result=view(a,semester);dedup.remember(a.id(),op,key,body,result);db.audit(a.id(),submit?"SCHEDULE_SUBMITTED":"DRAFT_SAVED",schedule.get("id").toString(),"version updated");return result;
        });
    }
    public Object delete(Actor a,long semester,Map<String,Object> body,String key){a.require("STUDENT");return db.tx(()->{
        var term=gates.semester(semester,true);gates.person(a,true);var row=lockSchedule(a.studentId(),semester);String op="deleteSchedule:"+semester;var prev=dedup.existing(a.id(),op,key,body);if(prev!=null)return prev;gates.window(term,false);gates.version(row,body.get("expectedVersion"));
        var active=db.list("SELECT e.offering_id FROM enrollment e JOIN offering o ON o.id=e.offering_id WHERE e.student_id=? AND o.semester_id=? AND e.state='ACTIVE'",a.studentId(),semester);gates.offerings(active.stream().map(r->Db.num(r,"offeringId")).toList());
        for(var item:active){db.exec("UPDATE enrollment SET state='WITHDRAWN',version=version+1 WHERE student_id=? AND offering_id=?",a.studentId(),item.get("offeringId"));db.exec("UPDATE offering_runtime SET enrolled_count=enrolled_count-1,version=version+1 WHERE offering_id=?",item.get("offeringId"));}
        db.exec("DELETE FROM schedule_item WHERE schedule_id=?",row.get("id"));db.exec("UPDATE schedule SET state='DELETED',submitted_choices_json=NULL,last_submitted_at=NULL,version=version+1 WHERE id=?",row.get("id"));var result=view(a,semester);dedup.remember(a.id(),op,key,body,result);db.audit(a.id(),"SCHEDULE_DELETED",row.get("id").toString(),"released seats");return result;});}
}
