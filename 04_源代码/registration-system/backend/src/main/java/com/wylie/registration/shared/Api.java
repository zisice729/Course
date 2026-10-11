package com.wylie.registration.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Api {
    private static final ThreadLocal<String> TRACE=new ThreadLocal<>();
    public static String trace(){return Optional.ofNullable(TRACE.get()).orElse("worker");}
    public static class Failure extends RuntimeException {
        public final int status;public final String code;public final Object details;
        public Failure(int status,String code,String message){this(status,code,message,null);}
        public Failure(int status,String code,String message,Object details){super(message);this.status=status;this.code=code;this.details=details;}
    }
    public static Failure bad(String message){return new Failure(400,"INVALID_ARGUMENT",message);}
    public static Failure conflict(String code,String message){return new Failure(409,code,message);}
    public static void check(boolean good,String message){if(!good)throw bad(message);}
    public static Map<String,Object> ok(Object data){return envelope("OK","操作成功",data,null);}
    public static Map<String,Object> envelope(String code,String message,Object data,Object details){
        Map<String,Object> r=new LinkedHashMap<>();r.put("code",code);r.put("message",message);r.put("data",safe(data));r.put("traceId",trace());if(details!=null)r.put("details",safe(details));return r;
    }
    /** IDs and decimals have stable wire representation; counts/versions remain numeric. */
    public static Object safe(Object v){
        if(v instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();map.forEach((k,x)->{String key=k.toString();boolean id=key.equals("id")||key.endsWith("Id");out.put(key,id&&x!=null?x.toString():safe(x));});return out;}
        if(v instanceof Collection<?> a)return a.stream().map(Api::safe).toList();
        if(v instanceof BigDecimal b)return b.setScale(2).toPlainString();
        if(v instanceof java.sql.Timestamp t)return t.toInstant().toString();
        if(v instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC).toString();
        if(v instanceof java.sql.Date d)return d.toLocalDate().toString();
        if(v instanceof java.sql.Time t)return t.toLocalTime().toString();
        return v;
    }
    @Component
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public static class TraceFilter extends org.springframework.web.filter.OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
            TRACE.set(UUID.randomUUID().toString());res.setHeader("X-Trace-Id",trace());try{chain.doFilter(req,res);}finally{TRACE.remove();}
        }
    }
    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(Failure.class) public ResponseEntity<?> failure(Failure e){return ResponseEntity.status(e.status).body(envelope(e.code,e.getMessage(),null,e.details));}
        @ExceptionHandler({CannotAcquireLockException.class,DeadlockLoserDataAccessException.class}) public ResponseEntity<?> busy(Exception e){return failure(new Failure(503,"TEMPORARY_DB_FAILURE","数据库暂时繁忙，请使用相同业务键重试"));}
        @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.ServletRequestBindingException.class}) public ResponseEntity<?> invalid(Exception e){return failure(bad("请求格式、必填请求头或参数类型不正确"));}
        @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) public ResponseEntity<?> missing(Exception e){return failure(new Failure(404,"NOT_FOUND","接口不存在"));}
        @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e){
            org.slf4j.LoggerFactory.getLogger(Errors.class).error("Unexpected failure traceId={} type={}",trace(),e.getClass().getSimpleName());
            return failure(new Failure(500,"INTERNAL_ERROR","服务异常，请提供traceId定位"));
        }
    }
}
