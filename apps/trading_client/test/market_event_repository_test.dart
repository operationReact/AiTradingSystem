import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/market_intelligence/data/market_event_repository.dart';

Map<String, dynamic> fixtureEvent() => {
  'eventId': '00000000-0000-0000-0000-000000000001',
  'version': 1,
  'headline': 'Fictional corporate filing',
  'eventClass': 'UNCLASSIFIED',
  'category': 'UNCLASSIFIED',
  'eventStatus': 'ACTIVE',
  'sourceId': 'fixture',
  'publisher': 'FICTIONAL PUBLISHER',
  'sourceUrl': 'https://news.example.test/fictional-item',
  'publishedAt': '2026-11-24T04:00:00Z',
  'firstObservedAt': '2026-11-24T04:05:00Z',
  'effectiveKnownAt': '2026-11-24T04:05:00Z',
  'instrumentResolutionStatus': 'UNVERIFIED',
};

void main() {
  test('reads authenticated customer canonical-event API without sending a model key', () async {
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    late http.Request request;
    final client = MockClient((req) async {
      request = req;
      return http.Response(jsonEncode({
        'items': [fixtureEvent()], 'page': 0, 'size': 20,
        'totalItems': 21, 'totalPages': 2,
      }), 200);
    });
    final api = AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('http://example.test'),
    );
    final page = await HttpMarketEventRepository(api).events(page: 0);
    expect(request.method, 'GET');
    expect(request.url.path, '/api/customer/market-intelligence/events');
    expect(request.url.queryParameters['page'], '0');
    expect(request.url.queryParameters['size'], '20');
    expect(request.headers['X-API-Key'], 'test-operator-key');
    expect(page.hasNext, isTrue);
    expect(page.items.single.instrumentResolutionStatus, 'UNVERIFIED');
    expect(page.items.single.firstObservedAt.isUtc, isTrue);
    expect(page.items.single.publishedAt!.isUtc, isTrue);
    expect(page.items.single.eventClass, 'UNCLASSIFIED');
    client.close();
  });

  test('fails closed on future evidence, bad url and invalid statuses', () {
    final future = fixtureEvent()..['effectiveKnownAt'] = '2026-11-24T04:00:00Z';
    expect(() => MarketEventCard.fromJson(future), throwsFormatException);
    final malicious = fixtureEvent()..['sourceUrl'] = 'javascript:alert(1)';
    expect(() => MarketEventCard.fromJson(malicious), throwsFormatException);
    final invalid = fixtureEvent()..['eventStatus'] = 'BUY_CE';
    expect(() => MarketEventCard.fromJson(invalid), throwsFormatException);
  });

  test('rejects duplicate events and incorrect response totals', () {
    final duplicate = {
      'items': [fixtureEvent(), fixtureEvent()],
      'page': 0, 'size': 20, 'totalItems': 2,
    };
    expect(() => MarketEventPage.fromJson(duplicate), throwsFormatException);
    expect(() => MarketEventPage.fromJson({
      'items': [fixtureEvent()], 'page': 0, 'size': 20, 'totalItems': 0,
    }), throwsFormatException);
  });

  test('unavailable API cannot fabricate an event', () async {
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    final client = MockClient((_) async => http.Response('Unavailable', 503));
    final api = AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('http://example.test'),
    );
    await expectLater(HttpMarketEventRepository(api).events(page: 0),
      throwsA(isA<ApiHttpFailure>()));
    client.close();
  });
}
