import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/market_intelligence/data/market_event_detail_repository.dart';
import 'package:trading_client/src/features/market_intelligence/presentation/market_event_evidence_page.dart';

const eventId = '00000000-0000-0000-0000-000000000001';

class _EvidenceFixture implements MarketEventDetailRepository {
  _EvidenceFixture({this.fail = false});
  final bool fail;
  int calls = 0;

  @override
  Future<MarketEventDetail> detail(String id) async {
    calls++;
    if (fail) throw StateError('No current publisher rights');
    return MarketEventDetail(
      eventId: id,
      eventClass: 'UNCLASSIFIED',
      eventStatus: 'ACTIVE',
      currentVersion: 2,
      firstSeenAt: DateTime.utc(2026, 11, 24, 4, 5),
      currentKnownAt: DateTime.utc(2026, 11, 24, 4, 10),
      instrumentResolutionStatus: 'UNVERIFIED',
      versionsTruncated: false,
      versions: [
        MarketEventRevision(
          version: 2,
          category: 'UNCLASSIFIED',
          effectiveKnownAt: DateTime.utc(2026, 11, 24, 4, 10),
          retraction: false,
          correctionOfVersion: 1,
          evidenceTruncated: false,
          evidence: [
            MarketEventReceipt(
              receiptId: 'receipt-correction',
              sourceId: 'fictional-publisher',
              publisher: 'FICTIONAL PUBLISHER',
              title: 'Fictional corrected filing',
              sourceUrl: Uri.parse('https://news.example.test/correction'),
              firstObservedAt: DateTime.utc(2026, 11, 24, 4, 5),
              observedAt: DateTime.utc(2026, 11, 24, 4, 5),
              evidenceKind: 'OTHER',
            ),
          ],
        ),
      ],
    );
  }
}

void main() {
  testWidgets('evidence page shows correction, provenance and no order action', (tester) async {
    final fixture = _EvidenceFixture();
    await tester.pumpWidget(MaterialApp(
      home: MarketEventEvidencePage(eventId: eventId, repository: fixture),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Event evidence'), findsOneWidget);
    expect(find.text('Publication and correction history'), findsOneWidget);
    expect(find.text('Fictional corrected filing'), findsOneWidget);
    expect(find.text('Correction of v1'), findsOneWidget);
    expect(find.text('Original publisher'), findsOneWidget);
    expect(find.text('Affected instruments are not yet verified'), findsOneWidget);
    expect(find.textContaining('BUY'), findsNothing);
    expect(fixture.calls, 1);
  });

  testWidgets('failed source permission does not show older static headline', (tester) async {
    final fixture = _EvidenceFixture(fail: true);
    await tester.pumpWidget(MaterialApp(
      home: MarketEventEvidencePage(eventId: eventId, repository: fixture),
    ));
    await tester.pumpAndSettle();

    expect(find.textContaining('no longer permitted for display'), findsOneWidget);
    expect(find.text('Fictional corrected filing'), findsNothing);
    expect(find.text('Retry'), findsOneWidget);
    await tester.tap(find.text('Retry'));
    await tester.pumpAndSettle();
    expect(fixture.calls, 2);
  });
}
