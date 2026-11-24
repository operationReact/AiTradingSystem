import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/market_intelligence/data/news_admin_health_repository.dart';

Map<String, dynamic> fixtureHealth() => {
  'observedAt': '2026-11-24T04:30:00Z',
  'ingestionEnabled': false,
  'eventLedgerEnabled': false,
  'analyzerBridgeEnabled': false,
  'analysisSchedulerEnabled': false,
  'mindContextEnabled': false,
  'model': {
    'selectedProvider': 'NONE',
    'configuredGeminiModel': 'gemini-2.5-flash',
    'geminiKeyConfigured': true,
    'realProviderCallVerified': false,
  },
  'receiptCount': 0,
  'eventCount': 0,
  'eventVersionCount': 0,
  'sources': [
    {
      'sourceId': 'fictional-source',
      'publisher': 'FICTIONAL PUBLISHER',
      'rightsState': 'REVIEW_PENDING',
      'canIngest': false,
      'canRetain': false,
      'canDisplay': false,
      'canTransferToModel': false,
      'latestStatus': 'NEVER_RUN',
      'lastFinishedAt': null,
      'secondsSinceLastRun': null,
      'lastFetchedItems': 0,
      'lastInsertedItems': 0,
      'lastErrorCode': null,
    },
  ],
};

void main() {
  test('reads the exact admin-only endpoint without a secret in the result', () async {
    final session = OperatorSession()..setOperatorKey('fixture-operator-key');
    late http.Request request;
    final mock = MockClient((captured) async {
      request = captured;
      return http.Response(jsonEncode(fixtureHealth()), 200,
        headers: {'content-type': 'application/json'});
    });
    final api = AuthenticatedApiClient(
      session: session, client: mock, baseUri: Uri.parse('http://example.test'),
    );
    final health = await HttpNewsAdminHealthRepository(api).readHealth();

    expect(request.method, 'GET');
    expect(request.url.path, '/api/admin/market-intelligence/health');
    expect(health.model.geminiKeyConfigured, isTrue);
    expect(health.model.realProviderCallVerified, isFalse);
    expect(health.ingestionEnabled, isFalse);
    expect(health.sources.single.canDisplay, isFalse);
    expect(health.sources.single.latestStatus, 'NEVER_RUN');
    expect(health.observedAt.isUtc, isTrue);
    mock.close();
  });

  test('rejects missing or false-typed readiness fields', () {
    final malformed = fixtureHealth()..['eventLedgerEnabled'] = 'true';
    expect(() => NewsAdminHealthSnapshot.fromJson(malformed), throwsFormatException);
    final wrongModel = fixtureHealth()..['model'] = {'selectedProvider': 'GEMINI'};
    expect(() => NewsAdminHealthSnapshot.fromJson(wrongModel), throwsFormatException);
  });

  test('rejects inconsistent negative counters and duplicate sources', () {
    final negative = fixtureHealth()..['eventCount'] = -1;
    expect(() => NewsAdminHealthSnapshot.fromJson(negative), throwsFormatException);
    final duplicates = fixtureHealth();
    duplicates['sources'] = [
      ...duplicates['sources'] as List,
      ...(duplicates['sources'] as List),
    ];
    expect(() => NewsAdminHealthSnapshot.fromJson(duplicates), throwsFormatException);
  });

  test('real API forbids OPERATOR and does not silently grant admin access', () async {
    final session = OperatorSession()..setOperatorKey('fixture-operator-key');
    final mock = MockClient((_) async => http.Response('Forbidden', 403));
    final api = AuthenticatedApiClient(
      session: session, client: mock, baseUri: Uri.parse('http://example.test'),
    );
    await expectLater(HttpNewsAdminHealthRepository(api).readHealth(),
      throwsA(isA<ApiHttpFailure>()));
    mock.close();
  });
}
