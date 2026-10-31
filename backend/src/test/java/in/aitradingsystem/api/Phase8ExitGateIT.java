package in.aitradingsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/** A fresh database proves the full operator story without inheriting another suite's ledger fixtures. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(Phase8ExitGateIT.TestClockConfiguration.class)
@TestPropertySource(properties = {"app.security.api-key=test-operator-key-0123456789", "app.risk.default-capital=100000"})
class Phase8ExitGateIT {
    private static final String KEY="test-operator-key-0123456789";
    private static final String DAY="2026-10-30";
    private static final Instant OPEN=Instant.parse("2026-10-30T04:30:00Z");
    private static final AtomicReference<Instant> NOW=new AtomicReference<>(OPEN);

    private static final String EXTERNAL_JDBC_URL=System.getenv("ATS_PHASE8_EXIT_POSTGRES_JDBC_URL");
    private static final String EXTERNAL_USERNAME=System.getenv().getOrDefault("ATS_TEST_POSTGRES_USERNAME","aitrading");
    private static final String EXTERNAL_PASSWORD=System.getenv().getOrDefault("ATS_TEST_POSTGRES_PASSWORD","aitrading");
    private static final PostgreSQLContainer<?> POSTGRES=EXTERNAL_JDBC_URL==null || EXTERNAL_JDBC_URL.isBlank()
        ? new PostgreSQLContainer<>("postgres:16-alpine")
        : null;

    static {
        if (POSTGRES!=null) POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        if (POSTGRES!=null) {
            registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username",POSTGRES::getUsername);
            registry.add("spring.datasource.password",POSTGRES::getPassword);
            return;
        }
        registry.add("spring.datasource.url",()->EXTERNAL_JDBC_URL);
        registry.add("spring.datasource.username",()->EXTERNAL_USERNAME);
        registry.add("spring.datasource.password",()->EXTERNAL_PASSWORD);
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        Clock phase8ExitClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(NOW.get(),zone); }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    private UUID catalogue;

    @BeforeEach
    void prepareMarketEvidence() {
        NOW.set(OPEN);
        // Align bootstrap lifecycle/funding evidence with the deterministic scenario; policy values stay immutable.
        jdbc.sql("UPDATE risk_policy_config SET effective_from=:at WHERE active=TRUE")
            .param("at",OPEN.minusSeconds(86400).atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("UPDATE paper_cash_entry SET created_at=:at WHERE entry_type='CAPITAL_SEED'")
            .param("at",LocalDate.parse(DAY).atStartOfDay(ZoneId.of("Asia/Kolkata")).minusHours(1).toOffsetDateTime()).update();
        catalogue=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO instrument_import_run(id,status,source_url,started_at,completed_at,accepted_count,active_count,catalogue_version)
            VALUES (:v,'SUCCEEDED','phase8-exit-fixture',:at,:at,1,1,:v)
            """).param("v",catalogue).param("at",OPEN.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("UPDATE instrument_catalogue_state SET active_version=:v,activated_at=:at WHERE singleton_id=1")
            .param("v",catalogue).param("at",OPEN.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
            INSERT INTO instrument_master(exchange_segment,exchange,symbol_token,trading_symbol,instrument_name,
                instrument_type,lot_size,tick_size,active,catalogue_version)
            VALUES ('NSE','NSE','P8GATE','P8GATE','Phase 8 gate','EQ',1,0.05,TRUE,:v)
            """).param("v",catalogue).update();
        jdbc.sql("""
            INSERT INTO execution_policy(id,policy_version,execution_mode,strategy,trading_style,approval_mode,
                max_order_notional,max_daily_trades,max_gross_exposure,max_daily_loss,active,effective_from,created_by)
            VALUES (:id,1,'PAPER','PAPER_MANUAL','SWING','MANUAL',1000000,100,1000000,1000000,TRUE,:at,'P08_GATE')
            """).param("id",UUID.randomUUID()).param("at",OPEN.minusSeconds(86400).atOffset(ZoneOffset.UTC)).update();
        mark(100);
    }

    @Test
    void operatorCanExplainARealPaperRoundTripAndRespondToAnIncidentThroughSupportedApis() throws Exception {
        UUID buy=placeAndApprove("BUY",100);
        NOW.set(OPEN.plusSeconds(60));
        mark(110);
        UUID sell=placeAndApprove("SELL",110);
        assertThat(jdbc.sql("SELECT count(*) FROM paper_fill").query(Long.class).single()).isEqualTo(2);
        NOW.set(OPEN.plusSeconds(86400)); // Completed IST day; the API must not publish an unfinished day.
        String dateBody="{\"tradeDate\":\""+DAY+"\"}";
        for (int i=0;i<2;i++) mvc.perform(post("/api/analytics/portfolio/recalculate").header("X-API-Key",KEY)
            .contentType(MediaType.APPLICATION_JSON).content(dateBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.openingEquity").value(100000))
            .andExpect(jsonPath("$.closingEquity").value(100009.69)).andExpect(jsonPath("$.netPnl").value(9.69))
            .andExpect(jsonPath("$.grossPnl").value(10.00)).andExpect(jsonPath("$.fees").value(0.10))
            .andExpect(jsonPath("$.slippage").value(0.21)).andExpect(jsonPath("$.tradeCount").value(2))
            .andExpect(jsonPath("$.winCount").value(1));
        assertThat(jdbc.sql("SELECT count(*) FROM portfolio_performance_daily").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM paper_fill").query(Long.class).single()).isEqualTo(2);
        mvc.perform(get("/api/paper-portfolio").header("X-API-Key",KEY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.exposurePercent").value(0));
        mvc.perform(get("/api/analytics/portfolio").header("X-API-Key",KEY).param("from",DAY).param("to",DAY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.materializationComplete").value(true))
            .andExpect(jsonPath("$.netPnl").value(9.69)).andExpect(jsonPath("$.exitMetrics.closedExitOrders").value(1))
            .andExpect(jsonPath("$.exitMetrics.winRatePercent").value(100))
            .andExpect(jsonPath("$.exposureSnapshot").doesNotExist());
        mvc.perform(get("/api/analytics/equity").header("X-API-Key",KEY).param("from",DAY).param("to",DAY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.equityCurve.length()").value(1));
        mvc.perform(get("/api/analytics/strategies").header("X-API-Key",KEY).param("from",DAY).param("to",DAY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].strategy").value("PAPER_MANUAL"))
            .andExpect(jsonPath("$.items[0].netPnl").value(9.69));
        mvc.perform(get("/api/analytics/research").header("X-API-Key",KEY).param("from",DAY).param("to",DAY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.advisoryOnly").value(true))
            .andExpect(jsonPath("$.profitabilityForecast").value(false));
        mvc.perform(post("/api/paper-reconciliation").header("X-API-Key",KEY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEAN"));
        JsonNode health=read("/api/operations/health");
        assertThat(health.path("executionMode").asText()).isEqualTo("PAPER");
        assertThat(health.path("liveAvailable").asBoolean()).isFalse();
        assertThat(health.path("components").findValuesAsText("state")).contains("HEALTHY");
        assertThat(health.path("components").findValuesAsText("reasonCode")).contains("LAST_RUN_CLEAN");
        mvc.perform(put("/api/risk/emergency-stop").header("X-API-Key",KEY).contentType(MediaType.APPLICATION_JSON)
            .content("{\"halted\":true,\"reason\":\"Phase 8 operator review\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.newOrdersHalted").value(true));
        JsonNode notifications=read("/api/notifications");
        JsonNode incident=null;
        for (JsonNode item:notifications.path("items")) if (item.path("type").asText().equals("RISK_EMERGENCY_STOP")) incident=item;
        assertThat(incident).as("Durable critical stop incident").isNotNull();
        assertThat(incident.path("severity").asText()).isEqualTo("CRITICAL");
        String notification=incident.path("id").asText();
        for (int i=0;i<2;i++) mvc.perform(post("/api/notifications/{id}/acknowledge",notification).header("X-API-Key",KEY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedBy").value("operator"));
        assertThat(read("/api/operations/health").path("emergencyStopActive").asBoolean()).isTrue();
        mvc.perform(get("/api/audit").header("X-API-Key",KEY).param("eventType","PERFORMANCE_REBUILT").param("actor","operator"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        String csv=mvc.perform(get("/api/audit/export").header("X-API-Key",KEY).param("entityType","PAPER_ORDER").param("actor","operator"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains(buy.toString(),sell.toString(),"EXECUTION_REVALIDATED");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type='NOTIFICATION_ACKNOWLEDGED' AND entity_id=:id")
            .param("id",notification).query(Long.class).single()).isEqualTo(1);
    }

    private UUID placeAndApprove(String side,int price) throws Exception {
        String body="""
            {"symbol":"P8GATE","side":"%s","quantity":1,"referencePrice":%d,"stopLoss":95,
             "target":120,"signalGeneratedAt":"%s","productType":"DELIVERY","orderType":"MARKET"}
            """.formatted(side,price,NOW.get());
        JsonNode created=json.readTree(mvc.perform(post("/api/paper-orders").header("X-API-Key",KEY)
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.order.status").value("APPROVAL_PENDING"))
            .andReturn().getResponse().getContentAsString());
        UUID order=UUID.fromString(created.at("/order/id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM paper_fill WHERE order_id=:id").param("id",order).query(Long.class).single()).isZero();
        UUID approval=jdbc.sql("SELECT approval_id FROM paper_order WHERE id=:id").param("id",order).query(UUID.class).single();
        mvc.perform(post("/api/approvals/{id}/approve",approval).header("X-API-Key",KEY).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRowVersion\":0,\"actor\":\"operator\",\"reason\":\"Gate scenario reviewed\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/paper-orders/{id}",order).header("X-API-Key",KEY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("FILLED"));
        return order;
    }

    private JsonNode read(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("X-API-Key",KEY)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private void mark(int price) {
        jdbc.sql("""
            INSERT INTO market_candle(instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,
                interval,candle_time,open_price,high_price,low_price,close_price,volume)
            SELECT instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,'ONE_MINUTE',:at,:p,:p,:p,:p,10000
              FROM instrument_master WHERE catalogue_version=:v
            """).param("v",catalogue).param("p",price).param("at",NOW.get().minusSeconds(10).atOffset(ZoneOffset.UTC)).update();
    }
}
