package com.wylie.registration.auth;

import com.wylie.registration.shared.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final Db db;private final PasswordEncoder passwords;
    private record Attempt(int count,long until){}
    private final Map<String,Attempt> attempts=new ConcurrentHashMap<>();
    public AuthController(Db db,PasswordEncoder passwords){this.db=db;this.passwords=passwords;}
    @GetMapping("/csrf") Object csrf(CsrfToken token){return Api.ok(Map.of("headerName",token.getHeaderName(),"token",token.getToken()));}
    @PostMapping("/login") Object login(@RequestBody Map<String,Object> body,HttpServletRequest req){
        String name=String.valueOf(body.getOrDefault("username","")),pass=String.valueOf(body.getOrDefault("password",""));
        Api.check(name.length()<=64&&pass.length()<=256,"凭据长度不正确");
        String key=req.getRemoteAddr();long now=System.currentTimeMillis();var failed=attempts.get(key);
        if(failed!=null&&failed.count()>=10&&failed.until()>now)throw new Api.Failure(429,"RATE_LIMITED","登录尝试过多，请稍后再试");
        var row=db.one("SELECT * FROM user_account WHERE username=?",name);
        boolean valid=row!=null&&"ACTIVE".equals(row.get("status"))&&passwords.matches(pass,Db.str(row,"passwordHash"));
        if(!valid){attempts.compute(key,(k,v)->new Attempt(v==null||v.until()<now?1:v.count()+1,now+60000));throw new Api.Failure(401,"BAD_CREDENTIALS","用户名或密码不正确");}
        attempts.remove(key);
        var actor=new Actor(Db.num(row,"id"),name,Db.str(row,"role"),row.get("studentId")==null?null:Db.num(row,"studentId"),row.get("professorId")==null?null:Db.num(row,"professorId"),Db.num(row,"credentialVersion"),Boolean.TRUE.equals(row.get("mustChangePassword"))||Db.num(row,"mustChangePassword")==1);
        req.getSession(true);req.changeSessionId();req.getSession().setAttribute("actor",actor);
        return Api.ok(actor.view());
    }
    @GetMapping("/me") Object me(HttpServletRequest req){return Api.ok(Actor.from(req).view());}
    @PostMapping("/logout") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) void logout(HttpServletRequest req){if(req.getSession(false)!=null)req.getSession().invalidate();}
    @PostMapping("/password") Object password(@RequestBody Map<String,Object> body,HttpServletRequest req){
        Actor a=Actor.from(req);String old=Objects.toString(body.get("oldPassword"),""),next=Objects.toString(body.get("newPassword"),"");
        Api.check(next.length()>=12&&next.length()<=128,"新密码须12—128字符");
        db.tx(()->{var row=db.one("SELECT * FROM user_account WHERE id=? FOR UPDATE",a.id());
            if(Db.num(row,"credentialVersion")!=a.credentialVersion()||!passwords.matches(old,Db.str(row,"passwordHash")))throw new Api.Failure(401,"BAD_CREDENTIALS","当前密码错误或会话失效");
            db.exec("UPDATE user_account SET password_hash=?,must_change_password=FALSE,credential_version=credential_version+1 WHERE id=?",passwords.encode(next),a.id());db.audit(a.id(),"PASSWORD_CHANGED",String.valueOf(a.id()),"credential revoked");return null;});
        req.getSession().invalidate();return Api.ok(Map.of("relogin",true));
    }
}
