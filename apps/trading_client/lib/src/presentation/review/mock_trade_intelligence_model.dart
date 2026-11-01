import 'package:flutter/foundation.dart';

import 'mock_review_data.dart';

@immutable
class MockEvidenceFact {
  const MockEvidenceFact({
    required this.category,
    required this.title,
    required this.verdict,
    required this.score,
    required this.detail,
    required this.provenance,
    required this.supportsTrade,
  });

  final String category;
  final String title;
  final String verdict;
  final int score;
  final String detail;
  final String provenance;
  final bool supportsTrade;
}

@immutable
class MockConfidenceEvent {
  const MockConfidenceEvent({
    required this.stage,
    required this.confidence,
    required this.reason,
  });

  final String stage;
  final int confidence;
  final String reason;
}

@immutable
class MockOutcomeFactor {
  const MockOutcomeFactor({
    required this.label,
    required this.weight,
    required this.detail,
    required this.helped,
  });

  final String label;
  final int weight;
  final String detail;
  final bool helped;
}

@immutable
class MockAuditReference {
  const MockAuditReference({
    required this.label,
    required this.value,
    required this.detail,
  });

  final String label;
  final String value;
  final String detail;
}

@immutable
class MockTradeIntelligenceRecord {
  const MockTradeIntelligenceRecord({
    required this.tradeId,
    required this.symbol,
    required this.company,
    required this.side,
    required this.status,
    required this.decision,
    required this.entryPrice,
    required this.stopPrice,
    required this.targetPrice,
    required this.lastPrice,
    required this.exitPrice,
    required this.realizedPnl,
    required this.initialConfidence,
    required this.currentConfidence,
    required this.decisionSummary,
    required this.primaryReason,
    required this.riskSummary,
    required this.outcomeSummary,
    required this.failureClassification,
    required this.exitReason,
    required this.executed,
    required this.closed,
    required this.cancelled,
    required this.evidence,
    required this.confidenceTimeline,
    required this.invalidationRules,
    required this.outcomeFactors,
    required this.auditReferences,
  });

  final String tradeId;
  final String symbol;
  final String company;
  final String side;
  final String status;
  final String decision;
  final double entryPrice;
  final double stopPrice;
  final double targetPrice;
  final double lastPrice;
  final double? exitPrice;
  final double? realizedPnl;
  final int initialConfidence;
  final int currentConfidence;
  final String decisionSummary;
  final String primaryReason;
  final String riskSummary;
  final String outcomeSummary;
  final String failureClassification;
  final String exitReason;
  final bool executed;
  final bool closed;
  final bool cancelled;
  final List<MockEvidenceFact> evidence;
  final List<MockConfidenceEvent> confidenceTimeline;
  final List<String> invalidationRules;
  final List<MockOutcomeFactor> outcomeFactors;
  final List<MockAuditReference> auditReferences;

  bool get isLoss => closed && (realizedPnl ?? 0) < 0;
  bool get isProfit => closed && (realizedPnl ?? 0) > 0;
  bool get isOpen => executed && !closed;

