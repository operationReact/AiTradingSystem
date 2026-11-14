import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/upstox_analytics/data/upstox_analytics_progress_repository.dart';
import 'package:trading_client/src/features/upstox_analytics/presentation/upstox_analytics_progress_page.dart';

class _FixedProgressRepository implements UpstoxAnalyticsProgressRepository {
  const _FixedProgressRepository(this.progress);
  final UpstoxAnalyticsProgress progress;

  @override
  Future<UpstoxAnalyticsProgress> load() async => progress;
}

class _SequenceProgressRepository implements UpstoxAnalyticsProgressRepository {
  int reads = 0;
  @override
  Future<UpstoxAnalyticsProgress> load() async {
    return _snapshot(partial: reads++ > 0);
  }
}

UpstoxAnalyticsProgress _snapshot({bool stale = false, bool partial = false}) {
  final at = DateTime.parse('2026-11-13T10:00:00Z');
  return UpstoxAnalyticsProgress(
    contractVersion: 'minttick.upstox.analytics.progress.v1',
    generatedAt: at,
    tasks: [
      UpstoxAnalyticsTask(
        code: 'SOURCE_VERIFICATION',
        state: 'VERIFIED',
        reasonCode: null,
        checked: 2,
        verified: 2,
        issues: 0,
        details: const {'validatedDailyCandles': 5, 'currentRatios': 7,
          'stored': false, 'modelTrainingEnabled': false},
        stale: stale,
        lastAttemptAt: at,
        lastSuccessAt: at,
      ),
      if (partial)
        const UpstoxAnalyticsTask(
          code: 'HISTORICAL_INGESTION',
          state: 'PARTIAL',
          reasonCode: null,
          details: {'progressProof': 'PERSISTED_CANDLE_CHECKPOINTS_V1',
            'targetUnits': 100},
          checked: 60, verified: 40, stale: false,
        )
      else
        const UpstoxAnalyticsTask(
          code: 'HISTORICAL_INGESTION',
          state: 'NOT_STARTED',
          reasonCode: 'NO_VERIFIED_COVERAGE',
          details: {},
          stale: false,
        ),
      const UpstoxAnalyticsTask(
        code: 'QUALITY_RIGHTS',
        state: 'NOT_STARTED',
        reasonCode: 'NO_VERIFIED_RIGHTS_RECEIPT',
        details: {},
        stale: false,
      ),
    ],
  );
}

UpstoxAnalyticsProgress _snapshotWithWorkerReports() {
  final original = _snapshot();
  return UpstoxAnalyticsProgress(
    generatedAt: original.generatedAt,
    contractVersion: original.contractVersion,
    tasks: [
      ...original.tasks,
      UpstoxAnalyticsTask(
        code: 'WORKER_REPORTS', state: 'VERIFIED',
        reasonCode: null, stale: false,
        details: {
          'workerReports': [
            for (var i = 1; i <= 5; i++)
              {
                'worker': i,
                'scope': const [
                  'SOURCE_INGESTION', 'CANONICAL_DATA', 'COVERAGE_GAPS',
                  'FUNDAMENTALS_EVENTS', 'OPERATIONS_UI',
                ][i - 1],
                'state': 'FRESH',
                'result': i == 1 ? 'MERGED' : i == 3 ? 'BLOCKED' : 'PR_OPEN',
                'ci': i == 1 || i == 3 ? 'PASS' : 'PENDING',
                'pr': '${1060 + i}',
                'url': 'https://github.com/operationReact/AiTradingSystem/pull/${1060 + i}',
                'reportedAt': '2026-11-14T09:00:00Z',
              },
          ],
        },
      ),
    ],
  );
}

