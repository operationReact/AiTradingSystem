package in.aitradingsystem.tradingrooms;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Independent, read-only shared PAPER wallet cash/P&L audit. Both cash-equity
 * and F&O fills debit the same wallet. This audit does not validate instrument
 * holdings, fresh quotes, source rights or broker execution.
 */
@Service
public class CustomerPaperSharedWalletAuditService {
    private static final int MAX_ROWS=5000;
    public record Audit(String status, String scope, Instant checkedAt,
            int equityFills, int optionFills, int initialAllocations,
            Long expectedCashPaise, Long recordedCashPaise,
            Long expectedRealizedPnlPaise, Long recordedRealizedPnlPaise,
            boolean cashAndRealizedPnlReconciled, boolean positionsReconciled,
            boolean marketDataVerified, boolean providerRightsVerified,
            boolean tradingApproved, List<String> blockerCodes) {}

    private record Wallet(long initial, long cash, long reserved, long realized) {}
    private record Allocation(String kind, long delta, long cashAfter) {}
    private record Fill(String side, long notional, long fees, long realized, boolean option) {}

    private final JdbcClient jdbc;

    public CustomerPaperSharedWalletAuditService(JdbcClient jdbc) {
        this.jdbc=jdbc;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Audit inspect(UUID owner, UUID room) {
        if(owner==null||room==null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Customer required");
        Wallet wallet=jdbc.sql("""
            SELECT a.initial_cash_paise,a.available_cash_paise,
                   a.reserved_cash_paise,a.realized_pnl_paise
            FROM customer_paper_account a
            JOIN customer_trading_room r ON r.id=a.room_id AND r.user_id=a.user_id
            WHERE a.room_id=:room AND a.user_id=:owner
              AND r.mode='PAPER' AND r.status='PAPER_FUNDED'
            """).param("room",room).param("owner",owner)
            .query((rs,n)->new Wallet(rs.getLong("initial_cash_paise"),
                    rs.getLong("available_cash_paise"),
                    rs.getLong("reserved_cash_paise"),rs.getLong("realized_pnl_paise")))
            .optional().orElseThrow(()->new ResponseStatusException(
                    HttpStatus.NOT_FOUND,"Funded PAPER Room not found"));

        List<Allocation> events=jdbc.sql("""
            SELECT event_kind,cash_delta_paise,cash_after_paise
            FROM customer_paper_cash_event
            WHERE user_id=:owner AND room_id=:room
            ORDER BY created_at,id LIMIT :limit
            """).param("owner",owner).param("room",room).param("limit",MAX_ROWS+1)
            .query((rs,n)->new Allocation(rs.getString("event_kind"),
                    rs.getLong("cash_delta_paise"),rs.getLong("cash_after_paise")))
            .list();
        List<Fill> equity=jdbc.sql("""
            SELECT side,notional_paise,fees_paise,realized_pnl_delta_paise
            FROM customer_paper_manual_order
            WHERE user_id=:owner AND room_id=:room
            ORDER BY created_at,id LIMIT :limit
            """).param("owner",owner).param("room",room).param("limit",MAX_ROWS+1)
            .query((rs,n)->new Fill(rs.getString("side"),rs.getLong("notional_paise"),
                    rs.getLong("fees_paise"),rs.getLong("realized_pnl_delta_paise"),false))
            .list();
        List<Fill> options=jdbc.sql("""
            SELECT side,notional_paise,estimated_fees_paise,realized_pnl_delta_paise
            FROM customer_paper_fno_order
            WHERE user_id=:owner AND room_id=:room
            ORDER BY created_at,id LIMIT :limit
            """).param("owner",owner).param("room",room).param("limit",MAX_ROWS+1)
            .query((rs,n)->new Fill(rs.getString("side"),rs.getLong("notional_paise"),
                    rs.getLong("estimated_fees_paise"),rs.getLong("realized_pnl_delta_paise"),true))
            .list();

        LinkedHashSet<String> issues=new LinkedHashSet<>();
        if(events.size()!=1 || events.size()>MAX_ROWS)
            issues.add("INITIAL_ALLOCATION_COUNT_NOT_ONE");
        if(equity.size()>MAX_ROWS || options.size()>MAX_ROWS)
            issues.add("AUDIT_ROW_LIMIT");
        if(wallet.reserved()!=0)
            issues.add("RESERVED_CASH_NOT_SUPPORTED_BY_AUDIT");
        Long expectedCash=null, expectedRealized=null;
        if(!issues.contains("AUDIT_ROW_LIMIT")&&events.size()==1) {
            Allocation initial=events.getFirst();
            if(!"INITIAL_PAPER_ALLOCATION".equals(initial.kind())
                    || initial.delta()!=wallet.initial()
                    || initial.cashAfter()!=wallet.initial())
                issues.add("INITIAL_ALLOCATION_MISMATCH");
            if(!issues.contains("INITIAL_ALLOCATION_MISMATCH")) {
                try {
                    long cash=initial.delta(),realized=0;
                    List<Fill> all=new ArrayList<>(equity.size()+options.size());
                    all.addAll(equity);
                    all.addAll(options);
                    for(Fill fill:all) {
                        if(fill.notional()<=0 || fill.fees()<=0
                                || (!"BUY".equals(fill.side())&&!"SELL".equals(fill.side()))) {
                            issues.add("INVALID_FILL_CASH_FIELDS");
                            continue;
                        }
                        long expectedFee=fill.option()
                                ?Math.max(1,Math.addExact(fill.notional(),1999)/2000)
                                :CustomerPaperOrderService.simulatedFees(fill.notional());
                        if(fill.fees()!=expectedFee)
                            issues.add("ORDER_FEE_MISMATCH");
                        if("BUY".equals(fill.side())&&fill.realized()!=0)
                            issues.add("BUY_WITH_REALIZED_PNL");
                        long flow="BUY".equals(fill.side())
                                ?-Math.addExact(fill.notional(),fill.fees())
                                :Math.subtractExact(fill.notional(),fill.fees());
                        cash=Math.addExact(cash,flow);
                        realized=Math.addExact(realized,fill.realized());
                    }
                    expectedCash=cash;
                    expectedRealized=realized;
                    if(cash!=wallet.cash())
                        issues.add("SHARED_WALLET_CASH_MISMATCH");
                    if(realized!=wallet.realized())
                        issues.add("SHARED_WALLET_REALIZED_PNL_MISMATCH");
                } catch(ArithmeticException ex) {
                    issues.add("WALLET_ARITHMETIC_OVERFLOW");
                }
            }
        }
        String status=issues.isEmpty()?"CASH_AND_REALIZED_PNL_MATCHED"
                :issues.contains("AUDIT_ROW_LIMIT")?"INCOMPLETE":"INCONSISTENT";
        return new Audit(status,"CASH_AND_REALIZED_PNL_ONLY",Instant.now(),
                Math.min(equity.size(),MAX_ROWS),Math.min(options.size(),MAX_ROWS),
                Math.min(events.size(),MAX_ROWS),expectedCash,wallet.cash(),
                expectedRealized,wallet.realized(),issues.isEmpty(),
                false,false,false,false,List.copyOf(issues));
    }
}
