package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StrategyConfigServiceTest {
    private final StrategyConfigRepository repository = mock(StrategyConfigRepository.class);
    private final ScannerStrategy strategy = new ConfigurableStrategy();
    private final StrategyConfigService service = new StrategyConfigService(new StrategyRegistry(List.of(strategy)), repository);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void activatesNextVersionAndClosesCurrentSnapshot() throws Exception {
        Instant previousFrom = Instant.parse("2026-10-01T00:00:00Z");
        Instant nextFrom = Instant.parse("2026-10-29T00:00:00Z");
        var current = new StrategyConfigSnapshot(java.util.UUID.randomUUID(), strategy.name(), 2, true,
                mapper.readTree("{\"threshold\":1.2}"), previousFrom, null, "ops", previousFrom);
        var expected = new StrategyConfigSnapshot(java.util.UUID.randomUUID(), strategy.name(), 3, true,
                mapper.readTree("{\"threshold\":1.4,\"window\":20}"), nextFrom, null, "ops", nextFrom);
        when(repository.findActive(strategy.name())).thenReturn(Optional.of(current));
        when(repository.nextVersion(strategy.name())).thenReturn(3);
        when(repository.insert(eq(strategy.name()), eq(3), eq(true), any(), eq(nextFrom), eq("ops"))).thenReturn(expected);

        StrategyConfigSnapshot actual = service.activate(strategy.name(), true,
                mapper.readTree("{\"threshold\":1.4,\"window\":20}"), nextFrom, " ops ");

        assertEquals(3, actual.configVersion());
        verify(repository).lockStrategy(strategy.name());
        verify(repository).closeActive(strategy.name(), nextFrom);
    }

    @Test
    void rejectsUnknownKeysAndWrongTypes() throws Exception {
        Instant from = Instant.parse("2026-10-29T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> service.activate(strategy.name(), true,
                mapper.readTree("{\"script\":\"price > sma\"}"), from, "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.activate(strategy.name(), true,
                mapper.readTree("{\"window\":12.5}"), from, "ops"));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsActivationBeforeCurrentEffectiveTime() throws Exception {
        Instant currentFrom = Instant.parse("2026-10-29T00:00:00Z");
        when(repository.findActive(strategy.name())).thenReturn(Optional.of(new StrategyConfigSnapshot(
                java.util.UUID.randomUUID(), strategy.name(), 1, true, mapper.createObjectNode(),
                currentFrom, null, "ops", currentFrom)));

        assertThrows(IllegalArgumentException.class, () -> service.activate(strategy.name(), true,
                mapper.createObjectNode(), currentFrom.minusSeconds(1), "ops"));
        verify(repository, never()).closeActive(anyString(), any());
        verify(repository, never()).insert(anyString(), anyInt(), anyBoolean(), any(), any(), anyString());
    }

    private static final class ConfigurableStrategy implements ScannerStrategy {
        @Override public String name() { return "TEST_CONFIG"; }
        @Override public int minimumCandles() { return 1; }
        @Override public StrategyDescriptor descriptor() {
            return new StrategyDescriptor(name(), "1.0", "Test config", SetSupport.FIVE_MINUTE, 1,
                    TradingStyle.INTRADAY, true, "HEALTHY", true, false);
        }
        @Override public Map<String, StrategyConfigValueType> configurationSchema() {
            return Map.of("threshold", StrategyConfigValueType.DECIMAL, "window", StrategyConfigValueType.INTEGER);
        }
        @Override public Optional<ScanCandidate> evaluate(ScanContext context) { return Optional.empty(); }
    }

    private static final class SetSupport {
        private static final java.util.Set<in.aitradingsystem.marketdata.HistoricalCandleInterval> FIVE_MINUTE =
                java.util.Set.of(in.aitradingsystem.marketdata.HistoricalCandleInterval.FIVE_MINUTE);
    }
}
