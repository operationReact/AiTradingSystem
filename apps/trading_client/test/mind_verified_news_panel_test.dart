import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_verified_news_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_verified_news_panel.dart';

const instrumentId = 12345;
final decisionAt = DateTime.utc(2026, 11, 24, 8, 10);

class _FixtureRepository implements MindVerifiedNewsRepository {
  _FixtureRepository({this.denied = false, this.verified = true});
  final bool denied;
  final bool verified;
  int requests = 0;

  @override
  Future<MindVerifiedNewsEvidence> verifiedAt({
    required int instrumentId,
    required DateTime asOf,
  }) async {
    requests++;
    if (denied) throw StateError('No current DISPLAY rights');
    final listing = verified
        ? '00000000-0000-0000-0000-000000000087'
        : null;
    return MindVerifiedNewsEvidence(
      instrumentId: instrumentId,
      researchCutoff: asOf,
      catalogueVersion: '00000000-0000-0000-0000-000000000090',
      verificationStatus: verified
          ? 'VERIFIED_LISTING_EVENT_EVIDENCE'
          : 'LISTING_IDENTITY_UNVERIFIED',
      verifiedListingId: listing,
      evidence: verified
          ? [
              MindVerifiedNewsReceipt(
                eventId: '00000000-0000-0000-0000-000000000088',
                eventVersion: 1,
                verifiedListingId: listing!,
                receiptId: '00000000-0000-0000-0000-000000000089',
                sourceId: 'fictional-source',
                publisher: 'FICTIONAL PUBLISHER',
                headline: 'Fictional issuer event',
                originalUrl: Uri.parse('https://news.example.test/report'),
                firstObservedAt: DateTime.utc(2026, 11, 24, 7, 10),
                fetchedAt: DateTime.utc(2026, 11, 24, 7, 12),
                mappingFirstKnownAt: DateTime.utc(2026, 11, 24, 7, 20),
                eventCategory: 'UNCLASSIFIED',
                retracted: false,
              )
            ]
          : const [],
    );
  }
}

void main() {
  testWidgets('source-backed issuer event renders as research evidence without orders',
      (tester) async {
    final repo = _FixtureRepository();
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(child: MindVerifiedNewsPanel(
        instrumentId: instrumentId, asOf: decisionAt, repository: repo,
      ))),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Independently verified issuer events'), findsOneWidget);
    expect(find.text('Research only · not included in trading decisions'), findsOneWidget);
    expect(find.text('Fictional issuer event'), findsOneWidget);
    expect(find.text('Original publisher'), findsOneWidget);
    expect(find.textContaining('Mapping known'), findsOneWidget);
    expect(find.textContaining('BUY'), findsNothing);
    expect(repo.requests, 1);
  });

  testWidgets('denied source returns an unavailable state and retry never reuses old evidence',
      (tester) async {
    final repo = _FixtureRepository(denied: true);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: MindVerifiedNewsPanel(
        instrumentId: instrumentId, asOf: decisionAt, repository: repo,
      )),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('verified-news-unavailable')), findsOneWidget);
    expect(find.text('Fictional issuer event'), findsNothing);
    await tester.tap(find.text('Retry'));
    await tester.pumpAndSettle();
    expect(repo.requests, 2);
  });

  testWidgets('missing independently verified listing does not display fictitious coverage',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: MindVerifiedNewsPanel(
        instrumentId: instrumentId, asOf: decisionAt,
        repository: _FixtureRepository(verified: false),
      )),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Issuer identity is not independently verified at this cutoff.'),
      findsOneWidget);
    expect(find.text('Fictional issuer event'), findsNothing);
  });
}
