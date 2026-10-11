package com.wylie.registration.shared;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.function.Supplier;

@Component
public class Db {
    private final SqlMapper mapper;
    private final TransactionTemplate tx;
    public Db(SqlMapper mapper, PlatformTransactionManager manager) {
        this.mapper=mapper; this.tx=new TransactionTemplate(manager);
    }
    private Map<String,Object> input(String sql,Object... args) {
        Map<String,Object> p=new HashMap<>();StringBuilder b=new StringBuilder();int n=0;
        for(char c:sql.toCharArray()) { if(c=='?') { b.append("#{p.a").append(n).append('}');p.put("a"+n,args[n++]); } else b.append(c); }
        if(n!=args.length)throw new IllegalArgumentException("SQL argument mismatch");
        return Map.of("sql",b.toString(),"p",p);
    }
    public List<Map<String,Object>> list(String sql,Object... args) {
        return mapper.query(input(sql,args)).stream().map(row->{
            Map<String,Object> result=new LinkedHashMap<>();row.forEach((k,v)->result.put(camel(k),v));return result;
        }).toList();
    }
    public Map<String,Object> one(String sql,Object... args) {
        var rows=list(sql,args);return rows.isEmpty()?null:rows.getFirst();
    }
    public int exec(String sql,Object... args) { return mapper.execute(input(sql,args)); }
    public long insert(String sql,Object... args) { exec(sql,args);return num(one("SELECT LAST_INSERT_ID() id"),"id"); }
    /** Retry the entire rolled-back unit, never individual statements or remote effects. */
    public <T>T tx(Supplier<T> work){
        for(int attempt=0;;attempt++){
            try{return tx.execute(status->work.get());}
            catch(org.springframework.dao.TransientDataAccessException e){
                boolean retryable=false;
                for(Throwable cause=e;cause!=null;cause=cause.getCause())
                    if(cause instanceof java.sql.SQLException sql&&(sql.getErrorCode()==1213||sql.getErrorCode()==1205))retryable=true;
                if(!retryable||attempt>=2)throw e;
                try{Thread.sleep(20L*(attempt+1)+java.util.concurrent.ThreadLocalRandom.current().nextInt(20));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw e;}
            }
        }
    }
    public void audit(long actor,String type,String entity,Object detail){
        exec("INSERT INTO audit_event(actor_id,event_type,entity_id,detail,trace_id) VALUES(?,?,?,?,?)",actor,type,entity,String.valueOf(detail),Api.trace());
    }
    public static long num(Map<String,?> row,String key){Object v=row.get(key);return v==null?0:v instanceof Boolean b?(b?1:0):Long.parseLong(v.toString());}
    public static String str(Map<String,?> row,String key){Object v=row.get(key);return v==null?null:v.toString();}
    public static String camel(String s){StringBuilder b=new StringBuilder();boolean upper=false;for(char c:s.toCharArray()){if(c=='_')upper=true;else{b.append(upper?Character.toUpperCase(c):c);upper=false;}}return b.toString();}
}
