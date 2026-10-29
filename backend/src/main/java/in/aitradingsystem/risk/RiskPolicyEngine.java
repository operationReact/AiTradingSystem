package in.aitradingsystem.risk;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperExecutionModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class RiskPolicyEngine {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Comparator<RiskRule> RULE_ORDER = Comparator
            .comparingInt(RiskRule::sequence)
            .thenComparing(rule -> rule.code().name());

    private final List<RiskRule> rules;

    public RiskPolicyEngine(List<RiskRule> rules) {
        List<RiskRule> ordered = new ArrayList<>(rules == null ? List.of() : rules);
        ordered.sort(RULE_ORDER);
        validateUniqueRuleCodes(ordered);
        this.rules = List.copyOf(ordered);
    }

    public RiskDecisionV2 evaluate(RiskContext context) {
        if (context == null) throw new IllegalArgumentException("context is required");

        List<RiskRuleResult> results = rules.stream()
                .map(rule -> evaluateRule(rule, context))
                .toList();

        RiskDecisionClass decisionClass = classify(results);
        MonetarySnapshot monetary = monetary(context);
        UUID decisionId = deterministicDecisionId(context, results, decisionClass);

        return new RiskDecisionV2(
                decisionId,
                decisionClass,
                results,
                monetary.fillPrice(),
                monetary.grossNotional(),
                monetary.fee(),
                monetary.slippage(),
                monetary.estimatedRisk(),
                monetary.riskBudget(),
                monetary.rewardRiskRatio(),
                monetary.grossExposureAfter(),
                monetary.symbolExposureAfter(),
                context.tradesToday() + 1,
                context.policy().id(),
                context.policy().version(),
                context.now());
    }

    public List<RiskRuleCode> orderedRuleCodes() {
        return rules.stream().map(RiskRule::code).toList();
    }

    private static RiskRuleResult evaluateRule(RiskRule rule, RiskContext context) {
        RiskRuleResult result = rule.evaluate(context);
        if (result == null) {
            throw new IllegalStateException("risk rule returned null: " + rule.code());
        }
        if (result.code() != rule.code()) {
            throw new IllegalStateException("risk rule result code mismatch: expected " + rule.code()
                    + " but was " + result.code());
        }
        return result;
    }

    private static RiskDecisionClass classify(List<RiskRuleResult> results) {
        if (results.stream().anyMatch(result -> result.outcome() == RiskOutcome.HARD_REJECT)) {
            return RiskDecisionClass.REJECTED;
        }
        if (results.stream().anyMatch(result -> result.outcome() == RiskOutcome.APPROVAL_REQUIRED)) {
            return RiskDecisionClass.APPROVAL_REQUIRED;
        }
        return RiskDecisionClass.APPROVED;
    }

    private static MonetarySnapshot monetary(RiskContext context) {
        RiskPolicySnapshot policy = context.policy();
        BigDecimal fillPrice = PaperExecutionModel.fillPrice(
                context.side(), context.referencePrice(), policy.slippageRate());
        BigDecimal quantity = BigDecimal.valueOf(context.quantity());
        BigDecimal gross = PaperExecutionModel.money(fillPrice.multiply(quantity));
        BigDecimal fee = PaperExecutionModel.fee(gross, policy.feeRate());
        BigDecimal slippage = PaperExecutionModel.money(
                context.referencePrice().subtract(fillPrice).abs().multiply(quantity));
        BigDecimal riskBudget = PaperExecutionModel.money(
                context.startingCapital().multiply(policy.riskPerTradePercent()).divide(HUNDRED));

        BigDecimal estimatedRisk = BigDecimal.ZERO.setScale(2);
        BigDecimal rewardRiskRatio = null;
        if (context.side() == Side.BUY && context.stopLoss() != null
                && fillPrice.compareTo(context.stopLoss()) > 0) {
            BigDecimal priceRiskPerShare = fillPrice.subtract(context.stopLoss());
            estimatedRisk = PaperExecutionModel.money(priceRiskPerShare.multiply(quantity).add(fee));
            if (context.targetPrice() != null && context.targetPrice().compareTo(fillPrice) > 0) {
                rewardRiskRatio = context.targetPrice().subtract(fillPrice)
                        .divide(priceRiskPerShare, 8, RoundingMode.HALF_UP);
            }
        }

        BigDecimal signedNotional = context.side() == Side.BUY ? gross : gross.negate();
        BigDecimal grossExposureAfter = context.currentGrossExposure().add(signedNotional).max(BigDecimal.ZERO);
        BigDecimal symbolExposureAfter = context.currentSymbolExposure().add(signedNotional).max(BigDecimal.ZERO);

        return new MonetarySnapshot(fillPrice, gross, fee, slippage, estimatedRisk, riskBudget,
                rewardRiskRatio, grossExposureAfter, symbolExposureAfter);
    }

    private static UUID deterministicDecisionId(
            RiskContext context, List<RiskRuleResult> results, RiskDecisionClass decisionClass) {
        StringBuilder canonical = new StringBuilder()
                .append(context.orderId()).append('|')
                .append(context.correlationId()).append('|')
                .append(context.executionMode()).append('|')
                .append(context.side()).append('|')
                .append(context.instrumentId()).append('|')
                .append(context.quantity()).append('|')
                .append(context.referencePrice().stripTrailingZeros().toPlainString()).append('|')
                .append(context.now()).append('|')
                .append(context.policy().id()).append('|')
                .append(context.policy().version()).append('|')
                .append(decisionClass);
        for (RiskRuleResult result : results) {
            canonical.append('|').append(result.code())
                    .append(':').append(result.outcome())
                    .append(':').append(result.reasonCode());
        }
        return UUID.nameUUIDFromBytes(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void validateUniqueRuleCodes(List<RiskRule> ordered) {
        Set<RiskRuleCode> seen = new HashSet<>();
        for (RiskRule rule : ordered) {
            if (rule == null || rule.code() == null) {
                throw new IllegalArgumentException("risk rule and code are required");
            }
            if (!seen.add(rule.code())) {
                throw new IllegalArgumentException("duplicate risk rule code: " + rule.code());
            }
        }
    }

    private record MonetarySnapshot(
            BigDecimal fillPrice,
            BigDecimal grossNotional,
            BigDecimal fee,
            BigDecimal slippage,
            BigDecimal estimatedRisk,
            BigDecimal riskBudget,
            BigDecimal rewardRiskRatio,
            BigDecimal grossExposureAfter,
            BigDecimal symbolExposureAfter) {}
}
