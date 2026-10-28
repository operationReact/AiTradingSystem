package in.aitradingsystem.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BacktestRunSpecificationTest {

    private static final Instant FROM = Instant.parse("2026-01-01T03:45:00Z");
    private static final Instant TO = Instant.parse("2026-02-01T10:00:00Z");

    @Test
    void identicalSemanticInputsProduceIdenticalFingerprint() {
        BacktestRunSpecification first = spec("100000.0", "5", "2.50");
        BacktestRunSpecification replay = spec("100000.00000000", "5.00000000", "2.50000000");

        assertEquals(first, replay);
        assertEquals(first.canonicalFingerprint(), replay.canonicalFingerprint());
    }

    @Test
    void executionAssumptionChangeProducesDifferentFingerprint() {
        BacktestRunSpecification baseline = spec("100000", "5", "2.5");
        BacktestRunSpecification changedSlippage = spec("100000", "5.1", "2.5");

        assertNotEquals(baseline.canonicalFingerprint(), changedSlippage.canonicalFingerprint());
    }

    @Test
    void rejectsAmbiguousOrInvalidRunBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> new BacktestRunSpecification(
                " ", "v1", FROM, TO, new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BacktestRunSpecification(
                "sma", "v1", TO, FROM, new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BacktestRunSpecification(
                "sma", "v1", FROM, TO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BacktestRunSpecification(
                "sma", "v1", FROM, TO, new BigDecimal("100000"), new BigDecimal("-0.01"), BigDecimal.ZERO));
    }

    private static BacktestRunSpecification spec(String capital, String slippageBps, String brokerageBps) {
        return new BacktestRunSpecification(
                "sma-volume",
                "v1",
                FROM,
                TO,
                new BigDecimal(capital),
                new BigDecimal(slippageBps),
                new BigDecimal(brokerageBps));
    }
}