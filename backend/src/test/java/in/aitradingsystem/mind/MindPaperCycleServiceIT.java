package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.ExpectedCandleSlot;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketDataQualityService;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.mind.MindCapabilityProgressService.Status;
import in.aitradingsystem.mind.MindPaperCycleService.Outcome;
import in.aitradingsystem.strategy.StrategyService;
import in.aitradingsystem.strategy.StrategySignalRepository;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.tradingrooms.CustomerPaperOrderService;
import in.aitradingsystem.tradingrooms.CustomerPaperWalletService;
import in.aitradingsystem.user.UserAccountStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * End-to-end PAPER vertical slice on real PostgreSQL: stored broker candles -> quality gate ->
 * SMA_TREND_VOLUME_V1 -> verified tick -> automatic sizing -> existing PAPER engine ->
 * positions/cash/P&L -> runtime evidence -> progress registry. Never calls a broker.
 */
class MindPaperCycleServiceIT extends AbstractPostgresIT {
    /** Thursday 2026-11-12 10:30 IST, inside the cash session. */
    private static final Instant NOW = Instant.parse("2026-11-12T05:00:00Z");
    private static final String REV = "c0ffee1234";
    private static final String SOURCE = "ANGEL_ONE_SMARTAPI";

    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired InstrumentMasterService instruments;
    @Autowired MarketDataRepository candles;
    @Autowired MarketDataQualityService quality;
    @Autowired StrategySignalRepository signals;
    @Autowired MarketLastTickStore lastTicks;
    @Autowired MarketSessionCalendar calendar;
    @Autowired CustomerPaperWalletService wallets;
    @Autowired in.aitradingsystem.user.CustomerIdentityStore identities;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    /**
     * Wire contract: the real Spring message converter output must match what the Flutter parsers
     * (mind_progress_repository.dart, mind_paper_cycle_repository.dart) accept.
     */
    @Test
    void httpContractMatchesFlutterParsersAndRequiresTheRoomOwner() throws Exception {
        String issuer = "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_mindtest";
        String subject = "mind-" + UUID.randomUUID();
        var account = identities.provisionVerifiedIdentity("cognito", issuer, subject, subject,
            subject + "@example.test");
        UUID owner = account.id();
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id").param("id", owner).update();
        UUID room = fundedRoom(owner);
        long instrument = instrument("RELIANCE-EQ", "2885");
        var customerJwt = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .jwt().jwt(j -> j.issuer(issuer).subject(subject))
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();

        var progressBody = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/mind/v1/progress").with(customerJwt))
            .andReturn().getResponse();
        assertThat(progressBody.getStatus()).isEqualTo(200);
        var progressJson = json.readTree(progressBody.getContentAsString());
        assertThat(progressJson.get("contractVersion").asText()).isEqualTo("mind.progress.v1");
        assertThat(Instant.parse(progressJson.get("generatedAt").asText())).isNotNull();
        assertThat(progressJson.get("revisionKnown").isBoolean()).isTrue();
        assertThat(progressJson.get("paperStage").get("status").isTextual()).isTrue();
        var firstItem = progressJson.get("items").get(0);
        for (String field : List.of("id", "kind", "name", "owner", "status", "declaredStatus", "done", "runtime")) {
            assertThat(firstItem.has(field)).as(field).isTrue();
        }
        assertThat(firstItem.get("done").isBoolean()).isTrue();
        var summary = progressJson.get("summaries").get(0);
        for (String field : List.of("kind", "denominator", "done", "tested", "inProgress", "blocked", "mock",
                "notStarted", "failed")) {
            assertThat(summary.has(field)).as(field).isTrue();
        }

        // POST through the production-wired service (system clock): whatever the market state, the
        // response is a well-formed, explicitly non-LIVE cycle result.
        var run = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/customer/trading-rooms/" + room + "/mind/paper-cycles").with(customerJwt)
                .header("Idempotency-Key", "http_contract_01").contentType("application/json")
                .content("{\"instrumentId\":" + instrument + ",\"interval\":\"FIFTEEN_MINUTE\"}"))
            .andReturn().getResponse();
        assertThat(run.getStatus()).as(run.getContentAsString()).isEqualTo(200);
        var result = json.readTree(run.getContentAsString());
        var cycle = result.get("cycle");
        assertThat(cycle.get("liveOrder").isBoolean()).isTrue();
        assertThat(cycle.get("liveOrder").asBoolean()).isFalse();
        assertThat(Instant.parse(cycle.get("createdAt").asText())).isNotNull();
        assertThat(cycle.get("instrumentId").isIntegralNumber()).isTrue();
        assertThat(cycle.get("stages").isArray()).isTrue();
        assertThat(cycle.get("reasons").isArray()).isTrue();
        assertThat(cycle.get("dataLabel").isTextual()).isTrue();
        assertThat(result.get("availableCashPaise").isIntegralNumber()).isTrue();
        assertThat(result.get("realizedPnlPaise").isIntegralNumber()).isTrue();

