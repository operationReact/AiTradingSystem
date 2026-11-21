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
 * Read-only point-in-time NIFTY daily close and CE/PE premium/depth/OI source.
 * All prices, strike/expiry/tick/lot metadata, original observation times and
 * capital/fee inputs require separate verified provider-specific source rows.
 * The V106 custody tables are EMPTY: no synthetic fallback or broker orders.
 */
@Component
public class FnoVerifiedNiftyLongOptionSourceStore {
    public static final String NIFTY = "NSE_INDEX|Nifty 50";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String CLOSE_SQL = """
        SELECT c.provider_code, c.index_key, c.session_date,
               c.close_price, c.first_available_at, cal.session_close_at
        FROM fno_verified_nifty_daily_close c
        JOIN golden_data_provider provider ON provider.provider_code = c.provider_code
            AND provider.source_kind = 'EXTERNAL'
        JOIN golden_historical_exchange_session_evidence cal
            ON cal.evidence_id = c.calendar_evidence_id
            AND cal.provider_id = provider.provider_id
            AND cal.source_domain = 'nse_index_trading_calendar'
            AND cal.exchange = 'NSE' AND cal.session_date = c.session_date
            AND cal.session_status = 'TRADING'
        JOIN golden_source_rights_receipt rights
            ON rights.receipt_id = c.index_close_rights_receipt_id
            AND rights.provider_id = provider.provider_id
            AND rights.source_domain = 'nse_nifty_completed_daily_close'
            AND rights.permitted_use = 'MODEL_INFERENCE'
            AND rights.receipt_status = 'VERIFIED'
        WHERE c.index_key = ?
          AND c.session_date BETWEEN ? AND ?
          AND c.retain_until >= ?
          AND c.captured_at <= ?
          AND c.first_available_at >= cal.session_close_at
          AND c.first_available_at <= ?
          AND cal.first_available_at <= ?
          AND cal.verified_at <= ? AND cal.captured_at <= ?
          AND rights.valid_from <= ?
          AND (rights.valid_to IS NULL OR rights.valid_to > ?)
          AND rights.verified_at <= ? AND rights.captured_at <= ?
        ORDER BY c.session_date, c.provider_code
        LIMIT 100
        """;

    private static final String OPTION_SQL = """
        SELECT q.provider_code, q.index_key, q.instrument_key,
               q.option_type, q.listed_from, q.expiry, q.lot_size,
               q.strike, q.tick_size, q.index_price, q.index_exchange_at,
               q.index_first_observed_at, q.option_bid, q.option_ask,
               q.ask_depth_lots, q.open_interest_lots, q.option_exchange_at,
               q.option_first_observed_at, q.hypothetical_move_at_expiry_bps,
               q.buy_slippage_bps, q.all_in_fees_per_lot_inr,
               q.minimum_scenario_reward_risk_bps, q.maximum_option_spread_bps,
               q.maximum_strike_distance_bps, q.minimum_ask_depth_lots,
               q.minimum_open_interest_lots, q.research_capital_inr,
               q.maximum_capital_allocation_bps, q.contract_master_sha256
        FROM fno_verified_nifty_long_option_quote q
        JOIN golden_data_provider provider ON provider.provider_code = q.provider_code
            AND provider.source_kind = 'EXTERNAL'
        JOIN golden_source_rights_receipt tick_rights
            ON tick_rights.receipt_id = q.underlying_tick_rights_receipt_id
            AND tick_rights.provider_id = provider.provider_id
            AND tick_rights.source_domain = 'nse_nifty_underlying_tick'
            AND tick_rights.permitted_use = 'MODEL_INFERENCE'
            AND tick_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt option_rights
            ON option_rights.receipt_id = q.option_quote_rights_receipt_id
            AND option_rights.provider_id = provider.provider_id
            AND option_rights.source_domain = 'nse_fo_option_chain_depth_oi'
            AND option_rights.permitted_use = 'MODEL_INFERENCE'
            AND option_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt master_rights
            ON master_rights.receipt_id = q.option_master_rights_receipt_id
            AND master_rights.provider_id = provider.provider_id
            AND master_rights.source_domain = 'nse_fo_option_instrument_master'
            AND master_rights.permitted_use = 'MODEL_INFERENCE'
            AND master_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt risk_rights
            ON risk_rights.receipt_id = q.fee_risk_rights_receipt_id
            AND risk_rights.provider_id = provider.provider_id
            AND risk_rights.source_domain = 'nse_fo_option_fees_research_capital'
            AND risk_rights.permitted_use = 'MODEL_INFERENCE'
            AND risk_rights.receipt_status = 'VERIFIED'
        WHERE q.contract_master_verified
          AND q.lot_units_reconciled
          AND q.fee_tax_schedule_verified
          AND q.research_budget_verified
          AND q.instrument_key = ?
          AND q.option_type = ?
          AND q.retain_until >= ?
          AND q.captured_at <= ?
          AND q.index_first_observed_at <= ?
          AND q.option_first_observed_at <= ?
          AND q.valid_until >= ?
          AND q.index_exchange_at <= ?
          AND q.option_exchange_at <= ?
          AND tick_rights.valid_from <= ?
          AND (tick_rights.valid_to IS NULL OR tick_rights.valid_to > ?)
          AND tick_rights.verified_at <= ? AND tick_rights.captured_at <= ?
          AND option_rights.valid_from <= ?
          AND (option_rights.valid_to IS NULL OR option_rights.valid_to > ?)
          AND option_rights.verified_at <= ? AND option_rights.captured_at <= ?
          AND master_rights.valid_from <= ?
          AND (master_rights.valid_to IS NULL OR master_rights.valid_to > ?)
          AND master_rights.verified_at <= ? AND master_rights.captured_at <= ?
          AND risk_rights.valid_from <= ?
          AND (risk_rights.valid_to IS NULL OR risk_rights.valid_to > ?)
          AND risk_rights.verified_at <= ? AND risk_rights.captured_at <= ?
        ORDER BY q.option_exchange_at DESC, q.provider_code
        LIMIT 2
        """;

