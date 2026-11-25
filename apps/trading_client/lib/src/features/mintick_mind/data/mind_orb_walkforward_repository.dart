import '../../../data/authenticated_api_client.dart';

class MindOrbWalkForwardFold {
  const MindOrbWalkForwardFold({
    required this.number,
    required this.trainingCoveragePercent,
    required this.validationCoveragePercent,
    required this.status,
    this.validationTrades,
    this.validationNetPnl,
    this.validationReturnPercent,
    this.validationDrawdownPercent,
  });

  final int number;
  final num trainingCoveragePercent;
  final num validationCoveragePercent;
  final String status;
  final int? validationTrades;
  final num? validationNetPnl;
  final num? validationReturnPercent;
  final num? validationDrawdownPercent;

  factory MindOrbWalkForwardFold.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Invalid ORB walk-forward fold');
    }
    final number = value['number'];
    final train = _number(value['trainingCoveragePercent']);
    final validation = _number(value['validationCoveragePercent']);
    final status = value['status'];
    final metrics = value['validation'];
    if (number is! int || number < 1 || train < 0 || train > 100 ||
        validation < 0 || validation > 100 ||
        !const {'EVALUATED', 'INSUFFICIENT_COVERAGE'}.contains(status) ||
        (status == 'EVALUATED' && metrics is! Map<String, dynamic>) ||
        (status == 'INSUFFICIENT_COVERAGE' && metrics != null)) {
      throw const FormatException('Untrustworthy ORB walk-forward fold');
    }
    if (metrics == null) {
      return MindOrbWalkForwardFold(
        number: number, trainingCoveragePercent: train,
        validationCoveragePercent: validation, status: status as String);
    }
    final trades = metrics['closedTrades'];
    final net = _number(metrics['netPnl']);
    final returns = _number(metrics['returnPercent']);
    final drawdown = _number(metrics['maxDrawdownPercent']);
    if (trades is! int || trades < 0 || drawdown < 0 || drawdown > 100) {
      throw const FormatException('Invalid ORB holdout metrics');
    }
    return MindOrbWalkForwardFold(
      number: number, trainingCoveragePercent: train,
      validationCoveragePercent: validation, status: status as String,
      validationTrades: trades, validationNetPnl: net,
      validationReturnPercent: returns, validationDrawdownPercent: drawdown);
  }
}

class MindOrbWalkForwardReport {
  const MindOrbWalkForwardReport({
    required this.generatedAt,
    required this.status,
    required this.instrument,
    required this.interval,
    required this.blockers,
    required this.folds,
    required this.completedFolds,
    required this.oosTrades,
    required this.costLabel,
    required this.feePerSide,
    required this.slippagePerSide,
    required this.executionEnabled,
    required this.paperOrderSubmitted,
    required this.validatedTradingEdge,
    this.meanOosReturnPercent,
    this.worstOosDrawdownPercent,
    this.oosNetPnl,
  });

  final DateTime generatedAt;
  final String status;
  final String? instrument;
  final String? interval;
  final List<String> blockers;
  final List<MindOrbWalkForwardFold> folds;
  final int completedFolds;
  final int oosTrades;
  final String costLabel;
  final num feePerSide;
  final num slippagePerSide;
  final bool executionEnabled;
  final bool paperOrderSubmitted;
  final bool validatedTradingEdge;
  final num? meanOosReturnPercent;
  final num? worstOosDrawdownPercent;
  final num? oosNetPnl;

