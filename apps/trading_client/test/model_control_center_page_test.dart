import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/model_control_repository.dart';
import 'package:trading_client/src/presentation/model_control_center_page.dart';

class _FakeRepository implements ModelControlRepository {
  _FakeRepository({this.forbidden = false, this.noModels = false});
  final bool forbidden;
  final bool noModels;

  @override
  Future<List<RegisteredModel>> models() async {
    if (forbidden) throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    if (noModels) return const [];
    return const [
      RegisteredModel(
        modelKey: 'alpha-model',
        displayName: 'Alpha Research',
        family: 'CATBOOST',
        role: 'ALPHA',
        provider: 'IN_HOUSE',
        licenseStatus: 'RESEARCH_ONLY',
        definitionFingerprint: 'model-definition-fingerprint',
      ),
    ];
  }

  @override
  Future<List<RegisteredModelVersion>> versions(String key) async {
    return const [
      RegisteredModelVersion(
        versionId: 'model-version-1',
        label: 'v1',
        artifactFingerprint: 'artifact-sha',
        featureSchemaFingerprint: 'feature-sha',
        trainingDatasetFingerprint: 'dataset-sha',
        codeSha: 'code-sha',
      ),
    ];
  }

  @override
  Future<List<EmergencyModelControl>> emergencyControls() async {
    return const [
      EmergencyModelControl(
        type: 'STATIC_FALLBACK',
        scope: 'GLOBAL',
        active: true,
        reason: 'Investigating degraded market source',
      ),
    ];
  }
}

void main() {
  testWidgets('connected model inventory displays exact versions and active emergency state',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: _FakeRepository()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Model Control Center'), findsOneWidget);
    expect(find.text('Alpha Research'), findsOneWidget);
    expect(find.textContaining('RESEARCH_ONLY'), findsOneWidget);
    expect(find.textContaining('1 active controls'), findsOneWidget);
    expect(find.textContaining('LIVE unavailable'), findsOneWidget);
    expect(find.text('STATIC_FALLBACK · GLOBAL'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('model-row-alpha-model')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Artifact: artifact-sha'), findsOneWidget);
    expect(find.byKey(const ValueKey('model-version-model-version-1')), findsOneWidget);
    expect(find.text('Enable LIVE'), findsNothing);
  });

  testWidgets('empty model inventory displays an honest empty state', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: _FakeRepository(noModels: true)),
    ));
    await tester.pumpAndSettle();
    expect(find.text('No registered models'), findsOneWidget);
    expect(find.text('Alpha Research'), findsNothing);
  });

  testWidgets('server permission denial hides previously inaccessible evidence', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ModelControlCenterPage(repository: _FakeRepository(forbidden: true)),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Administrator access required'), findsOneWidget);
    expect(find.text('Alpha Research'), findsNothing);
    expect(find.text('No registered models'), findsNothing);
  });
}
