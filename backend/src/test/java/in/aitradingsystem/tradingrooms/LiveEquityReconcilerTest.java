package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.tradingrooms.BrokerOrderRecord.BrokerReportedStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveEquityReconcilerTest {
    private static final String TAG = "MTabcdefgh12";
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    private static LiveEquityReconciler.Expected expected(String knownBrokerOrderId) {
        return new LiveEquityReconciler.Expected("UPSTOX", TAG, "NSE", "RELIANCE",
            "BUY", 10, PRICE, knownBrokerOrderId);
    }

    /** A fully matching record with the given status and fill. */
    private static BrokerOrderRecord rec(String orderId, BrokerReportedStatus status,
            Integer filled) {
        return new BrokerOrderRecord(orderId, TAG, "NSE", "RELIANCE", "BUY", 10,
            new BigDecimal("100.0"), "INTRADAY", "LIMIT", "DAY", status, filled,
            filled == null || filled == 0 ? null : new BigDecimal("100.00"));
    }

    @Test
    void nullBrokerHistoryStaysReserved() {
        var res = LiveEquityReconciler.reconcile(expected(null), null);
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("BROKER_HISTORY_UNAVAILABLE");
    }

    @Test
    void emptyHistoryIsNotProofOfRejection() {
        var res = LiveEquityReconciler.reconcile(expected(null), List.of());
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("NO_TAGGED_BROKER_ORDER_FOUND");
    }

    @Test
    void unrelatedTagsAreIgnoredAndLeaveOrderReserved() {
        var other = new BrokerOrderRecord("X", "MTotherorder9", "NSE", "RELIANCE",
            "BUY", 10, PRICE, "INTRADAY", "LIMIT", "DAY", BrokerReportedStatus.FILLED, 10, PRICE);
        var res = LiveEquityReconciler.reconcile(expected(null), List.of(other));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("NO_TAGGED_BROKER_ORDER_FOUND");
    }

    @Test
    void fullFillResolvesToFilled() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.FILLED, 10)));
        assertThat(res.reserved()).isFalse();
        assertThat(res.observedState()).isEqualTo(LiveEquityOrderState.FILLED);
        assertThat(res.brokerOrderId()).isEqualTo("BRK1");
        assertThat(res.filledQuantity()).isEqualTo(10);
    }

    @Test
    void filledButQuantityShortIsContradictoryAndReserved() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.FILLED, 7)));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("FILLED_QUANTITY_MISMATCH");
    }

    @Test
    void rejectResolvesToRejected() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.REJECTED, 0)));
        assertThat(res.observedState()).isEqualTo(LiveEquityOrderState.REJECTED);
    }

    @Test
    void rejectedWithFillsIsContradictoryAndReserved() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.REJECTED, 3)));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("REJECTED_WITH_FILLS_CONTRADICTION");
    }

    @Test
    void cleanCancelResolvesToCancelled() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.CANCELLED, 0)));
        assertThat(res.observedState()).isEqualTo(LiveEquityOrderState.CANCELLED);
        assertThat(res.filledQuantity()).isZero();
    }

    @Test
    void cancelAfterPartialFillNeverBecomesACleanCancellation() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.CANCELLED, 4)));
        // Exposure must be preserved: the order stays partially filled, reserved.
        assertThat(res.reserved()).isFalse();
        assertThat(res.observedState()).isEqualTo(LiveEquityOrderState.PARTIALLY_FILLED);
        assertThat(res.filledQuantity()).isEqualTo(4);
        assertThat(res.outcomeCode()).isEqualTo("CANCELLED_AFTER_PARTIAL_FILL_RESERVED");
    }

    @Test
    void partialFillResolvesToPartiallyFilled() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.OPEN, 6)));
        assertThat(res.observedState()).isEqualTo(LiveEquityOrderState.PARTIALLY_FILLED);
        assertThat(res.filledQuantity()).isEqualTo(6);
    }

    @Test
    void openWithNoFillResolvesToAcknowledged() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.OPEN, 0)));
        assertThat(res.observedState()).isEqualTo(LiveEquityOrderState.ACKNOWLEDGED);
    }

    @Test
    void malformedFillQuantityIsReserved() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.OPEN, 999)));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("BROKER_FILL_QUANTITY_MALFORMED");
    }

    @Test
    void unmappedStatusIsReserved() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.UNMAPPED, 0)));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("BROKER_STATUS_UNMAPPED");
    }

    @Test
    void identityMismatchContradictsTheClaim() {
        var wrongSide = new BrokerOrderRecord("BRK1", TAG, "NSE", "RELIANCE", "SELL", 10,
            PRICE, "INTRADAY", "LIMIT", "DAY", BrokerReportedStatus.FILLED, 10, PRICE);
        var res = LiveEquityReconciler.reconcile(expected(null), List.of(wrongSide));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("BROKER_ORDER_IDENTITY_CONTRADICTS_CLAIM");
    }

    @Test
    void priceMismatchContradictsTheClaim() {
        var wrongPrice = new BrokerOrderRecord("BRK1", TAG, "NSE", "RELIANCE", "BUY", 10,
            new BigDecimal("101.00"), "INTRADAY", "LIMIT", "DAY",
            BrokerReportedStatus.FILLED, 10, PRICE);
        var res = LiveEquityReconciler.reconcile(expected(null), List.of(wrongPrice));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("BROKER_ORDER_IDENTITY_CONTRADICTS_CLAIM");
    }

    @Test
    void twoDistinctBrokerOrdersForOneTagAreAmbiguousAndReserved() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.OPEN, 0),
                    rec("BRK2", BrokerReportedStatus.OPEN, 0)));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("AMBIGUOUS_DUPLICATE_BROKER_ORDERS");
    }

    @Test
    void knownBrokerOrderIdMustStillMatchTheTaggedHistory() {
        var res = LiveEquityReconciler.reconcile(expected("BRK1"),
            List.of(rec("BRK2", BrokerReportedStatus.FILLED, 10)));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("BROKER_ORDER_IDENTITY_MISMATCH");
    }

    @Test
    void missingBrokerOrderIdIsReserved() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec(null, BrokerReportedStatus.OPEN, 0)));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("BROKER_ORDER_IDENTITY_MISSING");
    }

    @Test
    void contradictoryTerminalStatusesAreReserved() {
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.FILLED, 10),
                    rec("BRK1", BrokerReportedStatus.REJECTED, 0)));
        assertThat(res.reserved()).isTrue();
        assertThat(res.outcomeCode()).isEqualTo("CONTRADICTORY_TERMINAL_BROKER_STATUS");
    }

    @Test
    void lifecycleHistoryCollapsesOpenThenFilledToFilled() {
        // Upstox history returns one entry per lifecycle step for the same id.
        var res = LiveEquityReconciler.reconcile(expected(null),
            List.of(rec("BRK1", BrokerReportedStatus.OPEN, 0),
                    rec("BRK1", BrokerReportedStatus.OPEN, 5),
                    rec("BRK1", BrokerReportedStatus.FILLED, 10)));
        assertThat(res.observedState()).isEqualTo(LiveEquityOrderState.FILLED);
        assertThat(res.filledQuantity()).isEqualTo(10);
    }
}
