package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Source/rights gated equity history plus *settled, broker-reconciled* actual
 * held-share/options evidence. It never reads paper holdings, accepts user typed
 * holdings as broker evidence, or treats a current LTP as a historical close.
 */
@Component
public final class FnoVerifiedEquityOwnedOptionStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String HISTORY_SQL = """
        SELECT e.provider_code, e.equity_key, e.session_date,
               e.close_price, e.first_available_at, cal.session_close_at
        FROM fno_verified_equity_owned_daily_close e
        JOIN golden_data_provider p ON p.provider_code = e.provider_code
            AND p.source_kind = 'EXTERNAL'
        JOIN golden_historical_exchange_session_evidence cal
            ON cal.evidence_id = e.calendar_evidence_id
            AND cal.provider_id = p.provider_id
            AND cal.source_domain = 'nse_eq_trading_calendar'
            AND cal.exchange = 'NSE'
            AND cal.session_date = e.session_date
            AND cal.session_status = 'TRADING'
        JOIN golden_source_rights_receipt rights
            ON rights.receipt_id = e.close_rights_receipt_id
            AND rights.provider_id = p.provider_id
            AND rights.source_domain = 'nse_eq_completed_daily_close'
            AND rights.permitted_use = 'MODEL_INFERENCE'
            AND rights.receipt_status = 'VERIFIED'
        WHERE e.equity_key = ?
          AND e.session_date BETWEEN ? AND ?
          AND e.retain_until >= ?
          AND e.captured_at <= ?
          AND e.first_available_at >= cal.session_close_at
          AND e.first_available_at <= ?
          AND cal.first_available_at <= ?
          AND cal.verified_at <= ?
          AND cal.captured_at <= ?
          AND rights.valid_from <= ?
          AND (rights.valid_to IS NULL OR rights.valid_to > ?)
          AND rights.verified_at <= ?
          AND rights.captured_at <= ?
        ORDER BY e.session_date, e.provider_code
        LIMIT 100
        """;

    private static final String OWNED_SQL = """
        SELECT e.provider_code, e.account_scope, e.equity_key, e.option_key,
               e.option_type, e.listed_from, e.expiry, e.lot_size,
               e.strike, e.tick_size, e.settled_owned_shares,
               e.average_acquisition_cost, e.holdings_source_observed_at,
               e.equity_price, e.equity_exchange_at,
               e.equity_first_observed_at, e.option_bid, e.option_ask,
               e.option_bid_depth_lots, e.option_ask_depth_lots, e.option_oi_lots,
               e.option_exchange_at, e.option_first_observed_at,
               e.hypothetical_expiry_move_bps, e.owned_position_trigger_bps,
               e.execution_slippage_bps, e.max_option_spread_bps,
               e.min_option_depth_lots, e.min_option_oi_lots,
               e.max_strike_distance_bps, e.all_in_one_lot_fees_inr,
               e.research_capital_inr, e.max_capital_allocation_bps,
               e.min_hedge_benefit_or_net_credit_inr, e.source_master_sha256
        FROM fno_verified_equity_owned_option e
        JOIN golden_data_provider p ON p.provider_code = e.provider_code
            AND p.source_kind = 'EXTERNAL'
        JOIN golden_source_rights_receipt holdings_rights
            ON holdings_rights.receipt_id = e.holdings_rights_receipt_id
            AND holdings_rights.provider_id = p.provider_id
            AND holdings_rights.source_domain = 'nse_eq_broker_settled_holdings'
            AND holdings_rights.permitted_use = 'MODEL_INFERENCE'
            AND holdings_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt equity_rights
            ON equity_rights.receipt_id = e.equity_tick_rights_receipt_id
            AND equity_rights.provider_id = p.provider_id
            AND equity_rights.source_domain = 'nse_eq_live_underlying_tick'
            AND equity_rights.permitted_use = 'MODEL_INFERENCE'
            AND equity_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt option_rights
            ON option_rights.receipt_id = e.option_chain_rights_receipt_id
            AND option_rights.provider_id = p.provider_id
            AND option_rights.source_domain = 'nse_fo_stock_option_chain_depth_oi'
            AND option_rights.permitted_use = 'MODEL_INFERENCE'
            AND option_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt master_rights
            ON master_rights.receipt_id = e.option_master_rights_receipt_id
            AND master_rights.provider_id = p.provider_id
            AND master_rights.source_domain = 'nse_fo_stock_option_master_delivery'
            AND master_rights.permitted_use = 'MODEL_INFERENCE'
            AND master_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt cost_rights
            ON cost_rights.receipt_id = e.fee_budget_rights_receipt_id
            AND cost_rights.provider_id = p.provider_id
            AND cost_rights.source_domain = 'nse_eq_option_fees_research_budget'
            AND cost_rights.permitted_use = 'MODEL_INFERENCE'
            AND cost_rights.receipt_status = 'VERIFIED'
        WHERE e.account_scope = ?
          AND e.equity_key = ?
          AND e.option_key = ?
          AND e.option_type = ?
          AND e.physical_delivery_match_verified
          AND e.settled_shares_broker_reconciled
          AND e.shares_deliverable_verified
          AND e.ownership_pledge_lien_absent
          AND e.retain_until >= ?
          AND e.captured_at <= ?
          AND e.holdings_source_observed_at <= ?
          AND e.equity_first_observed_at <= ?
          AND e.option_first_observed_at <= ?
          AND e.valid_until >= ?
          AND e.equity_exchange_at <= ?
          AND e.option_exchange_at <= ?
          AND holdings_rights.valid_from <= ?
          AND (holdings_rights.valid_to IS NULL OR holdings_rights.valid_to > ?)
          AND holdings_rights.verified_at <= ?
          AND holdings_rights.captured_at <= ?
          AND equity_rights.valid_from <= ?
          AND (equity_rights.valid_to IS NULL OR equity_rights.valid_to > ?)
          AND equity_rights.verified_at <= ? AND equity_rights.captured_at <= ?
          AND option_rights.valid_from <= ?
          AND (option_rights.valid_to IS NULL OR option_rights.valid_to > ?)
          AND option_rights.verified_at <= ? AND option_rights.captured_at <= ?
          AND master_rights.valid_from <= ?
          AND (master_rights.valid_to IS NULL OR master_rights.valid_to > ?)
          AND master_rights.verified_at <= ? AND master_rights.captured_at <= ?
          AND cost_rights.valid_from <= ?
          AND (cost_rights.valid_to IS NULL OR cost_rights.valid_to > ?)
          AND cost_rights.verified_at <= ? AND cost_rights.captured_at <= ?
        ORDER BY e.option_exchange_at DESC, e.provider_code
        LIMIT 2
        """;

    public record VerifiedClose(String provider, String equityKey, LocalDate session,
                                BigDecimal close, Instant firstKnownAt,
                                Instant verifiedSessionCloseAt) {}
    public record Holding(String provider, String accountScope, String equityKey,
                          int settledOwnedShares, BigDecimal acquisitionCost,
                          Instant observedAt) {}
    public record Option(String provider, String accountScope, String equityKey,
                         String optionKey, String kind, LocalDate listedFrom,
                         LocalDate expiry, int lotSize, BigDecimal strike,
                         BigDecimal tickSize, Holding holding,
                         BigDecimal equityPrice, Instant equityExchangeAt,
                         Instant equityFirstKnownAt,
                         BigDecimal bid, BigDecimal ask, long bidDepthLots,
                         long askDepthLots, long oiLots, Instant optionExchangeAt,
                         Instant optionFirstKnownAt, int expiryMoveBps,
                         int ownedTriggerBps, int slippageBps, int maxSpreadBps,
                         long minDepthLots, long minOiLots, int maxStrikeDistanceBps,
                         BigDecimal allInFeesInr, BigDecimal capitalInr,
                         int maxCapitalAllocationBps, int minimumBenefitInr,
                         String masterDigest) {}

    private final JdbcTemplate jdbc;

    public FnoVerifiedEquityOwnedOptionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public List<VerifiedClose> readCloses(String equityKey, Instant asOf) {
        if (!validStock(equityKey) || asOf == null)
            throw new IllegalArgumentException("OWNED_EQUITY_CLOSE_SOURCE_SCOPE_INVALID");
        LocalDate day = asOf.atZone(IST).toLocalDate();
        Object[] args = new Object[(int) HISTORY_SQL.chars().filter(c -> c == '?').count()];
        args[0] = equityKey;
        args[1] = Date.valueOf(day.minusDays(160));
        args[2] = Date.valueOf(day);
        args[3] = Date.valueOf(day);
        Arrays.fill(args, 4, args.length, Timestamp.from(asOf));
        return jdbc.query(HISTORY_SQL, (rs, i) -> new VerifiedClose(
            rs.getString("provider_code"), rs.getString("equity_key"),
            rs.getObject("session_date", LocalDate.class),
            rs.getBigDecimal("close_price"),
            rs.getTimestamp("first_available_at").toInstant(),
            rs.getTimestamp("session_close_at").toInstant()), args);
    }

    public Optional<Option> oneSettledLot(String accountScope, String equityKey,
                                          String optionKey, String side, Instant asOf) {
        if (accountScope == null || !accountScope.matches("[A-Za-z0-9_-]{8,80}")
                || !validStock(equityKey) || optionKey == null
                || !optionKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || !("PE".equals(side) || "CE".equals(side)) || asOf == null)
            throw new IllegalArgumentException("OWNED_EQUITY_OPTION_SCOPE_INVALID");
        Object[] args = new Object[(int) OWNED_SQL.chars().filter(c -> c == '?').count()];
        args[0] = accountScope;
        args[1] = equityKey;
        args[2] = optionKey;
        args[3] = side;
        args[4] = Date.valueOf(asOf.atZone(IST).toLocalDate());
        Arrays.fill(args, 5, args.length, Timestamp.from(asOf));
        List<Option> rows = jdbc.query(OWNED_SQL, (rs, i) -> {
            String provider = rs.getString("provider_code");
            String account = rs.getString("account_scope");
            String equity = rs.getString("equity_key");
            var holding = new Holding(provider, account, equity,
                rs.getInt("settled_owned_shares"),
                rs.getBigDecimal("average_acquisition_cost"),
                rs.getTimestamp("holdings_source_observed_at").toInstant());
            return new Option(provider, account, equity, rs.getString("option_key"),
                rs.getString("option_type"),
                rs.getObject("listed_from", LocalDate.class),
                rs.getObject("expiry", LocalDate.class),
                rs.getInt("lot_size"), rs.getBigDecimal("strike"),
                rs.getBigDecimal("tick_size"), holding,
                rs.getBigDecimal("equity_price"),
                rs.getTimestamp("equity_exchange_at").toInstant(),
                rs.getTimestamp("equity_first_observed_at").toInstant(),
                rs.getBigDecimal("option_bid"), rs.getBigDecimal("option_ask"),
                rs.getLong("option_bid_depth_lots"), rs.getLong("option_ask_depth_lots"),
                rs.getLong("option_oi_lots"),
                rs.getTimestamp("option_exchange_at").toInstant(),
                rs.getTimestamp("option_first_observed_at").toInstant(),
                rs.getInt("hypothetical_expiry_move_bps"),
                rs.getInt("owned_position_trigger_bps"),
                rs.getInt("execution_slippage_bps"),
                rs.getInt("max_option_spread_bps"),
                rs.getLong("min_option_depth_lots"),
                rs.getLong("min_option_oi_lots"),
                rs.getInt("max_strike_distance_bps"),
                rs.getBigDecimal("all_in_one_lot_fees_inr"),
                rs.getBigDecimal("research_capital_inr"),
                rs.getInt("max_capital_allocation_bps"),
                rs.getInt("min_hedge_benefit_or_net_credit_inr"),
                rs.getString("source_master_sha256"));
        }, args);
        if (rows == null || rows.size() != 1) return Optional.empty();
        var proof = rows.getFirst();
        if (proof == null || !accountScope.equals(proof.accountScope())
                || !equityKey.equals(proof.equityKey())
                || !optionKey.equals(proof.optionKey())
                || !side.equals(proof.kind()) || proof.holding() == null
                || !accountScope.equals(proof.holding().accountScope())
                || !equityKey.equals(proof.holding().equityKey())
                || proof.holding().settledOwnedShares() != proof.lotSize()
                || proof.masterDigest() == null
                || !proof.masterDigest().matches("[a-f0-9]{64}"))
            return Optional.empty();
        return Optional.of(proof);
    }

    private static boolean validStock(String key) {
        return key != null && key.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}");
    }

    public int purgeExpired(LocalDate today) {
        Date cutoff = Date.valueOf(Objects.requireNonNull(today));
        int options = jdbc.update(
            "DELETE FROM fno_verified_equity_owned_option WHERE retain_until < ?", cutoff);
        int history = jdbc.update(
            "DELETE FROM fno_verified_equity_owned_daily_close WHERE retain_until < ?", cutoff);
        return options + history;
    }

    @Scheduled(cron = "0 7 4 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }
}
