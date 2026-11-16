package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only owner-scoped forensic consistency check for virtual F&O order history.
 *
 * No source, quote, broker, rights flag, wallet update or order placement occurs.
 * The shared PAPER wallet also serves equities, so this audit deliberately does
 * NOT claim that its balance can be reconciled from F&O fills alone.
 */
@Service
public class CustomerPaperFnoReconciliationService {
    private static final int MAX_ROWS=5000;
    private static final BigDecimal PAISA=BigDecimal.valueOf(100);

    public record Receipt(String status, Instant checkedAt, int recordedFills,
            int buyFills, int sellFills, int completedRoundTrips,
            int openContracts, long computedRealizedPnlPaise,
            long recordedRealizedPnlPaise, List<String> blockerCodes,
            boolean walletReconciled, boolean marketDataVerified,
            boolean providerRightsVerified, boolean tradingApproved) {}

    private record Fill(String key, String side, LocalDate expiry,
            String kind, BigDecimal strike, int lot, int qty,
            BigDecimal fillPrice, long notional, long fees, long realized) {}
    private record Position(String key, LocalDate expiry, String kind,
            BigDecimal strike, int lot, int qty, long basis) {}

    private static final class Ledger {
        final LocalDate expiry;
        final String kind;
        final BigDecimal strike;
        final int lot;
        int qty;
        long basis;

        Ledger(Fill order) {
            expiry=order.expiry();
            kind=order.kind();
            strike=order.strike();
            lot=order.lot();
        }
        boolean same(Fill order) {
            return expiry.equals(order.expiry()) && kind.equals(order.kind())
                && strike.compareTo(order.strike())==0 && lot==order.lot();
        }
        boolean same(Position row) {
            return expiry.equals(row.expiry()) && kind.equals(row.kind())
                && strike.compareTo(row.strike())==0 && lot==row.lot();
        }
    }

    private final JdbcClient jdbc;