        var history = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/trading-rooms/" + room + "/mind/paper-cycles").with(customerJwt))
            .andReturn().getResponse();
        assertThat(json.readTree(history.getContentAsString()).isArray()).isTrue();

        // Another customer cannot run or read cycles in this room.
        String otherSubject = "mind-other-" + UUID.randomUUID();
        var other = identities.provisionVerifiedIdentity("cognito", issuer, otherSubject, otherSubject,
            otherSubject + "@example.test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id").param("id", other.id()).update();
        var otherJwt = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .jwt().jwt(j -> j.issuer(issuer).subject(otherSubject))
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"));
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/trading-rooms/" + room + "/mind/paper-cycles").with(otherJwt))
            .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void strategyCandidateBecomesOneVirtualFillWithPositionsPnlAndRuntimeEvidence() {
        long instrument = instrument("RELIANCE-EQ", "2885");
        seedRisingCandles(instrument, "RELIANCE-EQ", "2885", SOURCE);
        recordTick(instrument, "105.85", NOW.minusSeconds(2));
        UUID owner = customer();
        UUID room = fundedRoom(owner);
        var progress = progress();
        var service = service(progress, SOURCE);

        var first = service.run(owner, room, "cycle_key_0001", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE);
        assertThat(first.cycle().outcome()).as(first.cycle().stages().toString()).isEqualTo(Outcome.FILLED);
        assertThat(first.cycle().signalAction()).isEqualTo("BUY");
        assertThat(first.cycle().side()).isEqualTo("BUY");
        assertThat(first.cycle().quoteLtp()).isEqualByComparingTo("105.85");
        assertThat(first.cycle().liveOrder()).isFalse();
        assertThat(first.cycle().dataLabel()).contains(SOURCE).contains("VERIFIED_LIVE_TICK");
        // 20% exposure cap on a Rs 20,000 wallet at Rs 105.85 => 37 shares.
        assertThat(first.cycle().quantity()).isEqualTo(37);
        assertThat(first.order()).isNotNull();
        assertThat(first.order().fillPrice()).isEqualByComparingTo("105.85");
        assertThat(first.holdings()).singleElement().satisfies(h -> {
            assertThat(h.quantity()).isEqualTo(37);
            assertThat(h.unrealizedPnlPaise()).isNegative(); // fees only
        });
        assertThat(first.availableCashPaise()).isEqualTo(2_000_000L - first.order().notionalPaise() - first.order().feesPaise());
        assertThat(MindPaperCycleService.stageResults(first.cycle()))
            .containsEntry("DATA_GATE", "PASSED").containsEntry("PAPER_ORDER", "FILLED");

        // Same request key: same cycle, no new order.
        var replay = service.run(owner, room, "cycle_key_0001", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE);
        assertThat(replay.cycle().id()).isEqualTo(first.cycle().id());
        // New request on the same candle with an open position: hold, no pyramiding.
        var again = service.run(owner, room, "cycle_key_0002", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE);
        assertThat(again.cycle().outcome()).isEqualTo(Outcome.NO_ENTRY);
        assertThat(again.cycle().reasons()).contains("POSITION_ALREADY_OPEN");
        assertThat(countOrders(room)).isEqualTo(1);
        assertThat(service.recent(owner, room)).hasSize(2);

        // Runtime evidence makes the PAPER slice DONE on this known revision only.
        var slice = progress.detail(owner, "paper.vertical-slice");
        assertThat(slice.item().status()).isEqualTo(Status.RUNTIME_VERIFIED);
        assertThat(slice.item().runtime().evidenceRef()).isEqualTo("mind-paper-cycle:" + first.cycle().id());
        assertThat(progress.detail(owner, "strategy.STR-001").item().status()).isEqualTo(Status.RUNTIME_VERIFIED);
        assertThat(progressAt("unknown").detail(owner, "paper.vertical-slice").item().status())
            .isEqualTo(Status.TESTED);
        // Screens are never verified by an engine run.
        assertThat(progress.detail(owner, "screen.room-detail").item().done()).isFalse();

        // Protective exit: verified LTP falls through the entry stop -> full SELL, realized loss.
        recordTick(instrument, "104.00", NOW.minusSeconds(1));
        var exit = service.run(owner, room, "cycle_key_0003", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE);
        assertThat(exit.cycle().outcome()).as(exit.cycle().stages().toString()).isEqualTo(Outcome.FILLED);
        assertThat(exit.cycle().side()).isEqualTo("SELL");
        assertThat(exit.cycle().reasons()).contains("EXIT_STOP_HIT");
        assertThat(exit.holdings()).isEmpty();
        assertThat(exit.realizedPnlPaise()).isNegative();
        assertThat(countOrders(room)).isEqualTo(2);
    }

