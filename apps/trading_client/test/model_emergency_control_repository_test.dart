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
}
