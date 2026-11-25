package in.aitradingsystem.api;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.scanner.ScannerStrategy;
import in.aitradingsystem.scanner.StrategyConfigService;
import in.aitradingsystem.scanner.StrategyConfigSnapshot;
import in.aitradingsystem.scanner.StrategyConfigValueType;
import in.aitradingsystem.scanner.StrategyDescriptor;
import in.aitradingsystem.scanner.StrategyRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/research/strategies")
public class ResearchStrategyController {
    private final StrategyRegistry registry;
    private final StrategyConfigService configService;

    public ResearchStrategyController(StrategyRegistry registry, StrategyConfigService configService) {
        this.registry = registry;
        this.configService = configService;
    }

    @GetMapping
    public ResponseEntity<List<StrategyDescriptor>> list() {
        return ResponseEntity.ok(registry.descriptors());
    }

    @GetMapping("/{code}")
    public ResponseEntity<StrategyDetailResponse> get(@PathVariable String code) {
        ScannerStrategy strategy = requireStrategy(code);
        return ResponseEntity.ok(new StrategyDetailResponse(
                strategy.descriptor(), strategy.configurationSchema(), configService.active(code), configService.history(code)));
    }

    @PutMapping("/{code}/config")
    public ResponseEntity<StrategyConfigSnapshot> activate(
            @PathVariable String code,
            @RequestBody StrategyConfigRequest request,
            Authentication authentication) {
        requireStrategy(code);
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.config() == null) throw new IllegalArgumentException("config is required");
        if (request.effectiveFrom() == null) throw new IllegalArgumentException("effectiveFrom is required");
        String actor = authentication == null ? null : authentication.getName();
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("authenticated operator is required");
        return ResponseEntity.ok(configService.activate(code, request.enabled(), request.config(), request.effectiveFrom(), actor));
    }

    private ScannerStrategy requireStrategy(String code) {
        return registry.require(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + code));
    }

    public record StrategyConfigRequest(boolean enabled, JsonNode config, Instant effectiveFrom) {
    }

    public record StrategyDetailResponse(
            StrategyDescriptor descriptor,
            Map<String, StrategyConfigValueType> configurationSchema,
            StrategyConfigSnapshot activeConfig,
            List<StrategyConfigSnapshot> history) {
    }
}