    @Test
    void staleQuoteNonPermittedSourceAndLiveRoomsFailClosedWithoutOrders() {
        long instrument = instrument("SBIN-EQ", "3045");
        seedRisingCandles(instrument, "SBIN-EQ", "3045", SOURCE);
        UUID owner = customer();
        UUID room = fundedRoom(owner);
        var service = service(progress(), SOURCE);

        recordTick(instrument, "105.85", NOW.minusSeconds(40));
        var stale = service.run(owner, room, "stale_key_001", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE);
        assertThat(stale.cycle().outcome()).isEqualTo(Outcome.QUOTE_UNAVAILABLE);
        assertThat(stale.cycle().reasons()).contains("FRESH_QUOTE_UNAVAILABLE");

        recordTick(instrument, "112.00", NOW.minusSeconds(1));
        var diverged = service.run(owner, room, "diverge_key_1", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE);
        assertThat(diverged.cycle().outcome()).isEqualTo(Outcome.NO_ENTRY);
        assertThat(diverged.cycle().reasons()).contains("QUOTE_DIVERGED_FROM_SIGNAL");

        recordTick(instrument, "105.85", NOW.minusSeconds(1));
        var notPermitted = service(progress(), "UPSTOX_ONLY")
            .run(owner, room, "rights_key_01", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE);
        assertThat(notPermitted.cycle().outcome()).isEqualTo(Outcome.DATA_BLOCKED);
        assertThat(notPermitted.cycle().reasons()).contains("CANDLE_SOURCE_NOT_PERMITTED");

        var noHistory = service.run(owner, room, "history_key1", instrument, HistoricalCandleInterval.ONE_HOUR);
        assertThat(noHistory.cycle().outcome()).isEqualTo(Outcome.DATA_BLOCKED);
        assertThat(noHistory.cycle().reasons()).contains("INSUFFICIENT_HISTORY");

        assertThat(countOrders(room)).isZero();
        assertThat(wallets.get(owner, room).availableCashPaise()).isEqualTo(2_000_000L);

        assertThatThrownBy(() -> service.run(owner, room, "stale_key_001", instrument, HistoricalCandleInterval.ONE_HOUR))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Idempotency");
        UUID live = room(owner, "LIVE");
        assertThatThrownBy(() -> service.run(owner, live, "live_room_key", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE))
            .isInstanceOf(ResponseStatusException.class);
        UUID stranger = customer();
        assertThatThrownBy(() -> service.recent(stranger, room)).isInstanceOf(ResponseStatusException.class);
    }

    // ------------------------------------------------------------------ fixtures

    private MindCapabilityProgressService progress() { return progressAt(REV); }

