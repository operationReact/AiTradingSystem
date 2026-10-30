package in.aitradingsystem.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.common.CorrelationIdFilter;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

/** Append-only audit trail. Callers must never pass secrets or credentials in details. */
@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String eventType, String entityType, String entityId, Map<String, Object> details) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
        String actor = authenticated ? authentication.getName() : "SYSTEM";
        if (actor == null || actor.isBlank() || actor.length() > 120)
            throw new IllegalStateException("Audit actor identity must be bounded and non-empty");
        jdbc.sql("INSERT INTO audit_event (event_type, entity_type, entity_id, correlation_id, actor, actor_source, details) "
                + "VALUES (:eventType, :entityType, :entityId, :correlationId, :actor, :actorSource, CAST(:details AS jsonb))")
            .param("eventType", eventType)
            .param("entityType", entityType)
            .param("entityId", entityId)
            .param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
            .param("actor", actor)
            .param("actorSource", authenticated ? "AUTHENTICATED" : "SYSTEM")
            .param("details", toJson(details))
            .update();
    }

    private String toJson(Map<String, Object> details) {
        try {
            return mapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit details are not serializable", e);
        }
    }
}
