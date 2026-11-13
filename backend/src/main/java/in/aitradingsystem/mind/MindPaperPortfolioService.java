package in.aitradingsystem.mind;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.tradingrooms.CustomerPaperOrderService;
import in.aitradingsystem.tradingrooms.CustomerPaperWalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read-only aggregate of the owner's funded PAPER rooms for Mind Portfolio / Risk screens.
 * Virtual money only. Marks come from the last verified broker tick and carry their age;
 * a missing or stale mark is reported as such, never filled with a candle close.
 */
@Service
public class MindPaperPortfolioService {
    public static final String CONTRACT_VERSION = "mind.paper-portfolio.v1";
    static final Duration FRESH_MARK = Duration.ofSeconds(15);

    public record Position(UUID roomId, long instrumentId, String tradingSymbol, int quantity,
                           long avgCostPaise, long costBasisPaise, BigDecimal markPrice,
                           Instant markAt, String markState, Long marketValuePaise,
                           Long unrealizedPnlPaise) {}

    public record Room(UUID roomId, String name, long initialCashPaise, long availableCashPaise,
                       long realizedPnlPaise, boolean paused, int openPositions) {}

    /** The automatic, non-configurable limits actually enforced by the PAPER path. */
    public record RiskPolicy(String perTradeRiskBudget, String maxSingleOrderExposure,
                             String maxQuoteAge, String maxSignalQuoteDivergence,
                             String sessionGate, String sizing, String exits, String liveTrading) {}

    public record Portfolio(String contractVersion, Instant generatedAt, boolean liveMoney,
                            int fundedRooms, long initialCashPaise, long availableCashPaise,
                            long realizedPnlPaise, long costBasisPaise, Long marketValuePaise,
                            Long unrealizedPnlPaise, Double grossExposurePercent,
                            int staleOrMissingMarks, List<Room> rooms, List<Position> positions,
                            RiskPolicy riskPolicy) {}

    private final JdbcClient jdbc;
    private final CustomerPaperWalletService wallets;
    private final CustomerPaperOrderService orders;
    private final MarketLastTickStore ticks;
    private final InstrumentMasterService instruments;
    private final Clock clock;

    public MindPaperPortfolioService(JdbcClient jdbc, CustomerPaperWalletService wallets,
            CustomerPaperOrderService orders, MarketLastTickStore ticks,
            InstrumentMasterService instruments, Clock clock) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.orders = orders;
        this.ticks = ticks;
        this.instruments = instruments;
        this.clock = clock;
    }

    public Portfolio inspect(UUID owner) {
        Instant now = clock.instant();
        List<RoomRow> roomRows = jdbc.sql("""
                SELECT id,name FROM customer_trading_room
                WHERE user_id=:owner AND mode='PAPER' AND status='PAPER_FUNDED'
                ORDER BY created_at, id LIMIT 50
                """).param("owner", owner)
            .query((rs, row) -> new RoomRow(rs.getObject("id", UUID.class), rs.getString("name"))).list();
        List<Room> rooms = new ArrayList<>();
        List<CustomerPaperOrderService.Position> raw = new ArrayList<>();
        List<UUID> rawRooms = new ArrayList<>();
        long initial = 0, cash = 0, realized = 0;
        for (RoomRow r : roomRows) {
            var wallet = wallets.get(owner, r.id());
            var positions = orders.positions(owner, r.id());
            positions.forEach(p -> { raw.add(p); rawRooms.add(r.id()); });
            rooms.add(new Room(r.id(), r.name(), wallet.initialCashPaise(), wallet.availableCashPaise(),
                wallet.realizedPnlPaise(), wallet.halted(), positions.size()));
            initial += wallet.initialCashPaise();
            cash += wallet.availableCashPaise();
            realized += wallet.realizedPnlPaise();
        }
        Set<Long> ids = new LinkedHashSet<>();
        raw.forEach(p -> ids.add(p.instrumentId()));
        Map<Long, MarketLastTickStore.LastTick> marks = ids.isEmpty() ? Map.of() : ticks.lastFor(ids);
        Map<Long, Instrument> names = ids.isEmpty() ? Map.of() : instruments.activeInstrumentsByIds(ids);
        List<Position> positions = new ArrayList<>();
        long cost = 0, value = 0;
        boolean allMarked = true;
        int stale = 0;
        for (int i = 0; i < raw.size(); i++) {
            var p = raw.get(i);
            long basis = Math.multiplyExact(p.avgCostPaise(), (long) p.quantity());
            cost += basis;
            var tick = marks.get(p.instrumentId());
            String state;
            Long mv = null, upnl = null;
            if (tick == null || tick.ltp() == null || tick.ltp().signum() <= 0 || tick.exchangeAt() == null) {
                state = "MISSING";
            } else {
                state = Duration.between(tick.exchangeAt(), now).compareTo(FRESH_MARK) <= 0 ? "FRESH" : "STALE";
                mv = tick.ltp().multiply(BigDecimal.valueOf(p.quantity())).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
                upnl = mv - basis;
            }
            if (!"FRESH".equals(state)) stale++;
            if (mv == null) allMarked = false; else value += mv;
            Instrument instrument = names.get(p.instrumentId());
            positions.add(new Position(rawRooms.get(i), p.instrumentId(),
                instrument == null ? "#" + p.instrumentId() : instrument.tradingSymbol(), p.quantity(),
                p.avgCostPaise(), basis, tick == null ? null : tick.ltp(),
                tick == null ? null : tick.exchangeAt(), state, mv, upnl));
        }
        Long marketValue = allMarked ? value : null;
        Long unrealized = allMarked ? value - cost : null;
        Double exposure = initial == 0 ? null
            : Math.round((allMarked ? value : cost) * 10000.0 / initial) / 100.0;
        return new Portfolio(CONTRACT_VERSION, now, false, rooms.size(), initial, cash, realized, cost,
            marketValue, unrealized, exposure, stale, List.copyOf(rooms), List.copyOf(positions), POLICY);
    }

    private record RoomRow(UUID id, String name) {}

    static final RiskPolicy POLICY = new RiskPolicy(
        "1% of the room's starting virtual cash per entry",
        "20% of starting virtual cash per order (no leverage)",
        "15 seconds, verified broker tick, inside cash session",
        "3% between signal close and live quote",
        "NSE/BSE cash session only; fills rejected outside it",
        "Automatic: min(risk budget / stop distance, exposure cap, cash after fees)",
        "Trend break, entry stop or target; one open entry per instrument",
        "Not available in Mind; LIVE is separately gated and not authorized");
}
