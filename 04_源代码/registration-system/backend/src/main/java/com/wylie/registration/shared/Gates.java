package com.wylie.registration.shared;

import com.wylie.registration.auth.Actor;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class Gates {
    private final Db db;private final Settings settings;
    public Gates(Db db,Settings settings){this.db=db;this.settings=settings;}
    public Map<String,Object> semester(long id,boolean open){var row=db.one("SELECT * FROM semester WHERE id=? FOR SHARE",id);if(row==null)throw new Api.Failure(404,"NOT_FOUND","学期不存在");if(open&&!"OPEN".equals(row.get("state")))throw Api.conflict("REGISTRATION_CLOSED","注册已关闭");return row;}
    public void person(Actor a,boolean activeBusiness){
        var u=db.one("SELECT * FROM user_account WHERE id=? FOR SHARE",a.id());
        if(u==null||!"ACTIVE".equals(u.get("status"))||Db.num(u,"credentialVersion")!=a.credentialVersion())throw new Api.Failure(401,"AUTH_REQUIRED","账号状态已变化");
        if(a.studentId()!=null||a.professorId()!=null){boolean student=a.studentId()!=null;var p=db.one("SELECT * FROM "+(student?"student":"professor")+" WHERE id=? FOR SHARE",student?a.studentId():a.professorId());
            if(p==null||p.get("deletedAt")!=null)throw new Api.Failure(401,"AUTH_REQUIRED","人员已删除");
            if(activeBusiness&&!(student?"ENROLLED":"ON_DUTY").equals(p.get("status")))throw Api.conflict("PERSON_INACTIVE","当前人员业务状态不能办理此操作");
        }
    }
    public void window(Map<String,Object> semester,boolean first){
        String start=first?"registrationOpenAt":"addDropStartAt",end=first?"registrationCloseAt":"addDropEndAt";
        Instant a=instant(semester.get(start)),b=instant(semester.get(end)),now=settings.now();
        if(now.isBefore(a)||!now.isBefore(b))throw Api.conflict("WINDOW_CLOSED","当前不在办理窗口内");
    }
    public static Instant instant(Object value){if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);return Instant.parse(value.toString());}
    public void offerings(Collection<Long> ids){for(long id:new TreeSet<>(ids))if(db.one("SELECT offering_id FROM offering_runtime WHERE offering_id=? FOR UPDATE",id)==null)throw new Api.Failure(404,"NOT_FOUND","开课班不存在");}
    public void version(Map<String,Object> row,Object expected){Api.check(expected instanceof Integer||expected instanceof Long,"expectedVersion必须是整数");if(((Number)expected).longValue()!=Db.num(row,"version"))throw new Api.Failure(409,"VERSION_CONFLICT","数据已更新，请重新读取",Map.of("currentVersion",row.get("version")));}
}
