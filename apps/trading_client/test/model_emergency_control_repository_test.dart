import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/model_emergency_control_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('Admin GET reads real Java control evidence and never invents eligibility', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final repo = HttpModelEmergencyControlRepository(AuthenticatedApiClient(
      session: session, baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async {
        expect(request.url.path, '/api/admin/model-governance/emergency-controls');
        expect(request.headers['X-API-Key'], 'admin-key');
        return http.Response(jsonEncode([{
          'controlType': 'STATIC_FALLBACK', 'scopeKey': 'GLOBAL',
          'active': true, 'reason': 'External market feed degraded',
          'stateFingerprint': 'a' * 64,
        }]), 200);
      }),
    ));
    final controls = await repo.list();
    expect(controls.single.active, true);
    expect(controls.single.scopeKey, 'GLOBAL');
    expect(controls.single.reason, 'External market feed degraded');
  });

  test('only de-risk PUT payloads are exposed, all requiring audit reason', () async {
    final calls = <String>[];
    final session = OperatorSession()..setOperatorKey('admin-key');
    final repo = HttpModelEmergencyControlRepository(AuthenticatedApiClient(
      session: session, baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async {
        expect(request.method, 'PUT');
        expect(request.headers['X-API-Key'], 'admin-key');
        final data = jsonDecode(request.body) as Map<String, dynamic>;
        expect(data['reason'], 'Emergency market source outage');
        expect(data.keys, contains('reason'));
        expect(data['disabled'] == true || data['enabled'] == true, true);
        expect(data['disabled'] == false || data['enabled'] == false, false);
        calls.add(request.url.path);
        return http.Response(jsonEncode({
          'controlType': 'MODEL_VERSION_DISABLED',
          'scopeKey': 'research-model', 'active': true,
          'reason': data['reason'],
          'stateFingerprint': 'b' * 64,
        }), 200);
      }),
    ));
    await repo.enableStaticFallback('Emergency market source outage');
    await repo.disableFamily('CATBOOST', 'Emergency market source outage');
    await repo.disableVersion(
      '01234567-89ab-cdef-0123-456789abcdef', 'Emergency market source outage');
    expect(calls, [
      '/api/admin/model-governance/emergency-controls/static-fallback',
      '/api/admin/model-governance/emergency-controls/families/CATBOOST',
      '/api/admin/model-governance/emergency-controls/models/01234567-89ab-cdef-0123-456789abcdef',
    ]);
  });

  test('empty reason, invalid family, and non-UUID model fail before HTTP', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final repo = HttpModelEmergencyControlRepository(AuthenticatedApiClient(
      session: session, baseUri: Uri.parse('https://example.test'),
      client: MockClient((_) async => throw StateError('HTTP must not be called')),
    ));
    expect(() => repo.enableStaticFallback('too short'), throwsFormatException);
    expect(() => repo.disableFamily('bad/../', 'Emergency market source outage'), throwsFormatException);
    expect(() => repo.disableVersion('any-model', 'Emergency market source outage'), throwsFormatException);
  });

  test('403 access denial is not translated into safe success', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final repo = HttpModelEmergencyControlRepository(AuthenticatedApiClient(
      session: session, baseUri: Uri.parse('https://example.test'),
      client: MockClient((_) async => http.Response('Forbidden', 403)),
    ));
    await expectLater(repo.list(), throwsA(isA<ApiHttpFailure>()
      .having((failure) => failure.statusCode, 'status', 403)));
    await expectLater(repo.enableStaticFallback('Emergency market source outage'),
      throwsA(isA<ApiHttpFailure>().having((failure) => failure.statusCode, 'status', 403)));
  });

  test('ADMIN audit history GET uses Java endpoint and keeps capital denied', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final repository = HttpModelEmergencyControlRepository(AuthenticatedApiClient(
      session: session, baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/admin/model-governance/emergency-controls/history');
        expect(request.url.queryParameters['limit'], '25');
        expect(request.headers['X-API-Key'], 'admin-key');
        return http.Response(jsonEncode([{
          'id': 123,
          'controlType': 'STATIC_FALLBACK',
          'scopeKey': 'GLOBAL',
          'active': true,
          'previousActive': false,
          'reason': 'Market source validation incident',
          'actor': 'governance-admin',
          'occurredAt': '2026-11-05T10:00:00Z',
          'stateFingerprint': 'a' * 64,
          'canAuthorizeCapital': false,
        }]), 200);
      }),
    ));
    final events = await repository.history();
    expect(events.single.id, 123);
    expect(events.single.previousActive, false);
    expect(events.single.active, true);
    expect(events.single.actor, 'governance-admin');
    expect(events.single.reason, 'Market source validation incident');
  });

  test('unverified audit entry or excess output is refused, never success', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final accepted = <String, dynamic>{
      'id': 1, 'controlType': 'STATIC_FALLBACK', 'scopeKey': 'GLOBAL',
      'active': true, 'previousActive': false,
      'reason': 'Feed invalidated',
      'actor': 'review-admin', 'occurredAt': '2026-11-05T10:00:00Z',
      'stateFingerprint': 'a' * 64, 'canAuthorizeCapital': false,
    };
    final repo = HttpModelEmergencyControlRepository(AuthenticatedApiClient(
      session: session, baseUri: Uri.parse('https://example.test'),
      client: MockClient((_) async => http.Response(jsonEncode([{
        ...accepted, 'canAuthorizeCapital': true,
      }]), 200)),
    ));
    await expectLater(repo.history(), throwsFormatException);
    for (final change in <Map<String, dynamic>>[
      {'actor': ''}, {'active': 'true'}, {'stateFingerprint': 'not-a-sha'},
      {'occurredAt': 'invalid'}, {'controlType': 'LIVE_ENABLED'},
    ]) {
      final copy = <String, dynamic>{...accepted, ...change};
      expect(() => ModelEmergencyAuditEvent.fromJson(copy), throwsFormatException);
    }
    expect(() => repo.history(limit: 51), throwsFormatException);
  });

  test('history 403 rejects access rather than displaying cached events', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final repo = HttpModelEmergencyControlRepository(AuthenticatedApiClient(
      session: session, baseUri: Uri.parse('https://example.test'),
      client: MockClient((_) async => http.Response('Forbidden', 403)),
    ));
    await expectLater(repo.history(), throwsA(isA<ApiHttpFailure>()
      .having((failure) => failure.statusCode, 'status', 403)));
  });

}
