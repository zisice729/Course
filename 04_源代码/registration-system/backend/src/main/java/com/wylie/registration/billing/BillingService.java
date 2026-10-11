package com.wylie.registration.billing;

import com.wylie.registration.auth.Actor;
import com.wylie.registration.shared.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class BillingService {
    private final Db db;private final Dedup json;private final Settings settings;private final Faults faults;
    private final String owner=UUID.randomUUID().toString();private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public BillingService(Db db,Dedup json,Settings settings,Faults faults){this.db=db;this.json=json;this.settings=settings;this.faults=faults;}
    public List<Map<String,Object>> records(Actor a){a.require("REGISTRAR");return db.list("SELECT b.*,o.id outbox_id,o.state,o.attempt_count,o.next_attempt_at,o.external_ref,o.last_error FROM billing_record b JOIN billing_outbox o ON o.billing_record_id=b.id ORDER BY b.id DESC");}
    public Object retry(Actor a,long id){a.require("REGISTRAR");return db.tx(()->{var row=db.one("SELECT * FROM billing_outbox WHERE billing_record_id=? FOR UPDATE",id);if(row==null)throw new Api.Failure(404,"NOT_FOUND","账单任务不存在");if(!Set.of("SENDING","SENT").contains(row.get("state")))db.exec("UPDATE billing_outbox SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE id=?",row.get("id"));db.audit(a.id(),"BILLING_RETRY_REQUESTED",String.valueOf(id),"same task");return row;});}
    @Scheduled(fixedDelay=500)
    public void send(){
        var claim=db.tx(()->{var row=db.one("SELECT * FROM billing_outbox WHERE ((state IN ('PENDING','RETRYING') AND next_attempt_at<=CURRENT_TIMESTAMP(3)) OR (state='SENDING' AND lease_until<CURRENT_TIMESTAMP(3))) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");if(row==null)return null;
            db.exec("UPDATE billing_outbox SET state='SENDING',attempt_count=attempt_count+1,lease_token=lease_token+1,lease_owner=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)) WHERE id=?",owner,row.get("id"));return db.one("SELECT o.*,b.business_key,b.snapshot_json,b.payload_hash FROM billing_outbox o JOIN billing_record b ON b.id=o.billing_record_id WHERE o.id=?",row.get("id"));});
        if(claim==null)return;
        try{
            var request=HttpRequest.newBuilder(URI.create(settings.billingUrl+"/transactions")).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("Idempotency-Key",Db.str(claim,"businessKey")).header("X-Payload-Hash",Db.str(claim,"payloadHash")).header("X-Mock-Token",settings.mockToken).POST(HttpRequest.BodyPublishers.ofString(Db.str(claim,"snapshotJson"))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200&&response.statusCode()!=201)throw new IllegalStateException("BILLING_HTTP_"+response.statusCode());
            @SuppressWarnings("unchecked") var receipt=(Map<String,Object>)json.parse(response.body());Object reference=receipt.get("externalRef");if(reference==null||!claim.get("businessKey").equals(receipt.get("businessKey")))throw new IllegalStateException("BILLING_INVALID_RECEIPT");
            db.tx(()->{faults.hit("billing.beforeAck");db.exec("UPDATE billing_outbox SET state='SENT',external_ref=?,lease_until=NULL,last_error=NULL WHERE id=? AND lease_owner=? AND lease_token=? AND state='SENDING'",reference,claim.get("id"),owner,claim.get("leaseToken"));return null;});
        }catch(Exception e){int n=(int)Math.min(6,Db.num(claim,"attemptCount")-1);int delay=Math.min(300,5*(1<<n))+java.util.concurrent.ThreadLocalRandom.current().nextInt(3);String error=e instanceof Api.Failure f?f.code:e.getClass().getSimpleName();
            db.tx(()->{db.exec("UPDATE billing_outbox SET state='RETRYING',last_error=?,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)) WHERE id=? AND lease_owner=? AND lease_token=? AND state='SENDING'",error,delay,claim.get("id"),owner,claim.get("leaseToken"));db.audit(0,"BILLING_RETRY",String.valueOf(claim.get("billingRecordId")),error);return null;});
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
        }
        db.tx(()->{db.exec("UPDATE close_job j SET state='COMPLETE' WHERE j.state='LOCAL_COMMITTED' AND NOT EXISTS(SELECT 1 FROM billing_record b JOIN billing_outbox o ON o.billing_record_id=b.id WHERE b.close_job_id=j.id AND o.state<>'SENT')");return null;});
    }
}
