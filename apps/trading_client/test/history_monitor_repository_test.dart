import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/history_monitor_repository.dart';

Map<String, dynamic> historySnapshot({
  String status = 'IDLE',
  int rowCount = 7,
}) => {
  'schema': 'minttick.owner.history.progress.v1',
  'source': 'ANGEL_ONE',
  'generatedAt': '2026-11-11T08:30:00+05:30',
  'status': status,
  'task': {
    'name': 'NSE cash equities · Historical candles',
    'interval': 'ONE_DAY',
    'requestedFrom': '2006-11-10',
    'requestedThrough': '2026-11-10',
    'candles': rowCount,
    'stocksWithCandles': rowCount == 0 ? 0 : 2,
    'archivePages': 3,
    'failedWindows': 1,
    'pendingRecoveryScans': 9,
    'yearCoverageVerified': false,
    'listingHistoryVerified': false,
    'years': [
      for (var year = 2006; year <= 2026; year++)
        {
          'year': year,
          'candles': rowCount == 0 ? 0 : (year == 2024 ? 5 : year == 2025 ? 2 : 0),
          'stocks': rowCount == 0 ? 0 : (year == 2024 || year == 2025 ? 2 : 0),
          'firstDate': rowCount > 0 && year == 2024 ? '2024-01-02' : null,
          'lastDate': rowCount > 0 && year == 2024 ? '2024-01-08' : null,
          'completeness': rowCount > 0 && (year == 2024 || year == 2025)
              ? 'UNVERIFIED' : 'NO_DATA',
        },
    ],
  },
  'scheduler': {
    'cadence': 'BOUNDED_CONTINUOUS_OFF_HOURS',
    'weekdayHoursIST': '16:00–20:58',
    'weekendHoursIST': '09:00–20:58',
    'nextScheduledAt': '2026-11-11T16:15:00+05:30',
    'pausedUntil': null,
    'zeroCandleStreak': 1,
  },
  'recentRuns': [
    {
      'startedAt': '2026-11-11T08:15:00+05:30',
      'finishedAt': '2026-11-11T08:18:00+05:30',
      'windowsQueried': 12,
      'newCandles': 0,
      'state': 'COMPLETED',
    },
  ],
  'recentRequests': [
    {
      'symbol': 'RELIANCE-EQ',
      'fromDate': '2024-01-01',
      'throughDate': '2024-12-31',
      'outcome': 'EMPTY_CAUSE_UNKNOWN',
      'returnedCandles': 0,
    },
  ],
};

void main() {
  test('parses verified archive totals without claiming full coverage', () {
    final progress = HistoricalProgress.parse(historySnapshot());
    expect(progress.source, 'ANGEL_ONE');
    expect(progress.interval, 'ONE_DAY');
    expect(progress.candles, 7);
    expect(progress.yearsWithCandles, 2);
    expect(progress.missingYears, 19);
    expect(progress.recentRequests.first.returnedCandles, 0);
    expect(progress.years[18].completeness, 'UNVERIFIED');
  });

  test('rejects numbers that disagree with annual candle counts', () {
    final payload = historySnapshot();
    (payload['task'] as Map<String, dynamic>)['candles'] = 8;
    expect(() => HistoricalProgress.parse(payload), throwsFormatException);
  });

  test('rejects incomplete year lists or fabricated complete history', () {
    final payload = historySnapshot();
    ((payload['task'] as Map<String, dynamic>)['years'] as List).removeLast();
    expect(() => HistoricalProgress.parse(payload), throwsFormatException);
    final forged = historySnapshot();
    (forged['task'] as Map<String, dynamic>)['yearCoverageVerified'] = true;
    expect(() => HistoricalProgress.parse(forged), throwsFormatException);
  });

  test('accepts the verified owner-approved overnight schedule', () {
    final payload = historySnapshot();
    (payload['scheduler'] as Map<String, dynamic>)['cadence'] =
        'BOUNDED_CONTINUOUS_24H';
    (payload['scheduler'] as Map<String, dynamic>)['weekdayHoursIST'] =
        '00:00–23:58';
    (payload['scheduler'] as Map<String, dynamic>)['weekendHoursIST'] =
        '00:00–23:58';
    final progress = HistoricalProgress.parse(payload);
    expect(progress.schedulerCadence, 'BOUNDED_CONTINUOUS_24H');
    expect(progress.weekdayHours, '00:00–23:58');
    expect(progress.candles, 7);
  });

  test('rejects unexpected scheduler cadence', () {
    final payload = historySnapshot();
    (payload['scheduler'] as Map<String, dynamic>)['cadence'] = 'UNRESTRICTED';
    expect(() => HistoricalProgress.parse(payload), throwsFormatException);
  });

  test('continues reading earlier hourly dashboard metadata', () {
    final payload = historySnapshot();
    (payload['scheduler'] as Map<String, dynamic>)['cadence'] = 'HOURLY_OFF_HOURS';
    expect(HistoricalProgress.parse(payload).schedulerCadence,
        'HOURLY_OFF_HOURS');
  });

  test('rejects unsupported interval instead of showing hourly candles', () {
    final payload = historySnapshot();
    (payload['task'] as Map<String, dynamic>)['interval'] = 'ONE_HOUR';
    expect(() => HistoricalProgress.parse(payload), throwsFormatException);
  });
}
