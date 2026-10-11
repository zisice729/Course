package com.wylie.registration.shared;

import com.wylie.registration.catalog.CatalogImporter;
import org.springframework.stereotype.Component;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import java.time.*;

@Component
public class DemoSeed implements ApplicationRunner {
    private final Db db;private final PasswordEncoder encoder;private final CatalogImporter importer;private final Dedup json;
    @Value("${app.demo-password}") String password;
    @Value("${app.seed-demo}") boolean enabled;
    public DemoSeed(Db db,PasswordEncoder encoder,CatalogImporter importer,Dedup json){this.db=db;this.encoder=encoder;this.importer=importer;this.json=json;}
    @Override public void run(ApplicationArguments args){if(!enabled||db.one("SELECT id FROM academic_context WHERE id=1")!=null)return;Api.check(password!=null&&password.length()>=12,"请配置本地演示密码");
        var snapshot=importer.fetch();String hash=encoder.encode(password);
        db.tx(()->{
            db.exec("INSERT INTO semester VALUES(1,'2026 春季学期','2026-03-01','2026-06-30','2026-02-01','2026-02-15','2026-02-01','2026-02-20','CLOSED','COMPLETED')");
            db.exec("INSERT INTO semester VALUES(2,'2026 秋季学期','2026-09-01','2026-12-31','2026-10-07 00:00:00','2026-10-12 12:00:00','2026-10-07 00:00:00','2026-10-13 12:00:00','OPEN','IN_PROGRESS')");
            db.exec("INSERT INTO academic_context(id,registration_semester_id,previous_completed_semester_id) VALUES(1,2,1)");
            for(int n=1;n<=30;n++){
                db.exec("INSERT INTO student(id,name,birth_date,ssn,status) VALUES(?,?,'2004-01-01',?,'ENROLLED')",n,n==1?"林同学":"学生"+String.format("%02d",n),String.format("900%06d",n));
                db.exec("INSERT INTO user_account(username,password_hash,role,student_id,must_change_password) VALUES(?,?,'STUDENT',?,FALSE)","s"+n,hash,n);db.exec("INSERT INTO schedule(student_id,semester_id) VALUES(?,2)",n);
            }
            for(int n=1;n<=3;n++){
                db.exec("INSERT INTO professor(id,name,birth_date,ssn,status,department) VALUES(?,?,'1980-01-01',?,'ON_DUTY','计算机学院')",n,new String[]{"陈老师","李老师","周老师"}[n-1],String.format("800%06d",n));
                db.exec("INSERT INTO user_account(username,password_hash,role,professor_id,must_change_password) VALUES(?,?,'PROFESSOR',?,FALSE)","p"+n,hash,n);db.exec("INSERT INTO teaching_guard(professor_id,semester_id) VALUES(?,2)",n);
            }
            db.exec("INSERT INTO user_account(username,password_hash,role,must_change_password) VALUES('registrar',?,'REGISTRAR',FALSE)",hash);
            importer.importSnapshot(2,2,snapshot);
            db.exec("INSERT INTO catalog_snapshot VALUES(1,1,'demo-spring-v1',CURRENT_TIMESTAMP(3),'READY')");
            db.exec("INSERT INTO course_snapshot(snapshot_id,course_id,name,department) SELECT 1,id,IF(id=1,'程序设计基础','离散数学'),'计算机学院' FROM course WHERE id IN (1,2)");
            for(int p=1;p<=3;p++)for(int c=1;c<=2;c++){long id=200+(p-1)*10+c;
                db.exec("INSERT INTO offering(id,external_id,course_id,semester_id,snapshot_id) VALUES(?,?,?,1,1)",id,"OLD-"+id,c);
                db.exec("INSERT INTO offering_runtime(offering_id,semester_id,state,enrolled_count) VALUES(?,1,'CONFIRMED',10)",id);db.exec("INSERT INTO offering_assignment(offering_id,professor_id) VALUES(?,?)",id,p);
                db.exec("INSERT INTO offering_meeting(offering_id,weekday,start_time,end_time,first_date,last_date) VALUES(?,?,'08:00:00','09:30:00','2026-03-01','2026-06-30')",id,c);
            }
            for(int n=1;n<=30;n++)for(int c=1;c<=2;c++){
                long o=200+((n-1)/10)*10+c;long enrollment=db.insert("INSERT INTO enrollment(student_id,offering_id,state,origin_type) VALUES(?,?,'ACTIVE','PRIMARY')",n,o);
                String grade=c==2?null:new String[]{"A","B","C","D","F","I",null}[(n-1)%7];if(c==1&&(n==11||n==12))grade="A";
                db.exec("INSERT INTO grade(enrollment_id,grade,entered_by) VALUES(?,?,?)",enrollment,grade,(n-1)/10+1);
            }
            for(int p=1;p<=3;p++)for(var c:db.list("SELECT id FROM course"))db.exec("INSERT INTO teaching_eligibility VALUES(?,?)",p,c.get("id"));
            for(long id=101;id<=111;id++)db.exec("INSERT INTO offering_assignment(offering_id,professor_id) VALUES(?,?)",id,id<=106?1:id<=110?2:3);
            for(int n=2;n<=12;n++){
                List<Map<String,Object>> items=new ArrayList<>();long[] main=n<=10?new long[]{101,102,103,104}:new long[]{105,107,108,109};long[] alt=n<=10?new long[]{105,106}:new long[]{110,106};
                for(int j=0;j<4;j++)items.add(Map.of("offeringId",main[j],"choiceType","PRIMARY","order",j+1));for(int j=0;j<2;j++)items.add(Map.of("offeringId",alt[j],"choiceType","ALTERNATE","order",j+1));
                var sc=db.one("SELECT id FROM schedule WHERE student_id=? AND semester_id=2",n);db.exec("UPDATE schedule SET state='SUBMITTED',submitted_choices_json=?,first_submitted_at=TIMESTAMPADD(MINUTE,?,'2026-10-07 01:00:00'),last_submitted_at='2026-10-07 02:00:00' WHERE id=?",json.json(items),n,sc.get("id"));
                for(var i:items)db.exec("INSERT INTO schedule_item(schedule_id,offering_id,choice_type,choice_order,state) VALUES(?,?,?,?,?)",sc.get("id"),i.get("offeringId"),i.get("choiceType"),i.get("order"),i.get("choiceType").equals("PRIMARY")?"ENROLLED":"SELECTED");
                for(long id:main){db.exec("INSERT INTO enrollment(student_id,offering_id,state,origin_type) VALUES(?,?,'ACTIVE','PRIMARY')",n,id);db.exec("UPDATE offering_runtime SET enrolled_count=enrolled_count+1 WHERE offering_id=?",id);}
            }
            return null;
        });
    }
}
