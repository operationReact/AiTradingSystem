import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/model_control_center_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';

const sessionId = '11111111-2222-3333-4444-555555555555';

void main() {
  test('ADMIN model inventory and matched session use authenticated Java routes', () async {
    final operator = OperatorSession()..setOperatorKey('admin-session-key');
    final paths = <String>[];
    final api = AuthenticatedApiClient(
      session: operator,
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async {
        paths.add(request.url.path);
        expect(request.method, 'GET');
        expect(request.headers['X-API-Key'], 'admin-session-key');
        switch (request.url.path) {
          case '/api/admin/model-governance/models':
            return http.Response(jsonEncode([{
              'modelKey': 'catboost-alpha',
              'displayName': 'CatBoost alpha',
              'family': 'CATBOOST',
              'role': 'ALPHA',
              'provider': 'IN_HOUSE',
              'licenseStatus': 'RESEARCH_ONLY',
              'definitionFingerprint': 'a'.padRight(64, 'a'),
            }]), 200);
          case '/api/admin/model-governance/models/catboost-alpha/versions':
            return http.Response(jsonEncode([{
              'versionId': 'model-1',
              'versionLabel': '1.0',
              'versionFingerprint': 'b'.padRight(64, 'b'),
              'artifactFingerprint': 'c'.padRight(64, 'c'),
              'featureSchemaFingerprint': 'd'.padRight(64, 'd'),
              'trainingDatasetFingerprint': 'e'.padRight(64, 'e'),
              'trainingConfigFingerprint': 'f'.padRight(64, 'f'),
              'codeSha': '1234567890abcdef1234567890abcdef12345678',
              'researchRegistryFingerprint': 'a'.padRight(64, 'a'),
              'trainingCutoff': '2026-11-01T09:00:00Z',
              'createdAt': '2026-11-04T09:00:00Z',
            }]), 200);
          case '/api/admin/model-governance/paper-shadow/sessions/$sessionId':
            return http.Response(jsonEncode({
              'sessionId': sessionId,
              'enrollmentFingerprint': 'd'.padRight(64, 'd'),
              'createdAt': '2026-11-04T09:10:00Z',
              'createdBy': 'admin',
              'models': [{
                'modelVersionId': 'model-1',
                'role': 'PAPER_CHAMPION',
                'modelFingerprint': 'b'.padRight(64, 'b'),
              }],
              'canAuthorizeCapital': false,
            }), 200);
          case '/api/admin/model-governance/paper-shadow/sessions/$sessionId/observations':
            expect(request.url.queryParameters, {'limit': '50'});
            return http.Response(jsonEncode([{
              'caseKey': 'event-1',
              'symbol': 'RELIANCE-EQ',
              'decisionAt': '2026-11-04T09:11:00Z',
              'caseFingerprint': 'e'.padRight(64, 'e'),
              'independentlyVerifiedRuntime': false,
              'canAuthorizeCapital': false,
              'decisions': [
                {'modelVersionId': 'model-1', 'role': 'PAPER_CHAMPION',
                 'action': 'ENTER', 'predictedAt': '2026-11-04T09:11:00Z'},
                {'modelVersionId': 'model-2', 'role': 'SHADOW_CHALLENGER',
                 'action': 'WAIT', 'predictedAt': '2026-11-04T09:11:00Z'},
              ],
            }]), 200);
        }
        throw StateError('Unexpected connected model route');
      }),
    );
    final repository = HttpModelControlCenterRepository(api);
    final models = await repository.models();
    expect(models.single.key, 'catboost-alpha');
    expect(models.single.licenseStatus, 'RESEARCH_ONLY');
    final versions = await repository.versions(models.single.key);
    expect(versions.single.id, 'model-1');
    expect(versions.single.featureSchemaFingerprint, 'd'.padRight(64, 'd'));
    expect(versions.single.trainingDatasetFingerprint, 'e'.padRight(64, 'e'));
    expect(versions.single.trainingConfigFingerprint, 'f'.padRight(64, 'f'));
    expect(versions.single.codeSha, '1234567890abcdef1234567890abcdef12345678');
    expect(versions.single.researchRegistryFingerprint, 'a'.padRight(64, 'a'));
    expect(versions.single.trainingCutoff, DateTime.utc(2026, 11, 1, 9));
    final session = await repository.session(sessionId);
    expect(session.canAuthorizeCapital, false);
    expect(session.participants.single.role, 'PAPER_CHAMPION');
    final cases = await repository.recentCases(sessionId);
    expect(cases.single.hasDisagreement, true);
    expect(cases.single.verified, false);
    expect(cases.single.canAuthorizeCapital, false);
    expect(paths.length, 4);
  });

  test('rejects malformed evidence rather than fabricating defaults', () async {
    final operator = OperatorSession()..setOperatorKey('admin-key');
    final api = AuthenticatedApiClient(
      session: operator,
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async =>
        http.Response(jsonEncode({'items': []}), 200)),
    );
    final repo = HttpModelControlCenterRepository(api);
    await expectLater(repo.models(), throwsA(isA<ApiTransportFailure>()));
  });

  test('403 response is surfaced as permission denied', () async {
    final operator = OperatorSession()..setOperatorKey('trader-key');
    final api = AuthenticatedApiClient(
      session: operator,
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async => http.Response('{}', 403)),
    );
    final repo = HttpModelControlCenterRepository(api);
    await expectLater(
      repo.models(),
      throwsA(isA<ApiHttpFailure>().having((error) => error.statusCode, 'status', 403)),
    );
  });
}
