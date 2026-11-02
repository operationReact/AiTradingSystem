package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.aitradingsystem.strategy.StrategySignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FusionRankContractTest {

    private static final Instant NOW = Instant.parse("2026-11-02T05:00:00Z");

    @Test
    void strategySignalMapsToStableDeterministicEvidence() {
        StrategySignal signal = signal("BUY", 0.82);

        FusionRankRequest.FusionEvidence first = StrategySignalFusionMapper.toEvidence(signal);
        FusionRankRequest.FusionEvidence second = StrategySignalFusionMapper.toEvidence(signal);

        assertEquals("DETERMINISTIC", first.family());
        assertEquals("java-strategy", first.provider());
        assertEquals("SMA_TREND_VOLUME_V1", first.version());
        assertEquals(0.82, first.score());
        assertEquals(0.82, first.confidence());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(64, first.fingerprint().length());
    }

    @Test
    void watchStrategyDoesNotBecomeNegativeOrExecutableEvidence() {
        FusionRankRequest.FusionEvidence evidence =
                StrategySignalFusionMapper.toEvidence(signal("WATCH", 0.65));
        assertEquals(0.0, evidence.score());
        assertEquals(0.65, evidence.confidence());
    }

    @Test
    void requestRejectsFutureEvidence() {
        FusionRankRequest.FusionEvidence deterministic = evidence(
                "DETERMINISTIC", NOW.plusSeconds(1), "a");
        FusionRankRequest.FusionEvidence quant = evidence("QUANT", NOW, "b");

        assertThrows(IllegalArgumentException.class, () -> new FusionRankRequest(
                FusionRankRequest.VERSION,
                "RELIANCE",
                NOW,
                1800,
                deterministic,
                quant,
                null));
    }

    @Test
    void responseCannotAuthorizeCapitalOrBecomeExecutable() {
        FusionRankResponse safe = response(false, "CANDIDATE_NOT_EXECUTABLE");
        assertFalse(safe.canAuthorizeCapital());

        assertThrows(IllegalArgumentException.class,
                () -> response(true, "CANDIDATE_NOT_EXECUTABLE"));
        assertThrows(IllegalArgumentException.class,
                () -> response(false, "EXECUTABLE"));
    }

    @Test
    void disabledIntelligenceFailsClosedBeforeNetworkCall() {
        HttpIntelligenceClient client = new HttpIntelligenceClient("http://localhost:8090", false);
        FusionRankRequest request = new FusionRankRequest(
                FusionRankRequest.VERSION,
                "RELIANCE",
                NOW,
                1800,
                evidence("DETERMINISTIC", NOW, "c"),
                evidence("QUANT", NOW, "d"),
                null);

        assertThrows(IntelligenceUnavailableException.class, () -> client.rank(request));
    }

    private static FusionRankRequest.FusionEvidence evidence(
            String family, Instant observedAt, String fingerprintSeed) {
        return new FusionRankRequest.FusionEvidence(
                family,
                "fixture",
                "v1",
                0.7,
                0.8,
                observedAt,
                fingerprintSeed.repeat(64),
                List.of("fixture"));
    }

    private static FusionRankResponse response(boolean canAuthorizeCapital, String status) {
        return new FusionRankResponse(
                FusionRankRequest.VERSION,
                "RELIANCE",
                NOW,
                NOW,
                "BUY",
                0.7,
                0.85,
                0.8,
                List.of(),
                List.of("FUSION_BUY_THRESHOLD_MET"),
                List.of(),
                "e".repeat(64),
                status,
                canAuthorizeCapital);
    }

    private static StrategySignal signal(String action, double confidence) {
        return new StrategySignal(
                "signal-1",
                101L,
                "RELIANCE",
                "NSE",
                "5m",
                action,
                "SMA_TREND_VOLUME_V1",
                new BigDecimal("1400.00"),
                new BigDecimal("1385.00"),
                new BigDecimal("1430.00"),
                new BigDecimal("1398.00"),
                new BigDecimal("1390.00"),
                new BigDecimal("1000000"),
                new BigDecimal("1500000"),
                confidence,
                List.of("FAST_ABOVE_SLOW", "VOLUME_CONFIRMATION"),
                "MARKET_CANDLE",
                NOW,
                NOW.minusSeconds(60),
                "CANDIDATE_NOT_EXECUTABLE");
    }
}
