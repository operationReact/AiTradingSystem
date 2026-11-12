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
}
