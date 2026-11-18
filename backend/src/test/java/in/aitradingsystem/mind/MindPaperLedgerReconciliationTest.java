package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** STR-232 PAPER ledger reconciliation: the wallet must agree with its full cash-event chain before any entry. */
class MindPaperLedgerReconciliationTest {

    private static final long INITIAL = 10_000_000L; // Rs 1,00,000

    private static final long[] ALLOCATION = {INITIAL, INITIAL};

    @Test
    void activationAloneAgrees() {
        assertThat(MindPaperCycleService.ledgerMismatch(INITIAL, INITIAL, List.of(ALLOCATION))).isNull();
    }

    @Test
    void emptyLedgerFailsClosed() {
        assertThat(MindPaperCycleService.ledgerMismatch(INITIAL, INITIAL, List.of()))
            .startsWith("LEDGER_MISMATCH: no cash events");
    }

    @Test
    void consistentChainAgrees() {
        List<long[]> events = List.of(
            ALLOCATION,
            new long[] {-500_000L, 9_500_000L},   // a BUY debit
            new long[] {120_000L, 9_620_000L});   // a SELL credit
        assertThat(MindPaperCycleService.ledgerMismatch(INITIAL, 9_620_000L, events)).isNull();
    }

    @Test
    void brokenStepBlocksEvenIfTheEndBalanceMatches() {
        List<long[]> events = List.of(
            ALLOCATION,
            new long[] {-500_000L, 9_500_000L},
            new long[] {120_000L, 9_700_000L});   // should be 9,620,000
        assertThat(MindPaperCycleService.ledgerMismatch(INITIAL, 9_700_000L, events))
            .startsWith("LEDGER_MISMATCH: a cash event");
    }

    @Test
    void allocationDifferentFromInitialCashBlocks() {
        List<long[]> events = List.of(new long[] {9_000_000L, 9_000_000L});
        assertThat(MindPaperCycleService.ledgerMismatch(INITIAL, 9_000_000L, events))
            .startsWith("LEDGER_MISMATCH: the allocation");
    }

    @Test
    void walletBalanceDifferentFromLedgerBlocks() {
        List<long[]> events = List.of(ALLOCATION, new long[] {-500_000L, 9_500_000L});
        assertThat(MindPaperCycleService.ledgerMismatch(INITIAL, 9_400_000L, events))
            .startsWith("LEDGER_MISMATCH: ledger balance");
    }
}
