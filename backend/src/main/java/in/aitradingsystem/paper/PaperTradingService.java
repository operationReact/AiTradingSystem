package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.notification.NotificationService;
import in.aitradingsystem.notification.NotificationSeverity;
import in.aitradingsystem.paper.PaperViews.DecisionView;
import in.aitradingsystem.paper.PaperViews.OrderResult;
import in.aitradingsystem.paper.PaperViews.OrderView;
import in.aitradingsystem.risk.RiskRuleResult;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backward-compatible PAPER facade. Phase 6 orchestration now lives in {@link PaperOrderService}; this class
 * intentionally keeps the existing API/query integration surface stable while decomposition proceeds.
 */
@Service
public class PaperTradingService {
    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final PaperOrderService orders;
    private final PaperOrderRepository orderRepository;
    private final PaperAccountRepository accounts;
    private final AuditService audit;
    private final Clock clock;
    private final NotificationService notifications;

    public PaperTradingService(PaperOrderService orders, PaperOrderRepository orderRepository,
                               PaperAccountRepository accounts, AuditService audit, Clock clock, NotificationService notifications) {
        this.orders = orders;
        this.orderRepository = orderRepository;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
        this.notifications = notifications;
    }

    public OrderResult placeOrder(OrderCommand command, String idempotencyKey) {
        return orders.placeOrder(command, idempotencyKey);
    }

    @Transactional
    public void setNewOrdersHalted(boolean halted, String reason) {
        accounts.setNewOrdersHalted(halted, reason, clock.instant());
        audit.record("EMERGENCY_STOP_CHANGED", "TRADING_CONTROL", "1",
            Map.of("newOrdersHalted", halted, "reason", reason == null ? "" : reason));
        if (halted) notifications.raise("RISK_EMERGENCY_STOP", NotificationSeverity.CRITICAL,
            "PAPER emergency stop is active", "New PAPER orders are halted. Review the persisted trading controls.",
            "TRADING_CONTROL", "1", "RISK_EMERGENCY_STOP:1");
    }

    public boolean isHalted() {
        return accounts.isHalted();
    }

    Instant startOfMarketDay(Instant now) {
        return now.atZone(MARKET_ZONE).toLocalDate().atStartOfDay(MARKET_ZONE).toInstant();
    }

    OrderResult loadResult(UUID orderId, boolean replayed) {
        return orderRepository.loadResult(orderId, replayed);
    }

    static OrderView toOrderView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return PaperOrderRepository.toOrderView(rs);
    }

    static DecisionView toDecisionView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return PaperOrderRepository.toDecisionView(rs);
    }

    static List<String> parseReasons(String json) {
        return PaperOrderRepository.parseReasons(json);
    }

    static String reasonsText(List<RiskRuleResult> results) {
        return PaperOrderService.reasonsText(results);
    }

    static OffsetDateTime ts(Instant instant) {
        return PaperOrderRepository.ts(instant);
    }

    static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return PaperOrderRepository.instant(rs, column);
    }
}
