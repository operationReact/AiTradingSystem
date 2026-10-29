package in.aitradingsystem.risk;

import in.aitradingsystem.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskPolicyConfigService {
    private final RiskPolicyConfigRepository repository;
    private final AuditService audit;

    public RiskPolicyConfigService(RiskPolicyConfigRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public RiskPolicyView active() {
        return repository.requireActiveView();
    }

    public List<RiskPolicyView> list() {
        return repository.listViews();
    }

    public RiskPolicyView require(UUID id) {
        return repository.requireView(id);
    }

    @Transactional
    public RiskPolicyView activate(UUID id) {
        RiskPolicyView before = repository.requireActiveView();
        RiskPolicyView activated = repository.activate(id);
        if (!before.id().equals(activated.id())) {
            audit.record("RISK_POLICY_ACTIVATED", "RISK_POLICY_CONFIG", activated.id().toString(), Map.of(
                    "previousPolicyId", before.id().toString(),
                    "previousPolicyVersion", before.version(),
                    "policyVersion", activated.version()));
        }
        return activated;
    }
}
