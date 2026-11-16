/// Read-only presentation semantics for Upstox Analytics operational evidence.
///
/// This class does not fetch data or infer provider acceptance, permissions,
/// scheduler activity, or historical completeness.
class UpstoxOpsEvidence {
  const UpstoxOpsEvidence({
    required this.observedAt,
    required this.attempts,
    required this.successes,
    required this.failures,
    required this.lastSuccessAt,
    required this.cooldownUntil,
    required this.tokenAccepted,
    required this.retentionPermitted,
  });

  /// Null means the backend has not supplied an observation, not zero.
  final DateTime? observedAt;
  final int? attempts;
  final int? successes;
  final int? failures;
  final DateTime? lastSuccessAt;
  final DateTime? cooldownUntil;

  /// Null means unknown. Token acceptance does not grant retention rights.
  final bool? tokenAccepted;
  final bool? retentionPermitted;

  String get runStatus {
    if (observedAt == null || attempts == null) return 'Not observed';
    if (attempts! < 0) return 'Invalid telemetry';
    if (attempts == 0) return 'No runs recorded';
    if (successes == null || failures == null ||
        successes! < 0 || failures! < 0 ||
        successes! + failures! > attempts!) {
      return 'Incomplete telemetry';
    }
    return 'Runs recorded';
  }

  String get tokenStatus => switch (tokenAccepted) {
    true => 'Accepted (observed)',
    false => 'Rejected (observed)',
    null => 'Not verified',
  };

  String get rightsStatus => switch (retentionPermitted) {
    true => 'Reported permitted; verify grant',
    false => 'Not permitted',
    null => 'Rights not verified',
  };

  String get lastSuccessStatus =>
      lastSuccessAt == null ? 'No verified success timestamp' : lastSuccessAt!.toUtc().toIso8601String();

  String cooldownStatus(DateTime now) {
    if (cooldownUntil == null) return 'No cooldown reported';
    if (cooldownUntil!.isAfter(now)) {
      return 'Cooldown until ${cooldownUntil!.toUtc().toIso8601String()}';
    }
    return 'No active cooldown';
  }

  /// A valid telemetry snapshot is not evidence of a running backend service.
  bool get hasObservedCounters =>
      observedAt != null && attempts != null && attempts! >= 0 &&
      successes != null && successes! >= 0 &&
      failures != null && failures! >= 0 &&
      successes! + failures! <= attempts!;
}
