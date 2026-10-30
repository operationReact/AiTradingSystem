package in.aitradingsystem.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.common.CorrelationIdFilter;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AuditQueryServiceIT extends AbstractPostgresIT {
    @Autowired AuditService audit;
    @Autowired AuditQueryService query;
    @Autowired JdbcClient jdbc;
    private final String entity=UUID.randomUUID().toString();
    private final Instant from=Instant.now().minus(1,ChronoUnit.HOURS);
    private final Instant to=Instant.now().plus(1,ChronoUnit.HOURS);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext(); MDC.remove(CorrelationIdFilter.MDC_KEY);
        jdbc.sql("DELETE FROM audit_event WHERE entity_type='AUDIT_TEST' AND entity_id=:id").param("id",entity).update();
    }

    @Test
    void authenticatedActorAndCorrelationAreQueryableWithoutTrustingPayloadActorClaims() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator-a",null,
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        MDC.put(CorrelationIdFilter.MDC_KEY,"corr-"+entity);
        audit.record("AUDIT_TEST_EVENT","AUDIT_TEST",entity,Map.of("actor","forged-actor","safe","preserved"));
        var filter=new AuditQueryService.Filter("AUDIT_TEST_EVENT","AUDIT_TEST",entity,"corr-"+entity,"operator-a",from,to);
        var result=query.search(filter,0,10);
        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items().getFirst().actorSource()).isEqualTo("AUTHENTICATED");
        assertThat(result.items().getFirst().actor()).isEqualTo("operator-a");
        assertThat(query.search(new AuditQueryService.Filter(null,"AUDIT_TEST",entity,null,"forged-actor",from,to),0,10).totalItems()).isZero();
        assertThat(query.search(new AuditQueryService.Filter("' OR 1=1 --","AUDIT_TEST",entity,null,null,from,to),0,10).totalItems()).isZero();
    }

    @Test
    void legacyCredentialsAreRedactedInReadsAndCsvWithoutChangingPersistedEvidence() {
        String details="{\"password\":\"legacy-secret\",\"nested\":{\"refresh_token\":\"legacy-token\",\"symbolToken\":\"12345\"},\"safe\":\"preserved\"}";
        jdbc.sql("INSERT INTO audit_event(event_type,entity_type,entity_id,details) VALUES ('=cmd()','AUDIT_TEST',:id,CAST(:details AS jsonb))")
            .param("id",entity).param("details",details).update();
        var filter=new AuditQueryService.Filter(null,"AUDIT_TEST",entity,null,null,from,to);
        var event=query.search(filter,0,10).items().getFirst();
        assertThat(event.actor()).isNull();
        assertThat(event.actorSource()).isEqualTo("LEGACY_UNKNOWN");
        assertThat(event.details().toString()).doesNotContain("legacy-secret","legacy-token").contains("12345","preserved","[REDACTED]");
        String csv=query.exportCsv(filter);
        assertThat(csv).contains("\"'=cmd()\"").doesNotContain("legacy-secret","legacy-token");
        assertThat(jdbc.sql("SELECT details->>'password' FROM audit_event WHERE entity_type='AUDIT_TEST' AND entity_id=:id")
            .param("id",entity).query(String.class).single()).isEqualTo("legacy-secret");
    }

    @Test
    void paginationIsBoundedAndOversizedExportIsRejectedInsteadOfSilentlyTruncated() {
        jdbc.sql("INSERT INTO audit_event(event_type,entity_type,entity_id,details) SELECT 'AUDIT_LIMIT','AUDIT_TEST',:id,'{}'::jsonb FROM generate_series(1,1001)")
            .param("id",entity).update();
        var filter=new AuditQueryService.Filter("AUDIT_LIMIT","AUDIT_TEST",entity,null,null,from,to);
        var result=query.search(filter,0,100);
        assertThat(result.items()).hasSize(100);
        assertThat(result.totalItems()).isEqualTo(1001);
        assertThatThrownBy(() -> query.exportCsv(filter)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1000");
        assertThatThrownBy(() -> query.search(filter,0,101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.search(new AuditQueryService.Filter(null,null,null,null,null,from.minus(40,ChronoUnit.DAYS),to),0,10))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
