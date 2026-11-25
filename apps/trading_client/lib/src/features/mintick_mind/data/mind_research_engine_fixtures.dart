/// Design-only registry for the five externally assessed research frameworks.
///
/// These are PLANNED integrations, not installed plugins or functioning backtests.
/// Each record is a placeholder contract; no market-data, broker, or API access.
class MindResearchEngineFixture {
  const MindResearchEngineFixture({
    required this.id,
    required this.name,
    required this.category,
    required this.role,
    required this.decision,
    required this.license,
    required this.plannedPrs,
    required this.prerequisites,
    required this.sourceFiles,
    required this.mintTickTouchpoints,
    required this.targetScreens,
  });

  final String id;
  final String name;
  final String category;
  final String role;
  final String decision;
  final String license;
  final String plannedPrs;
  final List<String> prerequisites;
  final List<String> sourceFiles;
  final List<String> mintTickTouchpoints;
  final List<String> targetScreens;

  /// Explicitly false until an approved backend integration provides live status.
  bool get connected => false;
  bool get orderAuthority => false;
}

abstract final class MindResearchEngineFixtures {
  static const all = <MindResearchEngineFixture>[
    MindResearchEngineFixture(
      id: 'VECTORBT',
      name: 'VectorBT',
      category: 'Pilot',
      role: 'Batch strategy screening and parameter optimization',
      decision: 'First pilot; retain only if parity, licence, and cost gates pass',
      license: 'Apache 2.0 + Commons Clause; future commercial use requires review',
      plannedPrs: 'OSS-01–06, OSS-12–14',
      prerequisites: <String>[
        'Pinned compatible Python 3.11 / ARM64 package and licence decision',
        'Verified point-in-time NSE/BSE historical data and corporate actions',
        'Identical strategy rules, Indian costs, slippage, and walk-forward folds',
        'Benchmarked memory, speed, accuracy, and immutable experiment receipts',
      ],
      sourceFiles: <String>[
        'vectorbt/portfolio/base.py',
        'vectorbt/indicators/factory.py',
        'vectorbt/generic/splitters.py',
        'vectorbt/returns/metrics.py',
      ],
      mintTickTouchpoints: <String>[
        'intelligence/.../research_dataset.py',
        'intelligence/.../research_engines/vectorbt_adapter.py (proposed)',
        'backend/.../governance/ExperimentJobOrchestrationService.java',
      ],
      targetScreens: <String>['research','strategies','strategy-detail','experiment-detail','signal-scorecard'],
    ),
    MindResearchEngineFixture(
      id: 'BACKTESTING_PY',
      name: 'Backtesting.py',
      category: 'Validator',
      role: 'Independent bar-by-bar backtest and stop/target consistency checks',
      decision: 'Optional secondary oracle; do not duplicate the main Java engine',
      license: 'AGPL-3.0; network/commercial distribution obligations need review',
      plannedPrs: 'OSS-01–04, OSS-07, OSS-12–14',
      prerequisites: <String>[
        'Independent licence and dependency isolation review',
        'Same India market calendar, bars, cost model, and strategy version',
        'Golden tests for intrabar stop/target, gaps, cash, and order rounding',
      ],
      sourceFiles: <String>[
        'backtesting/backtesting.py',
        'backtesting/lib.py',
        'backtesting/_stats.py',
      ],
      mintTickTouchpoints: <String>[
        'backend/.../backtest/PaperBacktestService.java',
        'intelligence/.../research_engines/backtesting_py_adapter.py (proposed)',
        'backend/.../governance/BacktestComparisonService.java',
      ],
      targetScreens: <String>['research','experiment-detail','strategy-detail'],
    ),
    MindResearchEngineFixture(
      id: 'NAUTILUS',
      name: 'NautilusTrader',
      category: 'Advanced',
      role: 'Event-driven high-fidelity execution, latency, and fill replay',
      decision: 'Defer until existing fill-simulator gap is measured',
      license: 'LGPL-3.0; linking and future distribution need review',
      plannedPrs: 'OSS-01, OSS-09–10, OSS-12–14',
      prerequisites: <String>[
        'Document concrete fill accuracy deficits in the existing simulator',
        'Licensed tick / bid-ask / depth and synchronized event timestamps',
        'Isolated Rust/Python worker with deterministic India-market contracts',
        'Demonstrate value exceeding runtime and maintenance cost',
      ],
      sourceFiles: <String>[
        'crates/backtest/src/engine.rs',
        'crates/backtest/src/exchange.rs',
        'crates/backtest/src/execution_client.rs',
      ],
      mintTickTouchpoints: <String>[
        'intelligence/.../fill_simulator.py',
        'intelligence/.../research_engines/nautilus_adapter.py (proposed)',
        'backend/.../governance/BacktestComparisonService.java',
      ],
      targetScreens: <String>['shadow-arena','culprit-catcher','decision-lab','experiment-detail'],
    ),
    MindResearchEngineFixture(
      id: 'LEAN',
      name: 'QuantConnect LEAN',
      category: 'Reference',
      role: 'Independent architecture benchmark for India calendars, fees, orders',
      decision: 'Reference only; optional sandbox comparison, no .NET core replacement',
      license: 'Apache-2.0 for engine; data and related services are separate',
      plannedPrs: 'OSS-01, OSS-04, OSS-11, OSS-14',
      prerequisites: <String>[
        'Match Indian timezones, corporate adjustments, and order assumptions',
        'Validate separate data rights and optional .NET runtime cost',
        'Demonstrate measurable correctness value versus existing Java engine',
      ],
      sourceFiles: <String>[
        'Algorithm.CSharp/BasicTemplateIndiaAlgorithm.cs',
        'Common/Brokerages/ZerodhaBrokerageModel.cs',
        'Common/Data/Auxiliary/CorporateFactorProvider.cs',
      ],
      mintTickTouchpoints: <String>[
        'backend/.../backtest/BacktestDataGuard.java',
        'intelligence/.../research_engines/lean_reference.py (proposed)',
        'docs/mintick-mind/THIRD_PARTY_RESEARCH_ENGINES.md',
      ],
      targetScreens: <String>['research','experiment-detail','dependencies'],
    ),
    MindResearchEngineFixture(
      id: 'FREQTRADE',
      name: 'Freqtrade',
      category: 'Patterns',
      role: 'Study bias analysis, recursive indicators, hyperopt, and dry-run design',
      decision: 'No crypto bot runtime; implement useful ideas independently',
      license: 'GPL-3.0; do not copy covered source into MintTick by default',
      plannedPrs: 'OSS-01, OSS-08, OSS-14',
      prerequisites: <String>[
        'Audit current MintTick leakage and warm-up test coverage',
        'Write original India-market causal checks without copying GPL code',
        'Demonstrate detection of future-data leakage and recursion drift',
      ],
      sourceFiles: <String>[
        'freqtrade/optimize/analysis/lookahead.py',
        'freqtrade/optimize/analysis/recursive.py',
        'freqtrade/optimize/hyperopt/hyperopt.py',
      ],
      mintTickTouchpoints: <String>[
        'intelligence/.../combinatorial_purged_cv.py',
        'intelligence/.../research_engines/leakage_checks.py (proposed)',
        'intelligence/tests/test_research_engines_*.py (proposed)',
      ],
      targetScreens: <String>['research','experiment-detail','coverage'],
    ),
  ];

  static MindResearchEngineFixture byId(String id) =>
      all.firstWhere((engine) => engine.id == id, orElse: () => all.first);
}
