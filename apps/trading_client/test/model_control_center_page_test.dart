import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/model_control_center_repository.dart';
import 'package:trading_client/src/presentation/model_control_center_page.dart';

const sessionId = '11111111-2222-3333-4444-555555555555';

class _ModelEvidenceFake implements ModelControlCenterRepository {
  _ModelEvidenceFake({this.denied = false, this.empty = false});
  bool denied;
  final bool empty;
  int sessionRequests = 0;

  @override
  Future<List<RegisteredModelEvidence>> models() async {
    if (denied) throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    if (empty) return [];
    return const [
      RegisteredModelEvidence(
        key: 'catboost-alpha', displayName: 'CatBoost Alpha',
        family: 'CATBOOST', role: 'ALPHA', provider: 'IN_HOUSE',
        licenseStatus: 'RESEARCH_ONLY',
        fingerprint: 'aabbccddeeff',
      ),
    ];
  }

  @override
  Future<List<ModelVersionEvidence>> versions(String modelKey) async {
    if (denied) throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    return const [
        ModelVersionEvidence(
          id: 'version-1', label: 'v1', fingerprint: 'version-fingerprint',
          artifactFingerprint: 'artifact-fingerprint',
          featureSchemaFingerprint: 'features-fingerprint',
          trainingDatasetFingerprint: 'dataset-fingerprint',
          trainingConfigFingerprint: 'config-fingerprint',
          codeSha: 'code-commit-sha', createdAt: null,
        ),
      ];
  }

  @override
  Future<List<MatchedSessionEvidence>> recentSessions() async {
    if (denied) throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    return const [
    MatchedSessionEvidence(
      id: sessionId, fingerprint: 'enrollment-fingerprint',
      createdAt: null, createdBy: 'admin', canAuthorizeCapital: false,
      participants: [
        MatchedParticipantEvidence(
          versionId: 'version-1', role: 'PAPER_CHAMPION', modelFingerprint: 'model-alpha',
        ),
        MatchedParticipantEvidence(
          versionId: 'version-2', role: 'SHADOW_CHALLENGER', modelFingerprint: 'model-beta',
        ),
      ],
    ),
      ];
  }

  @override
  Future<MatchedSessionEvidence> session(String id) async {
    if (denied) throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    sessionRequests++;
    return const MatchedSessionEvidence(
      id: sessionId, fingerprint: 'enrollment-fingerprint',
      createdAt: null, createdBy: 'admin',
      canAuthorizeCapital: false,
      participants: [
        MatchedParticipantEvidence(
          versionId: 'version-1', role: 'PAPER_CHAMPION',
          modelFingerprint: 'model-alpha',
        ),
        MatchedParticipantEvidence(
          versionId: 'version-2', role: 'SHADOW_CHALLENGER',
          modelFingerprint: 'model-beta',
        ),
      ],
    );
  }

  @override
  Future<List<MatchedCaseEvidence>> recentCases(String id) async {
    if (denied) throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    return const [
        MatchedCaseEvidence(
          id: 'event-001', symbol: 'RELIANCE-EQ', decisionAt: null,
          caseFingerprint: 'event-fingerprint',
          verified: false, canAuthorizeCapital: false,
          candidates: [
            MatchedCandidateEvidence(
              versionId: 'version-1', role: 'PAPER_CHAMPION',
              action: 'ENTER', predictedAt: null,
            ),
            MatchedCandidateEvidence(
              versionId: 'version-2', role: 'SHADOW_CHALLENGER',
              action: 'WAIT', predictedAt: null,
            ),
          ],
        ),
          ];
  }

}

