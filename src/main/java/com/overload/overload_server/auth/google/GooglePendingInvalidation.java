package com.overload.overload_server.auth.google;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class GooglePendingInvalidation {
    private final JdbcTemplate jdbc;
    public GooglePendingInvalidation(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void retire(UUID user,UUID identity){
        var rows=jdbc.query("""
                SELECT a.session_primary_id,a.attribute_name,a.attribute_bytes FROM spring_session_attributes a
                JOIN spring_session s ON s.primary_id=a.session_primary_id
                WHERE s.principal_name=? AND a.attribute_name IN (?,?,?)
                """,(row,index)->new Attribute(row.getString(1),row.getString(2),row.getBytes(3)),user.toString(),
                GoogleContactRequests.class.getName()+".pending",GoogleReauthenticationRequests.class.getName()+".pending",GoogleLinkRequests.class.getName()+".pending");
        for(var row:rows){
            var value=new DeserializingConverter().convert(row.bytes());
            if(value instanceof GoogleBoundRequests.Pending pending && user.equals(pending.snapshot().userId())
                    && (identity.equals(pending.identity())||pending.purpose().equals("GOOGLE_LINK"))){
                jdbc.update("DELETE FROM spring_session_attributes WHERE session_primary_id=? AND attribute_name=? AND attribute_bytes=?",row.primary(),row.name(),row.bytes());
            }
        }
    }
    private record Attribute(String primary,String name,byte[] bytes){ }
}
