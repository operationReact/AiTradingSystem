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

  test('only first match in recent sample is used', () {
    final work = UpstoxDeliveryWorkstreams.fromRecentPrs([
      pr(1009, 'feat(upstox): source failure', 'OPEN'),
      pr(1004, 'feat(upstox): source batch', 'OPEN'),
    ]);
    expect(work.first.pr?.number, 1009);
  });
}
