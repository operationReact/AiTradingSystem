import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/data/research_experiment_job_repository.dart';

const experimentId = '11111111-2222-3333-4444-555555555555';
const jobId = '99999999-8888-7777-6666-555555555555';
const modelId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

void main() {
  test('ADMIN research client reads immutable specs and queue, posts only research enqueue',
      () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final requests = <http.Request>[];
    final api = AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async {
        requests.add(request);
        expect(request.headers['X-API-Key'], 'admin-key');
        switch (request.url.path) {
          case '/api/admin/model-governance/experiments':
            expect(request.method, 'GET');
            return http.Response(jsonEncode([{
              'experimentId': experimentId,
              'modelVersionId': modelId,
              'specFingerprint': 'a'.padRight(64, 'a'),
              'fromAt': '2026-10-01T00:00:00Z',
              'toAt': '2026-10-31T00:00:00Z',
              'canAuthorizeCapital': false,
            }]), 200);
          case '/api/admin/model-governance/experiment-jobs':
            if (request.method == 'GET') {
              return http.Response(jsonEncode([]), 200);
            }
            expect(request.method, 'POST');
            expect(jsonDecode(request.body), {
              'experimentId': experimentId,
              'runnerType': 'PYTHON_INTELLIGENCE',
              'runnerVersion': 'python-research-v4',
              'maxAttempts': 2,
            });
            return http.Response(jsonEncode({
              'jobId': jobId,
              'experimentId': experimentId,
              'runnerType': 'PYTHON_INTELLIGENCE',
              'runnerVersion': 'python-research-v4',
              'status': 'QUEUED',
              'attempt': 0,
              'maxAttempts': 2,
              'specFingerprint': 'a'.padRight(64, 'a'),
              'canAuthorizeCapital': false,
            }), 201);
          case '/api/admin/model-governance/experiment-jobs/$jobId/result':
            expect(request.method, 'GET');
            return http.Response(jsonEncode({
              'jobId': jobId,
              'assessment': 'INCONCLUSIVE',
              'sampleCount': 25,
              'resultFingerprint': 'b'.padRight(64, 'b'),
              'resultArtifactFingerprint': 'c'.padRight(64, 'c'),
              'canAuthorizeCapital': false,
            }), 200);
        }
        throw StateError('Unexpected route: ${request.url.path}');
      }),
    );

    final repo = HttpResearchExperimentJobRepository(api);
    final specs = await repo.listSpecs();
    expect(specs.single.id, experimentId);
    expect(specs.single.modelVersionId, modelId);
    expect(await repo.listJobs(), isEmpty);
    final queued = await repo.enqueue(
      experimentId: experimentId,
      runnerType: 'PYTHON_INTELLIGENCE',
      runnerVersion: '  python-research-v4  ',
      maxAttempts: 2,
    );
    expect(queued.id, jobId);
    expect(queued.status, 'QUEUED');
    expect(queued.canAuthorizeCapital, isFalse);
    final result = await repo.result(jobId);
    expect(result.assessment, 'INCONCLUSIVE');
    expect(result.canAuthorizeCapital, isFalse);
    expect(requests.length, 4);
  });

  test('rejects invalid runner inputs before any POST', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    var requestCount = 0;
    final repo = HttpResearchExperimentJobRepository(AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async {
        requestCount++;
        return http.Response('{}', 201);
      }),
    ));

    for (final runner in ['ANY_RUNNER', 'JAVA_SCRIPT']) {
      await expectLater(
        repo.enqueue(
          experimentId: experimentId,
          runnerType: runner,
          runnerVersion: 'validated-v4',
          maxAttempts: 1,
        ),
        throwsA(isA<ArgumentError>()),
      );
    }
    await expectLater(
      repo.enqueue(
        experimentId: experimentId,
        runnerType: 'JAVA_BACKTEST',
        runnerVersion: '',
        maxAttempts: 1,
      ),
      throwsA(isA<ArgumentError>()),
    );
    expect(requestCount, 0);
  });

  test('401/403 denials survive typed repository as authorization errors', () async {
    final session = OperatorSession()..setOperatorKey('not-admin');
    final repo = HttpResearchExperimentJobRepository(AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async => http.Response('{}', 403)),
    ));
    await expectLater(
      repo.listSpecs(),
      throwsA(isA<ApiHttpFailure>()
          .having((failure) => failure.statusCode, 'statusCode', 403)),
    );
  });

  test('invalid or fabricated capital-authorizing server job/result is rejected', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final repo = HttpResearchExperimentJobRepository(AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async {
        if (request.url.path.endsWith('/result')) {
          return http.Response(jsonEncode({
            'jobId': jobId,
            'assessment': 'PASS',
            'canAuthorizeCapital': true,
          }), 200);
        }
        return http.Response(jsonEncode({
          'jobId': jobId,
          'experimentId': experimentId,
          'runnerType': 'JAVA_BACKTEST',
          'runnerVersion': 'java-backtest-v4',
          'status': 'SUCCEEDED',
          'canAuthorizeCapital': true,
        }), 200);
      }),
    ));
    await expectLater(
      repo.enqueue(
        experimentId: experimentId,
        runnerType: 'JAVA_BACKTEST',
        runnerVersion: 'java-backtest-v4',
        maxAttempts: 1,
      ),
      throwsA(isA<ApiTransportFailure>()),
    );
    await expectLater(repo.result(jobId), throwsA(isA<ApiTransportFailure>()));
  });

  test('invalid API list response fails closed', () async {
    final session = OperatorSession()..setOperatorKey('admin-key');
    final repo = HttpResearchExperimentJobRepository(AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://example.test'),
      client: MockClient((request) async =>
          http.Response(jsonEncode({'items': []}), 200)),
    ));
    await expectLater(repo.listJobs(), throwsA(isA<ApiTransportFailure>()));
  });
}
