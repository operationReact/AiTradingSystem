import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/upstox_analytics/data/upstox_delivery_workstreams.dart';

Map<String, Object> pr(int number, String title, String state) => {
  'number': number,
  'title': title,
  'state': state,
  'url': 'https://github.com/operationReact/AiTradingSystem/pull/$number',
};

void main() {
  test('five distinct workstreams use only observed recent PR evidence', () {
    final work = UpstoxDeliveryWorkstreams.fromRecentPrs([
      pr(1009, 'feat(upstox): bounded source failure evidence', 'OPEN'),
      pr(1010, 'feat(data): canonical window quality', 'OPEN'),
      pr(1005, 'feat(data): Upstox coverage retry candidate', 'OPEN'),
      pr(1007, 'feat(upstox): truthful fundamentals availability', 'OPEN'),
      pr(1003, 'feat(upstox-ops): operational telemetry', 'OPEN'),
    ]);
    expect(work.map((w) => w.number).toList(), [1, 2, 3, 4, 5]);
    expect(work.map((w) => w.pr?.number).toList(), [1009, 1010, 1005, 1007, 1003]);
  });

  test('unobserved sample never implies idle or zero progress', () {
    final work = UpstoxDeliveryWorkstreams.fromRecentPrs(null);
    expect(work, hasLength(5));
    expect(work.every((w) => w.pr == null), isTrue);
    expect(UpstoxDeliveryWorkstreams.fromRecentPrs([]).last.pr, isNull);
  });

  test('rejects spoofed URLs and ignores unrelated Mind development', () {
    final forged = pr(1009, 'feat(upstox): source', 'MERGED');
    forged['url'] = 'https://example.org/steal';
    final work = UpstoxDeliveryWorkstreams.fromRecentPrs([
      forged,
      pr(1002, 'feat(mind): paper tracker', 'OPEN'),
      pr(1006, 'feat(data): canonical as-of-safe input', 'MERGED'),
    ]);
    expect(work.first.pr, isNull);
    expect(work[1].pr?.number, 1006);
    expect(work[1].pr?.state, 'MERGED');
  });

  test('PR implementing this dashboard is attributed to operations, not source', () {
    final rows = UpstoxDeliveryWorkstreams.fromRecentPrs([
      pr(1013, 'feat(upstox analytics): visible five data workstreams', 'OPEN'),
    ]);
    expect(rows[4].pr?.number, 1013);
    expect(rows[0].pr, isNull);
  });

  test('five backend worker reports are distinct from production readiness', () {
    final result = UpstoxWorkerObservation.fromBackend([
      {
        'worker': 2,
        'scope': 'CANONICAL_DATA',
        'state': 'FRESH',
        'result': 'MERGED',
        'pr': '1010',
        'ci': 'PASS',
        'blocker': 'RIGHTS_UNVERIFIED',
        'next': 'QUERY_REAL_DB',
        'reportedAt': '2026-11-14T01:00:00Z',
        'url': 'https://github.com/operationReact/AiTradingSystem/pull/1010',
      },
    ]);
    expect(result, hasLength(5));
    expect(result[0].state, 'NO_REPORT');
    expect(result[1].result, 'MERGED');
    expect(result[1].ci, 'PASS');
    expect(result[1].pr, 1010);
    expect(result[1].state, 'FRESH');
  });

  test('outdated backend observation is not presented as fresh', () {
    final rows = UpstoxWorkerObservation.fromBackend([
      {'worker': 5, 'scope': 'OPERATIONS_UI', 'state': 'FRESH'},
    ], staleSnapshot: true);
    expect(rows.last.state, 'STALE');
  });

  test('rejects spoofed worker scope and untrusted PR urls', () {
    final rows = UpstoxWorkerObservation.fromBackend([
      {'worker': 1, 'scope': 'OPERATIONS_UI', 'state': 'FRESH'},
      {
        'worker': 2, 'scope': 'CANONICAL_DATA', 'state': 'FRESH',
        'pr': '1010', 'url': 'https://example.com/forged',
      }
    ]);
    expect(rows.first.state, 'NO_REPORT');
    expect(rows[1].pr, isNull);
    expect(rows[1].url, isNull);
  });

  test('only first match in recent sample is used', () {
    final work = UpstoxDeliveryWorkstreams.fromRecentPrs([
      pr(1009, 'feat(upstox): source failure', 'OPEN'),
      pr(1004, 'feat(upstox): source batch', 'OPEN'),
    ]);
    expect(work.first.pr?.number, 1009);
  });

  test('worker run evidence shows previous report and no-change explicitly', () {
    final rows = UpstoxWorkerObservation.fromBackend([
      {
        'worker': 3, 'scope': 'COVERAGE_GAPS', 'state': 'FRESH',
        'reports12h': 3, 'lastRunChange': 'NO_NEW_DELIVERY',
        'previousReportedAt': '2026-11-14T00:15:00Z',
      },
    ]);
    expect(rows[2].reportCount12h, 3);
    expect(rows[2].lastRunChange, 'NO_NEW_DELIVERY');
    expect(rows[2].previousReportedAt, DateTime.utc(2026, 11, 14, 0, 15));
    expect(rows[0].lastRunChange, isNull);
  });
  test('rejects bogus worker run change and inflated report count', () {
    final rows = UpstoxWorkerObservation.fromBackend([
      {'worker': 2, 'scope': 'CANONICAL_DATA', 'state': 'FRESH',
       'reports12h': 10000, 'lastRunChange': 'MERGED_AND_DEPLOYED'},
    ]);
    expect(rows[1].reportCount12h, isNull);
    expect(rows[1].lastRunChange, isNull);
  });


  test('latest reported PR pipeline shows delivery gates, not backend readiness', () {
    final reports = UpstoxWorkerObservation.fromBackend([
      for (var i = 1; i <= 5; i++)
        {
          'worker': i,
          'scope': UpstoxWorkerObservation.scopes[i - 1],
          'state': 'FRESH',
          'result': i == 1 ? 'MERGED' : i == 3 ? 'BLOCKED' : 'PR_OPEN',
          'ci': i == 1 || i == 3 ? 'PASS' : 'PENDING',
          'pr': '${1060 + i}',
          'url': 'https://github.com/operationReact/AiTradingSystem/pull/${1060 + i}',
          'reportedAt': '2026-11-14T09:00:00Z',
        },
    ]);
    final cycle = UpstoxReportedPrPipeline.fromReports(reports);
    expect(cycle.observedWorkers, 5);
    expect(cycle.earnedGates, 8); // 3 + 1 + 2 + 1 + 1
    expect(cycle.percent, 53.3);
    expect(cycle.stages[0].percent, 100);
    expect(cycle.stages[1].percent, 33.3);
    expect(cycle.stages[2].percent, 66.6);
  });

  test('missing stale or forged worker reports never create progress', () {
    final reports = UpstoxWorkerObservation.fromBackend([
      {
        'worker': 1, 'scope': 'SOURCE_INGESTION', 'state': 'STALE',
        'result': 'MERGED', 'ci': 'PASS', 'pr': '1064',
        'url': 'https://github.com/operationReact/AiTradingSystem/pull/1064',
        'reportedAt': '2026-11-14T08:00:00Z',
      },
      {
        'worker': 2, 'scope': 'CANONICAL_DATA', 'state': 'FRESH',
        'result': 'MERGED', 'ci': 'PASS', 'pr': '1066',
        'url': 'https://not-github.invalid/1066',
        'reportedAt': '2026-11-14T09:00:00Z',
      },
    ]);
    final cycle = UpstoxReportedPrPipeline.fromReports(reports);
    expect(cycle.observedWorkers, 0);
    expect(cycle.earnedGates, 0);
    expect(cycle.percent, isNull);
    expect(cycle.stages.every((w) => w.percent == null), isTrue);
  });

  test('new PR resets cycle even after a reported merge', () {
    UpstoxWorkerObservation report(String status, String ci, String pr) =>
        UpstoxWorkerObservation.fromBackend([
          {
            'worker': 1, 'scope': 'SOURCE_INGESTION', 'state': 'FRESH',
            'result': status, 'ci': ci, 'pr': pr,
            'url': 'https://github.com/operationReact/AiTradingSystem/pull/$pr',
            'reportedAt': '2026-11-14T09:00:00Z',
          },
        ]).first;
    final merged = UpstoxReportedPrPipeline.fromReports([
      report('MERGED', 'PASS', '1064'),
    ]);
    final next = UpstoxReportedPrPipeline.fromReports([
      report('PR_OPEN', 'PENDING', '1071'),
    ]);
    expect(merged.stages.single.earned, 3);
    expect(next.stages.single.earned, 1);
    expect(next.percent, isNull); // Not comparable across missing workers.
  });

}
