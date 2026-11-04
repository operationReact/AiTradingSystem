import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/research_experiment_job_repository.dart';
import 'package:trading_client/src/presentation/research_experiment_jobs_page.dart';

const experimentId = '11111111-2222-3333-4444-555555555555';
const jobId = '99999999-8888-7777-6666-555555555555';

class _FakeResearchJobRepository implements ResearchExperimentJobRepository {
  _FakeResearchJobRepository({
    this.denied = false,
    this.empty = false,
    this.completed = false,
  });
  bool denied, empty, completed;
  int enqueueCalls = 0;
  String? runnerVersion;
  String? runnerType;
  int? maxAttempts;

  @override
  Future<List<ResearchExperimentSpec>> listSpecs() async {
    if (denied) {
      throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    }
    if (empty) return [];
    return const [
      ResearchExperimentSpec(
        id: experimentId,
        modelVersionId: '77777777-8888-9999-aaaa-bbbbbbbbbbbb',
        specFingerprint: 'valid-frozen-spec',
        fromAt: null,
        toAt: null,
      ),
    ];
  }

  @override
  Future<List<ResearchExperimentJob>> listJobs() async {
    if (denied) {
      throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    }
    if (completed || enqueueCalls > 0) return [job()];
    return [];
  }

  ResearchExperimentJob job() => ResearchExperimentJob(
    id: jobId,
    experimentId: experimentId,
    runnerType: 'PYTHON_INTELLIGENCE',
    runnerVersion: 'python-research-v4',
    status: completed ? 'SUCCEEDED' : 'QUEUED',
    attempt: 0,
    maxAttempts: 1,
    specFingerprint: 'valid-frozen-spec',
    createdAt: null,
    failureCode: null,
    canAuthorizeCapital: false,
  );

  @override
  Future<ResearchExperimentJob> enqueue({
    required String experimentId,
    required String runnerType,
    required String runnerVersion,
    required int maxAttempts,
  }) async {
    enqueueCalls++;
    this.runnerType = runnerType;
    this.runnerVersion = runnerVersion;
    this.maxAttempts = maxAttempts;
    return job();
  }

  @override
  Future<ResearchExperimentResult> result(String jobId) async =>
      ResearchExperimentResult(
        jobId: jobId,
        assessment: 'INCONCLUSIVE',
        sampleCount: 25,
        resultFingerprint: 'real-server-fingerprint',
        artifactFingerprint: 'registered-artifact-fingerprint',
        canAuthorizeCapital: false,
      );
}

Future<void> _scrollTo(WidgetTester tester, String key) async {
  await tester.scrollUntilVisible(
    find.byKey(ValueKey(key)),
    250,
    scrollable: find.descendant(
      of: find.byKey(const ValueKey('research-job-page')),
      matching: find.byType(Scrollable),
    ).first,
  );
}

Future<void> _selectAndEnter(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('research-job-spec-picker')));
  await tester.pumpAndSettle();
  await tester.tap(find.textContaining('11111111-222').last);
  await tester.pumpAndSettle();
  await _scrollTo(tester, 'research-job-version');
  await tester.enterText(
    find.byKey(const ValueKey('research-job-version')),
    'python-research-v4',
  );
  await _scrollTo(tester, 'research-job-enqueue');
  await tester.tap(find.byKey(const ValueKey('research-job-enqueue')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('registered experiment queues only after explicit dialog confirmation',
      (tester) async {
    final repository = _FakeResearchJobRepository();
    await tester.pumpWidget(MaterialApp(
      home: ResearchExperimentJobsPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('research-job-safety')), findsOneWidget);
    expect(find.byKey(const ValueKey('research-job-spec-picker')), findsOneWidget);

    await _selectAndEnter(tester);
    expect(find.text('Queue research experiment?'), findsOneWidget);
    expect(repository.enqueueCalls, 0);

    await tester.tap(find.byKey(const ValueKey('research-job-confirm')));
    await tester.pumpAndSettle();
    expect(repository.enqueueCalls, 1);
    expect(repository.runnerType, 'PYTHON_INTELLIGENCE');
    expect(repository.runnerVersion, 'python-research-v4');
    expect(repository.maxAttempts, 1);
    expect(find.widgetWithText(FilledButton, 'Switch to LIVE'), findsNothing);
  });

  testWidgets('cancel confirmed queue never invokes Java POST',
      (tester) async {
    final repository = _FakeResearchJobRepository();
    await tester.pumpWidget(MaterialApp(
      home: ResearchExperimentJobsPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    await _selectAndEnter(tester);
    await tester.tap(find.byKey(const ValueKey('research-job-cancel')));
    await tester.pumpAndSettle();
    expect(repository.enqueueCalls, 0);
    expect(find.byKey(const ValueKey('research-job-enqueued')), findsNothing);
  });

  testWidgets('empty and forbidden ADMIN evidence are explicit states',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ResearchExperimentJobsPage(
        repository: _FakeResearchJobRepository(empty: true),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('research-job-no-specs')), findsOneWidget);
    expect(find.byKey(const ValueKey('research-job-empty')), findsOneWidget);

    await tester.pumpWidget(MaterialApp(
      home: ResearchExperimentJobsPage(
        key: const ValueKey('forbidden-runner-page'),
        repository: _FakeResearchJobRepository(denied: true),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('research-job-access-denied')), findsOneWidget);
    expect(find.byKey(const ValueKey('research-job-spec-picker')), findsNothing);
  });

  testWidgets('existing finished job displays only server-provided immutable result',
      (tester) async {
    final repository = _FakeResearchJobRepository(completed: true);
    await tester.pumpWidget(MaterialApp(
      home: ResearchExperimentJobsPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    await _scrollTo(tester, 'research-job-result-$jobId');
    await tester.tap(find.byKey(const ValueKey('research-job-result-$jobId')));
    await tester.pumpAndSettle();
    await _scrollTo(tester, 'research-job-result-evidence');
    expect(find.byKey(const ValueKey('research-job-result-evidence')),
        findsOneWidget);
    expect(find.textContaining('INCONCLUSIVE'), findsOneWidget);
    expect(find.textContaining('Not a real-world profitability'), findsOneWidget);
  });

  testWidgets('validation prevents opening a queue confirmation',
      (tester) async {
    final repository = _FakeResearchJobRepository();
    await tester.pumpWidget(MaterialApp(
      home: ResearchExperimentJobsPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    await _scrollTo(tester, 'research-job-enqueue');
    await tester.tap(find.byKey(const ValueKey('research-job-enqueue')));
    await tester.pumpAndSettle();
    expect(repository.enqueueCalls, 0);
    expect(find.byKey(const ValueKey('research-job-validation')), findsOneWidget);
    expect(find.text('Queue research experiment?'), findsNothing);
  });
}
