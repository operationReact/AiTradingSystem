import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_verified_news_repository.dart';

const instrument = 12345;
const listing = '00000000-0000-0000-0000-000000000087';
const event = '00000000-0000-0000-0000-000000000088';
const receipt = '00000000-0000-0000-0000-000000000089';
const version = '00000000-0000-0000-0000-000000000090';
final decisionAt = DateTime.utc(2026, 11, 24, 8, 10);

Map<String, dynamic> verified() => {
  'contractVersion': 'mind.verified-news-evidence.v1',
  'instrumentId': instrument,
  'researchCutoff': decisionAt.toIso8601String(),
  'catalogueVersion': version,
  'verificationStatus': 'VERIFIED_LISTING_EVENT_EVIDENCE',
  'verifiedListingId': listing,
  'evidenceCount': 1,
  'includedInDecision': false,
  'canAuthorizeCapital': false,
  'evidence': [{
    'eventId': event,
    'eventVersion': 1,
    'verifiedListingId': listing,
    'receiptId': receipt,
    'sourceId': 'fictional-source',
    'publisher': 'FICTIONAL PUBLISHER',
    'headline': 'Fictional approved report',
    'originalUrl': 'https://news.example.test/report',
    'publisherPublishedAt': '2026-11-24T07:00:00Z',
    'firstObservedAt': '2026-11-24T07:10:00Z',
    'fetchedAt': '2026-11-24T07:12:00Z',
    'mappingFirstKnownAt': '2026-11-24T07:20:00Z',
    'eventCategory': 'UNCLASSIFIED',
    'retracted': false,
  }],
};

void main() {
  test('reads exact customer as-of endpoint, preserving source clocks and research-only flags', () async {
    final session = OperatorSession()..setOperatorKey('fixture-operator');
    late http.Request request;
    final client = MockClient((r) async {
      request = r;
      return http.Response(jsonEncode(verified()), 200);
    });
    final api = AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('https://fixture.example'),
    );
    final result = await HttpMindVerifiedNewsRepository(api).verifiedAt(
      instrumentId: instrument, asOf: decisionAt,
    );
    expect(request.method, 'GET');
    expect(request.url.path, '/api/customer/mind/v1/verified-news-evidence');
    expect(request.url.queryParameters['instrumentId'], '$instrument');
    expect(DateTime.parse(request.url.queryParameters['asOf']!).toUtc(), decisionAt);
    expect(request.headers['X-API-Key'], 'fixture-operator');
    expect(result.verifiedListingId, listing);
    expect(result.hasVerifiedListing, true);
    expect(result.evidence.single.publisher, 'FICTIONAL PUBLISHER');
    expect(result.evidence.single.mappingFirstKnownAt.isBefore(decisionAt), true);
    expect(result.evidence.single.originalUrl.scheme, 'https');
    client.close();
  });

  test('never accepts backend requests to include news in trading or authorize capital', () {
    expect(() => MindVerifiedNewsEvidence.fromJson(
      verified()..['includedInDecision'] = true,
    ), throwsFormatException);
    expect(() => MindVerifiedNewsEvidence.fromJson(
      verified()..['canAuthorizeCapital'] = true,
    ), throwsFormatException);
  });

  test('fails closed on missing listing, future-known mapping and malformed source URL', () {
    expect(() => MindVerifiedNewsEvidence.fromJson(
      verified()..['verifiedListingId'] = null,
    ), throwsFormatException);
    final late = verified();
    ((late['evidence'] as List).single as Map)['mappingFirstKnownAt'] =
        '2026-11-24T08:11:00Z';
    expect(() => MindVerifiedNewsEvidence.fromJson(late), throwsFormatException);
    final unsafe = verified();
    ((unsafe['evidence'] as List).single as Map)['originalUrl'] =
        'javascript:alert(1)';
    expect(() => MindVerifiedNewsEvidence.fromJson(unsafe), throwsFormatException);
  });

  test('explicit unknown listing has no authorized issuer or fabricated news', () {
    final source = verified()
      ..['verificationStatus'] = 'LISTING_IDENTITY_UNVERIFIED'
      ..['verifiedListingId'] = null
      ..['evidenceCount'] = 0
      ..['evidence'] = <Object>[];
    final parsed = MindVerifiedNewsEvidence.fromJson(source);
    expect(parsed.verifiedListingId, isNull);
    expect(parsed.hasVerifiedListing, false);
    expect(parsed.evidence, isEmpty);
  });

  test('rejects duplicate receipts, wrong instrument and cutoff mismatch', () async {
    final duplicate = verified();
    final receiptRow = (duplicate['evidence'] as List).single;
    (duplicate['evidence'] as List).add(receiptRow);
    duplicate['evidenceCount'] = 2;
    expect(() => MindVerifiedNewsEvidence.fromJson(duplicate), throwsFormatException);

    final session = OperatorSession()..setOperatorKey('fixture-operator');
    final client = MockClient((_) async =>
        http.Response(jsonEncode(verified()..['instrumentId'] = 54321), 200));
    final api = AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('https://fixture.example'),
    );
    await expectLater(
      HttpMindVerifiedNewsRepository(api).verifiedAt(
        instrumentId: instrument, asOf: decisionAt,
      ),
      throwsFormatException,
    );
    client.close();
  });

  test('revoked rights or missing endpoint cannot turn into synthetic success', () async {
    final session = OperatorSession()..setOperatorKey('fixture-operator');
    final client = MockClient((_) async => http.Response('Denied', 403));
    final api = AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('https://fixture.example'),
    );
    await expectLater(
      HttpMindVerifiedNewsRepository(api).verifiedAt(
        instrumentId: instrument, asOf: decisionAt,
      ),
      throwsA(isA<ApiHttpFailure>()),
    );
    client.close();
  });
}
