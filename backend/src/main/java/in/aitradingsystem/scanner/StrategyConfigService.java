package in.aitradingsystem.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StrategyConfigService {
    private final StrategyRegistry registry;
    private final StrategyConfigRepository repository;

    public StrategyConfigService(StrategyRegistry registry, StrategyConfigRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @Transactional
    public StrategyConfigSnapshot activate(String strategyCode, boolean enabled, JsonNode config,
                                           Instant effectiveFrom, String createdBy) {
        ScannerStrategy strategy = registry.require(strategyCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + strategyCode));
        if (config == null || !config.isObject()) throw new IllegalArgumentException("config must be a JSON object");
        if (effectiveFrom == null) throw new IllegalArgumentException("effectiveFrom is required");
        if (createdBy == null || createdBy.isBlank()) throw new IllegalArgumentException("createdBy is required");
        validateConfig(strategy, config);

        repository.lockStrategy(strategyCode);
        repository.findActive(strategyCode).ifPresent(active -> {
            if (effectiveFrom.isBefore(active.effectiveFrom())) {
                throw new IllegalArgumentException("effectiveFrom cannot precede the active configuration");
            }
            repository.closeActive(strategyCode, effectiveFrom);
        });
        int version = repository.nextVersion(strategyCode);
        return repository.insert(strategyCode, version, enabled, config, effectiveFrom, createdBy.trim());
    }

    public StrategyConfigSnapshot active(String strategyCode) {
        registry.require(strategyCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + strategyCode));
        return repository.findActive(strategyCode).orElse(null);
    }

    public List<StrategyConfigSnapshot> history(String strategyCode) {
        registry.require(strategyCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + strategyCode));
        return repository.history(strategyCode);
    }

    void validateConfig(ScannerStrategy strategy, JsonNode config) {
        Map<String, StrategyConfigValueType> schema = strategy.configurationSchema();
        Iterator<String> names = config.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            StrategyConfigValueType expected = schema.get(key);
            if (expected == null) throw new IllegalArgumentException("Unsupported config key for " + strategy.name() + ": " + key);
            if (!expected.accepts(config.get(key))) {
                throw new IllegalArgumentException("Invalid value type for config key " + key + "; expected " + expected);
            }
        }
    }
}
