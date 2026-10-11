package com.wylie.registration.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class Dedup {
    private final Db db;private final ObjectMapper json;
    public Dedup(Db db,ObjectMapper json){this.db=db;this.json=json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);}
    public String json(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException(e);}}
    public Object parse(String s){try{return json.readValue(s,Object.class);}catch(Exception e){throw new IllegalArgumentException(e);}}
    public static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public Object existing(long actor,String operation,String key,Object body){
        Api.check(key!=null&&!key.isBlank()&&key.length()<=100,"请提供有效Idempotency-Key");
        var row=db.one("SELECT * FROM request_dedup WHERE actor_id=? AND operation=? AND idempotency_key=?",actor,operation,key);
        if(row==null)return null;if(!hash(json(body)).equals(row.get("requestHash")))throw Api.conflict("IDEMPOTENCY_MISMATCH","相同业务键的请求内容不同");
        return parse(Db.str(row,"responseJson"));
    }
    public void remember(long actor,String operation,String key,Object body,Object result){db.exec("INSERT INTO request_dedup(actor_id,operation,idempotency_key,request_hash,response_json) VALUES(?,?,?,?,?)",actor,operation,key,hash(json(body)),json(Api.safe(result)));}
}
