import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/market_intelligence/data/historical_news_coverage_repository.dart';
import 'package:trading_client/src/features/market_intelligence/presentation/historical_news_coverage_screen.dart';

final cutoff = DateTime.utc(2026, 11, 1);
const version = '00000000-0000-0000-0000-000000000001';
final fingerprint = 'a' * 64;

Map<String, dynamic> payload() => {
  'catalogueVersion': version, 'catalogueFingerprint': fingerprint,
  'asOf': cutoff.toIso8601String(), 'totalCatalogueInstruments': 2,
  'offset': 0, 'measuredInstruments': 1, 'nextOffset': 1,
  'verifiedWithEvidence': 0, 'verifiedWithoutEvidence': 0,
  'unverifiedIdentity': 1, 'unavailable': 0, 'canAuthorizeCapital': false,
  'completeUniverseInThisPage': false,
  'rows': [{'instrumentId': 12, 'exchange': 'NSE',
    'verificationStatus': 'LISTING_IDENTITY_UNVERIFIED', 'verifiedEventReceipts': 0}],
};

class _Repo implements HistoricalNewsCoverageRepository {
  int calls = 0;
  @override
  Future<HistoricalNewsCoveragePage> read({required DateTime asOf, required int offset,
      String? version, String? fingerprint}) async {
    calls++;
    if (offset > 0) {
      expect(version, equals('00000000-0000-0000-0000-000000000001'));
      expect(fingerprint, equals('a' * 64));
      throw StateError('changed snapshot');
    }
    return HistoricalNewsCoveragePage.decode(payload(), asOf: asOf, offset: offset, limit: 25);
  }
}

void main() {
  test('rejects inconsistent counters, capital authority and wrong cutoff', () {
    final wrong = payload()..['unverifiedIdentity'] = 0;
    expect(() => HistoricalNewsCoveragePage.decode(wrong, asOf: cutoff, offset: 0, limit: 25),
      throwsFormatException);
    final authorized = payload()..['canAuthorizeCapital'] = true;
    expect(() => HistoricalNewsCoveragePage.decode(authorized, asOf: cutoff, offset: 0, limit: 25),
      throwsFormatException);
    expect(() => HistoricalNewsCoveragePage.decode(payload(), asOf: cutoff.add(const Duration(days: 1)), offset: 0, limit: 25),
      throwsFormatException);
  });

  testWidgets('measures on demand, pins next page and clears stale counts on failure', (tester) async {
    final repo = _Repo();
    await tester.pumpWidget(MaterialApp(home: HistoricalNewsCoverageScreen(
      repository: repo, initialCutoff: cutoff)));
    expect(repo.calls, 0);
    await tester.tap(find.text('Measure coverage'));
    await tester.pumpAndSettle();
    expect(find.text('Catalogue instruments: 2'), findsOneWidget);
    expect(find.text('Catalogue traversal: 50.0%'), findsOneWidget);
    final next = find.byKey(const ValueKey('historical-coverage-next'));
    await tester.scrollUntilVisible(next, 200);
    await tester.tap(next);
    await tester.pumpAndSettle();
    expect(repo.calls, 2);
    expect(find.byKey(const ValueKey('historical-coverage-unavailable')), findsOneWidget);
    expect(find.text('Catalogue instruments: 2'), findsNothing);
  });
}