    public CustomerPaperFnoReconciliationService(JdbcClient jdbc) {
        this.jdbc=jdbc;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Receipt inspect(UUID owner, UUID room) {
        if (owner==null || room==null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer required");
        boolean owned=jdbc.sql("""
            SELECT TRUE FROM customer_trading_room
            WHERE id=:room AND user_id=:owner AND mode='PAPER' AND status='PAPER_FUNDED'
            """).param("room",room).param("owner",owner)
            .query(Boolean.class).optional().orElse(false);
        if (!owned)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Funded PAPER Room not found");

        List<Fill> fills=jdbc.sql("""
            SELECT instrument_key,side,expiry,option_type,strike_price,lot_size,
                   quantity,fill_price,notional_paise,estimated_fees_paise,
                   realized_pnl_delta_paise
            FROM customer_paper_fno_order
            WHERE room_id=:room AND user_id=:owner
            ORDER BY created_at ASC,id ASC LIMIT :limit
            """).param("room",room).param("owner",owner).param("limit",MAX_ROWS+1)
            .query((rs,n)->new Fill(rs.getString("instrument_key"),rs.getString("side"),
                    rs.getObject("expiry",LocalDate.class),rs.getString("option_type"),
                    rs.getBigDecimal("strike_price"),rs.getInt("lot_size"),
                    rs.getInt("quantity"),rs.getBigDecimal("fill_price"),
                    rs.getLong("notional_paise"),rs.getLong("estimated_fees_paise"),
                    rs.getLong("realized_pnl_delta_paise"))).list();
        List<Position> positions=jdbc.sql("""
            SELECT instrument_key,expiry,option_type,strike_price,lot_size,
                   quantity,cost_basis_paise
            FROM customer_paper_fno_position
            WHERE room_id=:room AND user_id=:owner
            ORDER BY instrument_key LIMIT :limit
            """).param("room",room).param("owner",owner).param("limit",MAX_ROWS+1)
            .query((rs,n)->new Position(rs.getString("instrument_key"),
                    rs.getObject("expiry",LocalDate.class),rs.getString("option_type"),
                    rs.getBigDecimal("strike_price"),rs.getInt("lot_size"),
                    rs.getInt("quantity"),rs.getLong("cost_basis_paise"))).list();

        if (fills.size()>MAX_ROWS || positions.size()>MAX_ROWS)
            return receipt("INCOMPLETE",Math.min(fills.size(),MAX_ROWS),
                    0,0,0,0,0,0,List.of("RECONCILIATION_ROW_LIMIT"));

        var issues=new LinkedHashSet<String>();
        Map<String,Ledger> ledgers=new HashMap<>();
        int buys=0,sells=0,roundTrips=0;
        long calculatedRealized=0,storedRealized=0;

        for (Fill fill : fills) {
            try {
                storedRealized=Math.addExact(storedRealized,fill.realized());
                if (fill.key()==null || fill.expiry()==null || fill.kind()==null
                        || fill.strike()==null || fill.strike().signum()<=0
                        || fill.lot()<1 || fill.qty()<1 || fill.qty()%fill.lot()!=0
                        || fill.fillPrice()==null || fill.fillPrice().signum()<=0
                        || (!"CE".equals(fill.kind()) && !"PE".equals(fill.kind()))) {
                    issues.add("INVALID_FILL_METADATA");
                    continue;
                }
                long notional=fill.fillPrice().multiply(PAISA)
                        .multiply(BigDecimal.valueOf(fill.qty()))
                        .setScale(0,RoundingMode.HALF_UP).longValueExact();
                long estimatedFee=Math.max(1,Math.addExact(notional,1999)/2000);
                if (notional!=fill.notional() || estimatedFee!=fill.fees())
                    issues.add("NOTIONAL_OR_FEE_MISMATCH");

                Ledger state=ledgers.computeIfAbsent(fill.key(),k->new Ledger(fill));
                if (!state.same(fill)) {
                    issues.add("CONTRACT_METADATA_MISMATCH");
                    continue;
                }
                if ("BUY".equals(fill.side())) {
                    buys++;
                    if (fill.realized()!=0) issues.add("BUY_RECORDED_REALIZED_PNL");
                    state.qty=Math.addExact(state.qty,fill.qty());
                    state.basis=Math.addExact(state.basis,Math.addExact(notional,estimatedFee));
                } else if ("SELL".equals(fill.side())) {
                    sells++;
                    if (fill.qty()>state.qty) {
                        issues.add("OVERSOLD_ORDER_HISTORY");
                        continue;
                    }
                    int remaining=state.qty-fill.qty();
                    long allocated=remaining==0 ? state.basis
                            : Math.multiplyExact(state.basis,fill.qty())/state.qty;
                    long expectedRealized=Math.subtractExact(
                            Math.subtractExact(notional,estimatedFee),allocated);
                    calculatedRealized=Math.addExact(calculatedRealized,expectedRealized);
                    if (fill.realized()!=expectedRealized)
                        issues.add("REALIZED_PNL_MISMATCH");
                    state.basis=Math.subtractExact(state.basis,allocated);
                    state.qty=remaining;
                    if (remaining==0) roundTrips++;
                } else issues.add("INVALID_FILL_SIDE");
            } catch (ArithmeticException ex) {
                issues.add("FILL_ARITHMETIC_OVERFLOW");
            }
        }

        Map<String,Position> recorded=new HashMap<>();
        for (Position row : positions) {
            if (recorded.put(row.key(),row)!=null)
                issues.add("DUPLICATE_POSITION");
            Ledger computed=ledgers.get(row.key());
            if (computed==null) {
                issues.add("UNEXPLAINED_POSITION");
            } else if (!computed.same(row) || computed.qty!=row.qty()
                    || computed.basis!=row.basis()) {
                issues.add("POSITION_BASIS_OR_QUANTITY_MISMATCH");
            }
        }
        for (String key : ledgers.keySet()) {
            if (!recorded.containsKey(key)) issues.add("MISSING_POSITION_ROW");
        }
        int open=(int)ledgers.values().stream().filter(x->x.qty>0).count();
        String status=!issues.isEmpty()?"INCONSISTENT"
                :fills.isEmpty()?"NO_FILLS":"BOOKKEEPING_MATCHED";
        return receipt(status,fills.size(),buys,sells,roundTrips,open,
                calculatedRealized,storedRealized,List.copyOf(issues));
    }

    private static Receipt receipt(String status,int total,int buys,int sells,
            int roundTrips,int open,long computed,long stored,List<String> blockers) {
        return new Receipt(status,Instant.now(),total,buys,sells,roundTrips,
                open,computed,stored,blockers,false,false,false,false);
    }
}