    public record VerifiedClose(String provider, String indexKey,
                                LocalDate session, BigDecimal close,
                                Instant firstKnownAt, Instant verifiedSessionCloseAt) {}

    public record OptionProof(String provider, String indexKey, String optionKey,
                              String optionType, LocalDate listedFrom, LocalDate expiry,
                              int lotSize, BigDecimal strike, BigDecimal tickSize,
                              BigDecimal indexPrice, Instant indexExchangeAt,
                              Instant indexFirstObservedAt,
                              BigDecimal optionBid, BigDecimal optionAsk,
                              long askDepthLots, long openInterestLots,
                              Instant optionExchangeAt, Instant optionFirstObservedAt,
                              int hypotheticalMoveAtExpiryBps, int buySlippageBps,
                              BigDecimal allInFeesPerLotInr,
                              int minScenarioRewardRiskBps, int maxOptionSpreadBps,
                              int maxStrikeDistanceBps, long minAskDepthLots,
                              long minOpenInterestLots, BigDecimal researchCapitalInr,
                              int maxCapitalAllocationBps, String contractMasterSha256) {}

    private final JdbcTemplate jdbc;

    public FnoVerifiedNiftyLongOptionSourceStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /** Only source-verified, original first-known daily observations, never LTP. */
    public List<VerifiedClose> verifiedRecentCloses(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        LocalDate today = asOf.atZone(IST).toLocalDate();
        Object[] params = new Object[(int) CLOSE_SQL.chars().filter(c -> c == '?').count()];
        params[0] = NIFTY;
        params[1] = Date.valueOf(today.minusDays(160));
        params[2] = Date.valueOf(today);
        params[3] = Date.valueOf(today);
        Arrays.fill(params, 4, params.length, Timestamp.from(asOf));
        return jdbc.query(CLOSE_SQL, (rs, i) -> new VerifiedClose(
            rs.getString("provider_code"), rs.getString("index_key"),
            rs.getObject("session_date", LocalDate.class),
            rs.getBigDecimal("close_price"),
            rs.getTimestamp("first_available_at").toInstant(),
            rs.getTimestamp("session_close_at").toInstant()), params);
    }

    /** Explicit source-listed option key prevents silent strike or expiry choice. */
    public Optional<OptionProof> oneCurrentOption(String instrumentKey, String side,
                                                   Instant asOf) {
        if (instrumentKey == null
                || !instrumentKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
                || !("CE".equals(side) || "PE".equals(side)) || asOf == null) {
            throw new IllegalArgumentException("NIFTY_OPTION_SOURCE_SCOPE_INVALID");
        }
        Object[] params = new Object[(int) OPTION_SQL.chars().filter(c -> c == '?').count()];
        params[0] = instrumentKey;
        params[1] = side;
        params[2] = Date.valueOf(asOf.atZone(IST).toLocalDate());
        Arrays.fill(params, 3, params.length, Timestamp.from(asOf));
        List<OptionProof> rows = jdbc.query(OPTION_SQL, (rs, i) -> new OptionProof(
            rs.getString("provider_code"), rs.getString("index_key"),
            rs.getString("instrument_key"), rs.getString("option_type"),
            rs.getObject("listed_from", LocalDate.class),
            rs.getObject("expiry", LocalDate.class), rs.getInt("lot_size"),
            rs.getBigDecimal("strike"), rs.getBigDecimal("tick_size"),
            rs.getBigDecimal("index_price"),
            rs.getTimestamp("index_exchange_at").toInstant(),
            rs.getTimestamp("index_first_observed_at").toInstant(),
            rs.getBigDecimal("option_bid"), rs.getBigDecimal("option_ask"),
            rs.getLong("ask_depth_lots"), rs.getLong("open_interest_lots"),
            rs.getTimestamp("option_exchange_at").toInstant(),
            rs.getTimestamp("option_first_observed_at").toInstant(),
            rs.getInt("hypothetical_move_at_expiry_bps"),
            rs.getInt("buy_slippage_bps"),
            rs.getBigDecimal("all_in_fees_per_lot_inr"),
            rs.getInt("minimum_scenario_reward_risk_bps"),
            rs.getInt("maximum_option_spread_bps"),
            rs.getInt("maximum_strike_distance_bps"),
            rs.getLong("minimum_ask_depth_lots"),
            rs.getLong("minimum_open_interest_lots"),
            rs.getBigDecimal("research_capital_inr"),
            rs.getInt("maximum_capital_allocation_bps"),
            rs.getString("contract_master_sha256")), params);
        if (rows == null || rows.size() != 1) return Optional.empty();
        OptionProof proof = rows.getFirst();
        if (proof == null || !instrumentKey.equals(proof.optionKey())
                || !side.equals(proof.optionType()) || !NIFTY.equals(proof.indexKey())
                || proof.contractMasterSha256() == null
                || !proof.contractMasterSha256().matches("[a-f0-9]{64}")) return Optional.empty();
        return Optional.of(proof);
    }

    public int purgeExpired(LocalDate today) {
        Date cutoff = Date.valueOf(Objects.requireNonNull(today));
        int quotes = jdbc.update(
            "DELETE FROM fno_verified_nifty_long_option_quote WHERE retain_until < ?", cutoff);
        int closes = jdbc.update(
            "DELETE FROM fno_verified_nifty_daily_close WHERE retain_until < ?", cutoff);
        return quotes + closes;
    }

    @Scheduled(cron = "0 59 3 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }
}
