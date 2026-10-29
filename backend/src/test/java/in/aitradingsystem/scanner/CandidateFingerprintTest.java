package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CandidateFingerprintTest {

    @Test
    void canonicalizesEquivalentDecimals() {
        Instant signalTime = Instant.parse("2026-10-29T03:45:00Z");

        String first = CandidateFingerprint.of(17L, "FIVE_MINUTE", "VWAP_MOMENTUM_V1", "1",
                signalTime, new BigDecimal("100.00"), new BigDecimal("99.5000"), new BigDecimal("102.000"));
        String second = CandidateFingerprint.of(17L, "FIVE_MINUTE", "VWAP_MOMENTUM_V1", "1",
                signalTime, new BigDecimal("1E+2"), new BigDecimal("99.5"), new BigDecimal("102"));

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void strategyVersionAndEconomicLevelsChangeIdentity() {
        Instant signalTime = Instant.parse("2026-10-29T03:45:00Z");
        String base = CandidateFingerprint.of(17L, "FIVE_MINUTE", "VWAP_MOMENTUM_V1", "1",
                signalTime, new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("102"));

        assertNotEquals(base, CandidateFingerprint.of(17L, "FIVE_MINUTE", "VWAP_MOMENTUM_V1", "2",
                signalTime, new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("102")));
        assertNotEquals(base, CandidateFingerprint.of(17L, "FIVE_MINUTE", "VWAP_MOMENTUM_V1", "1",
                signalTime, new BigDecimal("100.01"), new BigDecimal("99"), new BigDecimal("102")));
    }
}
