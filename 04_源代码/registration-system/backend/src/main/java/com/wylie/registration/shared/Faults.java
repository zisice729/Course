package com.wylie.registration.shared;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Component
public class Faults {
    private final Settings settings;
    public Faults(Settings settings){this.settings=settings;}
    private record Hook(boolean fail,CountDownLatch release,CountDownLatch entered){}
    private final Map<String,Hook> hooks=new ConcurrentHashMap<>();
    public static final Set<String> POINTS=Set.of("registration.afterGate","registration.afterWithdraw","registration.afterEnroll","grade.afterFirst","people.afterPerson","close.afterGate","close.beforeCommit","billing.beforeAck");
    public void set(String name,String mode){Api.check(POINTS.contains(name),"未知检查点");release(name);if("fail".equals(mode)||"pause".equals(mode))hooks.put(name,new Hook("fail".equals(mode),new CountDownLatch(1),new CountDownLatch(1)));}
    public void release(String name){var h=hooks.remove(name);if(h!=null)h.release().countDown();}
    public Map<String,Object> status(){Map<String,Object> m=new LinkedHashMap<>();hooks.forEach((k,v)->m.put(k,Map.of("entered",v.entered().getCount()==0,"fail",v.fail())));return m;}
    public void hit(String name){if(!settings.test())return;var h=hooks.get(name);if(h==null)return;h.entered().countDown();if(h.fail())throw new Api.Failure(500,"TEST_FAULT","测试注入中断");try{if(!h.release().await(20,TimeUnit.SECONDS))throw new Api.Failure(503,"TEST_TIMEOUT","测试检查点等待超时");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
