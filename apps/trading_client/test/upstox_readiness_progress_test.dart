import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/upstox_analytics/data/upstox_analytics_progress_repository.dart';
import 'package:trading_client/src/features/upstox_analytics/data/upstox_readiness_progress.dart';

UpstoxAnalyticsProgress snapshot(List<UpstoxAnalyticsTask> tasks) =>
    UpstoxAnalyticsProgress(
      generatedAt: DateTime.utc(2026, 11, 13, 23),
      tasks: tasks,
      contractVersion: 'minttick.upstox.analytics.progress.v1',
    );

UpstoxAnalyticsTask task(String code, String state, {bool stale = false}) =>
    UpstoxAnalyticsTask(
      code: code,
      state: state,
      reasonCode: null,
      details: const {},
      stale: stale,
    );

void main() {
  test('weights sum to 100 and are versioned', () {
    expect(UpstoxReadinessProgress.formulaVersion, 'upstox.analytics.acceptance.v1');
    expect(UpstoxReadinessProgress.weights.values.reduce((a, b) => a + b), 100);
  });

  test('unobserved backend must not invent a zero-percent baseline', () {
    final progress = UpstoxReadinessProgress.calculate(snapshot([]));
    expect(progress.percent, isNull);
    expect(progress.fraction, isNull);
    expect(progress.gates.every((gate) => gate.evidenceState == 'NOT_OBSERVED'), true);
  });

  test('source token alone earns only five percent, not ingestion or paper', () {
    final progress = UpstoxReadinessProgress.calculate(snapshot([
      task('SOURCE_VERIFICATION', 'VERIFIED'),
      task('HISTORICAL_INGESTION', 'NOT_STARTED'),
      task('MIND_DATASETS', 'NOT_STARTED'),
      task('PAPER_READINESS', 'NOT_STARTED'),
    ]));
    expect(progress.percent, 5);
    expect(progress.fraction, 0.05);
    expect(progress.gates.first.earned, 5);
    expect(progress.gates.last.earned, 0);
  });

  test('merged GitHub PR does not increase acceptance percentage', () {
    final progress = UpstoxReadinessProgress.calculate(snapshot([
      task('GITHUB_DEVELOPMENT', 'VERIFIED'),
      task('SOURCE_VERIFICATION', 'NOT_STARTED'),
    ]));
    expect(progress.percent, 0);
  });

  test('stale or partial evidence earns no accepted weight', () {
    final progress = UpstoxReadinessProgress.calculate(snapshot([
      task('SOURCE_VERIFICATION', 'VERIFIED', stale: true),
      task('INSTRUMENT_MAPPING', 'RUNNING'),
      task('HISTORICAL_INGESTION', 'PARTIAL'),
      task('QUALITY_RIGHTS', 'BLOCKED'),
    ]));
    expect(progress.percent, 0);
    expect(progress.gates.first.evidenceState, 'STALE');
  });

  test('only fresh verified gates earn their fixed individual weights', () {
    final progress = UpstoxReadinessProgress.calculate(snapshot([
      task('SOURCE_VERIFICATION', 'VERIFIED'),
      task('INSTRUMENT_MAPPING', 'VERIFIED'),
      task('QUALITY_RIGHTS', 'VERIFIED'),
    ]));
    expect(progress.percent, 40);
  });

  test('all seven gates require fresh verification for 100 percent', () {
    final progress = UpstoxReadinessProgress.calculate(snapshot([
      for (final code in UpstoxReadinessProgress.weights.keys)
        task(code, 'VERIFIED'),
    ]));
    expect(progress.percent, 100);
    expect(progress.gates.map((e) => e.earned).reduce((a, b) => a + b), 100);
  });
}
