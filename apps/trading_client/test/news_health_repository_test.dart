import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/market_intelligence/data/news_health_repository.dart';

Map<String, dynamic> fixture() => {
  'observedAt': '2026-11-24T06:00:00Z',
  'ingestionEnabled': false,
  'eventLedgerEnabled': false,
  'analyzerBridgeEnabled': false,
  'analysisSchedulerEnabled': false,
  'mindContextEnabled': false,
  'model': {
    'selectedProvider': 'NONE',
    'configuredGeminiModel': 'gemini-2.5-flash',
    'geminiKeyConfigured': false,
    'realProviderCallVerified': false,
  },
  'receiptCount': 0,
  'eventCount': 0,
  'eventVersionCount': 0,
  'sources': [
    {
      'sourceId': 'fictional-feed',
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
  test('calls authenticated admin endpoint, not source or model APIs', () async {
    final session = OperatorSession()..setOperatorKey('fixture-key');
    late http.Request request;
    final client = MockClient((req) async {
      request = req;
      return http.Response(jsonEncode(fixture()), 200);
    });
    final repository = HttpNewsHealthRepository(
      AuthenticatedApiClient(
        session: session,
        client: client,
        baseUri: Uri.parse('https://example.test'),
      ),
    );
    final status = await repository.fetch();

    expect(request.method, 'GET');
    expect(request.url.path, '/api/admin/market-intelligence/health');
    expect(request.headers['X-API-Key'], 'fixture-key');
    expect(status.ingestionEnabled, false);
    expect(status.eventCount, 0);
    expect(status.realProviderCallVerified, false);
    expect(status.sources.single.canDisplay, false);
    expect(status.sources.single.rightsState, 'REVIEW_PENDING');
    client.close();
  });

  test('rejects malformed counters, flags, and duplicate publisher sources', () {
    expect(() => NewsHealthSnapshot.fromJson(
      fixture()..['eventCount'] = -1,
    ), throwsFormatException);
    expect(() => NewsHealthSnapshot.fromJson(
      fixture()..['analysisSchedulerEnabled'] = 'yes',
    ), throwsFormatException);
    final repeated = fixture();
    final row = (repeated['sources'] as List).first;
    (repeated['sources'] as List).add(Map<String, dynamic>.from(row as Map));
    expect(() => NewsHealthSnapshot.fromJson(repeated), throwsFormatException);
  });

  test('403 admin denial never returns a fake successful status', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final client = MockClient((_) async => http.Response('Forbidden', 403));
    final repository = HttpNewsHealthRepository(AuthenticatedApiClient(
      session: session, client: client,
      baseUri: Uri.parse('https://example.test'),
    ));
    await expectLater(repository.fetch(), throwsA(isA<ApiHttpFailure>()));
    client.close();
  });
}
