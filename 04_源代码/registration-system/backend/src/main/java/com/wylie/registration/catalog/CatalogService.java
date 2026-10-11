package com.wylie.registration.catalog;

import com.wylie.registration.shared.*;
import org.springframework.stereotype.Service;
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.util.*;

@Service
public class CatalogService {
    private final Db db;
    private final Cache<String,Map<String,Object>> staticCache=Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofSeconds(120)).build();
    public CatalogService(Db db){this.db=db;}
    public Map<String,Object> context(){return db.one("SELECT a.*,s.name,s.state,s.registration_open_at,s.registration_close_at,s.add_drop_start_at,s.add_drop_end_at FROM academic_context a JOIN semester s ON s.id=a.registration_semester_id WHERE a.id=1");}
    public Map<String,Object> semester(long id){var s=db.one("SELECT * FROM semester WHERE id=?",id);if(s==null)throw new Api.Failure(404,"NOT_FOUND","学期不存在");return s;}
    public List<Map<String,Object>> offerings(long semester){
        var rows=db.list("SELECT o.*,r.state,r.capacity,r.enrolled_count,r.version,r.fee,a.professor_id,p.name professor_name,c.name,c.department,x.source_version FROM offering o JOIN offering_runtime r ON r.offering_id=o.id JOIN course_snapshot c ON c.course_id=o.course_id AND c.snapshot_id=o.snapshot_id JOIN catalog_snapshot x ON x.id=o.snapshot_id LEFT JOIN offering_assignment a ON a.offering_id=o.id LEFT JOIN professor p ON p.id=a.professor_id WHERE o.semester_id=? AND x.status='READY' ORDER BY o.id",semester);
        if(rows.isEmpty()&&db.one("SELECT id FROM catalog_snapshot WHERE semester_id=? AND status='READY'",semester)==null)throw new Api.Failure(503,"CATALOG_UNAVAILABLE","学期完整目录快照不可用");
        return rows.stream().map(this::details).toList();
    }
    public Map<String,Object> offering(long id){var row=db.one("SELECT o.*,r.state,r.capacity,r.enrolled_count,r.version,r.fee,a.professor_id,p.name professor_name,c.name,c.department,x.source_version FROM offering o JOIN offering_runtime r ON r.offering_id=o.id JOIN course_snapshot c ON c.course_id=o.course_id AND c.snapshot_id=o.snapshot_id JOIN catalog_snapshot x ON x.id=o.snapshot_id LEFT JOIN offering_assignment a ON a.offering_id=o.id LEFT JOIN professor p ON p.id=a.professor_id WHERE o.id=? AND x.status='READY'",id);if(row==null)throw new Api.Failure(404,"NOT_FOUND","开课班不存在或快照未就绪");return details(row);}
    /** Page in SQL before enriching static metadata; never materialize the whole catalog per request. */
    public Map<String,Object> page(long semester,Long professor,String q,int page,int size,String sort){
        Api.check(page>=1&&page<=100000&&size>=1&&size<=100&&q.length()<=100&&Set.of("id","name").contains(sort),"分页、搜索或排序参数不正确");
        semester(semester);
        if(db.one("SELECT id FROM catalog_snapshot WHERE semester_id=? AND status='READY'",semester)==null)throw new Api.Failure(503,"CATALOG_UNAVAILABLE","学期完整目录快照不可用");
        String joins=" FROM offering o JOIN offering_runtime r ON r.offering_id=o.id JOIN course_snapshot c ON c.course_id=o.course_id AND c.snapshot_id=o.snapshot_id JOIN catalog_snapshot x ON x.id=o.snapshot_id LEFT JOIN offering_assignment a ON a.offering_id=o.id LEFT JOIN professor p ON p.id=a.professor_id WHERE o.semester_id=? AND x.status='READY'";
        List<Object> args=new ArrayList<>();args.add(semester);
        if(professor!=null){joins+=" AND (a.professor_id=? OR EXISTS(SELECT 1 FROM teaching_eligibility te WHERE te.professor_id=? AND te.course_id=o.course_id))";args.add(professor);args.add(professor);}
        if(!q.isBlank()){joins+=" AND (LOCATE(?,c.name)>0 OR CAST(o.id AS CHAR)=?)";args.add(q);args.add(q);}
        long total=Db.num(db.one("SELECT COUNT(*) total"+joins,args.toArray()),"total");
        args.add(size);args.add((long)(page-1)*size);
        var rows=db.list("SELECT o.*,r.state,r.capacity,r.enrolled_count,r.version,r.fee,a.professor_id,p.name professor_name,c.name,c.department,x.source_version"+joins+" ORDER BY "+(sort.equals("name")?"c.name,o.id":"o.id")+" LIMIT ? OFFSET ?",args.toArray()).stream().map(this::details).toList();
        return Map.of("items",rows,"total",total,"page",page,"size",size);
    }
    private Map<String,Object> details(Map<String,Object> row){
        String key=row.get("sourceVersion")+":"+row.get("id");
        var frozen=staticCache.get(key,k->{var m=new LinkedHashMap<String,Object>();m.put("meetings",db.list("SELECT * FROM offering_meeting WHERE offering_id=? ORDER BY weekday,start_time",row.get("id")));m.put("prerequisites",db.list("SELECT p.prerequisite_course_id course_id,c.name FROM course_prerequisite p JOIN course_snapshot c ON c.snapshot_id=p.snapshot_id AND c.course_id=p.prerequisite_course_id WHERE p.snapshot_id=? AND p.course_id=?",row.get("snapshotId"),row.get("courseId")));return m;});
        row.putAll(frozen);return row;
    }
    public void clear(){staticCache.invalidateAll();}
}
