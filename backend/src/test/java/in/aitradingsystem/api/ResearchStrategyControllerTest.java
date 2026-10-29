package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
import in.aitradingsystem.scanner.StrategyConfigService;
import in.aitradingsystem.scanner.StrategyConfigSnapshot;
import in.aitradingsystem.scanner.StrategyRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ResearchStrategyControllerTest {

    @Test
    void listAndDetailUseRegistryMetadata() {
        MovingAverageReclaimStrategy strategy = new MovingAverageReclaimStrategy();
        StrategyRegistry registry = new StrategyRegistry(List.of(strategy));
        StrategyConfigService configService = org.mockito.Mockito.mock(StrategyConfigService.class);
        ResearchStrategyController controller = new ResearchStrategyController(registry, configService);

        assertEquals(List.of(strategy.descriptor()), controller.list().getBody());
        ResearchStrategyController.StrategyDetailResponse detail = controller.get(strategy.name()).getBody();
        assertEquals(strategy.descriptor(), detail.descriptor());
        assertEquals(strategy.configurationSchema(), detail.configurationSchema());
    }

    @Test
    void configActivationUsesAuthenticatedOperatorIdentity() {
        MovingAverageReclaimStrategy strategy = new MovingAverageReclaimStrategy();
        StrategyRegistry registry = new StrategyRegistry(List.of(strategy));
        StrategyConfigService configService = org.mockito.Mockito.mock(StrategyConfigService.class);
        ResearchStrategyController controller = new ResearchStrategyController(registry, configService);
        Instant effectiveFrom = Instant.parse("2026-10-29T07:30:00Z");
        var config = new ObjectMapper().createObjectNode();
        StrategyConfigSnapshot expected = new StrategyConfigSnapshot(
                UUID.randomUUID(), strategy.name(), 2, true, config, effectiveFrom, null, "operator", effectiveFrom);
        when(configService.activate(strategy.name(), true, config, effectiveFrom, "operator")).thenReturn(expected);

        StrategyConfigSnapshot actual = controller.activate(strategy.name(),
                new ResearchStrategyController.StrategyConfigRequest(true, config, effectiveFrom),
                new UsernamePasswordAuthenticationToken("operator", null)).getBody();

        assertSame(expected, actual);
        verify(configService).activate(strategy.name(), true, config, effectiveFrom, "operator");
    }
}
