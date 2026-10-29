package in.aitradingsystem.scanner;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class StrategyRegistry {
    private final List<ScannerStrategy> strategies;
    private final Map<String, ScannerStrategy> byCode;

    public StrategyRegistry(List<ScannerStrategy> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("At least one scanner strategy is required");
        }
        this.strategies = strategies.stream()
                .sorted(Comparator.comparing(strategy -> strategy.descriptor().code()))
                .toList();
        Map<String, ScannerStrategy> indexed = new LinkedHashMap<>();
        for (ScannerStrategy strategy : this.strategies) {
            StrategyDescriptor descriptor = strategy.descriptor();
            if (!strategy.name().equals(descriptor.code())) {
                throw new IllegalArgumentException("Strategy name must match descriptor code: " + strategy.name());
            }
            if (strategy.minimumCandles() != descriptor.minimumCandles()) {
                throw new IllegalArgumentException("Strategy minimumCandles must match descriptor: " + descriptor.code());
            }
            if (indexed.putIfAbsent(descriptor.code(), strategy) != null) {
                throw new IllegalArgumentException("Duplicate strategy code: " + descriptor.code());
            }
        }
        this.byCode = Map.copyOf(indexed);
    }

    public List<ScannerStrategy> enabledStrategies() {
        return strategies;
    }

    public Optional<ScannerStrategy> require(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return Optional.ofNullable(byCode.get(code));
    }

    public List<StrategyDescriptor> descriptors() {
        return strategies.stream().map(ScannerStrategy::descriptor).toList();
    }
}
