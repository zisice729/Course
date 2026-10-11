package com.wylie.registration.shared;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** SQL text is supplied only by server-owned repositories, never by HTTP input. */
@Mapper
public interface SqlMapper {
    class Provider {
        public static String statement(Map<String,Object> input) { return (String) input.get("sql"); }
    }
    @SelectProvider(type=Provider.class, method="statement")
    List<Map<String,Object>> query(Map<String,Object> input);
    @UpdateProvider(type=Provider.class, method="statement")
    int execute(Map<String,Object> input);
}
