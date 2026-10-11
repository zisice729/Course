package com.wylie.registration.auth;

import com.wylie.registration.shared.Api;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.util.Map;

public record Actor(long id,String username,String role,Long studentId,Long professorId,long credentialVersion,boolean mustChangePassword) implements Serializable {
    public void require(String wanted){if(!role.equals(wanted))throw new Api.Failure(403,"FORBIDDEN","当前角色无权执行此操作");}
    public static Actor from(HttpServletRequest req){Object v=req.getAttribute("actor");if(!(v instanceof Actor a))throw new Api.Failure(401,"AUTH_REQUIRED","请先登录");return a;}
    public Map<String,Object> view(){var m=new java.util.LinkedHashMap<String,Object>();m.put("id",id);m.put("username",username);m.put("role",role);m.put("studentId",studentId);m.put("professorId",professorId);m.put("mustChangePassword",mustChangePassword);return m;}
}
