package com.wylie.registration.api;

import com.wylie.registration.auth.Actor;
import com.wylie.registration.catalog.*;
import com.wylie.registration.registration.RegistrationService;
import com.wylie.registration.teaching.TeachingService;
import com.wylie.registration.people.PeopleService;
import com.wylie.registration.closing.CloseService;
import com.wylie.registration.billing.BillingService;
import com.wylie.registration.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final Db db;private final CatalogService catalog;private final RegistrationService registration;private final TeachingService teaching;private final PeopleService people;private final CloseService closing;private final BillingService billing;private final Settings settings;private final Faults faults;private final CatalogImporter importer;
    public ApiController(Db db,CatalogService catalog,RegistrationService registration,TeachingService teaching,PeopleService people,CloseService closing,BillingService billing,Settings settings,Faults faults,CatalogImporter importer){this.db=db;this.catalog=catalog;this.registration=registration;this.teaching=teaching;this.people=people;this.closing=closing;this.billing=billing;this.settings=settings;this.faults=faults;this.importer=importer;}
    private Actor actor(HttpServletRequest req){return Actor.from(req);}
    private long id(String s){return RegistrationService.id(s);}
    private long current(){return Db.num(catalog.context(),"registrationSemesterId");}
    @GetMapping("/health") Object health(){if(db.one("SELECT id FROM academic_context WHERE id=1")==null)throw new Api.Failure(503,"APP_STARTING","初始化尚未完成");return Api.ok(Map.of("status","UP"));}
    @GetMapping({"/semesters/registration-current","/semesters/current"}) Object term(){var context=new LinkedHashMap<>(catalog.context());context.put("clock",settings.now().toString());context.put("fixedClock",settings.fixedClock!=null&&!settings.fixedClock.isBlank());return Api.ok(context);}
    @GetMapping("/catalog/offerings") Object offers(HttpServletRequest req,@RequestParam(required=false) String semesterId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="id") String sort){
        Actor a=actor(req);if(!Set.of("STUDENT","PROFESSOR").contains(a.role()))throw new Api.Failure(403,"FORBIDDEN","当前角色无目录查询权限");long term=semesterId==null?current():id(semesterId);
        return Api.ok(catalog.page(term,a.role().equals("PROFESSOR")?a.professorId():null,q,page,size,sort));
    }
    @GetMapping("/catalog/offerings/{offering}") Object detail(HttpServletRequest req,@PathVariable String offering){Actor a=actor(req);var o=catalog.offering(id(offering));if(a.role().equals("PROFESSOR")){if(teaching.eligible(a,Db.num(o,"semesterId")).stream().noneMatch(x->Db.num(x,"id")==id(offering)))throw new Api.Failure(403,"FORBIDDEN","无该课程查询权限");}else a.require("STUDENT");return Api.ok(o);}
    @GetMapping("/student/schedules/{semester}") Object schedule(HttpServletRequest req,@PathVariable String semester){Api.check(id(semester)==current(),"只操作当前注册学期");return Api.ok(registration.view(actor(req),id(semester)));}
    @PutMapping("/student/schedules/{semester}/draft") Object draft(HttpServletRequest req,@PathVariable String semester,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key){Api.check(id(semester)==current(),"只操作当前注册学期");return Api.ok(registration.write(actor(req),id(semester),body,key,false));}
    @PostMapping("/student/schedules/{semester}/submit") Object submit(HttpServletRequest req,@PathVariable String semester,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key){Api.check(id(semester)==current(),"只操作当前注册学期");return Api.ok(registration.write(actor(req),id(semester),body,key,true));}
    @DeleteMapping("/student/schedules/{semester}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteSchedule(HttpServletRequest req,@PathVariable String semester,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key){Api.check(id(semester)==current(),"只操作当前注册学期");registration.delete(actor(req),id(semester),body,key);}
    @GetMapping("/student/report-card") Object report(HttpServletRequest req,@RequestParam(required=false) String semesterId){if(semesterId!=null)Api.check(id(semesterId)==Db.num(catalog.context(),"previousCompletedSemesterId"),"仅查询上一已完成学期");return Api.ok(teaching.report(actor(req)));}
    @GetMapping("/professor/eligible-offerings") Object eligible(HttpServletRequest req){return Api.ok(teaching.eligible(actor(req),current()));}
    @GetMapping("/professor/assignments") Object assignments(HttpServletRequest req){return Api.ok(teaching.assignments(actor(req),current()));}
    @PutMapping("/professor/assignments") Object assign(HttpServletRequest req,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key){return Api.ok(teaching.assign(actor(req),current(),body,key));}
    @GetMapping("/professor/teaching-assignments/{semester}") Object termAssignments(HttpServletRequest req,@PathVariable String semester){Api.check(id(semester)==current(),"只操作当前授课学期");return Api.ok(teaching.assignments(actor(req),id(semester)));}
    @PutMapping("/professor/teaching-assignments/{semester}") Object termAssign(HttpServletRequest req,@PathVariable String semester,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key){Api.check(id(semester)==current(),"只操作当前授课学期");return Api.ok(teaching.assign(actor(req),id(semester),body,key));}
    @GetMapping("/professor/historical-offerings") Object historical(HttpServletRequest req){return Api.ok(teaching.historical(actor(req)));}
    @GetMapping("/professor/offerings/{offering}/roster") Object roster(HttpServletRequest req,@PathVariable String offering){return Api.ok(teaching.roster(actor(req),id(offering)));}
    @PutMapping("/professor/offerings/{offering}/grades") Object grades(HttpServletRequest req,@PathVariable String offering,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key){return Api.ok(teaching.grades(actor(req),id(offering),body,key));}
    @GetMapping("/registrar/{type:students|professors}") Object people(HttpServletRequest req,@PathVariable String type,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="id") String sort){return Api.ok(paginate(people.list(actor(req),type),q,page,size,sort));}
    @GetMapping("/registrar/{type:students|professors}/{person}") Object person(HttpServletRequest req,@PathVariable String type,@PathVariable String person){return Api.ok(people.detail(actor(req),type,id(person)));}
    @PostMapping("/registrar/{type:students|professors}") ResponseEntity<?> create(HttpServletRequest req,@PathVariable String type,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key){return ResponseEntity.status(201).body(Api.ok(people.create(actor(req),type,body,key)));}
    @PutMapping("/registrar/{type:students|professors}/{person}") Object update(HttpServletRequest req,@PathVariable String type,@PathVariable String person,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key") String key){return Api.ok(people.update(actor(req),type,id(person),body,key));}
    @DeleteMapping("/registrar/{type:students|professors}/{person}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(HttpServletRequest req,@PathVariable String type,@PathVariable String person,@RequestParam long expectedVersion){people.delete(actor(req),type,id(person),expectedVersion);}
    @PostMapping("/registrar/accounts/{account}/reset-password") Object reset(HttpServletRequest req,@PathVariable String account){return Api.ok(people.reset(actor(req),id(account)));}
    @PostMapping("/registrar/semesters/{semester}/close-jobs") ResponseEntity<?> close(HttpServletRequest req,@PathVariable String semester,@RequestHeader("Idempotency-Key") String key){Api.check(!key.isBlank()&&key.length()<=100,"业务键不正确");return ResponseEntity.status(202).body(Api.ok(closing.request(actor(req),id(semester))));}
    @GetMapping("/registrar/close-jobs/{job}") Object job(HttpServletRequest req,@PathVariable String job){actor(req).require("REGISTRAR");return Api.ok(closing.job(id(job)));}
    @GetMapping("/registrar/close-jobs") Object jobs(HttpServletRequest req){actor(req).require("REGISTRAR");return Api.ok(db.list("SELECT id,semester_id,state,attempt_count,last_error FROM close_job ORDER BY id DESC"));}
    @GetMapping("/registrar/billing-records") Object bills(HttpServletRequest req){return Api.ok(billing.records(actor(req)));}
    @PostMapping("/registrar/billing-records/{bill}/retry") Object retry(HttpServletRequest req,@PathVariable String bill){return Api.ok(billing.retry(actor(req),id(bill)));}
    @GetMapping("/registrar/audit-events") Object audit(HttpServletRequest req,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){actor(req).require("REGISTRAR");return Api.ok(paginate(db.list("SELECT * FROM audit_event ORDER BY id DESC"),"",page,size,"id"));}
    @GetMapping("/registrar/overview") Object overview(HttpServletRequest req){actor(req).require("REGISTRAR");return Api.ok(Map.of("offerings",catalog.offerings(current()),"students",db.one("SELECT COUNT(*) total FROM student WHERE deleted_at IS NULL").get("total"),"professors",db.one("SELECT COUNT(*) total FROM professor WHERE deleted_at IS NULL").get("total")));}
    @GetMapping("/registrar/integrity") Object integrity(HttpServletRequest req){actor(req).require("REGISTRAR");return Api.ok(db.tx(()->db.list("SELECT r.offering_id,r.enrolled_count,COALESCE(e.actual_count,0) actual_count FROM offering_runtime r LEFT JOIN (SELECT offering_id,COUNT(*) actual_count FROM enrollment WHERE state='ACTIVE' GROUP BY offering_id)e ON e.offering_id=r.offering_id WHERE r.enrolled_count<>COALESCE(e.actual_count,0)")));}
    @PostMapping("/registrar/catalog/probe") Object probe(HttpServletRequest req){actor(req).require("REGISTRAR");return Api.ok(Map.of("version",importer.fetch().get("version"),"mode","read-only source probe; frozen mirror unchanged"));}
    @GetMapping("/test/hooks") Object hooks(HttpServletRequest req){test(req);return Api.ok(faults.status());}
    @PostMapping("/test/hooks") Object hook(HttpServletRequest req,@RequestBody Map<String,Object> body){test(req);String name=Objects.toString(body.get("point"),"");faults.set(name,Objects.toString(body.get("mode"),"release"));return Api.ok(faults.status());}
    private void test(HttpServletRequest req){actor(req).require("REGISTRAR");if(!settings.test())throw new Api.Failure(404,"NOT_FOUND","此环境不提供测试控制");}
    private Map<String,Object> paginate(List<Map<String,Object>> source,String q,int page,int size,String sort){
        Api.check(page>=1&&page<=100000&&size>=1&&size<=100&&q.length()<=100&&Set.of("id","name").contains(sort),"分页、搜索或排序参数不正确");
        var filtered=source.stream().filter(r->q.isBlank()||Objects.toString(r.get("name"),"").contains(q)||Objects.toString(r.get("id"),"").equals(q)).sorted(sort.equals("name")?Comparator.comparing(r->Objects.toString(r.get("name"),"")):Comparator.comparingLong(r->Db.num(r,"id"))).toList();
        long start=(long)(page-1)*size;return Map.of("items",start>=filtered.size()?List.of():filtered.subList((int)start,Math.min(filtered.size(),(int)start+size)),"total",filtered.size(),"page",page,"size",size);
    }
}
