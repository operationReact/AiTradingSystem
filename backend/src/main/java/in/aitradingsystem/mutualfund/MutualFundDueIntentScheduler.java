package in.aitradingsystem.mutualfund;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional, preparation-only scheduler. It NEVER invokes a payment provider,
 * submits orders, or fabricates debits/fills/allotments. Live partner activation
 * and payment callbacks are intentionally absent until authorization is proven.
 */
@Component
public final class MutualFundDueIntentScheduler {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private final JdbcClient jdbc;
    private final boolean enabled;

    public MutualFundDueIntentScheduler(JdbcClient jdbc,
            @Value("${minttick.mf.preparation.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    private record QualifiedPlan(UUID id, UUID userId, int sipDay, LocalDate firstDue,
                                 long amountPaise) {}

    @Scheduled(cron = "${minttick.mf.preparation.cron:0 15 8 * * *}", zone = "Asia/Kolkata")
    @Transactional
    public void prepare() {
        if (!enabled) return;
        LocalDate today = LocalDate.now(INDIA);
        // Only mandates verified by a future trusted provider adapter are
        // eligible. Customer-facing APIs have no way to set these states.
        List<QualifiedPlan> plans = jdbc.sql("""
                SELECT p.id, p.user_id, p.sip_day, p.first_due_on, p.amount_paise
                FROM customer_mf_plan p
                JOIN customer_mf_mandate m ON m.id = p.mandate_id
                    AND m.user_id = p.user_id
                WHERE p.status = 'ACTIVE' AND m.status = 'ACTIVE'
                  AND m.verified_at IS NOT NULL
                  AND m.max_debit_paise >= p.amount_paise
                  AND p.max_monthly_paise >= p.amount_paise
                ORDER BY p.id LIMIT 1000
                """)
            .query((rs, row) -> new QualifiedPlan(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getInt("sip_day"), rs.getDate("first_due_on").toLocalDate(),
                rs.getLong("amount_paise"))).list();
        for (QualifiedPlan plan : plans) {
            if (!MutualFundScheduleRules.isDue(today, plan.firstDue(), plan.sipDay())) {
                continue;
            }
            // An ON CONFLICT guard prevents duplicate monthly intents across
            // multiple scheduler executions. Re-check ACTIVE and mandate
            // status at the same database write to avoid stale read grants.
            jdbc.sql("""
                    INSERT INTO customer_mf_purchase_intent
                        (id, plan_id, user_id, due_on, amount_paise, status)
                    SELECT :id, p.id, p.user_id, :due, p.amount_paise, 'PREPARED'
                    FROM customer_mf_plan p
                    JOIN customer_mf_mandate m ON m.id = p.mandate_id
                         AND m.user_id = p.user_id
                    WHERE p.id = :plan AND p.user_id = :user
                      AND p.status = 'ACTIVE' AND m.status = 'ACTIVE'
                      AND m.verified_at IS NOT NULL
                      AND m.max_debit_paise >= p.amount_paise
                    ON CONFLICT (plan_id, due_on) DO NOTHING
                    """)
                .param("id", UUID.randomUUID()).param("plan", plan.id())
                .param("user", plan.userId()).param("due", today).update();
        }
    }
}
