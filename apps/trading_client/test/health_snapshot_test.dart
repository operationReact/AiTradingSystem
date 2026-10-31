import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/operations_health/domain/health_snapshot.dart';

void main() {
  test('unknown server states fail closed to UNKNOWN', () {
    expect(parseHealthState('SOMETHING_NEW'), HealthState.unknown);
    expect(parseHealthState(null), HealthState.unknown);
  });

  test('parses operational health contract without inventing missing evidence', () {
    final snapshot = HealthSnapshot.fromJson({
      'checkedAt': '2026-10-30T10:00:00Z',
      'state': 'DEGRADED',
      'executionMode': 'PAPER',
      'liveAvailable': false,
      'emergencyStopActive': null,
      'components': [
        {'component': 'database', 'state': 'HEALTHY', 'asOf': '2026-10-30T10:00:00Z', 'ageSeconds': 0, 'reasonCode': 'READ_SUCCEEDED'},
        {'component': 'scanner', 'state': 'UNKNOWN', 'asOf': null, 'ageSeconds': null, 'reasonCode': 'NO_RUN_EVIDENCE'},
      ],
    });
    expect(snapshot.state, HealthState.degraded);
    expect(snapshot.executionMode, 'PAPER');
    expect(snapshot.liveAvailable, isFalse);
    expect(snapshot.emergencyStopActive, isNull);
    expect(snapshot.components.last.state, HealthState.unknown);
  });
}