  factory MindOrbWalkForwardReport.fromJson(Object? source) {
    if (source is! Map<String, dynamic> ||
        source['contractVersion'] != 'mind.orb-walkforward.v1' ||
        source['strategyCode'] != 'OPENING_RANGE_BREAKOUT_V1' ||
        source['strategyVersion'] != '1' ||
        source['decisionPosture'] != 'ABSTAIN' ||
        source['sourceKnownAtIndependentlyVerified'] != false ||
        source['validatedTradingEdge'] != false ||
        source['paperOrderSubmitted'] != false ||
        source['executionEnabled'] != false ||
        source['status'] is! String ||
        !const {'UNAVAILABLE', 'INSUFFICIENT_COVERAGE', 'INSUFFICIENT_SAMPLE',
          'EVALUATED_RESEARCH_ONLY'}.contains(source['status'])) {
      throw const FormatException('Unsafe ORB walk-forward contract');
    }
    final generated = DateTime.tryParse(source['generatedAt']?.toString() ?? '');
    final rawFolds = source['folds'];
    final rawBlockers = source['blockers'];
    final completed = source['completedFolds'];
    final trades = source['oosTrades'];
    final assumptions = source['assumptions'];
    if (generated == null || rawFolds is! List || rawBlockers is! List ||
        completed is! int || completed < 0 || trades is! int || trades < 0 ||
        assumptions is! Map<String, dynamic>) {
      throw const FormatException('Incomplete ORB walk-forward lineage');
    }
    final blockers = rawBlockers.map((v) => v is String && v.isNotEmpty
        ? v : throw const FormatException('Invalid ORB research gate'))
        .toList(growable: false);
    if (!blockers.contains('SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN') ||
        !blockers.contains('COST_MODEL_ESTIMATE_NOT_STATUTORY_CHARGES') ||
        !blockers.contains('ACCOUNT_RISK_NOT_EVALUATED') ||
        !blockers.contains('PAPER_SHADOW_ENROLLMENT_REQUIRED') ||
        !blockers.contains('LIVE_EXECUTION_DISABLED')) {
      throw const FormatException('Missing ORB research safety gates');
    }
    final fee = _number(assumptions['feePerSide']);
    final slippage = _number(assumptions['slippagePerSide']);
    final label = assumptions['label'];
    final interval = source['interval'];
    final instrument = source['instrument'];
    if (fee < 0 || slippage < 0 || label is! String ||
        !label.contains('ILLUSTRATIVE') ||
        (interval != null && !const {
          'ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE'
        }.contains(interval)) ||
        (instrument != null &&
          (instrument is! String ||
           !RegExp(r'^(NSE|BSE):[A-Z0-9._-]+-EQ$').hasMatch(instrument)))) {
      throw const FormatException('Invalid ORB research source or cost assumptions');
    }
    final folds = rawFolds.map(MindOrbWalkForwardFold.fromJson)
        .toList(growable: false);
    if (completed != folds.where((f) => f.status == 'EVALUATED').length ||
        (source['status'] == 'EVALUATED_RESEARCH_ONLY' &&
          (completed < 3 || trades < 20))) {
      throw const FormatException('ORB walk-forward sample gates do not reconcile');
    }
    return MindOrbWalkForwardReport(
      generatedAt: generated,
      status: source['status'] as String,
      instrument: instrument as String?,
      interval: interval as String?,
      blockers: blockers,
      folds: folds,
      completedFolds: completed,
      oosTrades: trades,
      costLabel: label,
      feePerSide: fee,
      slippagePerSide: slippage,
      executionEnabled: false,
      paperOrderSubmitted: false,
      validatedTradingEdge: false,
      meanOosReturnPercent: _optionalNumber(source['meanOosReturnPercent']),
      worstOosDrawdownPercent: _optionalNumber(source['worstOosDrawdownPercent']),
      oosNetPnl: _optionalNumber(source['oosNetPnl']),
    );
  }
}

abstract interface class MindOrbWalkForwardRepository {
  Future<MindOrbWalkForwardReport> load();
}

class HttpMindOrbWalkForwardRepository implements MindOrbWalkForwardRepository {
  const HttpMindOrbWalkForwardRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindOrbWalkForwardReport> load() async =>
      MindOrbWalkForwardReport.fromJson(
        await api.getJson('/api/customer/mind/v1/orb-walkforward'));
}

num _number(Object? value) {
  if (value is num && value.isFinite) return value;
  throw const FormatException('Expected finite ORB research number');
}

num? _optionalNumber(Object? value) => value == null ? null : _number(value);
