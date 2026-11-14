import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/upstox_analytics/data/upstox_analytics_progress_repository.dart';

void main() {
  Map<String, dynamic> fixture() => {
    'contractVersion': 'minttick.upstox.analytics.progress.v1',
    'generatedAt': '2026-11-13T01:00:00Z',
    'source': 'UPSTOX_ANALYTICS_READONLY',
    'liveTradingEnabled': false,
    'paperTradingEnabled': false,
    'modelTrainingRightsVerified': false,
    'tasks': [
      {
        'code': 'GITHUB_DEVELOPMENT',
        'state': 'VERIFIED',
        'checked': 4,
        'verified': 2,
        'issues': 1,
        'reasonCode': null,
        'details': {
          'scope': 'RECENT_SAMPLE_NOT_TOTAL',
          'openPrs': 2,
          'mergedPrs': 2,
        },
        'lastAttemptAt': '2026-11-13T00:58:00Z',
        'lastSuccessAt': '2026-11-13T00:58:00Z',
        'stale': false,
      },
      {
        'code': 'HISTORICAL_INGESTION',
        'state': 'NOT_STARTED',
        'checked': null,
        'verified': null,
        'issues': null,
        'reasonCode': 'NO_VERIFIED_COVERAGE',
        'details': <String, dynamic>{},
        'stale': false,
      },
    ],
  };

  test('preserves independently verified GitHub state and unknown coverage', () {
    final parsed = UpstoxAnalyticsProgress.fromJson(fixture());
    expect(parsed.find('GITHUB_DEVELOPMENT')?.verified, 2);
    expect(parsed.find('GITHUB_DEVELOPMENT')?.state, 'VERIFIED');
    expect(parsed.find('HISTORICAL_INGESTION')?.checked, isNull);
    expect(parsed.find('HISTORICAL_INGESTION')?.state, 'NOT_STARTED');
  });

  test('never accepts success claims that enable paper or live trading', () {
    final unsafe = fixture();
    unsafe['liveTradingEnabled'] = true;
    expect(() => UpstoxAnalyticsProgress.fromJson(unsafe), throwsFormatException);
    unsafe['liveTradingEnabled'] = false;
    unsafe['paperTradingEnabled'] = true;
    expect(() => UpstoxAnalyticsProgress.fromJson(unsafe), throwsFormatException);
  });

  test('rejects impossible verified counts and duplicate task codes', () {
    final invalid = fixture();
    final tasks = invalid['tasks'] as List;
    (tasks[0] as Map<String, dynamic>)['verified'] = 5;
    expect(() => UpstoxAnalyticsProgress.fromJson(invalid), throwsFormatException);
    (tasks[0] as Map<String, dynamic>)['verified'] = 2;
    tasks.add(Map<String, dynamic>.from(tasks[0] as Map));
    expect(() => UpstoxAnalyticsProgress.fromJson(invalid), throwsFormatException);
  });

  Map<String, dynamic> persisted() => {
    'contractVersion': 'minttick.upstox.persisted-receipts.v1',
    'generatedAt': '2026-11-14T09:00:00Z',
    'provider': 'UPSTOX',
    'modelTrainingRightsVerified': false,
    'paperTradingEnabled': false,
    'receipts': <String, dynamic>{
      'evidenceState': 'PERSISTED_CHECKPOINTS_ONLY',
      'jobs': 2, 'chunks': 5, 'attempts': 8,
      'archivedVerifiedChunks': 3, 'attestedRetentionChunks': 1,
      'archivedVerifiedRecords': 200, 'attestedRetentionRecords': 75,
      'failedChunks': 1, 'retryChunks': 1,
      'lastCheckpointAt': '2026-11-14T08:00:00Z',
      'cooldownUntil': null,
    },
  };

  test('reads independent database receipts without manufacturing readiness', () {
    final receipt = UpstoxPersistedReceipts.fromJson(persisted());
    expect(receipt.jobs, 2);
    expect(receipt.archivedVerifiedChunks, 3);
    expect(receipt.attestedRetentionChunks, 1);
    expect(receipt.attestedRetentionRecords, 75);
    expect(receipt.lastCheckpointAt, DateTime.utc(2026, 11, 14, 8));
    final noProvider = persisted();
    (noProvider['receipts'] as Map<String, dynamic>)['evidenceState'] =
        'NO_PROVIDER_RECEIPT';
    for (final key in <String>[
      'jobs','chunks','attempts','archivedVerifiedChunks',
      'attestedRetentionChunks','archivedVerifiedRecords',
      'attestedRetentionRecords','failedChunks','retryChunks',
    ]) {
      (noProvider['receipts'] as Map<String, dynamic>)[key] = null;
    }
    final unknown = UpstoxPersistedReceipts.fromJson(noProvider);
    expect(unknown.jobs, isNull);
  });

  test('rejects forged rights, impossible counts and provider mismatch', () {
    final malformed = persisted();
    malformed['modelTrainingRightsVerified'] = true;
    expect(() => UpstoxPersistedReceipts.fromJson(malformed),
        throwsFormatException);
    malformed['modelTrainingRightsVerified'] = false;
    (malformed['receipts'] as Map<String, dynamic>)
        ['attestedRetentionChunks'] = 5;
    expect(() => UpstoxPersistedReceipts.fromJson(malformed),
        throwsFormatException);
    (malformed['receipts'] as Map<String, dynamic>)
        ['attestedRetentionChunks'] = 1;
    malformed['provider'] = 'ANGEL_ONE';
    expect(() => UpstoxPersistedReceipts.fromJson(malformed),
        throwsFormatException);
  });

}
