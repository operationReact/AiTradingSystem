import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/upstox_analytics/upstox_ops_evidence.dart';

void main() {
  final now = DateTime.utc(2026, 11, 13, 16);

  UpstoxOpsEvidence evidence({
    DateTime? observedAt,
    int? attempts,
    int? successes,
    int? failures,
    DateTime? lastSuccessAt,
    DateTime? cooldownUntil,
    bool? tokenAccepted,
    bool? retentionPermitted,
  }) => UpstoxOpsEvidence(
    observedAt: observedAt,
    attempts: attempts,
    successes: successes,
    failures: failures,
    lastSuccessAt: lastSuccessAt,
    cooldownUntil: cooldownUntil,
    tokenAccepted: tokenAccepted,
    retentionPermitted: retentionPermitted,
  );

  test('unknown telemetry never renders as zero or a successful run', () {
    final state = evidence();
    expect(state.runStatus, 'Not observed');
    expect(state.hasObservedCounters, isFalse);
    expect(state.tokenStatus, 'Not verified');
    expect(state.rightsStatus, 'Rights not verified');
    expect(state.lastSuccessStatus, 'No verified success timestamp');
  });

  test('observed zero attempts is distinct from no observation', () {
    final state = evidence(observedAt: now, attempts: 0, successes: 0, failures: 0);
    expect(state.runStatus, 'No runs recorded');
    expect(state.hasObservedCounters, isTrue);
  });

  test('inconsistent counters fail closed', () {
    final state = evidence(observedAt: now, attempts: 1, successes: 2, failures: 0);
    expect(state.runStatus, 'Incomplete telemetry');
    expect(state.hasObservedCounters, isFalse);
  });

  test('accepted token is not a rights grant', () {
    final state = evidence(tokenAccepted: true);
    expect(state.tokenStatus, 'Accepted (observed)');
    expect(state.rightsStatus, 'Rights not verified');
  });

  test('cooldown is time bounded and UTC normalized', () {
    final state = evidence(cooldownUntil: now.add(const Duration(minutes: 5)));
    expect(state.cooldownStatus(now), contains('Cooldown until'));
    expect(state.cooldownStatus(now.add(const Duration(minutes: 6))), 'No active cooldown');
  });
}
