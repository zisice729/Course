package com.wylie.registration.closing;

import com.wylie.registration.registration.Rules;
import com.wylie.registration.shared.Db;
import java.time.Instant;
import java.util.*;
import java.util.function.BiPredicate;

/** LEVELING_V1_1: pure single-pass planner; persistence happens only after validation. */
public final class Leveling {
    public record Choice(long offeringId,String choiceType,int order){}
    public record Participant(long studentId,Instant first,List<Choice> choices,Set<Long> active){}
    public record Plan(Map<Long,Set<Long>> courses,Map<Long,Map<Long,String>> origins,Map<Long,Map<Long,String>> reasons,Map<Long,String> states,List<Map<String,Object>> attempts){}
    public static Plan calculate(List<Participant> input,List<Map<String,Object>> offerings,Map<String,Object> semester,BiPredicate<Long,Map<String,Object>> prerequisites){
        Map<Long,Map<String,Object>> offers=new TreeMap<>();offerings.forEach(o->offers.put(Db.num(o,"id"),o));
        Map<Long,Integer> count=new HashMap<>();offers.keySet().forEach(id->count.put(id,0));
        Map<Long,Set<Long>> courses=new LinkedHashMap<>();Map<Long,Map<Long,String>> origins=new HashMap<>(),reasons=new HashMap<>();List<Map<String,Object>> attempts=new ArrayList<>();
        var participants=input.stream().sorted(Comparator.comparing(Participant::first).thenComparingLong(Participant::studentId)).toList();
        for(var p:participants){var active=new TreeSet<>(p.active());Map<Long,String> origin=new HashMap<>(),why=new HashMap<>();
            for(long id:new TreeSet<>(active)){if(!offers.containsKey(id))throw new IllegalStateException("Missing offering");if(offers.get(id).get("professorId")==null){active.remove(id);why.put(id,"NO_INSTRUCTOR");}else{count.merge(id,1,Integer::sum);origin.put(id,"PRIMARY");}}
            courses.put(p.studentId(),active);origins.put(p.studentId(),origin);reasons.put(p.studentId(),why);
        }
        for(var p:participants){var active=courses.get(p.studentId());var origin=origins.get(p.studentId());var why=reasons.get(p.studentId());
            for(var candidate:p.choices().stream().filter(c->c.choiceType().equals("ALTERNATE")).sorted(Comparator.comparingInt(Choice::order)).toList()){
                long confirmed=active.stream().filter(id->count.get(id)>=3&&offers.get(id).get("professorId")!=null).count();if(confirmed>=4)break;
                Long replace=null;
                if(active.size()>=4){replace=p.choices().stream().filter(c->c.choiceType().equals("PRIMARY")&&active.contains(c.offeringId())&&"PRIMARY".equals(origin.get(c.offeringId()))&&count.get(c.offeringId())<3).max(Comparator.comparingInt(Choice::order)).map(Choice::offeringId).orElse(null);if(replace==null){attempts.add(event(p,candidate,"NO_REPLACEABLE_PRIMARY"));continue;}}
                var target=offers.get(candidate.offeringId());String failure=null;var retained=new TreeSet<>(active);if(replace!=null)retained.remove(replace);
                if(target==null)failure="OFFERING_MISSING";
                else if(target.get("professorId")==null)failure="NO_INSTRUCTOR";
                else if(active.contains(candidate.offeringId()))failure="ALREADY_ENROLLED";
                else if(count.get(candidate.offeringId())>=10)failure="COURSE_FULL";
                else if(!prerequisites.test(p.studentId(),target))failure="PREREQUISITE_UNMET";
                else for(long id:retained){if(Db.num(offers.get(id),"courseId")==Db.num(target,"courseId")){failure="DUPLICATE_COURSE";break;}if(Rules.conflict(offers.get(id),target,semester)){failure="TIME_CONFLICT";break;}}
                if(failure!=null){attempts.add(event(p,candidate,failure));continue;}
                if(replace!=null){active.remove(replace);count.merge(replace,-1,Integer::sum);origin.remove(replace);why.put(replace,"REPLACED_BY_ALTERNATE");}
                active.add(candidate.offeringId());count.merge(candidate.offeringId(),1,Integer::sum);origin.put(candidate.offeringId(),"ALTERNATE");attempts.add(event(p,candidate,"ACTIVATED"));
            }
        }
        Map<Long,String> states=new TreeMap<>();for(var e:offers.entrySet())states.put(e.getKey(),e.getValue().get("professorId")!=null&&count.get(e.getKey())>=3?"CONFIRMED":"CANCELLED");
        for(var p:participants){var active=courses.get(p.studentId());for(long id:new TreeSet<>(active))if("CANCELLED".equals(states.get(id))){active.remove(id);origins.get(p.studentId()).remove(id);reasons.get(p.studentId()).put(id,offers.get(id).get("professorId")==null?"NO_INSTRUCTOR":"LOW_ENROLLMENT");}
            if(active.size()>4)throw new IllegalStateException("More than four courses");var finalOffers=active.stream().map(offers::get).toList();Set<Long> unique=new HashSet<>();for(int i=0;i<finalOffers.size();i++){if(!unique.add(Db.num(finalOffers.get(i),"courseId")))throw new IllegalStateException("Duplicate final course");for(int j=0;j<i;j++)if(Rules.conflict(finalOffers.get(i),finalOffers.get(j),semester))throw new IllegalStateException("Final time conflict");}
        }
        for(long id:offers.keySet()){long actual=courses.values().stream().filter(s->s.contains(id)).count();if("CONFIRMED".equals(states.get(id))&&(actual<3||actual>10))throw new IllegalStateException("Final capacity violation");if("CANCELLED".equals(states.get(id))&&actual!=0)throw new IllegalStateException("Cancelled enrollment");}
        return new Plan(courses,origins,reasons,states,attempts);
    }
    private static Map<String,Object> event(Participant p,Choice c,String reason){return Map.of("studentId",String.valueOf(p.studentId()),"offeringId",String.valueOf(c.offeringId()),"order",c.order(),"reason",reason);}
}