void main() {
  testWidgets('model inventory renders registered versions and research-only status',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: _ModelEvidenceFake()),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-control-inventory')), findsOneWidget);
    expect(find.text('CatBoost Alpha'), findsOneWidget);
    expect(find.textContaining('RESEARCH_ONLY'), findsOneWidget);
    expect(find.textContaining('cannot authorize live orders'), findsWidgets);

    await tester.tap(find.byKey(const ValueKey('model-inventory-catboost-alpha')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-version-version-1')), findsOneWidget);
    expect(find.text('v1'), findsOneWidget);
    expect(find.textContaining('Feature schema:'), findsOneWidget);
    expect(find.textContaining('Training dataset:'), findsOneWidget);
    expect(find.textContaining('Training config:'), findsOneWidget);
    expect(find.textContaining('Code:'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Switch to LIVE'), findsNothing);
  });

  testWidgets('read-only PAPER/SHADOW displays source uncertainty and disagreement',
      (tester) async {
    final repository = _ModelEvidenceFake();
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('PAPER / SHADOW'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-session-picker')), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('model-session-input')), sessionId);
    await tester.tap(find.byKey(const ValueKey('model-session-fetch')));
    await tester.pumpAndSettle();
    expect(repository.sessionRequests, 1);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('model-session-summary')),
      250,
      scrollable: find.descendant(
        of: find.byKey(const ValueKey('model-control-paper-shadow')),
        matching: find.byType(Scrollable),
      ).first,
    );
    expect(find.byKey(const ValueKey('model-session-summary')), findsOneWidget);
    expect(find.textContaining('Source and runtime independently verified: NO'),
        findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('model-case-event-001')),
      250,
      scrollable: find.descendant(
        of: find.byKey(const ValueKey('model-control-paper-shadow')),
        matching: find.byType(Scrollable),
      ).first,
    );
    expect(find.byKey(const ValueKey('model-case-event-001')), findsOneWidget);
    expect(find.text('Champion/challenger disagreement'), findsOneWidget);
    expect(find.text('Independent source validation: NOT VERIFIED'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Enable LIVE'), findsNothing);
  });

  testWidgets('recent-session picker opens persisted matched evidence',
      (tester) async {
    final repository = _ModelEvidenceFake();
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('PAPER / SHADOW'));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('model-session-picker')));
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining('Session 11111111-222').last);
    await tester.pumpAndSettle();

    expect(repository.sessionRequests, 1);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('model-session-summary')),
      250,
      scrollable: find.descendant(
        of: find.byKey(const ValueKey('model-control-paper-shadow')),
        matching: find.byType(Scrollable),
      ).first,
    );
    expect(find.byKey(const ValueKey('model-session-summary')), findsOneWidget);
  });

  testWidgets('malformed session ID fails locally without any fetch',
      (tester) async {
    final repository = _ModelEvidenceFake();
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('PAPER / SHADOW'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('model-session-input')), 'invalid');
    await tester.tap(find.byKey(const ValueKey('model-session-fetch')));
    await tester.pump();
    expect(find.byKey(const ValueKey('model-session-validation')), findsOneWidget);
    expect(repository.sessionRequests, 0);
  });

  testWidgets('empty registry and ADMIN denial have explicit stable states',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: _ModelEvidenceFake(empty: true)),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-inventory-empty')), findsOneWidget);

    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(
        key: const ValueKey('denied-state'),
        repository: _ModelEvidenceFake(denied: true),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-control-access-denied')), findsOneWidget);
    expect(find.textContaining('ADMIN access is required'), findsOneWidget);
  });

  testWidgets('revoked ADMIN permissions clear an already populated model inventory',
      (tester) async {
    final repo = _ModelEvidenceFake();
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('model-inventory-catboost-alpha')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-version-version-1')), findsOneWidget);
    repo.denied = true;
    await tester.tap(find.byKey(const ValueKey('model-inventory-refresh')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-control-access-denied')), findsOneWidget);
    expect(find.byKey(const ValueKey('model-inventory-catboost-alpha')), findsNothing);
    expect(find.byKey(const ValueKey('model-version-version-1')), findsNothing);
  });

  testWidgets('revoked PAPER session access purges cached cases and model inventory',
      (tester) async {
    final repo = _ModelEvidenceFake();
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('PAPER / SHADOW'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('model-session-input')), sessionId);
    await tester.tap(find.byKey(const ValueKey('model-session-fetch')));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('model-session-summary')),
      250,
      scrollable: find.descendant(
        of: find.byKey(const ValueKey('model-control-paper-shadow')),
        matching: find.byType(Scrollable),
      ).first,
    );
    expect(find.byKey(const ValueKey('model-session-summary')), findsOneWidget);
    repo.denied = true;
    await tester.ensureVisible(find.byKey(const ValueKey('model-session-fetch')));
    await tester.tap(find.byKey(const ValueKey('model-session-fetch')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-control-access-denied')), findsWidgets);
    expect(find.byKey(const ValueKey('model-session-summary')), findsNothing);
    expect(find.byKey(const ValueKey('model-case-event-001')), findsNothing);
    await tester.tap(find.text('Model inventory'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('model-inventory-catboost-alpha')), findsNothing);
  });
}
