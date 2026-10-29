package in.aitradingsystem.risk;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.common.PageResponse;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskPolicyConfigService {
    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal TEN = new BigDecimal("10");
    private static final BigDecimal TWENTY = new BigDecimal("20");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final RiskPolicyConfigRepository repository;
    private final AuditService audit;

    public RiskPolicyConfigService(RiskPolicyConfigRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public RiskPolicyView active() {
        return repository.requireActiveView();
    }

    public PageResponse<RiskPolicyView> history(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page must be >= 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        return PageResponse.of(repository.listViews(page, size), page, size, repository.count());
    }

    public RiskPolicyView get(UUID id) {
        return repository.requireView(id);
    }

    @Transactional
    public RiskPolicyView createAndActivate(CreateRiskPolicyRequest request, String actor) {
        validate(request);
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("authenticated actor is required");

        int version = repository.nextVersionForUpdate();
        RiskPolicyView previous = repository.requireActiveView();
        UUID id = UUID.randomUUID();
        RiskPolicySnapshot snapshot = new RiskPolicySnapshot(
                id,
                version,
                request.riskPerTradePercent(),
                request.maxDailyDrawdownPercent(),
                request.maxOpenPositions(),
                request.minRewardRiskRatio(),
                request.maxOrderNotional(),
                request.maxGrossExposurePercent(),
                request.maxSymbolExposurePercent(),
                request.maxTradesPerDay(),
                request.signalMaxAgeMinutes(),
                request.marketStateMaxAgeSeconds(),
                request.enforceMarketSession(),
                request.rejectExistingSymbolPosition(),
                request.rejectDuplicatePendingExposure(),
                request.feeRate(),
                request.slippageRate(),
                request.feeSlippageProfileVersion());

        repository.insert(snapshot, request.policyName().trim(), false, actor);
        RiskPolicyView activated = repository.activate(id);
        audit.record("RISK_POLICY_CREATED_AND_ACTIVATED", "RISK_POLICY_CONFIG", id.toString(), Map.of(
                "previousPolicyId", previous.id().toString(),
                "previousPolicyVersion", previous.version(),
                "policyVersion", version,
                "actor", actor));
        return activated;
    }

    private static void validate(CreateRiskPolicyRequest request) {
        if (request == null) throw new IllegalArgumentException("risk policy request is required");
        if (request.policyName() == null || request.policyName().isBlank() || request.policyName().length() > 120) {
            throw new IllegalArgumentException("policyName is required and must be <= 120 characters");
        }
        requireRange("riskPerTradePercent", request.riskPerTradePercent(), BigDecimal.ZERO, FIVE, false);
        requireRange("maxDailyDrawdownPercent", request.maxDailyDrawdownPercent(), BigDecimal.ZERO, TWENTY, false);
        if (request.riskPerTradePercent().compareTo(request.maxDailyDrawdownPercent()) >= 0) {
            throw new IllegalArgumentException("riskPerTradePercent must be less than maxDailyDrawdownPercent");
        }
        if (request.maxOpenPositions() < 1 || request.maxOpenPositions() > 100) {
            throw new IllegalArgumentException("maxOpenPositions must be between 1 and 100");
        }
        requireRange("minRewardRiskRatio", request.minRewardRiskRatio(), BigDecimal.ONE, TEN, true);
        if (request.maxOrderNotional() == null || request.maxOrderNotional().signum() <= 0) {
            throw new IllegalArgumentException("maxOrderNotional must be positive");
        }
        requireRange("maxGrossExposurePercent", request.maxGrossExposurePercent(), BigDecimal.ZERO, HUNDRED, false);
        requireRange("maxSymbolExposurePercent", request.maxSymbolExposurePercent(), BigDecimal.ZERO, HUNDRED, false);
        if (request.maxSymbolExposurePercent().compareTo(request.maxGrossExposurePercent()) > 0) {
            throw new IllegalArgumentException("maxSymbolExposurePercent cannot exceed maxGrossExposurePercent");
        }
        if (request.maxTradesPerDay() < 1 || request.maxTradesPerDay() > 1000) {
            throw new IllegalArgumentException("maxTradesPerDay must be between 1 and 1000");
        }
        if (request.signalMaxAgeMinutes() < 1 || request.signalMaxAgeMinutes() > 1440) {
            throw new IllegalArgumentException("signalMaxAgeMinutes must be between 1 and 1440");
        }
        if (request.marketStateMaxAgeSeconds() < 1 || request.marketStateMaxAgeSeconds() > 3600) {
            throw new IllegalArgumentException("marketStateMaxAgeSeconds must be between 1 and 3600");
        }
        requireNonNegative("feeRate", request.feeRate());
        requireNonNegative("slippageRate", request.slippageRate());
        if (request.feeSlippageProfileVersion() == null || request.feeSlippageProfileVersion().isBlank()
                || request.feeSlippageProfileVersion().length() > 40) {
            throw new IllegalArgumentException("feeSlippageProfileVersion is required and must be <= 40 characters");
        }
    }

    private static void requireRange(String name, BigDecimal value, BigDecimal min, BigDecimal max, boolean inclusiveMin) {
        if (value == null || (inclusiveMin ? value.compareTo(min) < 0 : value.compareTo(min) <= 0)
                || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
    }

    private static void requireNonNegative(String name, BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(name + " must be >= 0");
    }
}
