import 'upstox_analytics_progress_repository.dart';

/// Owner-readable, versioned weights for verification gates. A weight is
/// earned only on fresh backend VERIFIED evidence for that exact capability.
/// This is an acceptance score, not an estimate of developer hours or PR count.
class UpstoxReadinessProgress {
  const UpstoxReadinessProgress._({
    required this.verifiedWeight,
    required this.partialTenths,
    required this.observed,
    required this.gates,
  });

  static const formulaVersion = 'upstox.analytics.acceptance.v2';

  // Sum = 100. Keep source smoke testing deliberately small; persistence,
  // lawful data use and paper execution are more significant than API access.
  static const weights = <String, int>{
    'SOURCE_VERIFICATION': 5,
    'INSTRUMENT_MAPPING': 15,
    'HISTORICAL_INGESTION': 20,
    'QUALITY_RIGHTS': 20,
    'FUNDAMENTALS': 5,
    'MIND_DATASETS': 15,
    'PAPER_READINESS': 20,
  };

  final int verifiedWeight;
  // Tenths of a percentage point earned from receipt-backed partial coverage.
  final int partialTenths;
  final bool observed;
  final List<UpstoxProgressGate> gates;

  num? get percent {
    if (!observed) return null;
    final tenths = verifiedWeight * 10 + partialTenths;
    return tenths % 10 == 0 ? tenths ~/ 10 : tenths / 10;
  }
  num? get remainingPercent {
    final value = percent;
    if (value == null) return null;
    final tenths = 1000 - verifiedWeight * 10 - partialTenths;
    return tenths % 10 == 0 ? tenths ~/ 10 : tenths / 10;
  }
  int get verifiedStages => gates.where((g) => g.evidenceState == 'VERIFIED' &&
      g.earned == g.weight).length;
  int get outstandingStages => gates.length - verifiedStages;
  double? get fraction => percent == null ? null : percent! / 100;

  // Only persisted backend evidence with a known, stable target may advance
  // a PARTIAL milestone. Smoke tests, HTTP success, GitHub reports and
  // arbitrary checked counts cannot mint progress.
  static const partialProofs = <String, String>{
    'INSTRUMENT_MAPPING': 'PERSISTED_INSTRUMENT_MAP_V1',
    'HISTORICAL_INGESTION': 'PERSISTED_CANDLE_CHECKPOINTS_V1',
    'FUNDAMENTALS': 'PERSISTED_FUNDAMENTAL_RECEIPTS_V1',
    'MIND_DATASETS': 'PERSISTED_MIND_FEATURES_V1',
  };

  static int _partialTenths(String code, int weight, UpstoxAnalyticsTask? task) {
    if (task == null || task.state != 'PARTIAL' || task.stale ||
        task.details['progressProof'] != partialProofs[code]) return 0;
    final total = task.details['targetUnits'];
    final verified = task.verified;
    final checked = task.checked;
    if (total is! int || total <= 0 || total > 100000000 ||
        verified == null || verified <= 0 || verified > total ||
        checked == null || checked < verified) return 0;
    // PARTIAL never earns the entire milestone; final completion needs
    // an independently verified VERIFIED state.
    final ratio = verified == total ? 0.99 : verified / total;
    return (weight * ratio * 10).floor();
  }

  static UpstoxReadinessProgress calculate(UpstoxAnalyticsProgress progress) {
    assert(weights.values.fold<int>(0, (a, b) => a + b) == 100);
    final gates = <UpstoxProgressGate>[];
    var verified = 0;
    var partialTenths = 0;
    var observed = false;
    for (final entry in weights.entries) {
      final task = progress.find(entry.key);
      if (task != null) observed = true;
      final accepted = task?.state == 'VERIFIED' && task?.stale == false;
      if (accepted) verified += entry.value;
      final partial = accepted ? 0 : _partialTenths(entry.key, entry.value, task);
      partialTenths += partial;
      gates.add(UpstoxProgressGate(
        code: entry.key,
        weight: entry.value,
        earned: accepted ? entry.value : partial / 10,
        evidenceState: task == null ? 'NOT_OBSERVED'
            : task.stale ? 'STALE' : task.state,
      ));
    }
    return UpstoxReadinessProgress._(
      verifiedWeight: verified,
      partialTenths: partialTenths,
      observed: observed,
      gates: List.unmodifiable(gates),
    );
  }
}

class UpstoxProgressGate {
  const UpstoxProgressGate({
    required this.code,
    required this.weight,
    required this.earned,
    required this.evidenceState,
  });

  final String code;
  final int weight;
  final num earned;
  final String evidenceState;
}
