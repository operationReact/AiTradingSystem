package in.aitradingsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import in.aitradingsystem.notification.NotificationService;
import in.aitradingsystem.notification.NotificationSeverity;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class Phase8ApiContractIT extends AbstractPostgresIT {
    private static final String DAY="2004-01-08";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired NotificationService notifications;
    private final String entity=UUID.randomUUID().toString();
    private UUID notification;
    private Long malformedCash;
    private UUID backfillRun;
    private Long checkpoint;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM portfolio_performance_daily WHERE trade_date=:day").param("day",LocalDate.parse(DAY)).update();
        jdbc.sql("DELETE FROM strategy_performance_daily WHERE trade_date=:day").param("day",LocalDate.parse(DAY)).update();
        jdbc.sql("DELETE FROM audit_event WHERE entity_type='PORTFOLIO_PERFORMANCE_DAILY' AND entity_id=:day").param("day",DAY).update();
        if (checkpoint!=null) jdbc.sql("DELETE FROM notification_source_projection WHERE source_type='BACKFILL_FAILURE' AND source_id=:id")
            .param("id",checkpoint.toString()).update();
        if (notification!=null) {
            jdbc.sql("DELETE FROM audit_event WHERE entity_type='SYSTEM_NOTIFICATION' AND entity_id=:id").param("id",notification.toString()).update();
            jdbc.sql("DELETE FROM system_notification WHERE id=:id").param("id",notification).update();
        }
        if (malformedCash!=null) jdbc.sql("DELETE FROM paper_cash_entry WHERE id=:id").param("id",malformedCash).update();
        if (backfillRun!=null) jdbc.sql("DELETE FROM candle_backfill_run WHERE id=:id").param("id",backfillRun).update();
    }

    @Test
    void everyPhase8ReadSurfaceRequiresOperatorAuthentication() throws Exception {
        for (String path:List.of("/api/analytics/portfolio","/api/analytics/equity","/api/analytics/strategies",
                "/api/analytics/research","/api/notifications","/api/operations/health","/api/audit","/api/audit/export")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            var authenticated=get(path).header("X-API-Key",API_KEY);
            if (path.equals("/api/audit/export")) authenticated.param("entityId",entity);
            mvc.perform(authenticated).andExpect(status().isOk());
        }
        mvc.perform(post("/api/analytics/portfolio/recalculate").contentType(MediaType.APPLICATION_JSON).content("{\"tradeDate\":\""+DAY+"\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void supportedReadContractsExposeCoverageAndAdvisorySemantics() throws Exception {
        mvc.perform(get("/api/analytics/portfolio").header("X-API-Key",API_KEY).param("from",DAY).param("to",DAY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.materializationComplete").value(false))
            .andExpect(jsonPath("$.materializedDays").value(0)).andExpect(jsonPath("$.equityCurve").isArray());
        mvc.perform(get("/api/analytics/research").header("X-API-Key",API_KEY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.advisoryOnly").value(true))
            .andExpect(jsonPath("$.profitabilityForecast").value(false));
        mvc.perform(get("/api/analytics/portfolio").header("X-API-Key",API_KEY).param("from","2004-01-09").param("to",DAY))
            .andExpect(status().isBadRequest());
    }

    @Test
    void operatorCanMaterializeThroughApiAndActionIsAudited() throws Exception {
        mvc.perform(post("/api/analytics/portfolio/recalculate").header("X-API-Key",API_KEY)
            .contentType(MediaType.APPLICATION_JSON).content("{\"tradeDate\":\""+DAY+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tradeDate").value(DAY));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type='PERFORMANCE_REBUILT' AND entity_id=:day AND actor='operator'")
            .param("day",DAY).query(Long.class).single()).isEqualTo(1);
        mvc.perform(post("/api/analytics/portfolio/recalculate").header("X-API-Key",API_KEY)
            .contentType(MediaType.APPLICATION_JSON).content("{\"tradeDate\":\""+LocalDate.now().plusDays(2)+"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidAccountingReturnsCorrelatedConflictInsteadOfPublishingFalsePerformance() throws Exception {
        var at=LocalDate.parse(DAY).atStartOfDay(in.aitradingsystem.marketdata.IndianCashMarketCalendar.ZONE).toOffsetDateTime().plusHours(10);
        malformedCash=jdbc.sql("INSERT INTO paper_cash_entry(entry_type,amount,balance_after,created_at) VALUES ('BUY_SETTLEMENT',-1,0,:at) RETURNING id")
            .param("at",at).query(Long.class).single();
        String correlation="p8-contract-"+entity;
        mvc.perform(post("/api/analytics/portfolio/recalculate").header("X-API-Key",API_KEY).header("X-Correlation-Id",correlation)
            .contentType(MediaType.APPLICATION_JSON).content("{\"tradeDate\":\""+DAY+"\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PERFORMANCE_LEDGER_MISMATCH"))
            .andExpect(jsonPath("$.correlationId").value(correlation));
    }

    @Test
    void notificationAcknowledgementUsesAuthenticatedActorAndDoesNotReplayTheAudit() throws Exception {
        notification=notifications.raise("P8_API_TEST",NotificationSeverity.INFO,"API contract test","Review test evidence",
            "P8_API_TEST",entity,"p8-api:"+entity).id();
        mvc.perform(get("/api/notifications").header("X-API-Key",API_KEY).param("size","100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.projection.backlogRemaining").isBoolean());
        mvc.perform(get("/api/notifications/{id}",notification).header("X-API-Key",API_KEY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(notification.toString()));
        for (int i=0;i<2;i++) mvc.perform(post("/api/notifications/{id}/acknowledge",notification).header("X-API-Key",API_KEY)
            .contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"spoofed-client-name\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedBy").value("operator"));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type='NOTIFICATION_ACKNOWLEDGED' AND entity_id=:id")
            .param("id",notification.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void auditApiReturnsBoundedJsonAndDownloadableCsv() throws Exception {
        mvc.perform(get("/api/audit").header("X-API-Key",API_KEY).param("size","10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/audit/export").header("X-API-Key",API_KEY).param("entityId",entity))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(header().string("Content-Disposition","attachment; filename=\"audit.csv\""));
        mvc.perform(get("/api/audit").header("X-API-Key",API_KEY).param("size","101")).andExpect(status().isBadRequest());
    }

    @Test
    void notificationApiProjectsFailedCheckpointWithoutLeakingProviderTextOrReopeningAcknowledgedHistory() throws Exception {
        backfillRun=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO candle_backfill_run(id,instrument_id,exchange,trading_symbol,symbol_token,interval,
                from_date,to_date,next_from_date,status)
            VALUES (:id,1,'NSE','P8API','1','ONE_MINUTE',:day,:day,:day,'FAILED')
            """).param("id",backfillRun).param("day",LocalDate.parse(DAY)).update();
        checkpoint=jdbc.sql("""
            INSERT INTO candle_backfill_checkpoint(run_id,chunk_from_date,chunk_to_date,status,error_message)
            VALUES (:id,:day,:day,'FAILED','provider-secret-test') RETURNING checkpoint_id
            """).param("id",backfillRun).param("day",LocalDate.parse(DAY)).query(Long.class).single();
        for (int i=0;i<20 && notification==null;i++) {
            mvc.perform(get("/api/notifications").header("X-API-Key",API_KEY)).andExpect(status().isOk());
            notification=jdbc.sql("SELECT notification_id FROM notification_source_projection WHERE source_type='BACKFILL_FAILURE' AND source_id=:id")
                .param("id",checkpoint.toString()).query(UUID.class).optional().orElse(null);
        }
        assertThat(notification).isNotNull();
        assertThat(notifications.require(notification).message()).doesNotContain("provider-secret-test");
        mvc.perform(post("/api/notifications/{id}/acknowledge",notification).header("X-API-Key",API_KEY))
            .andExpect(status().isOk());
        mvc.perform(get("/api/notifications").header("X-API-Key",API_KEY)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM system_notification WHERE entity_type='BACKFILL_FAILURE' AND entity_id=:id")
            .param("id",checkpoint.toString()).query(Long.class).single()).isEqualTo(1);
    }
}
