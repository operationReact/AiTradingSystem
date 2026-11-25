package in.aitradingsystem.mind;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.CandleSeriesValidator;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataQualityService;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.mind.MindCapabilityProgressService.EventType;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
import in.aitradingsystem.scanner.OpeningRangeBreakoutStrategy;
import in.aitradingsystem.scanner.ScanCandidate;
import in.aitradingsystem.scanner.ScanContext;
import in.aitradingsystem.scanner.ScannerStrategy;
import in.aitradingsystem.scanner.VwapMomentumStrategy;
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
import java.util.Objects;
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
 * permitted stored candles + quality gate (SMA, ORB, VWAP momentum, or moving-average reclaim)
 *   -> guarded intraday strategy candidate
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
    public static final String ORB_STRATEGY_CODE = OpeningRangeBreakoutStrategy.NAME;
    public static final String VWAP_STRATEGY_CODE = VwapMomentumStrategy.NAME;
    public static final String MA_RECLAIM_STRATEGY_CODE = MovingAverageReclaimStrategy.NAME;
    private static final String ORB_STRATEGY_CAPABILITY = "strategy.STR-024";
    /** Catalog entry "SMA Cross" (STR-001) maps to SMA_TREND_VOLUME_V1. */
    static final String STRATEGY_CAPABILITY = "strategy.STR-001";
    static final Set<HistoricalCandleInterval> INTERVALS = Set.of(HistoricalCandleInterval.ONE_MINUTE,
        HistoricalCandleInterval.THREE_MINUTE, HistoricalCandleInterval.FIVE_MINUTE,
        HistoricalCandleInterval.FIFTEEN_MINUTE, HistoricalCandleInterval.ONE_HOUR,
        HistoricalCandleInterval.ONE_DAY);
    static final BigDecimal RISK_BUDGET_FRACTION = new BigDecimal("0.01");
    static final BigDecimal MAX_EXPOSURE_FRACTION = new BigDecimal("0.20");
    static final BigDecimal MAX_QUOTE_DIVERGENCE = new BigDecimal("0.03");
    /** Cumulative realized PAPER loss, as a fraction of initial cash, after which new entries stop. */
    static final BigDecimal MAX_DRAWDOWN_FRACTION = new BigDecimal("0.03");
    /** Trailing-exit give-back from the highest stored quote since entry (STR-222). */
    static final BigDecimal TRAIL_FRACTION = new BigDecimal("0.02");
    private static final Duration MAX_TICK_AGE = Duration.ofSeconds(15);
    private static final List<String> FILL_CAPABILITIES = List.of("engine.data-gate",
        "engine.strategy-eval", "engine.risk-gates", "engine.strategy-to-paper",
        "engine.paper-submit", "engine.accounting", "paper.vertical-slice");
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
    private final AppAngelOneMarketFeedService liveFeed;
    private final Clock clock;
    private final Set<String> permittedSources;
    private final MarketDataRepository marketData;
    private final MarketDataQualityService dataQuality;
    private final OpeningRangeBreakoutStrategy orb;
    private final VwapMomentumStrategy vwap;
    private final MovingAverageReclaimStrategy maReclaim;

    public MindPaperCycleService(JdbcClient jdbc, InstrumentMasterService instruments,
            StrategyService strategies, CustomerPaperOrderService orders,
            CustomerPaperWalletService wallets, MarketLastTickStore ticks,
            MarketSessionCalendar calendar, MindCapabilityProgressService progress, Clock clock,
            AppAngelOneMarketFeedService liveFeed,
            @Value("${app.mind.paper.permitted-candle-sources:ANGEL_ONE_SMARTAPI}") String permittedSources) {
        this(jdbc, instruments, strategies, orders, wallets, ticks, calendar, progress, clock,
            liveFeed, permittedSources, null, null, null);
    }

    public MindPaperCycleService(JdbcClient jdbc, InstrumentMasterService instruments,
            StrategyService strategies, CustomerPaperOrderService orders,
            CustomerPaperWalletService wallets, MarketLastTickStore ticks,
            MarketSessionCalendar calendar, MindCapabilityProgressService progress, Clock clock,
            AppAngelOneMarketFeedService liveFeed,
            @Value("${app.mind.paper.permitted-candle-sources:ANGEL_ONE_SMARTAPI}") String permittedSources,
            MarketDataRepository marketData, MarketDataQualityService dataQuality,
            OpeningRangeBreakoutStrategy orb) {
        this(jdbc, instruments, strategies, orders, wallets, ticks, calendar, progress, clock,
            liveFeed, permittedSources, marketData, dataQuality, orb, null);
    }

    public MindPaperCycleService(JdbcClient jdbc, InstrumentMasterService instruments,
            StrategyService strategies, CustomerPaperOrderService orders,
            CustomerPaperWalletService wallets, MarketLastTickStore ticks,
            MarketSessionCalendar calendar, MindCapabilityProgressService progress, Clock clock,
            AppAngelOneMarketFeedService liveFeed,
            @Value("${app.mind.paper.permitted-candle-sources:ANGEL_ONE_SMARTAPI}") String permittedSources,
            MarketDataRepository marketData, MarketDataQualityService dataQuality,
            OpeningRangeBreakoutStrategy orb, VwapMomentumStrategy vwap) {
        this(jdbc, instruments, strategies, orders, wallets, ticks, calendar, progress, clock,
            liveFeed, permittedSources, marketData, dataQuality, orb, vwap, null);
    }

    @Autowired
    public MindPaperCycleService(JdbcClient jdbc, InstrumentMasterService instruments,
            StrategyService strategies, CustomerPaperOrderService orders,
            CustomerPaperWalletService wallets, MarketLastTickStore ticks,
            MarketSessionCalendar calendar, MindCapabilityProgressService progress, Clock clock,
            AppAngelOneMarketFeedService liveFeed,
            @Value("${app.mind.paper.permitted-candle-sources:ANGEL_ONE_SMARTAPI}") String permittedSources,
            MarketDataRepository marketData, MarketDataQualityService dataQuality,
            OpeningRangeBreakoutStrategy orb, VwapMomentumStrategy vwap,
            MovingAverageReclaimStrategy maReclaim) {
        this.liveFeed = liveFeed;
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
        this.marketData = marketData;
        this.dataQuality = dataQuality;
        this.orb = orb;
        this.vwap = vwap;
        this.maReclaim = maReclaim;
    }

    public CycleResult run(UUID owner, UUID roomId, String requestKey, long instrumentId,
                           HistoricalCandleInterval interval) {
        return run(owner, roomId, requestKey, instrumentId, interval, STRATEGY_CODE);
    }

    public CycleResult run(UUID owner, UUID roomId, String requestKey, long instrumentId,
                           HistoricalCandleInterval interval, String requestedStrategyCode) {
        String strategyCode = requestedStrategyCode == null || requestedStrategyCode.isBlank()
            ? STRATEGY_CODE : requestedStrategyCode;
        if (owner == null || roomId == null || requestKey == null
                || !requestKey.matches("[A-Za-z0-9_-]{8,64}") || instrumentId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Mind PAPER cycle request");
        }
        if (!Set.of(STRATEGY_CODE, ORB_STRATEGY_CODE, VWAP_STRATEGY_CODE, MA_RECLAIM_STRATEGY_CODE).contains(strategyCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported Mind PAPER strategy");
        }
        if (interval == null || !INTERVALS.contains(interval)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported interval");
        }
        Optional<Cycle> prior = byKey(owner, roomId, requestKey);
        if (prior.isPresent()) {
            if (prior.get().instrumentId() != instrumentId || !prior.get().interval().equals(interval.name())
                    || !prior.get().strategyCode().equals(strategyCode)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for another cycle");
            }
            return result(owner, roomId, prior.get());
        }
        requirePaperRoom(owner, roomId);
        // The configured room is authoritative. Never run a cash-equity Mind
        // strategy inside an F&O-only room, or an intraday candle strategy in
        // a Swing room (and vice versa). No client-supplied style can override it.
        requireMatchingRoomFocus(owner, roomId, interval, strategyCode);
        Draft d = new Draft();
        d.strategyCode = strategyCode;
        Instant now = clock.instant();
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        d.symbol = instrument.tradingSymbol();

        // 1. Data + deterministic strategy (stored completed candles, quality gate).
        // Never persisted to strategy_signal: the legacy automated PAPER engine trades persisted BUY
        // signals, and Mind keeps its own trail in mind_paper_cycle.
        CustomerPaperOrderService.Position held = orders.positions(owner, roomId).stream()
            .filter(p -> p.instrumentId() == instrumentId).findFirst().orElse(null);
        if (held != null && held.quantity() > 0) {
            String holdingStrategy = lastEntryStrategy(owner, roomId, instrumentId);
            if (!strategyCode.equals(holdingStrategy)) {
                d.stage("POSITION", "BLOCKED", "Open position belongs to a different or unknown strategy");
                d.reasons.add("POSITION_STRATEGY_MISMATCH");
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
        }
        CycleSignal signal;
        boolean strategyExitOnly = false;
        if (STRATEGY_CODE.equals(strategyCode)) {
            StrategyEvaluation evaluation = strategies.evaluateWithoutPersisting(instrumentId, interval);
            d.source = evaluation.dataSource();
            d.candleTime = evaluation.latestCandleAt();
            if (evaluation.signal() == null) {
                d.stage("DATA_GATE", "BLOCKED", String.join(",", evaluation.reasonCodes()));
                d.reasons.addAll(evaluation.reasonCodes());
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.DATA_BLOCKED, null);
            }
            StrategySignal raw = evaluation.signal();
            signal = new CycleSignal(raw.id(), raw.action(), raw.referencePrice(), raw.stopLoss(), raw.target(),
                raw.latestCandleAt(), raw.source(), raw.reasonCodes());
        } else {
            boolean isOrb = ORB_STRATEGY_CODE.equals(strategyCode);
            OrbEvaluation evaluation = isOrb ? evaluateOrb(instrument, interval, now)
                : evaluateIntraday(instrument, interval, now,
                    VWAP_STRATEGY_CODE.equals(strategyCode) ? vwap : maReclaim, strategyCode);
            d.source = evaluation.source();
            d.candleTime = evaluation.latestCandleAt();
            if (evaluation.blockingReason() != null) {
                if (held == null) {
                    d.stage("DATA_GATE", "BLOCKED", evaluation.blockingReason());
                    d.reasons.add(evaluation.blockingReason());
                    return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.DATA_BLOCKED, null);
                }
                // New entries require fully valid data. Existing positions may still close
                // from persisted protection levels using the fresh quote gate.
                strategyExitOnly = true;
                d.stage("DATA_GATE", "EXIT_ONLY", evaluation.blockingReason());
                d.reasons.add(isOrb ? "ORB_ENTRY_DATA_BLOCKED"
                    : VWAP_STRATEGY_CODE.equals(strategyCode) ? "VWAP_ENTRY_DATA_BLOCKED" : "MA_RECLAIM_ENTRY_DATA_BLOCKED");
                signal = new CycleSignal((isOrb ? "ORB_EXIT_ONLY_" : "VWAP_EXIT_ONLY_")
                    + hash(instrumentId + "|" + interval + "|" + d.candleTime).substring(0, 32),
                    "HOLD", evaluation.latestClose(), null, null, d.candleTime, d.source, List.of());
            } else {
                signal = evaluation.signal();
            }
            if (signal == null && held == null) {
                d.stage("DATA_GATE", "PASSED", d.source + " " + interval + " @" + d.candleTime);
                String setupName = isOrb ? "opening-range breakout"
                    : VWAP_STRATEGY_CODE.equals(strategyCode) ? "VWAP momentum" : "moving-average reclaim";
                d.stage("STRATEGY", "NO_SIGNAL", "No " + setupName + " candidate");
                d.reasons.add(isOrb ? "NO_ORB_CANDIDATE"
                    : VWAP_STRATEGY_CODE.equals(strategyCode) ? "NO_VWAP_CANDIDATE" : "NO_MA_RECLAIM_CANDIDATE");
                d.evaluated = true;
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
            if (signal == null) {
                signal = new CycleSignal((isOrb ? "ORB_HOLD_"
                    : VWAP_STRATEGY_CODE.equals(strategyCode) ? "VWAP_HOLD_" : "MA_RECLAIM_HOLD_")
                    + hash(instrumentId + "|" + interval + "|" + d.candleTime).substring(0, 32),
                    "HOLD", evaluation.latestClose(), null, null, d.candleTime, d.source, List.of());
                d.reasons.add(isOrb ? "NO_NEW_ORB_ENTRY_SIGNAL"
                    : VWAP_STRATEGY_CODE.equals(strategyCode) ? "NO_NEW_VWAP_ENTRY_SIGNAL" : "NO_NEW_MA_RECLAIM_ENTRY_SIGNAL");
            }
        }
        d.signalId = signal.id();
        d.action = signal.action();
        d.reasons.addAll(signal.reasonCodes());
        if (!strategyExitOnly && (signal.source() == null || !permittedSources.contains(signal.source()))) {
            d.stage("DATA_GATE", "BLOCKED", "Candle source not permitted for PAPER: " + signal.source());
            d.reasons.add("CANDLE_SOURCE_NOT_PERMITTED");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.DATA_BLOCKED, null);
        }
        if (!strategyExitOnly && (signal.latestCandleAt() == null
                || Duration.between(signal.latestCandleAt(), now).compareTo(maxCandleAge(interval)) > 0)) {
            d.stage("DATA_GATE", "BLOCKED", "Latest candle too old for an executable decision");
            d.reasons.add("SIGNAL_CANDLE_STALE");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.DATA_BLOCKED, null);
        }
        if (!strategyExitOnly) d.stage("DATA_GATE", "PASSED", d.source + " " + interval + " @" + signal.latestCandleAt());
        d.stage("STRATEGY", "EVALUATED", strategyCode + " -> " + signal.action());
        d.evaluated = true;

        // 2. Position policy: one open entry per instrument; exits on trend break or stop/target.
        String side;
        if (held != null && held.quantity() > 0) {
            Protective protective = lastEntryProtection(owner, roomId, instrumentId);
            MarketLastTickStore.LastTick preview = freshTick(instrument, now);
            boolean trendBroken = !"BUY".equals(signal.action())
                && (signal.reasonCodes().contains("CLOSE_NOT_ABOVE_SLOW_SMA")
                    || signal.reasonCodes().contains("TREND_NOT_BULLISH"));
            boolean stopHit = preview != null && protective != null && protective.stop() != null
                && preview.ltp().compareTo(protective.stop()) <= 0;
            boolean targetHit = preview != null && protective != null && protective.target() != null
                && protective.target().signum() > 0 && preview.ltp().compareTo(protective.target()) >= 0;
            // STR-222 trailing exit: once the position is in profit, exit if the price gives back
            // TRAIL_FRACTION from its highest recorded quote since entry. Uses only stored cycle quotes.
            BigDecimal highWater = preview == null ? null
                : highWaterSinceEntry(owner, roomId, instrumentId, preview.ltp());
            boolean trailingHit = preview != null && highWater != null
                && trailingExitHit(BigDecimal.valueOf(held.avgCostPaise(), 2).movePointLeft(2), highWater, preview.ltp(),
                    TRAIL_FRACTION);
            if (!(trendBroken || stopHit || targetHit || trailingHit)) {
                d.stage("POSITION", "HOLD", "Open position kept; no exit condition");
                d.reasons.add("POSITION_ALREADY_OPEN");
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
            side = "SELL";
            d.reasons.add(stopHit ? "EXIT_STOP_HIT" : targetHit ? "EXIT_TARGET_HIT"
                : trailingHit ? "EXIT_TRAILING_STOP" : "EXIT_TREND_BROKEN");
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

        // 3a. Kill switch: the global PAPER emergency stop and a cumulative drawdown limit block NEW entries.
        // Exits are never blocked by this gate, so a halted account can still close positions.
        if ("BUY".equals(side)) {
            String mismatch = ledgerReconciliationReason(owner, roomId);
            if (mismatch != null) {
                d.stage("RECONCILIATION", "BLOCKED", mismatch);
                d.reasons.add(mismatch.split(":", 2)[0]);
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
            d.stage("RECONCILIATION", "PASSED", "Virtual wallet agrees with its full PAPER cash ledger");
        }
        if ("BUY".equals(side)) {
            String halt = killSwitchReason(owner, roomId);
            if (halt != null) {
                d.stage("KILL_SWITCH", "BLOCKED", halt);
                d.reasons.add(halt.startsWith("GLOBAL_EMERGENCY_STOP") ? "GLOBAL_EMERGENCY_STOP" : "DRAWDOWN_KILL_SWITCH");
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
            d.stage("KILL_SWITCH", "PASSED", "No global emergency stop; drawdown within limit");
        }
        MarketLastTickStore.LastTick tick = freshTick(instrument, now);
        if (tick == null) {
            d.stage("QUOTE", "BLOCKED", "No verified tick within 15s");
            d.reasons.add("FRESH_QUOTE_UNAVAILABLE");
            return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.QUOTE_UNAVAILABLE, null);
        }
        d.ltp = tick.ltp();
        d.quoteAt = tick.exchangeAt();
        if ("BUY".equals(side)) {
            if (signal.referencePrice() == null || signal.referencePrice().signum() <= 0) {
                d.stage("QUOTE", "BLOCKED", "Entry signal has no valid reference price");
                d.reasons.add("INVALID_SIGNAL_REFERENCE_PRICE");
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
            BigDecimal divergence = tick.ltp().subtract(signal.referencePrice()).abs()
                .divide(signal.referencePrice(), 6, RoundingMode.HALF_UP);
            if (divergence.compareTo(MAX_QUOTE_DIVERGENCE) > 0) {
                d.stage("QUOTE", "BLOCKED", "LTP diverged " + divergence.movePointRight(2).setScale(2, RoundingMode.HALF_UP)
                    + "% from signal close");
                d.reasons.add("QUOTE_DIVERGED_FROM_SIGNAL");
                return finish(owner, roomId, requestKey, instrumentId, interval, d, Outcome.NO_ENTRY, null);
            }
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

    /** Closed Mind-originated PAPER round trips only; small samples are flagged, never extrapolated. */
    public record Performance(int cycles, int fills, int noEntry, int blocked, int closedTrades, int wins,
                              int losses, long realizedPnlPaise, Long averagePnlPaise, long maxDrawdownPaise,
                              Double winRatePercent, boolean sampleTooSmall, int minimumMeaningfulSample) {}

    public record Journal(String contractVersion, Instant generatedAt, boolean liveMoney,
                          List<Cycle> cycles, Performance performance) {}

    static final int MIN_MEANINGFUL_TRADES = 30;

    public Journal journal(UUID owner, int limit) {
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer required");
        int bounded = Math.max(1, Math.min(limit, 200));
        List<Cycle> cycles = jdbc.sql(SELECT + " " + """
                 WHERE user_id=:owner AND room_id IN (SELECT id FROM customer_trading_room
                     WHERE user_id=:owner AND mode='PAPER')
                 ORDER BY created_at DESC, id DESC LIMIT :limit""")
            .param("owner", owner).param("limit", bounded).query((rs, row) -> map(rs)).list();
        record Counts(int cycles, int fills, int noEntry, int blocked) {}
        Counts counts = jdbc.sql("""
                SELECT count(*) AS cycles,
                       count(*) FILTER (WHERE outcome='FILLED') AS fills,
                       count(*) FILTER (WHERE outcome='NO_ENTRY') AS no_entry,
                       count(*) FILTER (WHERE outcome IN ('DATA_BLOCKED','QUOTE_UNAVAILABLE','REJECTED_BY_GATE')) AS blocked
                FROM mind_paper_cycle WHERE user_id=:owner
                """).param("owner", owner).query((rs, row) -> new Counts(rs.getInt("cycles"), rs.getInt("fills"),
                    rs.getInt("no_entry"), rs.getInt("blocked"))).single();
        List<Long> closed = jdbc.sql("""
                SELECT o.realized_pnl_delta_paise FROM mind_paper_cycle c
                JOIN customer_paper_manual_order o ON o.id=c.order_id AND o.user_id=c.user_id
                WHERE c.user_id=:owner AND c.outcome='FILLED' AND c.side='SELL'
                ORDER BY o.created_at, o.id
                """).param("owner", owner).query(Long.class).list();
        return new Journal("mind.paper-journal.v1", clock.instant(), false, List.copyOf(cycles),
            performance(counts.cycles(), counts.fills(), counts.noEntry(), counts.blocked(), closed));
    }

    static Performance performance(int cycles, int fills, int noEntry, int blocked, List<Long> closedPnl) {
        long total = 0, peak = 0, maxDrawdown = 0;
        int wins = 0, losses = 0;
        for (long pnl : closedPnl) {
            total += pnl;
            if (pnl > 0) wins++; else if (pnl < 0) losses++;
            peak = Math.max(peak, total);
            maxDrawdown = Math.max(maxDrawdown, peak - total);
        }
        int n = closedPnl.size();
        return new Performance(cycles, fills, noEntry, blocked, n, wins, losses, total,
            n == 0 ? null : total / n, maxDrawdown,
            n == 0 ? null : Math.round(wins * 1000.0 / n) / 10.0,
            n < MIN_MEANINGFUL_TRADES, MIN_MEANINGFUL_TRADES);
    }

    // ------------------------------------------------------------------ policy

    /**
     * STR-232 PAPER ledger reconciliation (no LIVE broker exists to reconcile against). Reads the full
     * cash-event history, not the truncated customer view, and fails closed when the wallet and ledger disagree.
     */
    String ledgerReconciliationReason(UUID owner, UUID roomId) {
        CustomerPaperWalletService.PaperWallet wallet = wallets.get(owner, roomId);
        List<long[]> events = jdbc.sql("""
                SELECT cash_delta_paise, cash_after_paise
                FROM customer_paper_cash_event
                WHERE user_id = :owner AND room_id = :room
                ORDER BY created_at ASC, id ASC
                """)
            .param("owner", owner).param("room", roomId)
            .query((rs, row) -> new long[] {rs.getLong("cash_delta_paise"), rs.getLong("cash_after_paise")})
            .list();
        return ledgerMismatch(wallet.initialCashPaise(), wallet.availableCashPaise(), events);
    }

    /**
     * Pure ledger rule. events are {deltaPaise, cashAfterPaise} in chronological order, starting from zero.
     * Returns null when every step adds its delta, the first allocation equals the initial cash, and the last
     * balance equals available cash.
     */
    static String ledgerMismatch(long initialCashPaise, long availableCashPaise, List<long[]> events) {
        // An activated PAPER account always has its INITIAL_PAPER_ALLOCATION event (delta = cash_after = initial).
        // An empty ledger therefore cannot reconcile against a funded wallet and fails closed.
        if (events.isEmpty()) {
            return "LEDGER_MISMATCH: no cash events recorded for this PAPER room";
        }
        long previous = 0L;
        for (long[] event : events) {
            if (previous + event[0] != event[1]) {
                return "LEDGER_MISMATCH: a cash event does not follow the previous balance";
            }
            previous = event[1];
        }
        if (previous != availableCashPaise) {
            return "LEDGER_MISMATCH: ledger balance differs from the wallet balance";
        }
        if (events.get(0)[1] != initialCashPaise) {
            return "LEDGER_MISMATCH: the allocation does not equal the initial PAPER cash";
        }
        return null;
    }

    /** Returns the blocking reason for new entries, or null when entries may proceed. Fails closed on read errors. */
    String killSwitchReason(UUID owner, UUID roomId) {
        Boolean halted = jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id = 1")
            .query(Boolean.class).single();
        if (Boolean.TRUE.equals(halted)) {
            return "GLOBAL_EMERGENCY_STOP: new PAPER orders are halted";
        }
        CustomerPaperWalletService.PaperWallet wallet = wallets.get(owner, roomId);
        return drawdownReason(wallet.initialCashPaise(), wallet.realizedPnlPaise());
    }

    /** Highest stored cycle quote for this instrument since its last filled BUY, including the current quote. */
    BigDecimal highWaterSinceEntry(UUID owner, UUID roomId, long instrumentId, BigDecimal currentLtp) {
        BigDecimal stored = jdbc.sql("""
                SELECT MAX(quote_ltp) FROM mind_paper_cycle
                WHERE user_id = :owner AND room_id = :room AND instrument_id = :instrument
                  AND created_at >= COALESCE((
                        SELECT MAX(created_at) FROM mind_paper_cycle
                        WHERE user_id = :owner AND room_id = :room AND instrument_id = :instrument
                          AND outcome = 'FILLED' AND side = 'BUY'), TIMESTAMPTZ '-infinity')
                """)
            .param("owner", owner).param("room", roomId).param("instrument", instrumentId)
            .query(BigDecimal.class).optional().orElse(null);
        return stored == null ? currentLtp : stored.max(currentLtp);
    }

    /**
     * Pure trailing-exit rule. Active only once the price is above average cost; fires when the
     * current price is at or below highWater * (1 - trailFraction). Never fires on a loss.
     */
    static boolean trailingExitHit(BigDecimal avgCost, BigDecimal highWater, BigDecimal ltp, BigDecimal trailFraction) {
        if (avgCost == null || highWater == null || ltp == null || trailFraction == null) return false;
        if (avgCost.signum() <= 0 || highWater.compareTo(avgCost) <= 0) return false;
        BigDecimal trailStop = highWater.multiply(BigDecimal.ONE.subtract(trailFraction));
        return ltp.compareTo(trailStop) <= 0 && ltp.compareTo(avgCost) > 0;
    }

    /** Pure drawdown rule: a realized loss at or beyond MAX_DRAWDOWN_FRACTION of initial cash blocks entries. */
    static String drawdownReason(long initialCashPaise, long realizedPnlPaise) {
        BigDecimal floor = BigDecimal.valueOf(initialCashPaise).multiply(MAX_DRAWDOWN_FRACTION).negate();
        if (BigDecimal.valueOf(realizedPnlPaise).compareTo(floor) <= 0) {
            return "DRAWDOWN_KILL_SWITCH: realized PAPER loss reached the "
                + MAX_DRAWDOWN_FRACTION.movePointRight(2).stripTrailingZeros().toPlainString() + "% drawdown limit";
        }
        return null;
    }

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
            case ONE_MINUTE -> Duration.ofMinutes(3);
            case THREE_MINUTE -> Duration.ofMinutes(9);
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

    MarketLastTickStore.LastTick freshTick(Instrument instrument, Instant now) {
        MarketLastTickStore.LastTick tick = storedFreshTick(instrument.id(), now);
        if (tick != null) return tick;
        // The Mind quote gate uses the authorized application-owned live feed (SSM-backed SmartAPI session),
        // never a customer broker session. Any verified LIVE quote is recorded through the SAME store and
        // then judged by the SAME freshness rules below, so there is one quote path and no synthetic price.
        recordApplicationLiveQuote(instrument);
        return storedFreshTick(instrument.id(), now);
    }

    private MarketLastTickStore.LastTick storedFreshTick(long instrumentId, Instant now) {
        MarketLastTickStore.LastTick tick = ticks.lastFor(List.of(instrumentId)).get(instrumentId);
        if (tick == null || tick.ltp() == null || tick.ltp().signum() <= 0 || tick.exchangeAt() == null
                || tick.exchangeAt().isAfter(now.plusSeconds(2))
                || Duration.between(tick.exchangeAt(), now).compareTo(MAX_TICK_AGE) > 0
                || !calendar.isInsideCashSession(tick.exchangeAt())) {
            return null;
        }
        return tick;
    }

    private void recordApplicationLiveQuote(Instrument instrument) {
        if (liveFeed == null || !liveFeed.available()) return;
        try {
            var quote = liveFeed.live(instrument);
            if (quote != null && "LIVE".equals(quote.status())) {
                ticks.recordLive(List.of(quote));
            }
        } catch (RuntimeException ex) {
            // Provider or session failure is not a price. The cycle stays blocked with FRESH_QUOTE_UNAVAILABLE.
            log.warn("Mind PAPER quote unavailable from the application feed: {} {}", instrument.tradingSymbol(),
                ex.getClass().getSimpleName());
        }
    }

    private record Protective(BigDecimal stop, BigDecimal target) {}

    private record CycleSignal(String id, String action, BigDecimal referencePrice,
                               BigDecimal stopLoss, BigDecimal target, Instant latestCandleAt,
                               String source, List<String> reasonCodes) {}

    private record OrbEvaluation(CycleSignal signal, String blockingReason, String source,
                                 Instant latestCandleAt, BigDecimal latestClose) {}

    private OrbEvaluation evaluateOrb(Instrument instrument, HistoricalCandleInterval interval, Instant now) {
        if (marketData == null || orb == null) {
            return new OrbEvaluation(null, "ORB_PAPER_EVALUATOR_UNAVAILABLE", null, null, null);
        }
        if (!(interval == HistoricalCandleInterval.ONE_MINUTE || interval == HistoricalCandleInterval.THREE_MINUTE
                || interval == HistoricalCandleInterval.FIVE_MINUTE)) {
            return new OrbEvaluation(null, "ORB_INTERVAL_NOT_SUPPORTED", null, null, null);
        }
        List<MarketCandle> stored = marketData.recentCandles(instrument.id(), interval.name(), 64);
        Duration step = candleStep(interval);
        List<MarketCandle> completed = stored.stream()
            .filter(c -> c.candleTime() != null && !c.candleTime().plus(step).isAfter(now)).toList();
        if (completed.size() < orb.minimumCandles()) {
            return new OrbEvaluation(null, "INSUFFICIENT_COMPLETED_CANDLES", null,
                completed.isEmpty() ? null : completed.getLast().candleTime(), null);
        }
        MarketCandle latest = completed.getLast();
        String source = latest.source();
        boolean identityValid = completed.stream().allMatch(c -> c.instrumentId() == instrument.id()
            && Objects.equals(c.exchange(), instrument.exchange())
            && Objects.equals(c.tradingSymbol(), instrument.tradingSymbol())
            && Objects.equals(c.symbolToken(), instrument.symbolToken())
            && Objects.equals(c.catalogueVersion(), instrument.catalogueVersion())
            && Objects.equals(c.interval(), interval.name())
            && Objects.equals(c.source(), source)
            && c.fetchedAt() != null && !c.fetchedAt().isAfter(now)
            && c.open() != null && c.high() != null && c.low() != null && c.close() != null
            && c.high().compareTo(c.open()) >= 0 && c.high().compareTo(c.close()) >= 0
            && c.low().compareTo(c.open()) <= 0 && c.low().compareTo(c.close()) <= 0
            && c.high().compareTo(c.low()) >= 0 && c.volume() > 0);
        if (!identityValid || source == null || !permittedSources.contains(source)) {
            return new OrbEvaluation(null, "ORB_CANDLE_IDENTITY_OR_SOURCE_NOT_PERMITTED", source,
                latest.candleTime(), latest.close());
        }
        var validation = CandleSeriesValidator.validate(completed, now, maxCandleAge(interval));
        if (!validation.valid()) {
            return new OrbEvaluation(null, validation.reason(), source, latest.candleTime(), latest.close());
        }
        if (dataQuality != null && !dataQuality.eligibleForResearch(
                dataQuality.evaluateBars(instrument.id(), interval, completed))) {
            return new OrbEvaluation(null, "DATA_QUALITY_NOT_ELIGIBLE", source, latest.candleTime(), latest.close());
        }
        ScanCandidate candidate = orb.evaluate(new ScanContext(instrument.id(), instrument.tradingSymbol(),
            instrument.exchange(), interval.name(), now, completed)).orElse(null);
        if (candidate == null) {
            return new OrbEvaluation(null, null, source, latest.candleTime(), latest.close());
        }
        String signalId = "ORB_" + hash(instrument.id() + "|" + interval.name() + "|"
            + candidate.candleTime() + "|" + candidate.referencePrice()).substring(0, 32);
        CycleSignal signal = new CycleSignal(signalId, "BUY", candidate.referencePrice(), candidate.suggestedStop(),
            candidate.suggestedTarget(), candidate.candleTime(), source, candidate.reasons());
        return new OrbEvaluation(signal, null, source, latest.candleTime(), latest.close());
    }

    private OrbEvaluation evaluateIntraday(Instrument instrument, HistoricalCandleInterval interval, Instant now,
                                             ScannerStrategy evaluator, String strategyCode) {
        String label = VWAP_STRATEGY_CODE.equals(strategyCode) ? "VWAP" : "MA_RECLAIM";
        if (marketData == null || evaluator == null) {
            return new OrbEvaluation(null, label + "_PAPER_EVALUATOR_UNAVAILABLE", null, null, null);
        }
        if (!(interval == HistoricalCandleInterval.ONE_MINUTE || interval == HistoricalCandleInterval.THREE_MINUTE
                || interval == HistoricalCandleInterval.FIVE_MINUTE)) {
            return new OrbEvaluation(null, label + "_INTERVAL_NOT_SUPPORTED", null, null, null);
        }
        List<MarketCandle> stored = marketData.recentCandles(instrument.id(), interval.name(), 64);
        Duration step = candleStep(interval);
        List<MarketCandle> completed = stored.stream()
            .filter(c -> c.candleTime() != null && !c.candleTime().plus(step).isAfter(now)).toList();
        if (completed.size() < evaluator.minimumCandles()) {
            return new OrbEvaluation(null, "INSUFFICIENT_COMPLETED_CANDLES", null,
                completed.isEmpty() ? null : completed.getLast().candleTime(), null);
        }
        MarketCandle latest = completed.getLast();
        String source = latest.source();
        boolean identityValid = completed.stream().allMatch(c -> c.instrumentId() == instrument.id()
            && Objects.equals(c.exchange(), instrument.exchange())
            && Objects.equals(c.tradingSymbol(), instrument.tradingSymbol())
            && Objects.equals(c.symbolToken(), instrument.symbolToken())
            && Objects.equals(c.catalogueVersion(), instrument.catalogueVersion())
            && Objects.equals(c.interval(), interval.name())
            && Objects.equals(c.source(), source)
            && c.fetchedAt() != null && !c.fetchedAt().isAfter(now)
            && c.open() != null && c.high() != null && c.low() != null && c.close() != null
            && c.high().compareTo(c.open()) >= 0 && c.high().compareTo(c.close()) >= 0
            && c.low().compareTo(c.open()) <= 0 && c.low().compareTo(c.close()) <= 0
            && c.high().compareTo(c.low()) >= 0 && c.volume() > 0);
        if (!identityValid || source == null || !permittedSources.contains(source)) {
            return new OrbEvaluation(null, label + "_CANDLE_IDENTITY_OR_SOURCE_NOT_PERMITTED", source,
                latest.candleTime(), latest.close());
        }
        var validation = CandleSeriesValidator.validate(completed, now, maxCandleAge(interval));
        if (!validation.valid()) {
            return new OrbEvaluation(null, validation.reason(), source, latest.candleTime(), latest.close());
        }
        if (dataQuality != null && !dataQuality.eligibleForResearch(
                dataQuality.evaluateBars(instrument.id(), interval, completed))) {
            return new OrbEvaluation(null, "DATA_QUALITY_NOT_ELIGIBLE", source, latest.candleTime(), latest.close());
        }
        ScanCandidate candidate = evaluator.evaluate(new ScanContext(instrument.id(), instrument.tradingSymbol(),
            instrument.exchange(), interval.name(), now, completed)).orElse(null);
        if (candidate == null) {
            return new OrbEvaluation(null, null, source, latest.candleTime(), latest.close());
        }
        String signalId = label + "_" + hash(instrument.id() + "|" + interval.name() + "|"
            + candidate.candleTime() + "|" + candidate.referencePrice()).substring(0, 32);
        CycleSignal signal = new CycleSignal(signalId, "BUY", candidate.referencePrice(), candidate.suggestedStop(),
            candidate.suggestedTarget(), candidate.candleTime(), source, candidate.reasons());
        return new OrbEvaluation(signal, null, source, latest.candleTime(), latest.close());
    }

    private static Duration candleStep(HistoricalCandleInterval interval) {
        return switch (interval) {
            case ONE_MINUTE -> Duration.ofMinutes(1);
            case THREE_MINUTE -> Duration.ofMinutes(3);
            case FIVE_MINUTE -> Duration.ofMinutes(5);
            case FIFTEEN_MINUTE -> Duration.ofMinutes(15);
            case ONE_HOUR -> Duration.ofHours(1);
            case ONE_DAY -> Duration.ofDays(1);
            default -> Duration.ZERO;
        };
    }

    private static List<String> withStrategyCapability(List<String> capabilities, String strategyCode) {
        List<String> all = new ArrayList<>(capabilities);
        String strategyCapability = switch (strategyCode) {
            case ORB_STRATEGY_CODE -> ORB_STRATEGY_CAPABILITY;
            case STRATEGY_CODE -> STRATEGY_CAPABILITY;
            default -> null; // Do not attest an approximate catalogue mapping as exact.
        };
        if (strategyCapability != null) all.add(strategyCapability);
        return List.copyOf(all);
    }

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

    private String lastEntryStrategy(UUID owner, UUID roomId, long instrumentId) {
        return jdbc.sql("""
                SELECT strategy_code FROM mind_paper_cycle
                WHERE user_id=:owner AND room_id=:room AND instrument_id=:instrument
                  AND outcome='FILLED' AND side='BUY'
                ORDER BY created_at DESC LIMIT 1
                """).param("owner", owner).param("room", roomId).param("instrument", instrumentId)
            .query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ persistence + evidence

    private static final class Draft {
        String symbol, strategyCode = STRATEGY_CODE, signalId, action, source, side;
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
            evidence(cycle, withStrategyCapability(FILL_CAPABILITIES, cycle.strategyCode()), EventType.RUNTIME_VERIFIED);
        } else if (d.evaluated) {
            evidence(cycle, withStrategyCapability(EVALUATION_CAPABILITIES, cycle.strategyCode()), EventType.RUNTIME_VERIFIED);
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
            .param("strategy", d.strategyCode).param("signal", d.signalId).param("action", d.action)
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

    /**
     * Strict market + holding-horizon boundary for the supported SMA and ORB
     * cash-equity intraday PAPER strategies. Other strategy families must add their own
     * tested room-focus compatibility before execution.
     *
     * A daily candle is research input for Swing; this does NOT force any
     * minimum holding period or authorize leveraged/intraday products.
     */
    private void requireMatchingRoomFocus(UUID owner, UUID roomId,
                                          HistoricalCandleInterval interval, String strategyCode) {
        record Scope(String marketScope, String focus) {}
        Scope room = jdbc.sql("""
                SELECT market_scope, focus FROM customer_trading_room
                WHERE id=:room AND user_id=:owner AND mode='PAPER'
                  AND status='PAPER_FUNDED'
                """)
            .param("room", roomId).param("owner", owner)
            .query((rs, n) -> new Scope(
                rs.getString("market_scope"), rs.getString("focus")))
            .optional().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Funded Paper Room not found"));

        if (!("EQUITY".equals(room.marketScope()) || "ALL".equals(room.marketScope()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cash-equity Mind strategy is not allowed in an F&O-only room");
        }
        boolean strategyInterval = STRATEGY_CODE.equals(strategyCode)
            ? interval == HistoricalCandleInterval.FIVE_MINUTE || interval == HistoricalCandleInterval.FIFTEEN_MINUTE
                || interval == HistoricalCandleInterval.ONE_HOUR || interval == HistoricalCandleInterval.ONE_DAY
            : interval == HistoricalCandleInterval.ONE_MINUTE || interval == HistoricalCandleInterval.THREE_MINUTE
                || interval == HistoricalCandleInterval.FIVE_MINUTE;
        boolean compatible = strategyInterval && switch (room.focus()) {
            case "SWING" -> interval == HistoricalCandleInterval.ONE_DAY;
            case "INTRADAY" -> STRATEGY_CODE.equals(strategyCode)
                    ? interval == HistoricalCandleInterval.FIVE_MINUTE
                        || interval == HistoricalCandleInterval.FIFTEEN_MINUTE || interval == HistoricalCandleInterval.ONE_HOUR
                    : interval == HistoricalCandleInterval.ONE_MINUTE || interval == HistoricalCandleInterval.THREE_MINUTE
                        || interval == HistoricalCandleInterval.FIVE_MINUTE;
            // These PAPER evaluators are not validated for position investing
            // or an undecided strategy preference.
            default -> false;
        };
        if (!compatible) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Mind strategy and candle interval are not allowed by room focus");
        }
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
