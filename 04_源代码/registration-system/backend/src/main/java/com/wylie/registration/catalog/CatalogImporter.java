package com.wylie.registration.catalog;

import com.wylie.registration.shared.*;
import com.wylie.registration.registration.Rules;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Service
public class CatalogImporter {
    private final Db db;private final Dedup json;private final Settings settings;private final CatalogService catalog;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public CatalogImporter(Db db,Dedup json,Settings settings,CatalogService catalog){this.db=db;this.json=json;this.settings=settings;this.catalog=catalog;}
    @SuppressWarnings("unchecked")
    public Map<String,Object> fetch(){
        try{var request=HttpRequest.newBuilder(URI.create(settings.catalogUrl+"/snapshot")).timeout(Duration.ofSeconds(9)).GET().build();var response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new Api.Failure(503,"CATALOG_UNAVAILABLE","外部目录不可用");return (Map<String,Object>)json.parse(response.body());}
        catch(java.net.http.HttpTimeoutException e){throw new Api.Failure(504,"CATALOG_TIMEOUT","外部目录超过10秒调用预算");}
        catch(Api.Failure e){throw e;}catch(Exception e){throw new Api.Failure(503,"CATALOG_UNAVAILABLE","目录连接失败");}
    }
    @SuppressWarnings("unchecked")
    public void importSnapshot(long semester,long snapshot,Map<String,Object> data){
        Api.check(data.get("courses") instanceof List<?> && data.get("offerings") instanceof List<?>,"目录格式不正确");
        var courses=(List<Map<String,Object>>)data.get("courses");Set<Long> ids=new HashSet<>();for(var c:courses)Api.check(ids.add(Db.num(c,"id"))&&Db.num(c,"id")>0,"课程ID非法或重复");
        Map<Long,List<Long>> edges=new HashMap<>();for(var c:courses){List<Long> refs=new ArrayList<>();for(Object p:(List<?>)c.getOrDefault("prerequisites",List.of())){long id=Long.parseLong(p.toString());Api.check(ids.contains(id)&&id!=Db.num(c,"id"),"先修缺失或自引用");refs.add(id);}edges.put(Db.num(c,"id"),refs);}
        Set<Long> done=new HashSet<>();for(long id:ids)visit(id,edges,new HashSet<>(),done);
        var term=catalog.semester(semester);int weeks=(int)(java.time.temporal.ChronoUnit.DAYS.between(Rules.date(term.get("startDate")).minusDays(Rules.date(term.get("startDate")).getDayOfWeek().getValue()-1),Rules.date(term.get("endDate")))/7)+1;
        Set<Long> offerings=new HashSet<>();for(var o:(List<Map<String,Object>>)data.get("offerings")){Api.check(ids.contains(Db.num(o,"courseId"))&&offerings.add(Db.num(o,"id")),"开课引用非法或重复");for(var m:(List<Map<String,Object>>)o.get("meetings")){
            Api.check(Db.num(m,"weekday")>=1&&Db.num(m,"weekday")<=7&&Rules.time(m.get("startTime")).isBefore(Rules.time(m.get("endTime"))),"目录时段非法或跨夜");Api.check(!Rules.date(m.get("firstDate")).isAfter(Rules.date(m.get("lastDate"))),"教学日期非法");String mask=Db.str(m,"weekMask");Api.check(weeks<=128&&(mask==null||mask.length()==weeks&&mask.matches("[01]+")),"教学周掩码非法");
        }}
        db.exec("INSERT INTO catalog_snapshot(id,semester_id,source_version,fetched_at,status) VALUES(?,?,?,CURRENT_TIMESTAMP(3),'READY')",snapshot,semester,data.get("version"));
        for(var c:courses){db.exec("INSERT IGNORE INTO course(id,external_id) VALUES(?,?)",c.get("id"),"COURSE-"+c.get("id"));db.exec("INSERT INTO course_snapshot(snapshot_id,course_id,name,department) VALUES(?,?,?,?)",snapshot,c.get("id"),c.get("name"),c.get("department"));}
        for(var e:edges.entrySet())for(long p:e.getValue())db.exec("INSERT INTO course_prerequisite(snapshot_id,course_id,prerequisite_course_id) VALUES(?,?,?)",snapshot,e.getKey(),p);
        for(var o:(List<Map<String,Object>>)data.get("offerings")){db.exec("INSERT INTO offering(id,external_id,course_id,semester_id,snapshot_id) VALUES(?,?,?,?,?)",o.get("id"),"OFFERING-"+o.get("id"),o.get("courseId"),semester,snapshot);db.exec("INSERT INTO offering_runtime(offering_id,semester_id) VALUES(?,?)",o.get("id"),semester);
            for(var m:(List<Map<String,Object>>)o.get("meetings"))db.exec("INSERT INTO offering_meeting(offering_id,weekday,start_time,end_time,first_date,last_date,week_mask) VALUES(?,?,?,?,?,?,?)",o.get("id"),m.get("weekday"),m.get("startTime"),m.get("endTime"),m.get("firstDate"),m.get("lastDate"),m.get("weekMask"));
        }catalog.clear();
    }
    private void visit(long id,Map<Long,List<Long>> graph,Set<Long> visiting,Set<Long> done){if(done.contains(id))return;Api.check(visiting.add(id),"先修存在环");for(long p:graph.get(id))visit(p,graph,visiting,done);visiting.remove(id);done.add(id);}
}