    private MindCapabilityProgressService progressAt(String revision) {
        return new MindCapabilityProgressService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), revision,
            MindCapabilityProgressService.loadManifest());
    }

    private MindPaperCycleService service(MindCapabilityProgressService progress, String permitted) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var strategy = new StrategyService(instruments, candles, quality, signals, clock,
            5, 20, new BigDecimal("2"), new BigDecimal("0.5"), 1440);
        var orders = new CustomerPaperOrderService(jdbc, instruments, lastTicks, calendar, clock);
        return new MindPaperCycleService(jdbc, instruments, strategy, orders, wallets, lastTicks, calendar,
            progress, clock, permitted);
    }

    private void seedRisingCandles(long instrumentId, String symbol, String token, String source) {
        List<Instant> slots = new ArrayList<>();
        for (LocalDate day : List.of(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 12))) {
            for (ExpectedCandleSlot slot : calendar.expectedSlots(day, HistoricalCandleInterval.FIFTEEN_MINUTE)) {
                // Completed candles only: the slot must have closed before NOW.
                if (!slot.timestamp().plusSeconds(900).isAfter(NOW)) slots.add(slot.timestamp());
            }
        }
        assertThat(slots.size()).isGreaterThan(21);
        for (int i = 0; i < slots.size(); i++) {
            BigDecimal close = new BigDecimal("100.00").add(new BigDecimal("0.20").multiply(BigDecimal.valueOf(i - slots.size() + 30)));
            jdbc.sql("""
                    INSERT INTO market_candle
                      (instrument_id,exchange,trading_symbol,symbol_token,interval,candle_time,
                       open_price,high_price,low_price,close_price,volume,source,fetched_at)
                    VALUES (:id,'NSE',:symbol,:token,'FIFTEEN_MINUTE',:at,:open,:high,:low,:close,:volume,:source,:at)
                    ON CONFLICT (exchange,symbol_token,interval,candle_time) DO UPDATE SET
                        instrument_id=EXCLUDED.instrument_id, trading_symbol=EXCLUDED.trading_symbol,
                        open_price=EXCLUDED.open_price, high_price=EXCLUDED.high_price,
                        low_price=EXCLUDED.low_price, close_price=EXCLUDED.close_price,
                        volume=EXCLUDED.volume, source=EXCLUDED.source
                    """).param("id", instrumentId).param("symbol", symbol).param("token", token)
                .param("at", java.sql.Timestamp.from(slots.get(i)))
                .param("open", close.subtract(new BigDecimal("0.10")))
                .param("high", close.add(new BigDecimal("0.30")))
                .param("low", close.subtract(new BigDecimal("0.30")))
                .param("close", close).param("volume", 10_000L + i * 100L).param("source", source).update();
        }
    }

    /**
     * Imports exactly the shared IT catalogue (RELIANCE, SBIN-EQ, SBIN-AF). The instrument master
     * refuses catalogues that retain < 80% of the active one, so adding Mind-only rows here would
     * break other integration tests' imports.
     */
    private long instrument(String symbol, String token) {
        String data = """
                [
                  {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE",
                   "expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ",
                   "exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"3045","symbol":"SBIN-EQ","name":"SBIN",
                   "expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ",
                   "exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"11128","symbol":"SBIN-AF","name":"SBIN",
                   "expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ",
                   "exch_seg":"nse_cm","tick_size":"5"}
                ]
                """;
        assertThat(instruments.importSource(data.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo("SUCCEEDED");
        var found = instruments.activeInstrumentBySymbol("NSE", symbol).orElseThrow();
        assertThat(found.symbolToken()).isEqualTo(token);
        return found.id();
    }

    private UUID customer() {
        String subject = UUID.randomUUID().toString();
        var user = users.createUser("mind-paper-" + subject, "Mind paper", "mind-paper-" + subject + "@example.test",
            "test:customer");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id").param("id", user.id()).update();
        return user.id();
    }

    private UUID fundedRoom(UUID owner) {
        UUID room = room(owner, "PAPER");
        wallets.activate(owner, room);
        wallets.setPaused(owner, room, false);
        return room;
    }

    private UUID room(UUID owner, String mode) {
        UUID room = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_trading_room
                    (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,status)
                VALUES (:id,:user,:name,'INTRADAY',20000,'BALANCED',:mode,'DRAFT')
                """).param("id", room).param("user", owner).param("name", "Mind-" + room)
            .param("mode", mode).update();
        return room;
    }

    private void recordTick(long instrument, String price, Instant when) {
        jdbc.sql("""
                INSERT INTO market_last_tick (instrument_id,session_date,ltp,exchange_at,updated_at)
                VALUES (:id,DATE '2026-11-12',:ltp,:time,NOW())
                ON CONFLICT (instrument_id) DO UPDATE SET ltp=EXCLUDED.ltp,exchange_at=EXCLUDED.exchange_at,updated_at=NOW()
                """).param("id", instrument).param("ltp", new BigDecimal(price))
            .param("time", java.sql.Timestamp.from(when)).update();
    }

    private int countOrders(UUID room) {
        return jdbc.sql("SELECT count(*) FROM customer_paper_manual_order WHERE room_id=:room")
            .param("room", room).query(Integer.class).single();
    }
}
