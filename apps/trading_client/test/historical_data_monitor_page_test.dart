import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/history_monitor_repository.dart';
import 'package:trading_client/src/presentation/historical_data_monitor_page.dart';
import 'package:trading_client/src/presentation/settings_page.dart';

HistoricalProgress fixture() => HistoricalProgress(
  generatedAt: DateTime.utc(2026, 11, 11, 3, 30),
  status: 'IDLE',
  source: 'ANGEL_ONE',
  taskName: 'NSE cash equities · Historical candles',
  interval: 'ONE_DAY',
  requestedFrom: '2006-11-10',
  requestedThrough: '2026-11-10',
  candles: 56172,
  stocksWithCandles: 5,
  archivePages: 126,
  failedWindows: 18,
  pendingRecoveryScans: 61,
  years: List.generate(21, (i) => HistoryYear(
    2006 + i, i == 20 ? 56172 : 0, i == 20 ? 5 : 0,
    i == 20 ? '2026-01-02' : null,
    i == 20 ? '2026-11-10' : null,
    i == 20 ? 'UNVERIFIED' : 'NO_DATA',
  )),
  schedulerCadence: 'HOURLY_OFF_HOURS',
  weekdayHours: '16:15–20:15',
  weekendHours: '09:15–20:15',
  nextScheduledAt: DateTime.utc(2026, 11, 11, 10, 45),
  pausedUntil: null,
  zeroCandleStreak: 1,
  recentRuns: [
    HistoryRun(
      DateTime.utc(2026, 11, 11, 3, 20),
      DateTime.utc(2026, 11, 11, 3, 22),
      12, 0, 'COMPLETED',
    ),
  ],
  recentRequests: const [
    HistoryRequest('RELIANCE-EQ', '2024-01-01', '2024-12-31',
      'EMPTY_CAUSE_UNKNOWN', 0),
  ],
);

class _FakeMonitor implements HistoryMonitorRepository {
  _FakeMonitor({this.unavailable = false});
  final bool unavailable;
  @override
  Future<HistoricalProgress> load() async {
    if (unavailable) throw const FormatException('No archive');
    return fixture();
  }
}

void main() {
  testWidgets('shows source-backed interval, annual counts and a run',
      (tester) async {
    await tester.pumpWidget(MaterialApp(home:
      HistoricalDataMonitorPage(repository: _FakeMonitor())));
    await tester.pumpAndSettle();
    expect(find.text('Historical Data Monitor'), findsOneWidget);
    expect(find.text('56,172'), findsWidgets);
    expect(find.text('Daily · 1D (ONE_DAY)'), findsOneWidget);
    expect(find.text('Hourly, off-hours only'), findsOneWidget);
    expect(find.text('Coverage by year'), findsOneWidget);
    expect(find.byKey(const ValueKey('history-year-2026')), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('history-year-2026')), 180,
      scrollable: find.byType(Scrollable).first);
    await tester.tap(find.byKey(const ValueKey('history-year-2026')));
    await tester.pumpAndSettle();
    expect(find.text('Partial / completeness unverified'), findsWidgets);
  });

  testWidgets('failure never displays stale or invented candle totals',
      (tester) async {
    await tester.pumpWidget(MaterialApp(home:
      HistoricalDataMonitorPage(repository: _FakeMonitor(unavailable: true))));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('history-monitor-error')), findsOneWidget);
    expect(find.text('56,172'), findsNothing);
    expect(find.byKey(const ValueKey('history-monitor-retry')), findsOneWidget);
  });

  testWidgets('settings entry is explicitly optional and removable',
      (tester) async {
    var opened = false;
    await tester.pumpWidget(MaterialApp(home: SettingsPage(
      themeMode: ThemeMode.dark,
      onThemeModeChanged: (_) {},
      onHistoryMonitor: () => opened = true,
    )));
    expect(find.byKey(const ValueKey('settings-historical-monitor')),
      findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('settings-historical-monitor')));
    expect(opened, isTrue);
  });
}
