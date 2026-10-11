package com.wylie.registration.teaching;

import com.wylie.registration.auth.Actor;
import com.wylie.registration.catalog.CatalogService;
import com.wylie.registration.registration.*;
import com.wylie.registration.shared.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TeachingService {
    private final Db db;private final CatalogService catalog;private final Gates gates;private final Rules rules;private final Dedup dedup;private final Faults faults;
    public TeachingService(Db db,CatalogService catalog,Gates gates,Rules rules,Dedup dedup,Faults faults){this.db=db;this.catalog=catalog;this.gates=gates;this.rules=rules;this.dedup=dedup;this.faults=faults;}
    public Map<String,Object> assignments(Actor a,long semester){
        a.require("PROFESSOR");var row=db.one("SELECT version FROM teaching_guard WHERE professor_id=? AND semester_id=?",a.professorId(),semester);
        return Map.of("version",row==null?0:row.get("version"),"items",catalog.offerings(semester).stream().filter(o->Db.num(o,"professorId")==a.professorId()).toList());
    }
    public List<Map<String,Object>> eligible(Actor a,long semester){a.require("PROFESSOR");Set<Long> courses=new HashSet<>();db.list("SELECT course_id FROM teaching_eligibility WHERE professor_id=?",a.professorId()).forEach(r->courses.add(Db.num(r,"courseId")));return catalog.offerings(semester).stream().filter(o->courses.contains(Db.num(o,"courseId"))||Db.num(o,"professorId")==a.professorId()).toList();}
    public Object assign(Actor a,long semester,Map<String,Object> body,String key){
        a.require("PROFESSOR");Api.check(body.keySet().equals(Set.of("expectedVersion","offeringIds")),"授课字段不正确");Api.check(body.get("offeringIds") instanceof List<?>,"offeringIds须数组");List<?> raw=(List<?>)body.get("offeringIds");Api.check(raw.size()<=100,"授课集合过大");Set<Long> target=new HashSet<>();for(var v:raw){Api.check(v instanceof String&&target.add(RegistrationService.id(v)),"班ID须字符串且不可重复");}
        return db.tx(()->{
            var term=gates.semester(semester,true);gates.person(a,true);gates.window(term,false);
            db.exec("INSERT IGNORE INTO teaching_guard(professor_id,semester_id) VALUES(?,?)",a.professorId(),semester);
            var guard=db.one("SELECT * FROM teaching_guard WHERE professor_id=? AND semester_id=? FOR UPDATE",a.professorId(),semester);
            String op="teaching:"+semester;Object old=dedup.existing(a.id(),op,key,body);if(old!=null)return old;gates.version(guard,body.get("expectedVersion"));
            Set<Long> prior=new HashSet<>();db.list("SELECT a.offering_id FROM offering_assignment a JOIN offering o ON o.id=a.offering_id WHERE a.professor_id=? AND o.semester_id=?",a.professorId(),semester).forEach(r->prior.add(Db.num(r,"offeringId")));
            Set<Long> locks=new HashSet<>(prior);locks.addAll(target);gates.offerings(locks);
            List<Map<String,Object>> offers=new ArrayList<>();
            for(long id:target){var o=catalog.offering(id);Api.check(Db.num(o,"semesterId")==semester,"班级学期不正确");if(!"OPEN".equals(o.get("state")))throw Api.conflict("OFFERING_CLOSED","班级不可分配");
                if(db.one("SELECT course_id FROM teaching_eligibility WHERE professor_id=? AND course_id=?",a.professorId(),o.get("courseId"))==null)throw Api.conflict("NOT_ELIGIBLE","无该课程授课资格");
                if(o.get("professorId")!=null&&Db.num(o,"professorId")!=a.professorId())throw Api.conflict("OFFERING_ASSIGNED","班级已有其他教师");offers.add(o);
            }
            rules.noConflicts(offers,term,false);
            for(long id:prior)if(!target.contains(id))db.exec("DELETE FROM offering_assignment WHERE offering_id=? AND professor_id=?",id,a.professorId());
            for(long id:target)if(!prior.contains(id))db.exec("INSERT INTO offering_assignment(offering_id,professor_id) VALUES(?,?)",id,a.professorId());
            db.exec("UPDATE teaching_guard SET version=version+1 WHERE professor_id=? AND semester_id=?",a.professorId(),semester);var result=assignments(a,semester);dedup.remember(a.id(),op,key,body,result);db.audit(a.id(),"TEACHING_CHANGED",String.valueOf(a.professorId()),"assignment updated");return result;
        });
    }
    private Map<String,Object> ownedHistorical(Actor a,long offering){
        a.require("PROFESSOR");var o=catalog.offering(offering);if(Db.num(o,"professorId")!=a.professorId())throw new Api.Failure(403,"FORBIDDEN","无权访问其他教师班级");
        long previous=Db.num(catalog.context(),"previousCompletedSemesterId");if(Db.num(o,"semesterId")!=previous||!"COMPLETED".equals(catalog.semester(previous).get("teachingState")))throw Api.conflict("INVALID_SEMESTER","成绩仅操作上一已完成学期");return o;
    }
    public List<Map<String,Object>> historical(Actor a){a.require("PROFESSOR");long previous=Db.num(catalog.context(),"previousCompletedSemesterId");if(previous==0)return List.of();return catalog.offerings(previous).stream().filter(o->Db.num(o,"professorId")==a.professorId()).toList();}
    public List<Map<String,Object>> roster(Actor a,long offering){a.require("PROFESSOR");var o=catalog.offering(offering);if(Db.num(o,"professorId")!=a.professorId())throw new Api.Failure(403,"FORBIDDEN","无权访问其他教师班级");return db.list("SELECT e.id enrollment_id,e.student_id,s.name,g.grade,COALESCE(g.version,0) version FROM enrollment e JOIN student s ON s.id=e.student_id LEFT JOIN grade g ON g.enrollment_id=e.id WHERE e.offering_id=? AND e.state='ACTIVE' ORDER BY s.id",offering);}
    @SuppressWarnings("unchecked")
    public Object grades(Actor a,long offering,Map<String,Object> body,String key){
        var o=ownedHistorical(a,offering);Api.check(body.keySet().equals(Set.of("updates"))&&body.get("updates") instanceof List<?>,"成绩请求格式错误");var updates=(List<?>)body.get("updates");Api.check(updates.size()<=100,"单批最多100项");
        Set<Long> seen=new HashSet<>();for(var item:updates){Api.check(item instanceof Map<?,?>,"成绩项不正确");var u=(Map<String,Object>)item;Api.check(u.keySet().equals(Set.of("enrollmentId","grade","expectedVersion")),"成绩字段不正确");Api.check(u.get("enrollmentId") instanceof String&&seen.add(RegistrationService.id(u.get("enrollmentId"))),"名单ID不正确或重复");if(u.get("grade")!=null)Api.check(Rules.GRADES.contains(u.get("grade")),"成绩只允许A/B/C/D/F/I");}
        return db.tx(()->{
            gates.semester(Db.num(o,"semesterId"),false);gates.person(a,false);gates.offerings(List.of(offering));ownedHistorical(a,offering);
            String op="grades:"+offering;var prev=dedup.existing(a.id(),op,key,body);if(prev!=null)return prev;
            List<Map<String,Object>> sorted=updates.stream().map(u->(Map<String,Object>)u).sorted(Comparator.comparingLong(u->RegistrationService.id(u.get("enrollmentId")))).toList();
            int n=0;for(var u:sorted){long enrollment=RegistrationService.id(u.get("enrollmentId"));var e=db.one("SELECT * FROM enrollment WHERE id=? AND offering_id=? AND state='ACTIVE' FOR UPDATE",enrollment,offering);if(e==null)throw new Api.Failure(403,"FORBIDDEN","学生不属于本人班正式名单");
                if(u.get("grade")==null)continue;
                db.exec("INSERT IGNORE INTO grade(enrollment_id) VALUES(?)",enrollment);var g=db.one("SELECT * FROM grade WHERE enrollment_id=? FOR UPDATE",enrollment);gates.version(g,u.get("expectedVersion"));
                db.exec("UPDATE grade SET grade=?,version=version+1,entered_by=?,updated_at=CURRENT_TIMESTAMP(3) WHERE enrollment_id=?",u.get("grade"),a.professorId(),enrollment);db.audit(a.id(),"GRADE_CHANGED",String.valueOf(enrollment),"old="+g.get("grade")+",new="+u.get("grade"));if(++n==1)faults.hit("grade.afterFirst");
            }
            var result=roster(a,offering);dedup.remember(a.id(),op,key,body,result);return result;
        });
    }
    public List<Map<String,Object>> report(Actor a){a.require("STUDENT");long previous=Db.num(catalog.context(),"previousCompletedSemesterId");if(previous==0)return List.of();return db.list("SELECT o.id offering_id,c.name,p.name professor_name,g.grade FROM enrollment e JOIN offering o ON o.id=e.offering_id JOIN course_snapshot c ON c.snapshot_id=o.snapshot_id AND c.course_id=o.course_id LEFT JOIN offering_assignment oa ON oa.offering_id=o.id LEFT JOIN professor p ON p.id=oa.professor_id LEFT JOIN grade g ON g.enrollment_id=e.id WHERE e.student_id=? AND o.semester_id=? AND e.state='ACTIVE' ORDER BY o.id",a.studentId(),previous);}
}
