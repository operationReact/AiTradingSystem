package in.aitradingsystem.mind;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.mind.MindCapabilityProgressService.EventType;
import in.aitradingsystem.strategy.StrategyEvaluation;
import in.aitradingsystem.strategy.StrategyService;
import in.aitradingsystem.strategy.StrategySignal;
import in.aitradingsystem.tradingrooms.CustomerPaperOrderService;
import in.aitradingsystem.tradingrooms.CustomerPaperWalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * First end-to-end Mintick Mind PAPER vertical slice (issue #998 PR C).
 *
 * <pre>
 * permitted stored candles + quality gate (StrategyService)
 *   -> deterministic SMA_TREND_VOLUME_V1 candidate
 *   -> candle freshness / source permission / existing-position policy
 *   -> fresh broker-verified LTP (never a historical candle) + divergence guard
 *   -> automatic risk sizing (1% risk budget, 20% exposure cap, cash)
 *   -> existing CustomerPaperOrderService (session, 15s tick, marketable limit,
 *      wallet lock, idempotency, positions, cash, realized P&amp;L)
 *   -> decision trail + runtime evidence for the in-app progress tracker
 * </pre>
 *
 * Owner-initiated only: no scheduler, no retry after an UNKNOWN outcome, PAPER
 * rooms only, and no LIVE broker path exists in this class.
 */
@Service
public class MindPaperCycleService {
    private static final Logger log = LoggerFactory.getLogger(MindPaperCycleService.class);
    public static final String STRATEGY_CODE = "SMA_TREND_VOLUME_V1";
    /** Catalog entry "SMA Cross" (STR-001) maps to SMA_TREND_VOLUME_V1. */
    static final String STRATEGY_CAPABILITY = "strategy.STR-001";
    static final Set<HistoricalCandleInterval> INTERVALS = Set.of(HistoricalCandleInterval.FIVE_MINUTE,
        HistoricalCandleInterval.FIFTEEN_MINUTE, HistoricalCandleInterval.ONE_HOUR,
        HistoricalCandleInterval.ONE_DAY);
    static final BigDecimal RISK_BUDGET_FRACTION = new BigDecimal("0.01");
    static final BigDecimal MAX_EXPOSURE_FRACTION = new BigDecimal("0.20");
    static final BigDecimal MAX_QUOTE_DIVERGENCE = new BigDecimal("0.03");
    private static final Duration MAX_TICK_AGE = Duration.ofSeconds(15);
    private static final List<String> FILL_CAPABILITIES = List.of("engine.data-gate",
        "engine.strategy-eval", "engine.risk-gates", "engine.strategy-to-paper",
        "engine.paper-submit", "engine.accounting", "paper.vertical-slice", STRATEGY_CAPABILITY);
    private static final List<String> EVALUATION_CAPABILITIES = List.of("engine.data-gate",
        "engine.strategy-eval");

    public enum Outcome { FILLED, NO_ENTRY, DATA_BLOCKED, QUOTE_UNAVAILABLE, REJECTED_BY_GATE, FAILED_UNKNOWN }

    public record Stage(String code, String result, String detail) {}

    public record Holding(long instrumentId, int quantity, long avgCostPaise,
                          BigDecimal markPrice, Long unrealizedPnlPaise) {}

    public record Cycle(UUID id, UUID roomId, long instrumentId, String tradingSymbol,
                        String interval, String strategyCode, String signalId, String signalAction,
                        Instant candleTime, String candleSource, Outcome outcome, String side,
                        Integer quantity, BigDecimal limitPrice, BigDecimal stopPrice,
                        BigDecimal targetPrice, BigDecimal quoteLtp, Instant quoteExchangeAt,
                        UUID orderId, List<String> reasons, List<Stage> stages,
                        String backendRevision, Instant createdAt,
                        String dataLabel, boolean liveOrder) {}

    public record CycleResult(Cycle cycle, CustomerPaperOrderService.Receipt order,
                              Long availableCashPaise, Long realizedPnlPaise,
                              List<Holding> holdings) {}

    private final JdbcClient jdbc;
    private final InstrumentMasterService instruments;
    private final StrategyService strategies;
    private final CustomerPaperOrderService orders;
    private final CustomerPaperWalletService wallets;
    private final MarketLastTickStore ticks;
    private final MarketSessionCalendar calendar;
    private final MindCapabilityProgressService progress;
    private final Clock clock;
    private final Set<String> permittedSources;

    @Autowired
    public MindPaperCycleService(JdbcClient jdbc, InstrumentMasterService instruments,
            StrategyService strategies, CustomerPaperOrderService orders,
            CustomerPaperWalletService wallets, MarketLastTickStore ticks,
            MarketSessionCalendar calendar, MindCapabilityProgressService progress, Clock clock,
            @Value("${app.mind.paper.permitted-candle-sources:ANGEL_ONE_SMARTAPI}") String permittedSources) {
        this.jdbc = jdbc;
        this.instruments = instruments;
        this.strategies = strategies;
        this.orders = orders;
        this.wallets = wallets;
        this.ticks = ticks;
        this.calendar = calendar;
        this.progress = progress;
        this.clock = clock;
        this.permittedSources = Arrays.stream(permittedSources == null ? new String[0] : permittedSources.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public CycleResult run(UUID owner, UUID roomId, String requestKey, long instrumentId,
                           HistoricalCandleInterval interval) {
        if (owner == null || roomId == null || requestKey == null
                || !requestKey.matches("[A-Za-z0-9_-]{8,64}") || instrumentId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Mind PAPER cycle request");
        }
        if (interval == null || !INTERVALS.contains(interval)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported interval");
        }
        Optional<Cycle> prior = byKey(owner, roomId, requestKey);
        if (prior.isPresent()) {
            if (prior.get().instrumentId() != instrumentId || !prior.get().interval().equals(interval.name())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for another cycle");
            }
            return result(owner, roomId, prior.get());
        }
        requirePaperRoom(owner, roomId);
        Draft d = new Draft();
        Instant now = clock.instant();
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        d.symbol = instrument.tradingSymbol();

        // 1. Data + deterministic strategy (stored completed candles, quality gate).
        StrategyEvaluation evaluation = strategies.evaluate(instrumentId, interval);
        StrategySignal signal = evaluation.signal();
        d.source = evaluation.dataSource();
        d.candleTime = evaluation.latestCandleAt();
        if (signal == null) {
            d.stage("DATA_GATE", "BLOCKED", String.join(",", evaluation.reasonCodes()));
            d.reasons.addAll(evaluation.reasonCodes());
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.DATA_BLOCKED, null);
        }
        d.signalId = signal.id();
        d.action = signal.action();
        d.reasons.addAll(signal.reasonCodes());
        if (!permittedSources.contains(signal.source())) {
            d.stage("DATA_GATE", "BLOCKED", "Candle source not permitted for PAPER: " + signal.source());
            d.reasons.add("CANDLE_SOURCE_NOT_PERMITTED");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.DATA_BLOCKED, null);
        }
        if (signal.latestCandleAt() == null
                || Duration.between(signal.latestCandleAt(), now).compareTo(maxCandleAge(interval)) > 0) {
            d.stage("DATA_GATE", "BLOCKED", "Latest candle too old for an executable decision");
            d.reasons.add("SIGNAL_CANDLE_STALE");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.DATA_BLOCKED, null);
        }
        d.stage("DATA_GATE", "PASSED", d.source + " " + interval + " @" + signal.latestCandleAt());
        d.stage("STRATEGY", "EVALUATED", STRATEGY_CODE + " -> " + signal.action());
        d.evaluated = true;

        // 2. Position policy: one open entry per instrument; exits on trend break or stop/target.
        CustomerPaperOrderService.Position held = orders.positions(owner, roomId).stream()
            .filter(p -> p.instrumentId() == instrumentId).findFirst().orElse(null);
        String side;
        if (held != null && held.quantity() > 0) {
            Protective protective = lastEntryProtection(owner, roomId, instrumentId);
            MarketLastTickStore.LastTick preview = freshTick(instrumentId, now);
            boolean trendBroken = !"BUY".equals(signal.action())
                && (signal.reasonCodes().contains("CLOSE_NOT_ABOVE_SLOW_SMA")
                    || signal.reasonCodes().contains("TREND_NOT_BULLISH"));
            boolean stopHit = preview != null && protective != null && protective.stop() != null
                && preview.ltp().compareTo(protective.stop()) <= 0;
            boolean targetHit = preview != null && protective != null && protective.target() != null
                && protective.target().signum() > 0 && preview.ltp().compareTo(protective.target()) >= 0;
            if (!(trendBroken || stopHit || targetHit)) {
                d.stage("POSITION", "HOLD", "Open position kept; no exit condition");
                d.reasons.add("POSITION_ALREADY_OPEN");
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
            side = "SELL";
            d.reasons.add(stopHit ? "EXIT_STOP_HIT" : targetHit ? "EXIT_TARGET_HIT" : "EXIT_TREND_BROKEN");
            d.quantity = held.quantity();
            d.stage("POSITION", "EXIT", d.reasons.getLast());
        } else {
            if (!"BUY".equals(signal.action())) {
                d.stage("POSITION", "NO_ENTRY", "Strategy candidate is " + signal.action());
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
            side = "BUY";
            d.stop = signal.stopLoss();
            d.target = signal.target();
        }
        d.side = side;

        // 3. Fresh broker-authoritative quote. Historical candles are never executable prices.
        if (!calendar.isInsideCashSession(now)) {
            d.stage("SESSION", "BLOCKED", "Cash session closed");
            d.reasons.add("MARKET_SESSION_CLOSED");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.QUOTE_UNAVAILABLE, null);
        }
        MarketLastTickStore.LastTick tick = freshTick(instrumentId, now);
        if (tick == null) {
            d.stage("QUOTE", "BLOCKED", "No verified tick within 15s");
            d.reasons.add("FRESH_QUOTE_UNAVAILABLE");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.QUOTE_UNAVAILABLE, null);
        }
        d.ltp = tick.ltp();
        d.quoteAt = tick.exchangeAt();
        BigDecimal divergence = tick.ltp().subtract(signal.referencePrice()).abs()
            .divide(signal.referencePrice(), 6, RoundingMode.HALF_UP);
        if ("BUY".equals(side) && divergence.compareTo(MAX_QUOTE_DIVERGENCE) > 0) {
            d.stage("QUOTE", "BLOCKED", "LTP diverged " + divergence.movePointRight(2).setScale(2, RoundingMode.HALF_UP)
                + "% from signal close");
            d.reasons.add("QUOTE_DIVERGED_FROM_SIGNAL");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
        }
        if ("BUY".equals(side) && d.stop != null && tick.ltp().compareTo(d.stop) <= 0) {
            d.stage("QUOTE", "BLOCKED", "LTP already at/below stop");
            d.reasons.add("QUOTE_BELOW_STOP");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
        }
        d.stage("QUOTE", "PASSED", "LTP " + tick.ltp() + " @" + tick.exchangeAt());

        // 4. Automatic risk sizing — no user sliders.
        BigDecimal tickSize = instrument.tickSize() != null && instrument.tickSize().signum() > 0
            ? instrument.tickSize() : new BigDecimal("0.05");
        d.limit = "BUY".equals(side) ? roundUp(tick.ltp(), tickSize) : roundDown(tick.ltp(), tickSize);
        if ("BUY".equals(side)) {
            CustomerPaperWalletService.PaperWallet wallet = wallets.get(owner, roomId);
            int qty = size(wallet.initialCashPaise(), wallet.availableCashPaise(), d.limit, d.stop);
            if (qty < 1) {
                d.stage("SIZING", "BLOCKED", "Risk budget / exposure cap / cash allows < 1 share");
                d.reasons.add("SIZE_BELOW_ONE_SHARE");
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
            d.quantity = qty;
        }
        d.stage("SIZING", "PASSED", d.quantity + " @ limit " + d.limit);

        // 5. Existing PAPER engine. Deterministic order key => one order per signal and side.
        String orderKey = "mind_" + hash(roomId + "|" + signal.id() + "|" + side).substring(0, 40);
        CustomerPaperOrderService.Receipt receipt;
        try {
            receipt = orders.submit(owner, roomId, orderKey, instrumentId, side, d.quantity, d.limit);
        } catch (ResponseStatusException rejected) {
            d.stage("PAPER_ORDER", "REJECTED", rejected.getReason());
            d.reasons.add("PAPER_ENGINE_REJECTED");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.REJECTED_BY_GATE, null);
        } catch (RuntimeException unknown) {
            // Outcome unknown: never retried automatically. The owner inspects the order ledger.
            log.warn("Mind PAPER cycle order outcome unknown for room {}", roomId, unknown);
            d.stage("PAPER_ORDER", "UNKNOWN", "Order outcome unknown; not retried");
            d.reasons.add("ORDER_OUTCOME_UNKNOWN");
            Cycle failed = persist(owner, roomId, requestKey, instrumentId, interval, d, Outcome.FAILED_UNKNOWN, null);
            evidence(failed, List.of("engine.paper-submit"), EventType.RUNTIME_FAILED);
            return result(owner, roomId, failed);
        }
        d.stage("PAPER_ORDER", "FILLED", receipt.side() + " " + receipt.quantity() + " @ " + receipt.fillPrice());
        d.stage("ACCOUNTING", "POSTED", "cash " + receipt.cashAfterPaise() + " paise; realized delta "
            + receipt.realizedPnlDeltaPaise());
        return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.FILLED, receipt);
    }

    public List<Cycle> recent(UUID owner, UUID roomId) {
        requirePaperRoom(owner, roomId);
        return jdbc.sql(SELECT + " WHERE user_id=:owner AND room_id=:room ORDER BY created_at DESC, id DESC LIMIT 25")
            .param("owner", owner).param("room", roomId).query((rs, row) -> map(rs)).list();
    }

    // ------------------------------------------------------------------ policy

    static int size(long initialCashPaise, long availableCashPaise, BigDecimal limit, BigDecimal stop) {
        if (limit == null || limit.signum() <= 0) return 0;
        BigDecimal initial = BigDecimal.valueOf(initialCashPaise).movePointLeft(2);
        BigDecimal available = BigDecimal.valueOf(availableCashPaise).movePointLeft(2);
        BigDecimal perShareRisk = stop == null ? BigDecimal.ZERO : limit.subtract(stop);
        if (perShareRisk.signum() <= 0) return 0;
        BigDecimal byRisk = initial.multiply(RISK_BUDGET_FRACTION).divide(perShareRisk, 0, RoundingMode.DOWN);
        BigDecimal byExposure = initial.multiply(MAX_EXPOSURE_FRACTION).divide(limit, 0, RoundingMode.DOWN);
        // Reserve fees (0.05%, min Rs 1) when checking cash.
        BigDecimal byCash = available.subtract(BigDecimal.ONE).max(BigDecimal.ZERO)
            .divide(limit.multiply(new BigDecimal("1.0005")), 0, RoundingMode.DOWN);
        long qty = byRisk.min(byExposure).min(byCash).min(BigDecimal.valueOf(10_000)).longValue();
        return (int) Math.max(0, qty);
    }

    static Duration maxCandleAge(HistoricalCandleInterval interval) {
        return switch (interval) {
            case FIVE_MINUTE -> Duration.ofMinutes(20);
            case FIFTEEN_MINUTE -> Duration.ofMinutes(60);
            case ONE_HOUR -> Duration.ofHours(3);
            case ONE_DAY -> Duration.ofDays(4);
            default -> Duration.ZERO;
        };
    }

    static BigDecimal roundUp(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.CEILING).multiply(tick).setScale(Math.min(4, tick.scale()), RoundingMode.UNNECESSARY);
    }

    static BigDecimal roundDown(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.FLOOR).multiply(tick).setScale(Math.min(4, tick.scale()), RoundingMode.UNNECESSARY);
    }

    private MarketLastTickStore.LastTick freshTick(long instrumentId, Instant now) {
        MarketLastTickStore.LastTick tick = ticks.lastFor(List.of(instrumentId)).get(instrumentId);
        if (tick == null || tick.ltp() == null || tick.ltp().signum() <= 0 || tick.exchangeAt() == null
                || tick.exchangeAt().isAfter(now.plusSeconds(2))
                || Duration.between(tick.exchangeAt(), now).compareTo(MAX_TICK_AGE) > 0
                || !calendar.isInsideCashSession(tick.exchangeAt())) {
            return null;
        }
        return tick;
    }

    private record Protective(BigDecimal stop, BigDecimal target) {}

    private Protective lastEntryProtection(UUID owner, UUID roomId, long instrumentId) {
        return jdbc.sql("""
                SELECT stop_price,target_price FROM mind_paper_cycle
                WHERE user_id=:owner AND room_id=:room AND instrument_id=:instrument
                  AND outcome='FILLED' AND side='BUY'
                ORDER BY created_at DESC LIMIT 1
                """).param("owner", owner).param("room", roomId).param("instrument", instrumentId)
            .query((rs, row) -> new Protective(rs.getBigDecimal("stop_price"), rs.getBigDecimal("target_price")))
            .optional().orElse(null);
    }

    // ------------------------------------------------------------------ persistence + evidence

    private static final class Draft {
        String symbol, signalId, action, source, side;
        Instant candleTime, quoteAt;
        Integer quantity;
        BigDecimal limit, stop, target, ltp;
        boolean evaluated;
        final List<String> reasons = new ArrayList<>();
        final List<Stage> stages = new ArrayList<>();
        void stage(String code, String result, String detail) { stages.add(new Stage(code, result, detail)); }
    }

    private CycleResult finish(UUID owner, UUID roomId, String key, long instrumentId,
                               HistoricalCandleInterval interval, Draft d, Outcome outcome,
                               CustomerPaperOrderService.Receipt receipt) {
        Cycle cycle = persist(owner, roomId, key, instrumentId, interval, d, outcome, receipt);
        if (outcome == Outcome.FILLED) {
            evidence(cycle, FILL_CAPABILITIES, EventType.RUNTIME_VERIFIED);
        } else if (d.evaluated) {
            evidence(cycle, EVALUATION_CAPABILITIES, EventType.RUNTIME_VERIFIED);
        }
        return result(owner, roomId, cycle);
    }

    private Cycle persist(UUID owner, UUID roomId, String key, long instrumentId,
                          HistoricalCandleInterval interval, Draft d, Outcome outcome,
                          CustomerPaperOrderService.Receipt receipt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO mind_paper_cycle
                  (id,user_id,room_id,request_key,instrument_id,trading_symbol,candle_interval,strategy_code,
                   signal_id,signal_action,candle_time,candle_source,outcome,side,quantity,limit_price,
                   stop_price,target_price,quote_ltp,quote_exchange_at,order_id,reasons,stages,backend_revision,
                   created_at)
                VALUES (:id,:owner,:room,:key,:instrument,:symbol,:interval,:strategy,:signal,:action,:candle,
                   :source,:outcome,:side,:qty,:limit,:stop,:target,:ltp,:quoteAt,:order,:reasons,:stages,:revision,
                   :createdAt)
                ON CONFLICT (user_id,room_id,request_key) DO NOTHING
                """)
            .param("id", id).param("owner", owner).param("room", roomId).param("key", key)
            .param("instrument", instrumentId).param("symbol", d.symbol).param("interval", interval.name())
            .param("strategy", STRATEGY_CODE).param("signal", d.signalId).param("action", d.action)
            .param("candle", d.candleTime == null ? null : java.sql.Timestamp.from(d.candleTime))
            .param("source", truncate(d.source, 40)).param("outcome", outcome.name())
            .param("side", d.side)
            .param("qty", d.quantity).param("limit", d.limit)
            .param("stop", outcome == Outcome.FILLED && "BUY".equals(d.side) ? d.stop : null)
            .param("target", outcome == Outcome.FILLED && "BUY".equals(d.side) ? d.target : null)
            .param("ltp", d.ltp).param("quoteAt", d.quoteAt == null ? null : java.sql.Timestamp.from(d.quoteAt))
            .param("order", receipt == null ? null : receipt.id())
            .param("reasons", truncate(String.join(",", d.reasons), 2000))
            .param("stages", truncate(encodeStages(d.stages), 2000))
            .param("revision", progress.backendRevision())
            .param("createdAt", java.sql.Timestamp.from(clock.instant())).update();
        return byKey(owner, roomId, key).orElseThrow();
    }

    private void evidence(Cycle cycle, List<String> capabilities, EventType type) {
        String dataset = "candles:" + cycle.candleSource() + ":" + cycle.interval() + ":" + cycle.candleTime();
        for (String capability : capabilities) {
            try {
                progress.recordRuntime(capability, type, dataset, "mind-paper-cycle:" + cycle.id(),
                    cycle.outcome() + " " + cycle.tradingSymbol() + " " + cycle.signalAction(),
                    "paper-cycle:" + cycle.id() + ":" + capability, cycle.createdAt());
            } catch (RuntimeException ex) {
                // Evidence must never alter the trading outcome; missing evidence only delays DONE.
                log.warn("Could not record Mind progress evidence for {}", capability, ex);
            }
        }
    }

    private CycleResult result(UUID owner, UUID roomId, Cycle cycle) {
        CustomerPaperOrderService.Receipt order = cycle.orderId() == null ? null
            : orders.orders(owner, roomId).stream().filter(o -> o.id().equals(cycle.orderId())).findFirst().orElse(null);
        CustomerPaperWalletService.PaperWallet wallet = wallets.get(owner, roomId);
        List<CustomerPaperOrderService.Position> positions = orders.positions(owner, roomId);
        Map<Long, MarketLastTickStore.LastTick> marks = positions.isEmpty() ? Map.of()
            : ticks.lastFor(positions.stream().map(CustomerPaperOrderService.Position::instrumentId).toList());
        List<Holding> holdings = new ArrayList<>();
        for (CustomerPaperOrderService.Position p : positions) {
            MarketLastTickStore.LastTick mark = marks.get(p.instrumentId());
            Long unrealized = mark == null || mark.ltp() == null ? null
                : mark.ltp().multiply(BigDecimal.valueOf(p.quantity())).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact()
                    - Math.multiplyExact(p.avgCostPaise(), (long) p.quantity());
            holdings.add(new Holding(p.instrumentId(), p.quantity(), p.avgCostPaise(),
                mark == null ? null : mark.ltp(), unrealized));
        }
        return new CycleResult(cycle, order, wallet.availableCashPaise(), wallet.realizedPnlPaise(),
            List.copyOf(holdings));
    }

    private void requirePaperRoom(UUID owner, UUID roomId) {
        Boolean ok = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM customer_trading_room
                    WHERE id=:room AND user_id=:owner AND mode='PAPER' AND status='PAPER_FUNDED')
                """).param("room", roomId).param("owner", owner).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(ok)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Funded Paper Room not found");
    }

    private Optional<Cycle> byKey(UUID owner, UUID roomId, String key) {
        return jdbc.sql(SELECT + " WHERE user_id=:owner AND room_id=:room AND request_key=:key")
            .param("owner", owner).param("room", roomId).param("key", key)
            .query((rs, row) -> map(rs)).optional();
    }

    private static final String SELECT = """
            SELECT id,room_id,instrument_id,trading_symbol,candle_interval,strategy_code,signal_id,signal_action,
                   candle_time,candle_source,outcome,side,quantity,limit_price,stop_price,target_price,quote_ltp,
                   quote_exchange_at,order_id,reasons,stages,backend_revision,created_at
            FROM mind_paper_cycle""";

    private static Cycle map(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp candle = rs.getTimestamp("candle_time");
        java.sql.Timestamp quoteAt = rs.getTimestamp("quote_exchange_at");
        int qty = rs.getInt("quantity");
        Integer quantity = rs.wasNull() ? null : qty;
        String reasons = rs.getString("reasons");
        String source = rs.getString("candle_source");
        return new Cycle(rs.getObject("id", UUID.class), rs.getObject("room_id", UUID.class),
            rs.getLong("instrument_id"), rs.getString("trading_symbol"), rs.getString("candle_interval"),
            rs.getString("strategy_code"), rs.getString("signal_id"), rs.getString("signal_action"),
            candle == null ? null : candle.toInstant(), source, Outcome.valueOf(rs.getString("outcome")),
            rs.getString("side"), quantity, rs.getBigDecimal("limit_price"), rs.getBigDecimal("stop_price"),
            rs.getBigDecimal("target_price"), rs.getBigDecimal("quote_ltp"),
            quoteAt == null ? null : quoteAt.toInstant(), rs.getObject("order_id", UUID.class),
            reasons == null || reasons.isEmpty() ? List.of() : List.of(reasons.split(",")),
            decodeStages(rs.getString("stages")), rs.getString("backend_revision"),
            rs.getTimestamp("created_at").toInstant(),
            source == null ? "NO_DATA" : "STORED_BROKER_CANDLES:" + source + " + VERIFIED_LIVE_TICK", false);
    }

    static String encodeStages(List<Stage> stages) {
        return stages.stream().map(s -> s.code() + "|" + s.result() + "|" + clean(s.detail()))
            .collect(Collectors.joining("\n"));
    }

    static List<Stage> decodeStages(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Stage> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3) out.add(new Stage(parts[0], parts[1], parts[2]));
        }
        return List.copyOf(out);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('|', '/');
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required", ex);
        }
    }

    /** Exposed for tests: stage list in stable order. */
    static Map<String, String> stageResults(Cycle cycle) {
        Map<String, String> out = new LinkedHashMap<>();
        cycle.stages().forEach(s -> out.put(s.code(), s.result()));
        return out;
    }
}
