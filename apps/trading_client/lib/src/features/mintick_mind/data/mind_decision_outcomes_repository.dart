import '../../../data/authenticated_api_client.dart';

/// Persisted per-strategy research outcomes from succeeded scan runs. Read-only research
/// evidence: a CANDIDATE is not an order, and nothing here enables execution.
abstract interface class MindDecisionOutcomesRepository {
  Future<MindDecisionOutcomes> load();
}

const _outcomeValues = {'CANDIDATE', 'NO_SIGNAL', 'NOT_EVALUATED'};

class MindOutcomeRunSummary {
  const MindOutcomeRunSummary({
    required this.scanRunId,
    required this.completedAt,
    required this.interval,
    required this.candidates,
    required this.noSignal,
    required this.notEvaluated,
  });

  final String scanRunId;
  final DateTime completedAt;
  final String interval;
  final int candidates;
  final int noSignal;
  final int notEvaluated;

  static MindOutcomeRunSummary decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid outcome run');
    final id = value['scanRunId'];
    final interval = value['interval'];
    final c = value['candidates'];
    final n = value['noSignal'];
    final e = value['notEvaluated'];
    final completed = _time(value['completedAt']);
    if (id is! String || id.isEmpty || interval is! String ||
        c is! int || c < 0 || n is! int || n < 0 || e is! int || e < 0) {
      throw const FormatException('Untrustworthy outcome run');
    }
    return MindOutcomeRunSummary(
      scanRunId: id, completedAt: completed, interval: interval,
      candidates: c, noSignal: n, notEvaluated: e);
  }
}

class MindOutcome {
  const MindOutcome({
    required this.scanRunId,
    required this.completedAt,
    required this.strategy,
    required this.outcome,
    required this.reasonCode,
    required this.instrumentId,
    required this.tradingSymbol,
    required this.dataCutoffAt,
  });

  final String scanRunId;
  final DateTime completedAt;
  final String strategy;
  final String outcome;
  final String reasonCode;
  final int instrumentId;
  final String? tradingSymbol;
  final DateTime? dataCutoffAt;

  static MindOutcome decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid outcome');
    final run = value['scanRunId'];
    final strategy = value['strategy'];
    final outcome = value['outcome'];
    final reason = value['reasonCode'];
    final instrument = value['instrumentId'];
    final symbol = value['tradingSymbol'];
    final cutoffRaw = value['dataCutoffAt'];
    if (run is! String || run.isEmpty || strategy is! String || strategy.isEmpty ||
        !_outcomeValues.contains(outcome) || reason is! String || reason.isEmpty ||
        instrument is! int || instrument < 0 || (symbol != null && symbol is! String)) {
      throw const FormatException('Untrustworthy outcome');
    }
    final cutoff = cutoffRaw == null ? null : _time(cutoffRaw);
    // A CANDIDATE is only evidence with its data cutoff; without one it is rejected, never shown.
    if (outcome == 'CANDIDATE' && cutoff == null) {
      throw const FormatException('Candidate without a data cutoff');
    }
    return MindOutcome(
      scanRunId: run,
      completedAt: _time(value['completedAt']),
      strategy: strategy,
      outcome: outcome as String,
      reasonCode: reason,
      instrumentId: instrument,
      tradingSymbol: symbol as String?,
      dataCutoffAt: cutoff,
    );
  }
}

class MindDecisionOutcomes {
  const MindDecisionOutcomes({
    required this.generatedAt,
    required this.runs,
    required this.outcomes,
    required this.limitations,
  });

  static const contract = 'mind.decision-outcomes.v1';

  final DateTime generatedAt;
  final List<MindOutcomeRunSummary> runs;
  final List<MindOutcome> outcomes;
  final List<String> limitations;

  static MindDecisionOutcomes decode(Object? value) {
    if (value is! Map<String, dynamic> || value['contract'] != contract) {
      throw const FormatException('Unknown decision outcomes contract');
    }
    final runs = value['runs'];
    final rows = value['outcomes'];
    final limits = value['limitations'];
    if (runs is! List || rows is! List || limits is! List || limits.any((l) => l is! String)) {
      throw const FormatException('Untrustworthy decision outcomes');
    }
    return MindDecisionOutcomes(
      generatedAt: _time(value['generatedAt']),
      runs: runs.map(MindOutcomeRunSummary.decode).toList(growable: false),
      outcomes: rows.map(MindOutcome.decode).toList(growable: false),
      limitations: List.unmodifiable(limits.cast<String>()),
    );
  }
}

DateTime _time(Object? value) {
  final parsed = value is String ? DateTime.tryParse(value) : null;
  if (parsed == null) throw const FormatException('Invalid timestamp');
  return parsed.toUtc();
}

class HttpMindDecisionOutcomesRepository implements MindDecisionOutcomesRepository {
  const HttpMindDecisionOutcomesRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MindDecisionOutcomes> load() async =>
      MindDecisionOutcomes.decode(await api.getJson('/api/customer/mind/v1/decision-outcomes'));
}
