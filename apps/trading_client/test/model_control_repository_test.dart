import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/model_control_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';

void main() {
  test('Admin model repository reads registered definitions, versions, and control state', () async {
    final session = OperatorSession()..setOperatorKey('research-operator-key');
    final paths = <String>[];
    final repo = HttpModelControlRepository(
      AuthenticatedApiClient(
        session: session,
        baseUri: Uri.parse('https://example.test'),
        client: MockClient((request) async {
          expect(request.headers['X-API-Key'], 'research-operator-key');
          expect(request.method, 'GET');
          paths.add(request.url.path);
          if (request.url.path == '/api/admin/model-governance/models') {
            return http.Response(jsonEncode([{
              'modelKey': 'catboost-alpha',
              'displayName': 'CatBoost Alpha',
              'family': 'CATBOOST',
              'role': 'ALPHA',
              'provider': 'IN_HOUSE',
              'licenseStatus': 'RESEARCH_ONLY',
              'definitionFingerprint': 'a' * 64,
            }]), 200);
          }
          if (request.url.path ==
              '/api/admin/model-governance/models/catboost-alpha/versions') {
            return http.Response(jsonEncode([{
              'versionId': 'version-one',
              'versionLabel': 'v1',
              'artifactFingerprint': 'b' * 64,
              'featureSchemaFingerprint': 'c' * 64,
              'trainingDatasetFingerprint': 'd' * 64,
              'codeSha': 'abcd',
            }]), 200);
          }
          if (request.url.path ==
              '/api/admin/model-governance/emergency-controls') {
            return http.Response(jsonEncode([{
              'controlType': 'STATIC_FALLBACK',
              'scopeKey': 'GLOBAL',
              'active': true,
              'reason': 'Research regression',
            }]), 200);
          }
          return http.Response('not found', 404);
        }),
      ),
    );
    final models = await repo.models();
    final versions = await repo.versions('catboost-alpha');
    final controls = await repo.emergencyControls();

    expect(models.single.modelKey, 'catboost-alpha');
    expect(models.single.licenseStatus, 'RESEARCH_ONLY');
    expect(versions.single.artifactFingerprint, 'b' * 64);
    expect(controls.single.active, isTrue);
    expect(paths, [
      '/api/admin/model-governance/models',
      '/api/admin/model-governance/models/catboost-alpha/versions',
      '/api/admin/model-governance/emergency-controls',
    ]);
  });

  test('malformed evidence and invalid keys fail instead of inventing models', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final repository = HttpModelControlRepository(
      AuthenticatedApiClient(
        session: session,
        baseUri: Uri.parse('https://example.test'),
        client: MockClient((_) async => http.Response('{"wrong":"shape"}', 200)),
      ),
    );
    expect(repository.models(), throwsFormatException);
    expect(repository.versions('../escape'), throwsFormatException);
    expect(repository.emergencyControls(), throwsFormatException);
  });

  test('backend 403 remains an authorization error rather than empty inventory', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final repository = HttpModelControlRepository(
      AuthenticatedApiClient(
        session: session,
        baseUri: Uri.parse('https://example.test'),
        client: MockClient((_) async => http.Response('Forbidden', 403)),
      ),
    );
    await expectLater(
      repository.models(),
      throwsA(isA<ApiHttpFailure>().having((e) => e.statusCode, 'statusCode', 403)),
    );
  });
}
