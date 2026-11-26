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
import java.time.ZoneId;
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
        // Summary contract (issue #998): totals from the full inventory, never a page.
        assertThat(progressJson.get("contractVersion").asText()).isEqualTo("mind.progress.v2");
        assertThat(Instant.parse(progressJson.get("generatedAt").asText())).isNotNull();
        assertThat(progressJson.get("revisionKnown").isBoolean()).isTrue();
        assertThat(progressJson.get("paperStage").get("status").isTextual()).isTrue();
        var overall = progressJson.get("overall");
        assertThat(overall.get("total").asInt()).isPositive();
        assertThat(overall.get("done").asInt() + overall.get("pending").asInt()).isEqualTo(overall.get("total").asInt());
        assertThat(progressJson.get("inventoryPath").asText()).isEqualTo("/api/customer/mind/v1/progress/inventory");
        var summary = progressJson.get("summaries").get(0);
        for (String field : List.of("kind", "denominator", "done", "tested", "inProgress", "blocked", "mock",
                "notStarted", "failed")) {
            assertThat(summary.has(field)).as(field).isTrue();
        }
        // Inventory page contract: full items, bounded page size, flutter-required fields present.
        var pageResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/mind/v1/progress/inventory?offset=0&limit=5").with(customerJwt))
            .andReturn().getResponse();
        assertThat(pageResponse.getStatus()).isEqualTo(200);
        var pageJson = json.readTree(pageResponse.getContentAsString());
        assertThat(pageJson.get("contractVersion").asText()).isEqualTo("mind.progress-inventory.v1");
        var firstItem = pageJson.get("items").get(0);
        for (String field : List.of("id", "kind", "name", "owner", "status", "declaredStatus", "done", "runtime")) {
            assertThat(firstItem.has(field)).as(field).isTrue();
        }
        assertThat(firstItem.get("done").isBoolean()).isTrue();

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

        var orbRun = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/customer/trading-rooms/" + room + "/mind/paper-cycles").with(customerJwt)
                .header("Idempotency-Key", "http_orb_cycle_01").contentType("application/json")
                .content("{\"instrumentId\":" + instrument
                    + ",\"interval\":\"FIVE_MINUTE\",\"strategyCode\":\"OPENING_RANGE_BREAKOUT_V1\"}"))
            .andReturn().getResponse();
        assertThat(orbRun.getStatus()).as(orbRun.getContentAsString()).isEqualTo(200);
        var orbCycle = json.readTree(orbRun.getContentAsString()).get("cycle");
        assertThat(orbCycle.get("strategyCode").asText()).isEqualTo(MindPaperCycleService.ORB_STRATEGY_CODE);
        assertThat(orbCycle.get("liveOrder").asBoolean()).isFalse();

        var portfolioBody = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/mind/v1/paper-portfolio").with(customerJwt)).andReturn().getResponse();
        assertThat(portfolioBody.getStatus()).isEqualTo(200);
        var portfolioJson = json.readTree(portfolioBody.getContentAsString());
        assertThat(portfolioJson.get("contractVersion").asText()).isEqualTo("mind.paper-portfolio.v1");
        assertThat(portfolioJson.get("liveMoney").isBoolean()).isTrue();
        assertThat(portfolioJson.get("liveMoney").asBoolean()).isFalse();
        assertThat(Instant.parse(portfolioJson.get("generatedAt").asText())).isNotNull();
        assertThat(portfolioJson.get("initialCashPaise").isIntegralNumber()).isTrue();
        assertThat(portfolioJson.get("rooms").get(0).get("paused").isBoolean()).isTrue();
        assertThat(portfolioJson.get("riskPolicy").get("perTradeRiskBudget").isTextual()).isTrue();

        var journalBody = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/mind/v1/paper-journal").with(customerJwt)).andReturn().getResponse();
        assertThat(journalBody.getStatus()).isEqualTo(200);
        var journalJson = json.readTree(journalBody.getContentAsString());
        assertThat(journalJson.get("contractVersion").asText()).isEqualTo("mind.paper-journal.v1");
        assertThat(journalJson.get("liveMoney").asBoolean(true)).isFalse();
        assertThat(journalJson.get("cycles").isArray()).isTrue();
        assertThat(journalJson.get("performance").get("sampleTooSmall").isBoolean()).isTrue();

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
    void swingFocusBlocksIntradayMindAndFnoScopeBlocksEveryCashMindCycle() {
        long instrument = instrument("RELIANCE-EQ", "2885");
        UUID owner = customer();
        UUID room = fundedRoom(owner);
        var service = service(progress(), SOURCE);

        jdbc.sql("UPDATE customer_trading_room SET focus='SWING' WHERE id=:room")
            .param("room", room).update();
        assertThatThrownBy(() -> service.run(owner, room, "swing_15min_001",
                instrument, HistoricalCandleInterval.FIFTEEN_MINUTE))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not allowed by room focus");
        assertThat(countOrders(room)).isZero();

        // A daily Swing evaluation can proceed through its normal data gate;
        // absence of candles does not produce any trade or fake signal.
        var daily = service.run(owner, room, "swing_daily_01",
                instrument, HistoricalCandleInterval.ONE_DAY);
        assertThat(daily.cycle().outcome()).isEqualTo(Outcome.DATA_BLOCKED);
        assertThat(countOrders(room)).isZero();

        jdbc.sql("UPDATE customer_trading_room SET market_scope='FNO' WHERE id=:room")
            .param("room", room).update();
        assertThatThrownBy(() -> service.run(owner, room, "fno_cash_mind_01",
                instrument, HistoricalCandleInterval.ONE_DAY))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not allowed in an F&O-only room");
        assertThat(countOrders(room)).isZero();
        assertThat(wallets.get(owner, room).availableCashPaise()).isEqualTo(2_000_000L);
    }

    @Test
    void intradayFocusRejectsDailyAndUndecidedFocusCannotExecuteMind() {
        long instrument = instrument("RELIANCE-EQ", "2885");
        UUID owner = customer();
        UUID room = fundedRoom(owner);
        var service = service(progress(), SOURCE);
        assertThatThrownBy(() -> service.run(owner, room, "intraday_daily_01",
                instrument, HistoricalCandleInterval.ONE_DAY))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not allowed by room focus");
        assertThatThrownBy(() -> service.run(owner, room, "sma_one_minute_01",
                instrument, HistoricalCandleInterval.ONE_MINUTE, MindPaperCycleService.STRATEGY_CODE))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not allowed by room focus");
        assertThatThrownBy(() -> service.run(owner, room, "orb_one_day_001",
                instrument, HistoricalCandleInterval.ONE_DAY, MindPaperCycleService.ORB_STRATEGY_CODE))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not allowed by room focus");
        jdbc.sql("UPDATE customer_trading_room SET focus='HELP_ME_CHOOSE' WHERE id=:room")
            .param("room", room).update();
        assertThatThrownBy(() -> service.run(owner, room, "undecided_cycle_01",
                instrument, HistoricalCandleInterval.FIFTEEN_MINUTE))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not allowed by room focus");
        assertThat(countOrders(room)).isZero();
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
        // Open position is marked with the fresh verified tick, never a candle close.
        var open = portfolio().inspect(owner);
        assertThat(open.positions()).singleElement().satisfies(p -> {
            assertThat(p.tradingSymbol()).isEqualTo("RELIANCE-EQ");
            assertThat(p.markState()).isEqualTo("FRESH");
            assertThat(p.markPrice()).isEqualByComparingTo("105.85");
            assertThat(p.unrealizedPnlPaise()).isNegative();
        });
        assertThat(open.grossExposurePercent()).isBetween(19.0, 20.0);
        assertThat(open.unrealizedPnlPaise()).isEqualTo(open.positions().getFirst().unrealizedPnlPaise());
        var again = service.run(owner, room, "cycle_key_0002", instrument, HistoricalCandleInterval.FIFTEEN_MINUTE);
        assertThat(again.cycle().outcome()).isEqualTo(Outcome.NO_ENTRY);
        assertThat(again.cycle().reasons()).contains("POSITION_ALREADY_OPEN");
        assertThat(countOrders(room)).isEqualTo(1);
        assertThat(service.recent(owner, room)).hasSize(2);
        // Regression: Mind candidates must never reach the legacy automated engine's signal table.
        assertThat(jdbc.sql("SELECT count(*) FROM strategy_signal WHERE id::text=:id")
            .param("id", first.cycle().signalId()).query(Integer.class).single()).isZero();

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

        // Portfolio aggregate: realized loss carried, no open positions after the exit.
        var portfolio = portfolio().inspect(owner);
        assertThat(portfolio.liveMoney()).isFalse();
        assertThat(portfolio.fundedRooms()).isEqualTo(1);
        assertThat(portfolio.realizedPnlPaise()).isEqualTo(exit.realizedPnlPaise());
        assertThat(portfolio.positions()).isEmpty();
        assertThat(portfolio.grossExposurePercent()).isZero();

        // Journal: 3 cycles for this owner, one closed round trip with its realized loss.
        var journal = service.journal(owner, 50);
        assertThat(journal.liveMoney()).isFalse();
        assertThat(journal.cycles()).hasSize(3);
        assertThat(journal.performance().fills()).isEqualTo(2);
        assertThat(journal.performance().closedTrades()).isEqualTo(1);
        assertThat(journal.performance().losses()).isEqualTo(1);
        assertThat(journal.performance().realizedPnlPaise()).isEqualTo(exit.realizedPnlPaise());
        assertThat(journal.performance().sampleTooSmall()).isTrue();
        assertThat(service.journal(customer(), 50).cycles()).isEmpty();
    }

    @Test
    void volumeBreakoutCandidateUsesTheGuardedPaperEngine() {
        long instrumentId = instrument("SBIN-EQ", "3045");
        seedVolumeBreakoutCandles(instrumentId, "SBIN-EQ", "3045");
        recordTick(instrumentId, "101.00", NOW.minusSeconds(2));
        UUID owner = customer();
        UUID room = fundedRoom(owner);

        var entry = service(progress(), SOURCE).run(owner, room, "volume_breakout_entry01", instrumentId,
            HistoricalCandleInterval.ONE_MINUTE, MindPaperCycleService.VOLUME_BREAKOUT_STRATEGY_CODE);

        assertThat(entry.cycle().outcome()).as(entry.cycle().stages().toString()).isEqualTo(Outcome.FILLED);
        assertThat(entry.cycle().strategyCode()).isEqualTo(MindPaperCycleService.VOLUME_BREAKOUT_STRATEGY_CODE);
        assertThat(entry.cycle().signalAction()).isEqualTo("BUY");
        assertThat(entry.cycle().reasons()).contains("CLOSE_ABOVE_PRIOR_20_BAR_HIGH",
            "VOLUME_AT_LEAST_1_5X_AVERAGE");
        assertThat(entry.cycle().liveOrder()).isFalse();
        assertThat(entry.order()).isNotNull();
        assertThat(countOrders(room)).isEqualTo(1);
    }

    @Test
    void movingAverageReclaimCandidateUsesTheGuardedPaperEngine() {
        long instrumentId = instrument("SBIN-AF", "11128");
        seedMovingAverageReclaimCandles(instrumentId, "SBIN-AF", "11128");
        recordTick(instrumentId, "103.00", NOW.minusSeconds(2));
        UUID owner = customer();
        UUID room = fundedRoom(owner);

        var entry = service(progress(), SOURCE).run(owner, room, "ma_reclaim_entry01", instrumentId,
            HistoricalCandleInterval.ONE_MINUTE, MindPaperCycleService.MA_RECLAIM_STRATEGY_CODE);

        assertThat(entry.cycle().outcome()).as(entry.cycle().stages().toString()).isEqualTo(Outcome.FILLED);
        assertThat(entry.cycle().strategyCode()).isEqualTo(MindPaperCycleService.MA_RECLAIM_STRATEGY_CODE);
        assertThat(entry.cycle().signalAction()).isEqualTo("BUY");
        assertThat(entry.cycle().reasons()).contains("LATEST_CLOSE_RECLAIMED_SLOW_SMA", "FAST_SMA_ABOVE_SLOW_SMA");
        assertThat(entry.cycle().liveOrder()).isFalse();
        assertThat(entry.order()).isNotNull();
        assertThat(countOrders(room)).isEqualTo(1);
    }

    @Test
    void vwapMomentumCandidateUsesTheGuardedPaperEngine() {
        long instrumentId = instrument("RELIANCE-EQ", "2885");
        seedVwapCandles(instrumentId, "RELIANCE-EQ", "2885");
        recordTick(instrumentId, "101.00", NOW.minusSeconds(2));
        UUID owner = customer();
        UUID room = fundedRoom(owner);

        var entry = service(progress(), SOURCE).run(owner, room, "vwap_paper_entry01", instrumentId,
            HistoricalCandleInterval.ONE_MINUTE, MindPaperCycleService.VWAP_STRATEGY_CODE);

        assertThat(entry.cycle().outcome()).as(entry.cycle().stages().toString()).isEqualTo(Outcome.FILLED);
        assertThat(entry.cycle().strategyCode()).isEqualTo(MindPaperCycleService.VWAP_STRATEGY_CODE);
        assertThat(entry.cycle().signalAction()).isEqualTo("BUY");
        assertThat(entry.cycle().reasons()).contains("CLOSE_ABOVE_ROLLING_VWAP", "VOLUME_CONFIRMED");
        assertThat(entry.cycle().liveOrder()).isFalse();
        assertThat(entry.order()).isNotNull();
        assertThat(countOrders(room)).isEqualTo(1);
    }

    @Test
    void rsiReversalCandidateUsesTheGuardedPaperEngine() {
        long instrumentId = instrument("SBIN-EQ", "3045");
        seedRsiRecoveryCandles(instrumentId, "SBIN-EQ", "3045");
        recordTick(instrumentId, "105.00", NOW.minusSeconds(2));
        UUID owner = customer();
        UUID room = fundedRoom(owner);
        var entry = service(progress(), SOURCE).run(owner, room, "rsi_reversal_entry01", instrumentId,
            HistoricalCandleInterval.ONE_MINUTE, MindPaperCycleService.RSI_REVERSAL_STRATEGY_CODE);
        assertThat(entry.cycle().outcome()).as(entry.cycle().stages().toString()).isEqualTo(Outcome.FILLED);
        assertThat(entry.cycle().strategyCode()).isEqualTo(MindPaperCycleService.RSI_REVERSAL_STRATEGY_CODE);
        assertThat(entry.cycle().reasons()).contains("RSI_RECOVERED_ABOVE_30", "PRICE_RECOVERY_CONFIRMED");
        assertThat(entry.cycle().liveOrder()).isFalse();
        assertThat(countOrders(room)).isEqualTo(1);
    }

    @Test
    void orbCandidateUsesTheGuardedPaperEngineAndProtectiveExitWorksWithoutNewSignal() {
        long instrumentId = instrument("RELIANCE-EQ", "2885");
        seedOrbCandles(instrumentId, "RELIANCE-EQ", "2885");
        recordTick(instrumentId, "101.00", NOW.minusSeconds(2));
        UUID owner = customer();
        UUID room = fundedRoom(owner);
        var service = service(progress(), SOURCE);

        var entry = service.run(owner, room, "orb_paper_entry1", instrumentId,
            HistoricalCandleInterval.FIVE_MINUTE, MindPaperCycleService.ORB_STRATEGY_CODE);
        assertThat(entry.cycle().outcome()).as(entry.cycle().stages().toString()).isEqualTo(Outcome.FILLED);
        assertThat(entry.cycle().strategyCode()).isEqualTo(MindPaperCycleService.ORB_STRATEGY_CODE);
        assertThat(entry.cycle().signalAction()).isEqualTo("BUY");
        assertThat(entry.cycle().side()).isEqualTo("BUY");
        assertThat(entry.cycle().stopPrice()).isEqualByComparingTo("100.50");
        assertThat(entry.cycle().targetPrice()).isEqualByComparingTo("102.00");
        assertThat(entry.cycle().liveOrder()).isFalse();
        assertThat(entry.cycle().reasons()).contains("CLOSE_ABOVE_OPENING_RANGE", "OPENING_VOLUME_CONFIRMED");
        assertThat(entry.order()).isNotNull();
        assertThat(countOrders(room)).isEqualTo(1);
        assertThat(progress().detail(owner, "strategy.STR-024").item().status())
            .isEqualTo(Status.RUNTIME_VERIFIED);
        assertThatThrownBy(() -> service.run(owner, room, "orb_paper_entry1", instrumentId,
                HistoricalCandleInterval.FIVE_MINUTE, MindPaperCycleService.STRATEGY_CODE))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Idempotency");
        var switchedStrategy = service.run(owner, room, "orb_paper_switch1", instrumentId,
            HistoricalCandleInterval.FIVE_MINUTE);
        assertThat(switchedStrategy.cycle().outcome()).isEqualTo(Outcome.NO_ENTRY);
        assertThat(switchedStrategy.cycle().reasons()).contains("POSITION_STRATEGY_MISMATCH");
        assertThat(countOrders(room)).isEqualTo(1);

        // Invalidate the latest breakout bar. A held position still evaluates the independent
        // stop gate instead of becoming stranded when the strategy no longer emits a candidate.
        jdbc.sql("""
                UPDATE market_candle SET high_price=100.40, close_price=100.30, volume=1000
                WHERE instrument_id=:id AND interval='FIVE_MINUTE' AND candle_time=:at
                """)
            .param("id", instrumentId)
            .param("at", java.sql.Timestamp.from(LocalDate.of(2026, 11, 12).atTime(10, 25)
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant())).update();
        recordTick(instrumentId, "100.40", NOW.minusSeconds(1));
        var exit = service.run(owner, room, "orb_paper_exit001", instrumentId,
            HistoricalCandleInterval.FIVE_MINUTE, MindPaperCycleService.ORB_STRATEGY_CODE);
        assertThat(exit.cycle().outcome()).isEqualTo(Outcome.FILLED);
        assertThat(exit.cycle().side()).isEqualTo("SELL");
        assertThat(exit.cycle().reasons()).contains("EXIT_STOP_HIT");
        assertThat(exit.cycle().liveOrder()).isFalse();
        assertThat(exit.holdings()).isEmpty();
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

    private MindPaperPortfolioService portfolio() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new MindPaperPortfolioService(jdbc, wallets,
            new CustomerPaperOrderService(jdbc, instruments, lastTicks, calendar, clock), lastTicks, instruments, clock);
    }

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
            progress, clock, null, permitted, candles, quality,
            new in.aitradingsystem.scanner.OpeningRangeBreakoutStrategy(),
            new in.aitradingsystem.scanner.VwapMomentumStrategy(),
            new in.aitradingsystem.scanner.MovingAverageReclaimStrategy(),
            new in.aitradingsystem.scanner.VolumeBreakoutStrategy(),
            new in.aitradingsystem.scanner.RsiReversalStrategy());
    }

    private void seedRsiRecoveryCandles(long instrumentId, String symbol, String token) {
        var active = instruments.requireActiveInstrument(instrumentId);
        Instant fetchedAt = NOW.minusSeconds(30);
        for (int index = 0; index < 21; index++) {
            Instant candleTime = LocalDate.of(2026, 11, 12).atTime(10, 9).plusMinutes(index)
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            BigDecimal close = BigDecimal.valueOf(index <= 5 ? 100 : index < 20 ? 105 - index : 105);
            jdbc.sql("""
                    INSERT INTO market_candle
                      (instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,interval,candle_time,
                       open_price,high_price,low_price,close_price,volume,source,fetched_at)
                    VALUES (:id,'NSE',:symbol,:token,:catalogue,'ONE_MINUTE',:at,
                       :open,:high,:low,:close,1000,:source,:fetched)
                    """)
                .param("id", instrumentId).param("symbol", symbol).param("token", token)
                .param("catalogue", active.catalogueVersion()).param("at", java.sql.Timestamp.from(candleTime))
                .param("open", close).param("high", close.add(new BigDecimal("0.50")))
                .param("low", close.subtract(new BigDecimal("0.50"))).param("close", close)
                .param("source", SOURCE).param("fetched", java.sql.Timestamp.from(fetchedAt)).update();
        }
    }

    private void seedOrbCandles(long instrumentId, String symbol, String token) {
        var active = instruments.requireActiveInstrument(instrumentId);
        var catalogue = active.catalogueVersion();
        Instant fetchedAt = NOW.minusSeconds(30);
        for (int index = 0; index < 15; index++) {
            Instant candleTime = LocalDate.of(2026, 11, 12).atTime(9, 15).plusMinutes(index * 5L)
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            BigDecimal open = new BigDecimal("100.10");
            BigDecimal close = index == 14 ? new BigDecimal("101.00")
                : index < 3 ? new BigDecimal("100.20") : new BigDecimal("100.30");
            BigDecimal high = index < 3 ? new BigDecimal("100.50")
                : index == 14 ? new BigDecimal("101.10") : new BigDecimal("100.40");
            BigDecimal low = new BigDecimal("100.00");
            long volume = index == 14 ? 2_000L : 1_000L;
            jdbc.sql("""
                    INSERT INTO market_candle
                      (instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,interval,candle_time,
                       open_price,high_price,low_price,close_price,volume,source,fetched_at)
                    VALUES (:id,'NSE',:symbol,:token,:catalogue,'FIVE_MINUTE',:at,
                       :open,:high,:low,:close,:volume,:source,:fetched)
                    """)
                .param("id", instrumentId).param("symbol", symbol).param("token", token)
                .param("catalogue", catalogue).param("at", java.sql.Timestamp.from(candleTime))
                .param("open", open).param("high", high).param("low", low).param("close", close)
                .param("volume", volume).param("source", SOURCE)
                .param("fetched", java.sql.Timestamp.from(fetchedAt)).update();
        }
    }

    private void seedVolumeBreakoutCandles(long instrumentId, String symbol, String token) {
        var active = instruments.requireActiveInstrument(instrumentId);
        Instant fetchedAt = NOW.minusSeconds(30);
        for (int index = 0; index < 21; index++) {
            Instant candleTime = LocalDate.of(2026, 11, 12).atTime(10, 9).plusMinutes(index)
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            boolean latest = index == 20;
            BigDecimal open = latest ? new BigDecimal("100.80") : new BigDecimal("100.00");
            BigDecimal high = latest ? new BigDecimal("101.20") : new BigDecimal("100.20");
            BigDecimal low = latest ? new BigDecimal("100.70") : new BigDecimal("99.80");
            BigDecimal close = latest ? new BigDecimal("101.00") : new BigDecimal("100.00");
            jdbc.sql("""
                    INSERT INTO market_candle
                      (instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,interval,candle_time,
                       open_price,high_price,low_price,close_price,volume,source,fetched_at)
                    VALUES (:id,'NSE',:symbol,:token,:catalogue,'ONE_MINUTE',:at,
                       :open,:high,:low,:close,:volume,:source,:fetched)
                    """)
                .param("id", instrumentId).param("symbol", symbol).param("token", token)
                .param("catalogue", active.catalogueVersion()).param("at", java.sql.Timestamp.from(candleTime))
                .param("open", open).param("high", high).param("low", low).param("close", close)
                .param("volume", latest ? 2_000L : 1_000L).param("source", SOURCE)
                .param("fetched", java.sql.Timestamp.from(fetchedAt)).update();
        }
    }

    private void seedMovingAverageReclaimCandles(long instrumentId, String symbol, String token) {
        var active = instruments.requireActiveInstrument(instrumentId);
        Instant fetchedAt = NOW.minusSeconds(30);
        for (int index = 0; index < 21; index++) {
            Instant candleTime = LocalDate.of(2026, 11, 12).atTime(10, 9).plusMinutes(index)
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            boolean previous = index == 19;
            boolean latest = index == 20;
            BigDecimal open = previous ? new BigDecimal("99.20")
                : latest ? new BigDecimal("102.50") : new BigDecimal("100.00");
            BigDecimal high = latest ? new BigDecimal("103.20") : new BigDecimal("100.20");
            BigDecimal low = previous ? new BigDecimal("98.50") : latest ? new BigDecimal("102.00")
                : new BigDecimal("99.80");
            BigDecimal close = previous ? new BigDecimal("99.00")
                : latest ? new BigDecimal("103.00") : new BigDecimal("100.00");
            jdbc.sql("""
                    INSERT INTO market_candle
                      (instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,interval,candle_time,
                       open_price,high_price,low_price,close_price,volume,source,fetched_at)
                    VALUES (:id,'NSE',:symbol,:token,:catalogue,'ONE_MINUTE',:at,
                       :open,:high,:low,:close,1000,:source,:fetched)
                    """)
                .param("id", instrumentId).param("symbol", symbol).param("token", token)
                .param("catalogue", active.catalogueVersion()).param("at", java.sql.Timestamp.from(candleTime))
                .param("open", open).param("high", high).param("low", low).param("close", close)
                .param("source", SOURCE).param("fetched", java.sql.Timestamp.from(fetchedAt)).update();
        }
    }

    private void seedVwapCandles(long instrumentId, String symbol, String token) {
        var active = instruments.requireActiveInstrument(instrumentId);
        Instant fetchedAt = NOW.minusSeconds(30);
        for (int index = 0; index < 20; index++) {
            Instant candleTime = LocalDate.of(2026, 11, 12).atTime(10, 10).plusMinutes(index)
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            boolean latest = index == 19;
            BigDecimal open = latest ? new BigDecimal("100.80") : new BigDecimal("100.00");
            BigDecimal high = latest ? new BigDecimal("101.20") : new BigDecimal("100.20");
            BigDecimal low = latest ? new BigDecimal("100.70") : new BigDecimal("99.80");
            BigDecimal close = latest ? new BigDecimal("101.00") : new BigDecimal("100.00");
            jdbc.sql("""
                    INSERT INTO market_candle
                      (instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,interval,candle_time,
                       open_price,high_price,low_price,close_price,volume,source,fetched_at)
                    VALUES (:id,'NSE',:symbol,:token,:catalogue,'ONE_MINUTE',:at,
                       :open,:high,:low,:close,:volume,:source,:fetched)
                    """)
                .param("id", instrumentId).param("symbol", symbol).param("token", token)
                .param("catalogue", active.catalogueVersion()).param("at", java.sql.Timestamp.from(candleTime))
                .param("open", open).param("high", high).param("low", low).param("close", close)
                .param("volume", latest ? 2_000L : 1_000L).param("source", SOURCE)
                .param("fetched", java.sql.Timestamp.from(fetchedAt)).update();
        }
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
