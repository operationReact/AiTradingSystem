import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/fno_history_progress_repository.dart';

Map<String, dynamic> base() => {
  'schema': 'minttick.fno.history.progress.v1',
  'source': 'upstox-expired-nse-fo',
  'planScope': 'CURRENT_JOURNAL_BATCHES_ONLY',
  'state': 'NOT_CONFIGURED',
  'evidence': 'NO_RUNTIME_CHECKPOINT_SNAPSHOT',
  'retentionRightsVerified': false,
  'storageActive': false,
  'generatedAt': null,
  'totalBatches': null,
  'completedBatches': null,
  'completionPercent': null,
  'rowsWritten': null,
  'counts': null,
};

Map<String, dynamic> planned() => {
  ...base(),
  'state': 'PREPARATION_ONLY',
  'evidence': 'CHECKPOINT_METADATA_ONLY',
  'generatedAt': DateTime.now().toUtc().toIso8601String(),
  'totalBatches': 4,
  'completedBatches': 2,
  'completionPercent': 50.0,
  'rowsWritten': 12,
  'counts': {
    'PENDING': 2, 'ACTIVE': 0, 'RETRY': 0, 'DONE': 2,
    'EMPTY': 0, 'FAILED': 0, 'BLOCKED': 0,
  },
};

void main() {
  test('not configured cannot claim a fake percentage', () {
    final parsed = FnoHistoryProgress.parse(base());
    expect(parsed.state, 'NOT_CONFIGURED');
    expect(parsed.percent, isNull);
    expect(parsed.total, isNull);
    expect(parsed.hasPlannedBatches, isFalse);
  });

  test('valid plan accepts only evidence-backed job progress', () {
    final parsed = FnoHistoryProgress.parse(planned());
    expect(parsed.state, 'PREPARATION_ONLY');
    expect(parsed.total, 4);
    expect(parsed.completed, 2);
    expect(parsed.percent, 50);
    expect(parsed.rows, 12);
    expect(parsed.counts?['PENDING'], 2);
  });

  test('rejects made-up percentage and non-matching counts', () {
    final forged = planned()..['completionPercent'] = 99.0;
    expect(() => FnoHistoryProgress.parse(forged), throwsFormatException);
    final wrong = planned();
    (wrong['counts'] as Map<String, dynamic>)['PENDING'] = 3;
    expect(() => FnoHistoryProgress.parse(wrong), throwsFormatException);
  });

  test('rejects storage or retention rights claims from backend', () {
    final wrong = planned()..['storageActive'] = true;
    expect(() => FnoHistoryProgress.parse(wrong), throwsFormatException);
    final rights = planned()..['retentionRightsVerified'] = true;
    expect(() => FnoHistoryProgress.parse(rights), throwsFormatException);
  });

  test('unavailable runtime status has no fabricated completed jobs', () {
    final value = base()..['state'] = 'UNAVAILABLE';
    expect(FnoHistoryProgress.parse(value).percent, isNull);
    final forged = base()..['completionPercent'] = 5.0;
    expect(() => FnoHistoryProgress.parse(forged), throwsFormatException);
  });

  test('unknown provider or stale future timestamp is rejected', () {
    final other = planned()..['source'] = 'synthetic-data';
    expect(() => FnoHistoryProgress.parse(other), throwsFormatException);
    final future = planned()
      ..['generatedAt'] = DateTime.now().toUtc()
          .add(const Duration(hours: 2)).toIso8601String();
    expect(() => FnoHistoryProgress.parse(future), throwsFormatException);
  });
}
