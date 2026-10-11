package com.wylie.registration.people;

import com.wylie.registration.auth.Actor;
import com.wylie.registration.catalog.CatalogService;
import com.wylie.registration.shared.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;

@Service
public class PeopleService {
    private final Db db;private final Gates gates;private final CatalogService catalog;private final Settings settings;private final Dedup dedup;private final PasswordEncoder passwords;private final Faults faults;
    public PeopleService(Db db,Gates gates,CatalogService catalog,Settings settings,Dedup dedup,PasswordEncoder passwords,Faults faults){this.db=db;this.gates=gates;this.catalog=catalog;this.settings=settings;this.dedup=dedup;this.passwords=passwords;this.faults=faults;}
    private static String table(String type){if("students".equals(type))return "student";if("professors".equals(type))return "professor";throw new Api.Failure(404,"NOT_FOUND","人员类型不存在");}
    public List<Map<String,Object>> list(Actor a,String type){a.require("REGISTRAR");var rows=db.list("SELECT * FROM "+table(type)+" WHERE deleted_at IS NULL ORDER BY id");rows.forEach(r->r.put("ssn","***-**-"+Db.str(r,"ssn").substring(5)));return rows;}
    public Map<String,Object> detail(Actor a,String type,long id){a.require("REGISTRAR");String t=table(type);var r=db.one("SELECT * FROM "+t+" WHERE id=? AND deleted_at IS NULL",id);if(r==null)throw new Api.Failure(404,"NOT_FOUND","人员不存在");var account=db.one("SELECT id account_id,username FROM user_account WHERE "+t+"_id=?",id);if(account!=null)r.putAll(account);return r;}
    private Map<String,Object> fields(String type,Map<String,Object> body){
        String t=table(type);Set<String> allow=new HashSet<>(Set.of("name","birthDate","ssn","status","expectedVersion"));allow.add(t.equals("student")?"graduationDate":"department");Api.check(allow.containsAll(body.keySet()),"人员字段不正确");
        String name=Objects.toString(body.get("name"),"").trim();Api.check(!name.isBlank()&&name.codePointCount(0,name.length())<=100,"姓名须1—100字符");
        LocalDate birth;try{birth=LocalDate.parse(Objects.toString(body.get("birthDate"),""));}catch(Exception e){throw Api.bad("生日格式不正确");}Api.check(!birth.isAfter(settings.today()),"生日不能在未来");
        String ssn=Objects.toString(body.get("ssn"),"").replace("-","").replace(" ","");Api.check(ssn.matches("[0-9]{9}"),"SSN须9位虚构数字");
        String status=Objects.toString(body.get("status"),"");Api.check((t.equals("student")?Set.of("ENROLLED","GRADUATED","SUSPENDED"):Set.of("ON_DUTY","ON_LEAVE","RETIRED")).contains(status),"人员状态不正确");
        var r=new LinkedHashMap<String,Object>();r.put("name",name);r.put("birthDate",birth);r.put("ssn",ssn);r.put("status",status);
        if(t.equals("student")){Object v=body.get("graduationDate");LocalDate graduation=null;if(v!=null&&!v.toString().isBlank()){try{graduation=LocalDate.parse(v.toString());}catch(Exception e){throw Api.bad("毕业日期格式不正确");}Api.check(!graduation.isBefore(birth),"毕业日期不能早于生日");}r.put("graduationDate",graduation);}else{String dept=Objects.toString(body.get("department"),"").trim();Api.check(!dept.isEmpty()&&dept.length()<=100,"院系必填且最多100字符");r.put("department",dept);}return r;
    }
    private void lockOpen(){for(var term:db.list("SELECT id FROM semester WHERE state IN ('OPEN','CLOSING') ORDER BY id"))gates.semester(Db.num(term,"id"),false);}
    private boolean related(String t,long id){return db.one(t.equals("student")?"SELECT e.id FROM enrollment e JOIN offering o ON o.id=e.offering_id JOIN semester s ON s.id=o.semester_id WHERE e.student_id=? AND e.state='ACTIVE' AND s.state IN ('OPEN','CLOSING') LIMIT 1":"SELECT a.offering_id FROM offering_assignment a JOIN offering o ON o.id=a.offering_id JOIN semester s ON s.id=o.semester_id WHERE a.professor_id=? AND s.state IN ('OPEN','CLOSING') LIMIT 1",id)!=null;}
    private String temporary(){byte[] b=new byte[24];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public Object create(Actor a,String type,Map<String,Object> body,String key){a.require("REGISTRAR");String t=table(type);var f=fields(type,body);return db.tx(()->{
        gates.person(a,false);db.one("SELECT id FROM user_account WHERE id=? FOR UPDATE",a.id());String op="create:"+type;var prev=dedup.existing(a.id(),op,key,body);if(prev!=null)return prev;
        long id=t.equals("student")?db.insert("INSERT INTO student(name,birth_date,ssn,status,graduation_date) VALUES(?,?,?,?,?)",f.get("name"),f.get("birthDate"),f.get("ssn"),f.get("status"),f.get("graduationDate")):db.insert("INSERT INTO professor(name,birth_date,ssn,status,department) VALUES(?,?,?,?,?)",f.get("name"),f.get("birthDate"),f.get("ssn"),f.get("status"),f.get("department"));
        faults.hit("people.afterPerson");String pass=temporary(),username=(t.equals("student")?"s":"p")+id;
        long account=db.insert("INSERT INTO user_account(username,password_hash,role,"+t+"_id) VALUES(?,?,?,?)",username,passwords.encode(pass),t.equals("student")?"STUDENT":"PROFESSOR",id);
        long term=Db.num(catalog.context(),"registrationSemesterId");if(t.equals("student"))db.exec("INSERT INTO schedule(student_id,semester_id) VALUES(?,?)",id,term);else db.exec("INSERT INTO teaching_guard(professor_id,semester_id) VALUES(?,?)",id,term);
        Map<String,Object> publicResult=new LinkedHashMap<>(detail(a,type,id));publicResult.put("accountId",account);publicResult.put("username",username);dedup.remember(a.id(),op,key,body,publicResult);db.audit(a.id(),"PERSON_CREATED",t+":"+id,"account created");
        publicResult.put("credentialDelivery",Map.of("temporaryPassword",pass,"mustChangePassword",true));return publicResult;
    });}
    public Object update(Actor a,String type,long id,Map<String,Object> body,String key){a.require("REGISTRAR");String t=table(type);var f=fields(type,body);return db.tx(()->{
        lockOpen();gates.person(a,false);db.one("SELECT * FROM user_account WHERE "+t+"_id=? FOR UPDATE",id);var row=db.one("SELECT * FROM "+t+" WHERE id=? FOR UPDATE",id);if(row==null||row.get("deletedAt")!=null)throw new Api.Failure(404,"NOT_FOUND","人员不存在");String op="update:"+type+":"+id;var prev=dedup.existing(a.id(),op,key,body);if(prev!=null)return prev;gates.version(row,body.get("expectedVersion"));
        if(!(t.equals("student")?"ENROLLED":"ON_DUTY").equals(f.get("status"))&&related(t,id))throw Api.conflict("ACTIVE_RELATION_EXISTS","存在当前选课/授课关系，请先解除");
        db.exec("UPDATE "+t+" SET name=?,birth_date=?,ssn=?,status=?,"+(t.equals("student")?"graduation_date":"department")+"=?,version=version+1 WHERE id=?",f.get("name"),f.get("birthDate"),f.get("ssn"),f.get("status"),f.get(t.equals("student")?"graduationDate":"department"),id);var result=detail(a,type,id);dedup.remember(a.id(),op,key,body,result);db.audit(a.id(),"PERSON_UPDATED",t+":"+id,"profile updated");return result;
    });}
    public void delete(Actor a,String type,long id,long expected){a.require("REGISTRAR");String t=table(type);db.tx(()->{
        lockOpen();gates.person(a,false);db.one("SELECT * FROM user_account WHERE "+t+"_id=? FOR UPDATE",id);var row=db.one("SELECT * FROM "+t+" WHERE id=? FOR UPDATE",id);if(row==null)throw new Api.Failure(404,"NOT_FOUND","人员不存在");if(row.get("deletedAt")!=null)return null;
        gates.version(row,expected);if(related(t,id))throw Api.conflict("ACTIVE_RELATION_EXISTS","存在当前选课/授课关系，请先解除");
        db.exec("UPDATE "+t+" SET deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=?",id);db.exec("UPDATE user_account SET status='DISABLED',credential_version=credential_version+1 WHERE "+t+"_id=?",id);db.audit(a.id(),"PERSON_DELETED",t+":"+id,"logical deletion");return null;
    });}
    public Object reset(Actor a,long account){a.require("REGISTRAR");return db.tx(()->{
        gates.person(a,false);var row=db.one("SELECT * FROM user_account WHERE id=? FOR UPDATE",account);if(row==null)throw new Api.Failure(404,"NOT_FOUND","账号不存在");if(!"ACTIVE".equals(row.get("status")))throw Api.conflict("ACCOUNT_DISABLED","停用账号不能重置启用");
        String pass=temporary();db.exec("UPDATE user_account SET password_hash=?,must_change_password=TRUE,credential_version=credential_version+1 WHERE id=?",passwords.encode(pass),account);db.audit(a.id(),"PASSWORD_RESET",String.valueOf(account),"credential revoked");return Map.of("id",account,"username",row.get("username"),"credentialDelivery",Map.of("temporaryPassword",pass,"mustChangePassword",true));
    });}
}
