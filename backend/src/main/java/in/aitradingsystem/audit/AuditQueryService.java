package in.aitradingsystem.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.common.PageResponse;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AuditQueryService {
    private static final String WHERE = """
         WHERE created_at >= :from AND created_at < :to
           AND (:eventType IS NULL OR event_type = :eventType)
           AND (:entityType IS NULL OR entity_type = :entityType)
           AND (:entityId IS NULL OR entity_id = :entityId)
           AND (:correlation IS NULL OR correlation_id = :correlation)
           AND (:actor IS NULL OR actor = :actor)
        """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    public AuditQueryService(JdbcClient jdbc, ObjectMapper json, Clock clock) { this.jdbc = jdbc; this.json = json; this.clock = clock; }

    public PageResponse<AuditEvent> search(Filter requested, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid audit page/size");
        Filter filter = resolve(requested);
        long count = bind(jdbc.sql("SELECT count(*) FROM audit_event"+WHERE),filter).query(Long.class).single();
        List<AuditEvent> rows = rows(filter,size,(long)page*size);
        return PageResponse.of(rows,page,size,count);
    }

    public String exportCsv(Filter requested) {
        List<AuditEvent> rows = rows(resolve(requested),1001,0);
        if (rows.size()>1000) throw new IllegalArgumentException("Audit export exceeds 1000 records; narrow the filters");
        StringBuilder csv = new StringBuilder("id,createdAt,eventType,entityType,entityId,correlationId,actor,actorSource,details\r\n");
        long bytes = csv.length();
        for (AuditEvent row : rows) {
            String line = String.join(",",cell(Long.toString(row.id())),cell(row.createdAt().toString()),cell(row.eventType()),
                cell(row.entityType()),cell(row.entityId()),cell(row.correlationId()),cell(row.actor()),cell(row.actorSource()),cell(row.details().toString()))+"\r\n";
            bytes += line.getBytes(StandardCharsets.UTF_8).length;
            if (bytes>5_000_000) throw new IllegalArgumentException("Audit export exceeds 5 MB; narrow the filters");
            csv.append(line);
        }
        return csv.toString();
    }

    private List<AuditEvent> rows(Filter filter,int limit,long offset) {
        return bind(jdbc.sql("SELECT * FROM audit_event"+WHERE+" ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset"),filter)
            .param("limit",limit).param("offset",offset).query((rs,row) -> map(rs)).list();
    }
    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement,Filter f) {
        return statement.param("from",f.from().atOffset(ZoneOffset.UTC)).param("to",f.to().atOffset(ZoneOffset.UTC))
            .param("eventType",f.eventType(),Types.VARCHAR).param("entityType",f.entityType(),Types.VARCHAR)
            .param("entityId",f.entityId(),Types.VARCHAR).param("correlation",f.correlationId(),Types.VARCHAR)
            .param("actor",f.actor(),Types.VARCHAR);
    }
    private Filter resolve(Filter input) {
        if (input==null) input=new Filter(null,null,null,null,null,null,null);
        // Keep explicit windows half-open [from,to), but make the implicit "through now" window
        // read-your-write safe for events persisted at exactly the injected clock instant.
        // PostgreSQL timestamptz has microsecond precision, so adding only 1ns is rounded back to
        // the same persisted boundary and still excludes an event written at clock.instant().
        Instant to=input.to()==null?clock.instant().plusNanos(1_000):input.to();
        Instant from=input.from()==null?to.minus(Duration.ofDays(7)):input.from();
        if (!to.isAfter(from) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0)
            throw new IllegalArgumentException("Audit window must be ordered and at most 31 days");
        return new Filter(bound(input.eventType(),60),bound(input.entityType(),40),bound(input.entityId(),80),
            bound(input.correlationId(),64),bound(input.actor(),120),from,to);
    }
    private static String bound(String value,int max) {
        if (value==null || value.isBlank()) return null;
        if (value.trim().length()>max) throw new IllegalArgumentException("Audit filter exceeds its field bound");
        return value.trim();
    }
    private AuditEvent map(ResultSet rs) throws SQLException {
        try {
            return new AuditEvent(rs.getLong("id"),rs.getString("event_type"),rs.getString("entity_type"),rs.getString("entity_id"),
                rs.getString("correlation_id"),rs.getString("actor"),rs.getString("actor_source"),
                redact(json.readTree(rs.getString("details"))),rs.getObject("created_at",OffsetDateTime.class).toInstant());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Invalid persisted audit JSON",ex); }
    }
    private static JsonNode redact(JsonNode node) {
        JsonNode copy=node.deepCopy();
        if (copy.isObject()) {
            ObjectNode object=(ObjectNode)copy;
            var names=new java.util.ArrayList<String>(); object.fieldNames().forEachRemaining(names::add);
            for (String name:names) {
                String key=name.replaceAll("[^A-Za-z0-9]","").toLowerCase(Locale.ROOT);
                if (key.contains("password") || key.contains("secret") || key.equals("token") || key.equals("jwt")
                        || key.endsWith("accesstoken") || key.endsWith("refreshtoken") || key.endsWith("jwttoken") || key.endsWith("feedtoken") || key.contains("apikey")
                        || key.contains("authorization") || key.contains("cookie") || key.contains("credential")) object.put(name,"[REDACTED]");
                else object.set(name,redact(object.get(name)));
            }
        } else if (copy.isArray()) {
            var array=(com.fasterxml.jackson.databind.node.ArrayNode)copy;
            for (int i=0;i<array.size();i++) array.set(i,redact(array.get(i)));
        }
        return copy;
    }
    static String cell(String value) {
        if (value==null) value="";
        int first=0;
        while (first<value.length() && (Character.isWhitespace(value.charAt(first)) || value.charAt(first)=='\ufeff')) first++;
        if ((!value.isEmpty() && (value.charAt(0)=='\t' || value.charAt(0)=='\r' || value.charAt(0)=='\n'))
                || (first<value.length() && "=+-@".indexOf(value.charAt(first))>=0)) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    public record Filter(String eventType,String entityType,String entityId,String correlationId,String actor,Instant from,Instant to) {}
    public record AuditEvent(long id,String eventType,String entityType,String entityId,String correlationId,String actor,
                             String actorSource,JsonNode details,Instant createdAt) {}
}
