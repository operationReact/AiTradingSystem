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
}
