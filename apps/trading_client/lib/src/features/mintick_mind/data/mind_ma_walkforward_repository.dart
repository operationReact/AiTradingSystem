import '../../../data/authenticated_api_client.dart';

/// Explicit research-only contract. Validation of a historical series is
/// NOT predictive model promotion, paper order authority, or broker access.
class MindMaWalkForwardMetrics {
  const MindMaWalkForwardMetrics({
    required this.closedTrades,
    required this.grossPnl,
    required this.feeCost,
    required this.slippageCost,
    required this.netPnl,
    required this.returnPercent,
    required this.maxDrawdownPercent,
  });

  final int closedTrades;
  final num grossPnl;
  final num feeCost;
  final num slippageCost;
  final num netPnl;
  final num returnPercent;
  final num maxDrawdownPercent;

  factory MindMaWalkForwardMetrics.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Missing out-of-sample metrics');
    }
    final trades = value['closedTrades'];
    final gross = _finite(value['grossPnl']);
    final fee = _finite(value['feeCost']);
    final slip = _finite(value['slippageCost']);
    final net = _finite(value['netPnl']);
    final returns = _finite(value['returnPercent']);
    final drawdown = _finite(value['maxDrawdownPercent']);
    if (trades is! int || trades < 0 || fee < 0 || slip < 0 ||
        drawdown < 0 || drawdown > 100) {
      throw const FormatException('Invalid walk-forward cost or risk metrics');
    }
    return MindMaWalkForwardMetrics(
      closedTrades: trades, grossPnl: gross, feeCost: fee,
      slippageCost: slip, netPnl: net, returnPercent: returns,
      maxDrawdownPercent: drawdown,
    );
  }
}

class MindMaWalkForwardFold {
  const MindMaWalkForwardFold({
    required this.number,
    required this.trainingFrom,
    required this.trainingTo,
    required this.validationFrom,
    required this.validationTo,
    required this.trainingCoveragePercent,
    required this.validationCoveragePercent,
    required this.status,
    this.validation,
  });

  final int number;
  final DateTime trainingFrom;
  final DateTime trainingTo;
  final DateTime validationFrom;
  final DateTime validationTo;
  final num trainingCoveragePercent;
  final num validationCoveragePercent;
  final String status;
  final MindMaWalkForwardMetrics? validation;

  factory MindMaWalkForwardFold.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Invalid fold');
    }
    final number = value['number'];
    final trainFrom = DateTime.tryParse(value['trainingFrom']?.toString() ?? '');
    final trainTo = DateTime.tryParse(value['trainingTo']?.toString() ?? '');
    final valFrom = DateTime.tryParse(value['validationFrom']?.toString() ?? '');
    final valTo = DateTime.tryParse(value['validationTo']?.toString() ?? '');
    final trainCoverage = _finite(value['trainingCoveragePercent']);
    final valCoverage = _finite(value['validationCoveragePercent']);
    final status = value['status'];
    if (number is! int || number < 1 ||
        trainFrom == null || trainTo == null ||
        valFrom == null || valTo == null ||
        trainTo.isBefore(trainFrom) ||
        !valFrom.isAfter(trainTo) ||
        valTo.isBefore(valFrom) ||
        trainCoverage < 0 || trainCoverage > 100 ||
        valCoverage < 0 || valCoverage > 100 ||
        !const {'EVALUATED', 'INSUFFICIENT_COVERAGE'}.contains(status) ||
        (status == 'INSUFFICIENT_COVERAGE' && value['validation'] != null) ||
        (status == 'EVALUATED' && value['validation'] == null)) {
      throw const FormatException('Untrustworthy walk-forward fold');
    }
    return MindMaWalkForwardFold(
      number: number,
      trainingFrom: trainFrom,
      trainingTo: trainTo,
      validationFrom: valFrom,
      validationTo: valTo,
      trainingCoveragePercent: trainCoverage,
      validationCoveragePercent: valCoverage,
      status: status as String,
      validation: value['validation'] == null ? null :
          MindMaWalkForwardMetrics.fromJson(value['validation']),
    );
  }
}

class MindMaWalkForwardReport {
  const MindMaWalkForwardReport({
    required this.generatedAt,
    required this.status,
    required this.blockers,
    required this.folds,
    required this.completedFolds,
    required this.oosTrades,
    required this.costLabel,
    required this.feePerSide,
    required this.slippagePerSide,
    this.instrument,
    this.meanOosReturnPercent,
    this.worstOosDrawdownPercent,
    this.oosNetPnl,
  });

  final DateTime generatedAt;
  final String status;
  final String? instrument;
  final String costLabel;
  final num feePerSide;
  final num slippagePerSide;
  final List<MindMaWalkForwardFold> folds;
  final List<String> blockers;
  final int completedFolds;
  final int oosTrades;
  final num? meanOosReturnPercent;
  final num? worstOosDrawdownPercent;
  final num? oosNetPnl;

