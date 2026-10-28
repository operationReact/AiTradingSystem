package in.aitradingsystem.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only aggregate analytics for the persisted PAPER order lifecycle. */
@Service
@Transactional(readOnly = true)
public class PaperOrderAnalyticsService {
    private static final BigDecimal ZERO_PERCENT = BigDecimal.ZERO.setScale(2);

    private final JdbcClient jdbc;
    private final Clock clock;

    public PaperOrderAnalyticsService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public OrderAnalytics snapshot() {
        long total = count("SELECT COUNT(*) FROM paper_order");
        long filled = count("SELECT COUNT(*) FROM paper_order WHERE status = 'FILLED'");
        long rejected = count("SELECT COUNT(*) FROM paper_order WHERE status = 'REJECTED'");
        long riskApproved = count("SELECT COUNT(*) FROM paper_order WHERE status = 'RISK_APPROVED'");
        long fresh = count("SELECT COUNT(*) FROM paper_order WHERE status = 'NEW'");
        long distinctSymbols = count("SELECT COUNT(DISTINCT symbol) FROM paper_order");

        BigDecimal fillRate = percentage(filled, total);
        BigDecimal rejectionRate = percentage(rejected, total);
        return new OrderAnalytics(total, fresh, riskApproved, filled, rejected, distinctSymbols,
            fillRate, rejectionRate, Instant.now(clock));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static BigDecimal percentage(long value, long total) {
        if (total == 0) return ZERO_PERCENT;
        return BigDecimal.valueOf(value)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    public record OrderAnalytics(
        long totalOrders,
        long newOrders,
        long riskApprovedOrders,
        long filledOrders,
        long rejectedOrders,
        long distinctSymbols,
        BigDecimal fillRatePercent,
        BigDecimal rejectionRatePercent,
        Instant asOf) {}
}
