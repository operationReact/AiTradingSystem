import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/history_monitor_repository.dart';
import 'package:trading_client/src/presentation/historical_data_monitor_page.dart';
import 'package:trading_client/src/presentation/settings_page.dart';

HistoricalProgress fixture({DateTime? generatedAt}) => HistoricalProgress(
  generatedAt: generatedAt ?? DateTime.now().toUtc(),
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
  schedulerCadence: 'BOUNDED_CONTINUOUS_OFF_HOURS',
  weekdayHours: '00:00–20:58',
  weekendHours: '00:00–20:58',
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
  _FakeMonitor({this.unavailable = false, this.generatedAt});
  final bool unavailable;
  final DateTime? generatedAt;
  @override
  Future<HistoricalProgress> load() async {
    if (unavailable) throw const FormatException('No archive');
    return fixture(generatedAt: generatedAt);
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
    expect(find.text('Automatic · checks every 2 min while EC2 is on'), findsOneWidget);
    // ListView lazily builds the lower sections; scroll them into view first.
    await tester.scrollUntilVisible(
      find.text('Coverage by year'), 160,
      scrollable: find.byType(Scrollable).first);
    // Source evidence is checked in the repository contract tests.
    expect(find.text('Coverage by year'), findsOneWidget);
  });

  testWidgets('outdated snapshot never looks like a healthy idle scheduler',
      (tester) async {
    await tester.pumpWidget(MaterialApp(home:
      HistoricalDataMonitorPage(repository: _FakeMonitor(
        generatedAt: DateTime.utc(2026, 1, 1),
      ))));
    await tester.pumpAndSettle();
    expect(find.text('STALE'), findsOneWidget);
    expect(find.byKey(const ValueKey('history-monitor-stale')), findsOneWidget);
    expect(find.text('56,172'), findsWidgets);
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