  factory MindMaWalkForwardReport.fromJson(Object? source) {
    if (source is! Map<String, dynamic> ||
        source['contractVersion'] != 'mind.ma-walkforward.v1' ||
        source['strategyCode'] != 'MA_RECLAIM_V1' ||
        source['strategyVersion'] != '1' ||
        source['interval'] != 'FIVE_MINUTE' ||
        source['selectionMethod'] !=
            'RECENT_SCANNER_CANDIDATE_SELECTION_BIASED' ||
        source['decisionPosture'] != 'ABSTAIN' ||
        source['sourceKnownAtIndependentlyVerified'] != false ||
        source['validatedTradingEdge'] != false ||
        source['paperOrderSubmitted'] != false ||
        source['executionEnabled'] != false) {
      throw const FormatException('Unsafe or unknown Mind walk-forward contract');
    }
    final status = source['status'];
    if (!const {
      'UNAVAILABLE', 'INSUFFICIENT_COVERAGE',
      'INSUFFICIENT_SAMPLE', 'EVALUATED_RESEARCH_ONLY',
    }.contains(status)) {
      throw const FormatException('Invalid walk-forward research state');
    }
    final at = DateTime.tryParse(source['generatedAt']?.toString() ?? '');
    final block = source['blockers'];
    final rawFolds = source['folds'];
    final assumptions = source['assumptions'];
    final completed = source['completedFolds'], trades = source['oosTrades'];
    if (at == null || block is! List || rawFolds is! List ||
        assumptions is! Map<String, dynamic> ||
        assumptions['label'] !=
          'ILLUSTRATIVE_COMPOSITE_COSTS_NOT_ACTUAL_BROKER_OR_STATUTORY_CHARGES' ||
        completed is! int || completed < 0 ||
        trades is! int || trades < 0 || rawFolds.length > 6) {
      throw const FormatException('Missing walk-forward evidence');
    }
    final blockers = block.map((e) => e is String && e.isNotEmpty
        ? e : throw const FormatException('Invalid blocker')).toList(growable: false);
    for (final mandatory in const [
      'SELECTION_BIAS_NOT_CORRECTED',
      'SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN',
      'COST_MODEL_ESTIMATE_NOT_STATUTORY_CHARGES',
      'ACCOUNT_RISK_NOT_EVALUATED',
      'PAPER_SHADOW_ENROLLMENT_REQUIRED',
      'LIVE_EXECUTION_DISABLED',
    ]) {
      if (!blockers.contains(mandatory)) {
        throw const FormatException('Missing walk-forward safety gate');
      }
    }
    final folds = rawFolds.map(MindMaWalkForwardFold.fromJson).toList(growable: false);
    for (var i = 0; i < folds.length; i++) {
      if (folds[i].number != i + 1 ||
          (i > 0 && !folds[i].validationFrom.isAfter(folds[i-1].validationTo))) {
        throw const FormatException('Overlapping walk-forward holdouts');
      }
    }
    final instrument = source['instrument'];
    if (instrument != null && (instrument is! String ||
        !RegExp(r'^(NSE|BSE):[A-Z0-9._-]+-EQ$').hasMatch(instrument))) {
      throw const FormatException('Unsupported research instrument');
    }
    final mean = source['meanOosReturnPercent'] == null
        ? null : _finite(source['meanOosReturnPercent']);
    final dd = source['worstOosDrawdownPercent'] == null
        ? null : _finite(source['worstOosDrawdownPercent']);
    final pnl = source['oosNetPnl'] == null
        ? null : _finite(source['oosNetPnl']);
    if (completed > folds.length ||
        completed != folds.where((e) => e.status == 'EVALUATED').length ||
        ((status == 'UNAVAILABLE') &&
            (folds.isNotEmpty || instrument != null || mean != null)) ||
        (status == 'EVALUATED_RESEARCH_ONLY' &&
            (completed < 3 || trades < 20 || completed != folds.length)) ||
        (mean != null && completed == 0) ||
        (dd != null && (dd < 0 || dd > 100))) {
      throw const FormatException('Walk-forward report overclaims validation');
    }
    return MindMaWalkForwardReport(
      generatedAt: at,
      status: status as String,
      instrument: instrument as String?,
      blockers: blockers,
      folds: folds,
      completedFolds: completed,
      oosTrades: trades,
      costLabel: assumptions['label'] as String,
      feePerSide: _finite(assumptions['feePerSide']),
      slippagePerSide: _finite(assumptions['slippagePerSide']),
      meanOosReturnPercent: mean,
      worstOosDrawdownPercent: dd,
      oosNetPnl: pnl,
    );
  }
}

num _finite(Object? raw) {
  if (raw is! num || !raw.toDouble().isFinite) {
    throw const FormatException('Invalid numeric research evidence');
  }
  return raw;
}

abstract interface class MindMaWalkForwardRepository {
  Future<MindMaWalkForwardReport> load();
}

class HttpMindMaWalkForwardRepository implements MindMaWalkForwardRepository {
  const HttpMindMaWalkForwardRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindMaWalkForwardReport> load() async =>
      MindMaWalkForwardReport.fromJson(
        await api.getJson('/api/customer/mind/v1/ma-walkforward'));
}
