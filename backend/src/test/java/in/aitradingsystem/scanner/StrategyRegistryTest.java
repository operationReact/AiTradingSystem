package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyRegistryTest {

    @Test
    void sortsStrategiesByStableDescriptorCode() {
        StrategyRegistry registry = new StrategyRegistry(List.of(
                new VwapMomentumStrategy(),
                new OpeningRangeBreakoutStrategy(),
                new MovingAverageReclaimStrategy(),
                new VolumeBreakoutStrategy(),
                new RsiReversalStrategy()));

        assertEquals(List.of(
                        MovingAverageReclaimStrategy.NAME,
                        OpeningRangeBreakoutStrategy.NAME,
                        RsiReversalStrategy.NAME,
                        VolumeBreakoutStrategy.NAME,
                        VwapMomentumStrategy.NAME),
                registry.descriptors().stream().map(StrategyDescriptor::code).toList());
        assertTrue(registry.require(OpeningRangeBreakoutStrategy.NAME).isPresent());
        assertTrue(registry.require("UNKNOWN").isEmpty());
    }

    @Test
    void descriptorsAreLongOnlyAndMatchRuntimeRequirements() {
        StrategyRegistry registry = new StrategyRegistry(List.of(
                new VolumeBreakoutStrategy(), new MovingAverageReclaimStrategy(),
                new OpeningRangeBreakoutStrategy(), new VwapMomentumStrategy(), new RsiReversalStrategy()));

        for (ScannerStrategy strategy : registry.enabledStrategies()) {
            StrategyDescriptor descriptor = strategy.descriptor();
            assertEquals(strategy.name(), descriptor.code());
            assertEquals(strategy.minimumCandles(), descriptor.minimumCandles());
            assertEquals("HEALTHY", descriptor.minimumDataQuality());
            assertTrue(descriptor.longCapable());
            assertFalse(descriptor.shortCapable());
            assertFalse(descriptor.supportedIntervals().isEmpty());
        }
    }

    @Test
    void duplicateStrategyCodesAreRejected() {
        ScannerStrategy duplicate = new VolumeBreakoutStrategy();
        assertThrows(IllegalArgumentException.class,
                () -> new StrategyRegistry(List.of(duplicate, duplicate)));
    }
}