  factory MockTradeIntelligenceRecord.fromStore({
    required MockReviewStore store,
    required String symbol,
    String side = 'BUY',
    double? entryPrice,
    double? exitPrice,
    double? realizedPnl,
    bool executed = true,
    bool closed = false,
    bool cancelled = false,
    String exitReason = 'Position still open',
  }) {
    final quote = store.quotes.firstWhere(
      (item) => item.symbol == symbol,
      orElse: () => MockQuote(
        symbol: symbol,
        name: symbol,
        price: entryPrice ?? 100,
        changePercent: 0,
        sparkline: const [20, 21, 20, 22, 23, 22, 24],
      ),
    );
    final opportunity = store.opportunities.firstWhere(
      (item) => item.symbol == symbol,
      orElse: () => MockOpportunity(
        symbol: symbol,
        company: quote.name,
        price: quote.price,
        changePercent: quote.changePercent,
        rating: 'Review',
        confidence: 76,
        setup: 'Multi-signal setup',
        riskReward: '1 : 2.2',
        timeframe: '15 min',
        thesis: 'Synthetic review record combining price structure, momentum, volume, model evidence and market context.',
        entry: entryPrice ?? quote.price,
        stop: (entryPrice ?? quote.price) * .989,
        target: (entryPrice ?? quote.price) * 1.024,
      ),
    );

    final entry = entryPrice ?? opportunity.entry;
    final effectiveExit = exitPrice;
    final pnl = realizedPnl ?? (closed && effectiveExit != null
        ? (effectiveExit - entry) * 10 * (side == 'BUY' ? 1 : -1)
        : null);
    final loss = closed && (pnl ?? 0) < 0;
    final profit = closed && (pnl ?? 0) > 0;
    final openUnderwater = executed && !closed && quote.price < entry;
    final currentConfidence = cancelled
        ? 42
        : loss
            ? 47
            : profit
                ? 89
                : openUnderwater
                    ? 61
                    : executed
                        ? 82
                        : opportunity.confidence;

    final decision = cancelled
        ? 'WAIT / CANCELLED'
        : opportunity.confidence >= 80
            ? 'TRADE ELIGIBLE'
            : 'WATCH / MANUAL REVIEW';
    final status = cancelled
        ? 'CANCELLED'
        : closed
            ? 'CLOSED'
            : executed
                ? 'OPEN'
                : 'PRE-TRADE';

    final outcomeSummary = cancelled
        ? 'The order was not executed. The decision record is retained so the user can see what analysis existed and why capital was not exposed.'
        : loss
            ? 'The setup was valid at entry, but later evidence moved against the thesis. The loss is explained by the changed market path, not rewritten as if the original signal never existed.'
            : profit
                ? 'The original setup remained valid after entry and the realized path broadly followed the expected breakout/momentum thesis.'
                : openUnderwater
                    ? 'The position is currently below entry. That is not automatically a failed trade; the original thesis remains active until an invalidation, stop, exit rule or new evidence says otherwise.'
                    : executed
                        ? 'The position is open and the original thesis is still within its planned risk and invalidation envelope.'
                        : 'The trade has not been executed. This record shows the evidence and thresholds available before capital exposure.';

    final failureClassification = cancelled
        ? 'No trade failure — capital was not exposed'
        : loss
            ? 'Post-entry regime/thesis deterioration with execution impact'
            : profit
                ? 'No failure — positive outcome, still subject to attribution uncertainty'
                : openUnderwater
                    ? 'Not failed yet — unrealized drawdown under active thesis'
                    : 'No failure identified';

    final evidence = <MockEvidenceFact>[
      const MockEvidenceFact(
        category: 'TECHNICAL',
        title: 'Trend / structure',
        verdict: 'Bullish breakout',
        score: 88,
        detail: 'Price cleared the review resistance zone and remained above the short-term structure used by the setup.',
        provenance: 'strategy.breakout-v3 · bars@10:31:00',
        supportsTrade: true,
      ),
      const MockEvidenceFact(
        category: 'TECHNICAL',
        title: 'Momentum',
        verdict: 'Positive',
        score: 84,
        detail: 'RSI was constructive without being extremely overbought and MACD momentum had turned positive.',
        provenance: 'feature-set intraday-v7 · RSI14/MACD',
        supportsTrade: true,
      ),
      const MockEvidenceFact(
        category: 'MARKET DATA',
        title: 'Volume confirmation',
        verdict: 'Confirmed',
        score: 79,
        detail: 'Volume was approximately 1.6× the recent intraday average, supporting the breakout rather than a low-liquidity move.',
        provenance: 'market-snapshot review-fixture-1042',
        supportsTrade: true,
      ),
      const MockEvidenceFact(
        category: 'QUANT',
        title: 'Quantitative baseline',
        verdict: 'Long bias',
        score: 82,
        detail: 'The calibrated review baseline ranked the setup above the configured opportunity threshold after volatility normalization.',
        provenance: 'quant-baseline-v2.4 · feature-schema-v7',
        supportsTrade: true,
      ),
      const MockEvidenceFact(
        category: 'PYTORCH',
        title: 'Learned model',
        verdict: 'Supportive',
        score: 81,
        detail: 'The synthetic PyTorch review model assigned a positive short-horizon probability while remaining below a certainty threshold.',
        provenance: 'pytorch-fusion-review-v0.9 · calibration-v3',
        supportsTrade: true,
      ),
      const MockEvidenceFact(
        category: 'GEMINI',
        title: 'News / event intelligence',
        verdict: 'Positive / no contradiction',
        score: 72,
        detail: 'The mock structured Gemini evidence contained no adverse high-materiality event and modestly supported the directional thesis.',
        provenance: 'gemini-review-v1 · sources G-104,G-118',
        supportsTrade: true,
      ),
      MockEvidenceFact(
        category: 'REGIME',
        title: 'Market regime',
        verdict: loss || openUnderwater ? 'Turned adverse after entry' : 'Supportive',
        score: loss ? 43 : openUnderwater ? 58 : 76,
        detail: loss || openUnderwater
            ? 'The broad-market regime weakened after the pre-trade snapshot. This later change must not be back-filled into the original decision.'
            : 'Index breadth and intraday regime were supportive at the decision timestamp.',
        provenance: 'regime-classifier-v2 · frozen pre-trade state',
        supportsTrade: !(loss || openUnderwater),
      ),
      const MockEvidenceFact(
        category: 'RISK',
        title: 'Risk Engine',
        verdict: 'Approved inside limits',
        score: 100,
        detail: 'Position size, estimated downside, stop protection, exposure and daily-loss limits were inside the configured PAPER risk envelope.',
        provenance: 'risk-policy-v5 · decision RSK-1042',
        supportsTrade: true,
      ),
    ];

    final confidenceTimeline = <MockConfidenceEvent>[
      const MockConfidenceEvent(stage: 'Initial scan', confidence: 58, reason: 'Candidate detected from price structure and liquidity filters.'),
      MockConfidenceEvent(stage: 'Signal fusion', confidence: opportunity.confidence, reason: 'Technical, quant, model and event evidence combined into the trade score.'),
      MockConfidenceEvent(stage: 'Risk validation', confidence: (opportunity.confidence - 3).clamp(0, 100).toInt(), reason: 'Risk Engine approved exposure but reduced effective conviction for downside and execution uncertainty.'),
      if (executed)
        MockConfidenceEvent(
          stage: closed ? 'Before exit' : 'Live thesis',
          confidence: currentConfidence,
          reason: loss || openUnderwater
              ? 'Regime weakened and price failed to follow the expected path, reducing confidence.'
              : 'Breakout structure and volume remained valid after entry.',
        ),
      if (closed)
        MockConfidenceEvent(
          stage: 'Post-trade evaluation',
          confidence: profit ? 89 : 47,
          reason: profit
              ? 'Outcome was directionally consistent with the pre-trade thesis; this is evaluation evidence, not proof of causality.'
              : 'Outcome contradicted part of the thesis; the record separates changed market evidence from model/execution error.',
        ),
    ];

    final invalidationRules = <String>[
      'Price closes below ₹${opportunity.stop.toStringAsFixed(2)} or configured stop is hit.',
      'Breakout loses volume confirmation and returns below the trigger zone.',
      'Market regime flips materially adverse relative to the frozen pre-trade snapshot.',
      'High-materiality adverse event/news contradicts the original thesis.',
      'Risk Engine blocks further exposure or Emergency Stop becomes active.',
      'Data/model evidence becomes stale beyond the allowed freshness window.',
    ];

    final outcomeFactors = loss
        ? const <MockOutcomeFactor>[
            MockOutcomeFactor(label: 'Regime reversal', weight: 32, detail: 'Broad-market support deteriorated after entry.', helped: false),
            MockOutcomeFactor(label: 'Breakout failure', weight: 28, detail: 'Price returned below the expected continuation zone.', helped: false),
            MockOutcomeFactor(label: 'New adverse information', weight: 18, detail: 'Later evidence contradicted the original thesis.', helped: false),
            MockOutcomeFactor(label: 'Entry timing', weight: 12, detail: 'Entry occurred relatively late in the local impulse.', helped: false),
            MockOutcomeFactor(label: 'Execution / slippage', weight: 10, detail: 'Execution worsened the realized result but was not the primary cause.', helped: false),
          ]
        : const <MockOutcomeFactor>[
            MockOutcomeFactor(label: 'Breakout continuation', weight: 35, detail: 'Price followed the expected post-entry structure.', helped: true),
            MockOutcomeFactor(label: 'Volume confirmation', weight: 25, detail: 'Volume stayed above the confirmation threshold.', helped: true),
            MockOutcomeFactor(label: 'Supportive regime', weight: 18, detail: 'The broad market did not invalidate the trade thesis.', helped: true),
            MockOutcomeFactor(label: 'Entry timing', weight: 12, detail: 'Entry remained inside the planned zone.', helped: true),
            MockOutcomeFactor(label: 'Execution quality', weight: 10, detail: 'Mock slippage remained small.', helped: true),
          ];

    final auditReferences = <MockAuditReference>[
      const MockAuditReference(label: 'Pre-trade snapshot', value: 'review-fixture-1042', detail: 'Frozen market/features before the decision.'),
      const MockAuditReference(label: 'Feature schema', value: 'feature-schema-v7', detail: 'Exact feature definitions used by the review models.'),
      const MockAuditReference(label: 'Strategy bundle', value: 'strategy-bundle-v3.2', detail: 'Deterministic setup logic and thresholds.'),
      const MockAuditReference(label: 'Quant model', value: 'quant-baseline-v2.4', detail: 'Calibrated quantitative baseline version.'),
      const MockAuditReference(label: 'PyTorch model', value: 'pytorch-fusion-review-v0.9', detail: 'Synthetic UI-review model version; production must use real registry lineage.'),
      const MockAuditReference(label: 'Gemini evidence', value: 'GEM-1042 · G-104,G-118', detail: 'Structured evidence snapshot and supporting source IDs.'),
      const MockAuditReference(label: 'Risk decision', value: 'RSK-1042 · policy-v5', detail: 'Mandatory Risk Engine decision and policy version.'),
      MockAuditReference(label: 'Trade decision', value: 'TRD-${symbol.toUpperCase()}-${side.substring(0, 1)}-${entry.toStringAsFixed(0)}-1042', detail: 'Immutable decision identifier linking evidence, risk, execution and outcome.'),
      const MockAuditReference(label: 'Decision hash', value: 'sha256:review-7d4c…91af', detail: 'Illustrative integrity fingerprint for UI review.'),
      const MockAuditReference(label: 'Data cutoff', value: '2026-11-01 10:31:02 IST', detail: 'No later information may be inserted into the pre-trade explanation.'),
    ];

    return MockTradeIntelligenceRecord(
      tradeId: 'TRD-${symbol.toUpperCase()}-${side.substring(0, 1)}-${entry.toStringAsFixed(0)}-1042',
      symbol: symbol,
      company: opportunity.company,
      side: side,
      status: status,
      decision: decision,
      entryPrice: entry,
      stopPrice: opportunity.stop,
      targetPrice: opportunity.target,
      lastPrice: quote.price,
      exitPrice: effectiveExit,
      realizedPnl: pnl,
      initialConfidence: opportunity.confidence,
      currentConfidence: currentConfidence,
      decisionSummary: opportunity.thesis,
      primaryReason: 'Momentum/breakout structure + volume confirmation + quantitative/model agreement + supportive pre-trade market context.',
      riskSummary: 'Risk Engine approved the trade because estimated downside, exposure, stop protection and daily-loss constraints were inside the configured limits.',
      outcomeSummary: outcomeSummary,
      failureClassification: failureClassification,
      exitReason: exitReason,
      executed: executed,
      closed: closed,
      cancelled: cancelled,
      evidence: evidence,
      confidenceTimeline: confidenceTimeline,
      invalidationRules: invalidationRules,
      outcomeFactors: outcomeFactors,
      auditReferences: auditReferences,
    );
  }
}
