import 'upstox_analytics_progress_repository.dart';

/// Owner-readable, versioned weights for verification gates. A weight is
/// earned only on fresh backend VERIFIED evidence for that exact capability.
/// This is an acceptance score, not an estimate of developer hours or PR count.
class UpstoxReadinessProgress {
  const UpstoxReadinessProgress._({
    required this.verifiedWeight,
    required this.observed,
    required this.gates,
  });

  static const formulaVersion = 'upstox.analytics.acceptance.v1';

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
  final bool observed;
  final List<UpstoxProgressGate> gates;

  int? get percent => observed ? verifiedWeight : null;
  double? get fraction => percent == null ? null : percent! / 100;

  static UpstoxReadinessProgress calculate(UpstoxAnalyticsProgress progress) {
    assert(weights.values.fold<int>(0, (a, b) => a + b) == 100);
    final gates = <UpstoxProgressGate>[];
    var verified = 0;
    var observed = false;
    for (final entry in weights.entries) {
      final task = progress.find(entry.key);
      if (task != null) observed = true;
      final accepted = task?.state == 'VERIFIED' && task?.stale == false;
      if (accepted) verified += entry.value;
      gates.add(UpstoxProgressGate(
        code: entry.key,
        weight: entry.value,
        earned: accepted ? entry.value : 0,
        evidenceState: task == null ? 'NOT_OBSERVED'
            : task.stale ? 'STALE' : task.state,
      ));
    }
    return UpstoxReadinessProgress._(
      verifiedWeight: verified,
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
  final int earned;
  final String evidenceState;
}
