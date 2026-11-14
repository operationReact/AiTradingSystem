/// Human-reviewed planning evidence, NEVER backend acceptance or trading eligibility.
/// Keep independent of receipt-backed historical ingestion completion.
enum FnoEvidenceState { verified, partial, notVerified }

class FnoReadinessGate {
  const FnoReadinessGate({
    required this.id,
    required this.label,
    required this.weight,
    required this.earned,
    required this.state,
    required this.evidence,
  }) : assert(weight > 0),
       assert(earned >= 0),
       assert(earned <= weight);

  final String id;
  final String label;
  final int weight;
  final int earned;
  final FnoEvidenceState state;
  final String evidence;
}

class FnoReadinessScorecard {
  const FnoReadinessScorecard({
    required this.version,
    required this.reviewedAtUtc,
    required this.gates,
  });

  final String version;
  final DateTime reviewedAtUtc;
  final List<FnoReadinessGate> gates;

  int get earnedPoints => gates.fold(0, (sum, gate) => sum + gate.earned);
  int get totalPoints => gates.fold(0, (sum, gate) => sum + gate.weight);

  bool get paperTradable => false; // Never infer from a planning score.

  String get label => 'Human-reviewed planning estimate (not runtime acceptance)';

  bool get isValid =>
      totalPoints == 100 &&
      gates.map((gate) => gate.id).toSet().length == gates.length &&
      gates.every((gate) => gate.earned <= gate.weight);

  /// This dated review must not silently become a live runtime measurement.
  static FnoReadinessScorecard reviewedNovember14() => FnoReadinessScorecard(
    version: 'fno-paper-plan-2026-11-14T14:30Z-v1',
    reviewedAtUtc: DateTime.utc(2026, 11, 14, 14, 30),
    gates: const [
      FnoReadinessGate(id: 'source-chain', label: 'NIFTY contract/chain read', weight: 15, earned: 15, state: FnoEvidenceState.verified, evidence: 'GitHub Actions 43603258735: 284 contracts, 142 strikes, 142 CE, 142 PE, 142 Greeks; source read only'),
      FnoReadinessGate(id: 'canonical', label: 'Versioned canonical contract', weight: 10, earned: 8, state: FnoEvidenceState.partial, evidence: 'PR 1129 merged; runtime producer wiring unverified'),
      FnoReadinessGate(id: 'v3-stream', label: 'Fresh V3 exchange-LTT', weight: 15, earned: 4, state: FnoEvidenceState.partial, evidence: 'Protocol code merged; no live per-contract tick receipt'),
      FnoReadinessGate(id: 'paper-ledger', label: 'Long-option PAPER ledger', weight: 20, earned: 14, state: FnoEvidenceState.partial, evidence: 'PR 1080 integration tests and deploy; no runtime BUY/close receipt'),
      FnoReadinessGate(id: 'flutter', label: 'Flutter F&O screen', weight: 15, earned: 7, state: FnoEvidenceState.partial, evidence: 'Review snapshot; check PR 1128 and Android device proof independently'),
      FnoReadinessGate(id: 'rights', label: 'Display/PAPER source rights', weight: 10, earned: 0, state: FnoEvidenceState.notVerified, evidence: 'NOT_VERIFIED; API read does not confer display or simulation rights'),
      FnoReadinessGate(id: 'delivery', label: 'Backend/API + Android delivery', weight: 10, earned: 7, state: FnoEvidenceState.partial, evidence: 'Backend and edge deployed; connected F&O APK/device proof missing at review time'),
      FnoReadinessGate(id: 'end-to-end', label: 'One-lot PAPER BUY/close', weight: 5, earned: 0, state: FnoEvidenceState.notVerified, evidence: 'No runtime acceptance receipt'),
    ],
  );
}
