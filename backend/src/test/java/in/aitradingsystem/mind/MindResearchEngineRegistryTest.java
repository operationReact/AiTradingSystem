package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MindResearchEngineRegistryTest {
    @Test
    void no_external_engine_is_reported_as_connected_or_enabled() {
        var registry = MindResearchEngineRegistry.snapshot(Clock.fixed(Instant.parse("2026-11-14T06:00:00Z"), ZoneOffset.UTC));
        assertThat(registry.connected()).isZero();
        assertThat(registry.total()).isEqualTo(5);
        assertThat(registry.engines()).allSatisfy(e -> {
            assertThat(e.connected()).isFalse();
            assertThat(e.enabled()).isFalse();
            assertThat(e.missing()).isNotBlank();
        });
        assertThat(registry.engines()).extracting(MindResearchEngineRegistry.Engine::status)
            .doesNotContain("CONNECTED", "AVAILABLE", "RUNNING");
    }
}