void main() {
  testWidgets('shows read-only sample evidence in compact three-tab layout',
      (tester) async {
    tester.view.physicalSize = const Size(420, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      home: UpstoxAnalyticsProgressPage(
        repository: _FixedProgressRepository(_snapshot()),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Overview'), findsOneWidget);
    expect(find.text('Coverage'), findsOneWidget);
    expect(find.text('Operations'), findsOneWidget);
    expect(find.text('SOURCE OK'), findsOneWidget);
    expect(find.text('Data & PAPER acceptance'), findsOneWidget);
    expect(find.text('5%'), findsOneWidget);
    expect(find.text('Remaining: 95%'), findsOneWidget);
    expect(find.byKey(const ValueKey('upstox-verified-completion-bar')), findsOneWidget);
    expect(find.text('Candles'), findsOneWidget);
    expect(find.text('5'), findsOneWidget);
    expect(find.text('Ratios'), findsOneWidget);
    expect(find.text('7'), findsOneWidget);
    expect(find.textContaining('not a historical download'), findsOneWidget);

    await tester.tap(find.text('Coverage'));
    await tester.pumpAndSettle();
    expect(find.text('Historical coverage'), findsOneWidget);
    expect(find.textContaining('No verified timeframe'), findsOneWidget);

    await tester.tap(find.text('Operations'));
    await tester.pumpAndSettle();
    expect(find.text('Five data workstreams'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Source verification'), 280,
      scrollable: find.byType(Scrollable).first);
    await tester.pumpAndSettle();
    expect(find.text('Source verification'), findsWidgets);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('explains milestone weights without pretending PRs count as completion',
      (tester) async {
    tester.view.physicalSize = const Size(420, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      home: UpstoxAnalyticsProgressPage(
        repository: _FixedProgressRepository(_snapshot()),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Pending details'));
    await tester.pumpAndSettle();
    expect(find.text('Percentage calculation'), findsOneWidget);
    expect(find.text('Persistent historical data'), findsOneWidget);
    expect(find.text('20% left'), findsWidgets);
    expect(find.text('5% done'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('does not present stale source evidence as current',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: UpstoxAnalyticsProgressPage(
        repository: _FixedProgressRepository(_snapshot(stale: true)),
      ),
    ));
    await tester.pumpAndSettle();
    // A stale source is surfaced first; the source metrics remain further
    // down in the scroll view and must never display cached values.
    expect(find.textContaining('source verification is stale'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Candles'), 180,
      scrollable: find.byType(Scrollable).first);
    await tester.pumpAndSettle();
    expect(find.text('Candles'), findsOneWidget);
    expect(find.text('Ratios'), findsOneWidget);
    expect(find.text('5'), findsNothing);
    expect(find.text('7'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('shows genuine partial receipt coverage in tenths-based score',
      (tester) async {
    tester.view.physicalSize = const Size(420, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      home: UpstoxAnalyticsProgressPage(
        repository: _FixedProgressRepository(_snapshot(partial: true)),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('13%'), findsOneWidget);
    expect(find.text('Remaining: 87%'), findsOneWidget);
    await tester.tap(find.text('Pending details'));
    await tester.pumpAndSettle();
    expect(find.text('8% / 20% earned'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });


  testWidgets('backend refresh delta counts only changed evidence',
      (tester) async {
    final repository = _SequenceProgressRepository();
    await tester.pumpWidget(MaterialApp(
      home: UpstoxAnalyticsProgressPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    expect(find.text('5%'), findsOneWidget);
    expect(find.byKey(const ValueKey('upstox-acceptance-refresh-delta')),
        findsNothing);
    await tester.tap(find.byTooltip('Refresh analytics progress'));
    await tester.pumpAndSettle();
    expect(find.text('13%'), findsOneWidget);
    expect(find.text('Since previous refresh: +8.0 pp'), findsOneWidget);
    await tester.tap(find.byTooltip('Refresh analytics progress'));
    await tester.pumpAndSettle();
    expect(find.text('Since previous refresh: 0.0 pp'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });


  testWidgets('shows reported PR delivery without altering 5% operational acceptance',
      (tester) async {
    tester.view.physicalSize = const Size(420, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      home: UpstoxAnalyticsProgressPage(
        repository: _FixedProgressRepository(_snapshotWithWorkerReports()),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Current PR delivery (reported)'), findsOneWidget);
    expect(find.byKey(const ValueKey('upstox-reported-pr-delivery-percent')),
        findsOneWidget);
    expect(find.text('53.3%'), findsOneWidget);
    expect(find.text('5%'), findsOneWidget);
    expect(find.text('Remaining: 95%'), findsOneWidget);
    await tester.tap(find.text('Operations'));
    await tester.pumpAndSettle();
    expect(find.text('Current PR delivery (reported)'), findsOneWidget);
    expect(find.text('53.3%'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('unknown worker PR reporting is not incorrectly 0% delivered',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: UpstoxAnalyticsProgressPage(
        repository: _FixedProgressRepository(_snapshot()),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Current PR delivery (reported)'), findsOneWidget);
    expect(find.textContaining('unknown PR stages'), findsOneWidget);
    expect(find.text('0%'), findsNothing);
    expect(find.text('5%'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

}
