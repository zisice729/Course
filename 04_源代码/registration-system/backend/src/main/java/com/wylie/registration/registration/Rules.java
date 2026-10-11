package com.wylie.registration.registration;

import com.wylie.registration.shared.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class Rules {
    public static final Set<String> GRADES=Set.of("A","B","C","D","F","I");
    private final Db db;
    public Rules(Db db){this.db=db;}
    public void prerequisites(long student,Map<String,Object> offering){
        for(var p:db.list("SELECT prerequisite_course_id course_id FROM course_prerequisite WHERE snapshot_id=? AND course_id=?",offering.get("snapshotId"),offering.get("courseId"))){
            var pass=db.one("SELECT e.id FROM enrollment e JOIN offering o ON o.id=e.offering_id JOIN semester s ON s.id=o.semester_id JOIN grade g ON g.enrollment_id=e.id WHERE e.student_id=? AND o.course_id=? AND s.teaching_state='COMPLETED' AND s.end_date<(SELECT start_date FROM semester WHERE id=?) AND g.grade IN ('A','B','C','D') LIMIT 1",student,p.get("courseId"),offering.get("semesterId"));
            if(pass==null)throw Api.conflict("PREREQUISITE_UNMET","尚未满足课程先修条件");
        }
    }
    public void noConflicts(List<Map<String,Object>> offers,Map<String,Object> semester,boolean courses){
        Set<Long> seen=new HashSet<>();
        for(int i=0;i<offers.size();i++){
            var a=offers.get(i);if(courses&&!seen.add(Db.num(a,"courseId")))throw Api.conflict("DUPLICATE_COURSE","同一课程不能正式注册两个班");
            for(int j=0;j<i;j++)if(conflict(a,offers.get(j),semester))throw new Api.Failure(409,"TIME_CONFLICT","上课时间冲突",Map.of("offeringIds",List.of(a.get("id"),offers.get(j).get("id"))));
        }
    }
    @SuppressWarnings("unchecked")
    public static boolean conflict(Map<String,Object> a,Map<String,Object> b,Map<String,Object> semester){
        for(var x:(List<Map<String,Object>>)a.get("meetings"))for(var y:(List<Map<String,Object>>)b.get("meetings")){
            LocalTime xs=time(x.get("startTime")),xe=time(x.get("endTime")),ys=time(y.get("startTime")),ye=time(y.get("endTime"));
            if(xs.isBefore(ye)&&ys.isBefore(xe)){
                var xd=dates(x,semester);var yd=dates(y,semester);if(xd.stream().anyMatch(yd::contains))return true;
            }
        }return false;
    }
    public static LocalTime time(Object v){return LocalTime.parse(v.toString());}
    public static LocalDate date(Object v){return LocalDate.parse(v.toString().substring(0,10));}
    public static Set<LocalDate> dates(Map<String,Object> meeting,Map<String,Object> semester){
        LocalDate start=date(semester.get("startDate")),end=date(semester.get("endDate"));
        LocalDate first=date(meeting.get("firstDate")),last=date(meeting.get("lastDate"));
        first=first.isAfter(start)?first:start;last=last.isBefore(end)?last:end;
        LocalDate monday=start.minusDays(start.getDayOfWeek().getValue()-1);
        String mask=Db.str(meeting,"weekMask");int day=(int)Db.num(meeting,"weekday");Set<LocalDate> result=new HashSet<>();
        for(LocalDate d=first;!d.isAfter(last);d=d.plusDays(1)){
            int week=(int)(java.time.temporal.ChronoUnit.DAYS.between(monday,d)/7);
            if(d.getDayOfWeek().getValue()==day&&(mask==null||week<mask.length()&&mask.charAt(week)=='1'))result.add(d);
        }return result;
    }
}
