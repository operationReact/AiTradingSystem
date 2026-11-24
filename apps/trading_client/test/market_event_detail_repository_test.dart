import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/market_intelligence/data/market_event_detail_repository.dart';

const eventId = '00000000-0000-0000-0000-000000000001';

Map<String, dynamic> fixtureReceipt(String id, String title) => {
  'receiptId': id,
  'sourceId': 'fixture-publisher',
  'publisher': 'FICTIONAL SOURCE',
  'title': title,
  'sourceUrl': 'https://news.example.test/fictional/$id',
  'publishedAt': '2026-11-24T04:00:00Z',
  'firstObservedAt': '2026-11-24T04:05:00Z',
  'observedAt': '2026-11-24T04:05:00Z',
  'evidenceKind': 'OTHER',
};

Map<String, dynamic> fixtureDetail() => {
  'eventId': eventId,
  'eventClass': 'UNCLASSIFIED',
  'eventStatus': 'ACTIVE',
  'currentVersion': 2,
  'firstSeenAt': '2026-11-24T04:05:00Z',
  'currentKnownAt': '2026-11-24T04:10:00Z',
  'instrumentResolutionStatus': 'UNVERIFIED',
  'versionsTruncated': false,
  'versions': [
    {
      'version': 2,
      'category': 'UNCLASSIFIED',
      'effectiveKnownAt': '2026-11-24T04:10:00Z',
      'retraction': false,
      'correctionOfVersion': 1,
      'evidenceTruncated': false,
      'evidence': [
        fixtureReceipt('00000000-0000-0000-0000-000000000102',
          'Fictional corrected disclosure'),
      ],
    },
    {
      'version': 1,
      'category': 'UNCLASSIFIED',
      'effectiveKnownAt': '2026-11-24T04:05:00Z',
      'retraction': false,
      'correctionOfVersion': null,
      'evidenceTruncated': false,
      'evidence': [
        fixtureReceipt('00000000-0000-0000-0000-000000000101',
          'Fictional original disclosure'),
      ],
    },
  ],
};

void main() {
  test('authenticated event detail preserves correction source and known-at dates', () async {
    final session = OperatorSession()..setOperatorKey('test-key');
    late http.Request seen;
    final client = MockClient((request) async {
      seen = request;
      return http.Response(jsonEncode(fixtureDetail()), 200);
    });
    final repository = HttpMarketEventDetailRepository(
      AuthenticatedApiClient(
        session: session, client: client,
        baseUri: Uri.parse('https://example.test'),
      ),
    );
    final result = await repository.detail(eventId);
    expect(seen.method, 'GET');
    expect(seen.url.path, '/api/customer/market-intelligence/events/$eventId');
    expect(seen.headers['X-API-Key'], 'test-key');
    expect(result.currentVersion, 2);
    expect(result.versions.length, 2);
    expect(result.versions.first.correctionOfVersion, 1);
    expect(result.versions.first.evidence.first.title,
      'Fictional corrected disclosure');
    expect(result.versions.last.evidence.first.title,
      'Fictional original disclosure');
    expect(result.instrumentResolutionStatus, 'UNVERIFIED');
    expect(result.currentKnownAt.isUtc, true);
    client.close();
  });

  test('invalid source links and conflicting canonical revision are rejected', () {
    final malicious = fixtureDetail();
    ((malicious['versions'] as List).first['evidence'] as List)
      .first['sourceUrl'] = 'javascript:alert(1)';
    expect(() => MarketEventDetail.fromJson(malicious), throwsFormatException);

    final future = fixtureDetail()..['currentKnownAt'] = '2026-11-24T04:00:00Z';
    expect(() => MarketEventDetail.fromJson(future), throwsFormatException);

    final duplicate = fixtureDetail();
    final revisions = duplicate['versions'] as List;
    revisions[1] = Map<String, dynamic>.from(revisions[0] as Map);
    expect(() => MarketEventDetail.fromJson(duplicate), throwsFormatException);
  });

  test('a different event ID from backend cannot be displayed as requested', () async {
    final session = OperatorSession()..setOperatorKey('test-key');
    final client = MockClient((_) async {
      final mismatch = fixtureDetail()
        ..['eventId'] = '00000000-0000-0000-0000-000000000002';
      return http.Response(jsonEncode(mismatch), 200);
    });
    final repository = HttpMarketEventDetailRepository(
      AuthenticatedApiClient(
        session: session, client: client,
        baseUri: Uri.parse('https://example.test'),
      ),
    );
    await expectLater(repository.detail(eventId), throwsFormatException);
    client.close();
  });

  test('revoked or unavailable backend returns failure without synthetic fallback', () async {
    final session = OperatorSession()..setOperatorKey('test-key');
    final client = MockClient((_) async => http.Response('Not Found', 404));
    final repository = HttpMarketEventDetailRepository(
      AuthenticatedApiClient(
        session: session, client: client,
        baseUri: Uri.parse('https://example.test'),
      ),
    );
    await expectLater(repository.detail(eventId), throwsA(isA<ApiHttpFailure>()));
    expect(() => repository.detail('../news'), throwsArgumentError);
    client.close();
  });
}
