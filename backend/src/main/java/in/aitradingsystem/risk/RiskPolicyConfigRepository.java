package in.aitradingsystem.risk;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RiskPolicyConfigRepository {
    private final JdbcTemplate jdbc;

    public RiskPolicyConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RiskPolicySnapshot requireActive() {
        return jdbc.query("""
                SELECT * FROM risk_policy_config
                WHERE active = TRUE AND effective_from <= now()
                  AND (effective_to IS NULL OR effective_to > now())
                ORDER BY policy_version DESC
                LIMIT 1
                """, (rs, row) -> map(rs)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no active risk policy configured"));
    }

    public List<RiskPolicySnapshot> listAll() {
        return jdbc.query("SELECT * FROM risk_policy_config ORDER BY policy_version DESC", (rs, row) -> map(rs));
    }

    public boolean existsAny() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM risk_policy_config", Integer.class);
        return count != null && count > 0;
    }

    public void insert(RiskPolicySnapshot snapshot, String policyName, boolean active, String createdBy) {
        jdbc.update("""
                INSERT INTO risk_policy_config (
                    id, policy_version, policy_name, active,
                    risk_per_trade_percent, max_daily_drawdown_percent, max_open_positions,
                    min_reward_risk_ratio, max_order_notional, max_gross_exposure_percent,
                    max_symbol_exposure_percent, max_trades_per_day, signal_max_age_minutes,
                    market_state_max_age_seconds, enforce_market_session,
                    reject_existing_symbol_position, reject_duplicate_pending_exposure,
                    fee_rate, slippage_rate, fee_slippage_profile_version,
                    effective_from, effective_to, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), NULL, ?)
                """,
                snapshot.id(), snapshot.version(), policyName, active,
                snapshot.riskPerTradePercent(), snapshot.maxDailyDrawdownPercent(), snapshot.maxOpenPositions(),
                snapshot.minRewardRiskRatio(), snapshot.maxOrderNotional(), snapshot.maxGrossExposurePercent(),
                snapshot.maxSymbolExposurePercent(), snapshot.maxTradesPerDay(), snapshot.signalMaxAgeMinutes(),
                snapshot.marketStateMaxAgeSeconds(), snapshot.enforceMarketSession(),
                snapshot.rejectExistingSymbolPosition(), snapshot.rejectDuplicatePendingExposure(),
                snapshot.feeRate(), snapshot.slippageRate(), snapshot.feeSlippageProfileVersion(), createdBy);
    }

    @Transactional
    public void activate(UUID id) {
        RiskPolicySnapshot current = jdbc.query("""
                SELECT * FROM risk_policy_config
                WHERE active = TRUE
                FOR UPDATE
                """, (rs, row) -> map(rs)).stream().findFirst().orElse(null);

        if (current != null && current.id().equals(id)) return;

        int targetExists = jdbc.queryForObject("SELECT COUNT(*) FROM risk_policy_config WHERE id = ?", Integer.class, id);
        if (targetExists != 1) throw new IllegalArgumentException("risk policy not found: " + id);

        jdbc.update("UPDATE risk_policy_config SET active = FALSE, effective_to = now() WHERE active = TRUE");
        int rows = jdbc.update("UPDATE risk_policy_config SET active = TRUE, effective_from = now(), effective_to = NULL WHERE id = ?", id);
        if (rows != 1) throw new IllegalStateException("risk policy activation failed: " + id);
    }

    private static RiskPolicySnapshot map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RiskPolicySnapshot(
                rs.getObject("id", UUID.class),
                rs.getInt("policy_version"),
                rs.getBigDecimal("risk_per_trade_percent"),
                rs.getBigDecimal("max_daily_drawdown_percent"),
                rs.getInt("max_open_positions"),
                rs.getBigDecimal("min_reward_risk_ratio"),
                rs.getBigDecimal("max_order_notional"),
                rs.getBigDecimal("max_gross_exposure_percent"),
                rs.getBigDecimal("max_symbol_exposure_percent"),
                rs.getInt("max_trades_per_day"),
                rs.getInt("signal_max_age_minutes"),
                rs.getInt("market_state_max_age_seconds"),
                rs.getBoolean("enforce_market_session"),
                rs.getBoolean("reject_existing_symbol_position"),
                rs.getBoolean("reject_duplicate_pending_exposure"),
                rs.getBigDecimal("fee_rate"),
                rs.getBigDecimal("slippage_rate"),
                rs.getString("fee_slippage_profile_version"));
    }
}
