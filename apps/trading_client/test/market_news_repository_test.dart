import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/market_intelligence/data/market_news_repository.dart';

Map<String, dynamic> fixtureArticle({String url = 'https://news.example.test/article-1'}) => {
  'id': 'fixture-article-1',
  'source': 'TEST SOURCE',
  'title': 'Fixture corporate announcement',
  'url': url,
  'excerpt': 'Synthetic excerpt for contract testing only.',
  'publishedAt': '2026-11-24T04:00:00Z',
  'ingestedAt': '2026-11-24T04:05:00Z',
  'analysisStatus': 'ANALYZED',
  'stockImpact': {
    'sentiment': 'NEGATIVE', 'directionScore': -0.35, 'category': 'EARNINGS',
    'confidence': 0.8, 'materiality': 0.6, 'horizon': 'MULTI_DAY',
    'contradiction': false, 'ambiguity': true,
    'impactedSymbols': ['TCS'], 'evidence': 'Synthetic guidance evidence.',
    'risks': ['Synthetic risk.'], 'analyzedAt': '2026-11-24T04:06:00Z',
  },
};

void main() {
  test('reads only authenticated customer market news API, never publisher or Gemini', () async {
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    late http.Request request;
    final client = MockClient((captured) async {
      request = captured;
      return http.Response(jsonEncode({
        'items': [fixtureArticle()], 'page': 0, 'size': 20,
        'totalItems': 21, 'totalPages': 2,
      }), 200, headers: {'content-type': 'application/json'});
    });
    final api = AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('http://example.test'),
    );
    final page = await HttpMarketNewsRepository(api).articles(page: 0, symbol: ' tcs ');

    expect(request.method, 'GET');
    expect(request.url.path, '/api/customer/market-intelligence/articles');
    expect(request.url.queryParameters['page'], '0');
    expect(request.url.queryParameters['size'], '20');
    expect(request.url.queryParameters['symbol'], 'TCS');
    expect(request.headers['X-API-Key'], 'test-operator-key');
    expect(page.hasNext, isTrue);
    expect(page.items.single.source, 'TEST SOURCE');
    expect(page.items.single.ingestedAt.isUtc, isTrue);
    expect(page.items.single.excerpt, contains('Synthetic excerpt'));
    expect(page.items.single.analysisStatus, 'ANALYZED');
    expect(page.items.single.stockImpact?.sentiment, 'NEGATIVE');
    expect(page.items.single.stockImpact?.impactedSymbols, ['TCS']);
    expect(page.items.single.stockImpact?.directionScore, -0.35);
    client.close();
  });

  test('rejects invalid article URL and malformed source timestamps', () {
    expect(
      () => MarketNewsArticle.fromJson(fixtureArticle(url: 'javascript:alert(1)')),
      throwsFormatException,
    );
    final invalid = fixtureArticle()..['publishedAt'] = 'not-a-date';
    expect(() => MarketNewsArticle.fromJson(invalid), throwsFormatException);
  });

  test('rejects duplicate records and contradictory page totals', () {
    final repeated = {
      'items': [fixtureArticle(), fixtureArticle()],
      'page': 0, 'size': 20, 'totalItems': 2,
    };
    expect(() => MarketNewsPage.fromJson(repeated), throwsFormatException);
    expect(() => MarketNewsPage.fromJson({
      'items': [fixtureArticle()], 'page': 0, 'size': 20, 'totalItems': 0,
    }), throwsFormatException);
  });

  test('does not convert an unavailable API response into made-up headlines', () async {
    final session = OperatorSession()..setOperatorKey('test-operator-key');
    final client = MockClient((_) async => http.Response('Not Found', 404));
    final api = AuthenticatedApiClient(
      session: session, client: client, baseUri: Uri.parse('http://example.test'),
    );
    await expectLater(HttpMarketNewsRepository(api).articles(page: 0),
      throwsA(isA<ApiHttpFailure>()));
    client.close();
  });
}
