package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PaperServiceDecompositionTest {
    @Test
    void compatibilityFacadeDoesNotOwnJdbcRiskOrExecutionDependencies() {
        Set<Class<?>> dependencies = Arrays.stream(PaperTradingService.class.getDeclaredFields())
            .map(Field::getType)
            .collect(Collectors.toSet());

        assertThat(dependencies).contains(
            PaperOrderService.class,
            PaperOrderRepository.class,
            PaperAccountRepository.class);
        assertThat(dependencies.stream().map(Class::getName))
            .noneMatch(name -> name.contains("JdbcClient"))
            .noneMatch(name -> name.contains("RiskPolicyEngine"))
            .noneMatch(name -> name.contains("RiskContextFactory"))
            .noneMatch(name -> name.contains("RiskDecisionPersistenceService"))
            .noneMatch(name -> name.contains("PaperExecutionEngine"));
    }

    @Test
    void canonicalPhase6ServicesAndRepositoriesExist() {
        assertThat(PaperOrderService.class).isNotNull();
        assertThat(PaperOrderRepository.class).isNotNull();
        assertThat(PaperFillRepository.class).isNotNull();
        assertThat(PaperAccountRepository.class).isNotNull();
        assertThat(PaperPositionRepository.class).isNotNull();
        assertThat(PositionAccountingService.class).isNotNull();
        assertThat(PaperExecutionEngine.class).isNotNull();
    }
}
