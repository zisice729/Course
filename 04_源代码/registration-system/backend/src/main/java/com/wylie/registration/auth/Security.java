package com.wylie.registration.auth;

import com.wylie.registration.shared.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Configuration
public class Security {
    @Bean PasswordEncoder passwords(){return PasswordEncoderFactories.createDelegatingPasswordEncoder();}
    @Bean SecurityFilterChain chain(HttpSecurity http,Db db,ObjectMapper json)throws Exception {
        var repository=new HttpSessionCsrfTokenRepository();
        var handler=new CsrfTokenRequestAttributeHandler();
        http.csrf(c->c.csrfTokenRepository(repository).csrfTokenRequestHandler(handler))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable())
            .logout(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/health").permitAll().anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->write(json,res,401,"AUTH_REQUIRED","请先登录"))
                .accessDeniedHandler((req,res,x)->write(json,res,403,x instanceof CsrfException?"CSRF_INVALID":"FORBIDDEN","身份或请求验证失败")))
            .addFilterBefore(new SessionActor(db,json),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    static void write(ObjectMapper json,HttpServletResponse res,int status,String code,String message)throws IOException {
        res.setStatus(status);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getOutputStream(),Api.envelope(code,message,null,null));
    }
    static class SessionActor extends OncePerRequestFilter {
        private final Db db;private final ObjectMapper json;
        SessionActor(Db db,ObjectMapper json){this.db=db;this.json=json;}
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
            var session=req.getSession(false);
            Actor old=session==null?null:(Actor)session.getAttribute("actor");
            if(old!=null){
                var row=db.one("SELECT u.*,s.deleted_at student_deleted,p.deleted_at professor_deleted FROM user_account u LEFT JOIN student s ON s.id=u.student_id LEFT JOIN professor p ON p.id=u.professor_id WHERE u.id=?",old.id());
                if(row==null||!"ACTIVE".equals(row.get("status"))||Db.num(row,"credentialVersion")!=old.credentialVersion()||row.get("studentDeleted")!=null||row.get("professorDeleted")!=null){session.invalidate();write(json,res,401,"AUTH_REQUIRED","登录已失效，请重新登录");return;}
                req.setAttribute("actor",old);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(old,null,List.of(new SimpleGrantedAuthority("ROLE_"+old.role()))));
                if(old.mustChangePassword()&&!Set.of("/api/v1/auth/me","/api/v1/auth/csrf","/api/v1/auth/logout","/api/v1/auth/password").contains(req.getRequestURI())){
                    write(json,res,403,"PASSWORD_CHANGE_REQUIRED","请先修改初始密码");return;
                }
            }
            chain.doFilter(req,res);
        }
    }
}
