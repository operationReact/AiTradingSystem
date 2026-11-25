import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_orb_walkforward_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_orb_walkforward_panel.dart';

class _Repository implements MindOrbWalkForwardRepository {
  @override
  Future<MindOrbWalkForwardReport> load() async => MindOrbWalkForwardReport(
    generatedAt: DateTime.utc(2026, 11, 25),
    status: 'INSUFFICIENT_COVERAGE',
    instrument: 'NSE:INFY-EQ',
    interval: 'FIVE_MINUTE',
    blockers: const ['SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN',
      'PAPER_SHADOW_ENROLLMENT_REQUIRED'],
    folds: const [MindOrbWalkForwardFold(number: 1,
      trainingCoveragePercent: 100, validationCoveragePercent: 82,
      status: 'INSUFFICIENT_COVERAGE')],
    completedFolds: 0,
    oosTrades: 0,
    costLabel: 'ILLUSTRATIVE_COMPOSITE_COSTS',
    feePerSide: 0.001,
    slippagePerSide: 0.0005,
    executionEnabled: false,
    paperOrderSubmitted: false,
    validatedTradingEdge: false,
  );
}

void main() {
  testWidgets('shows data gates and keeps the report research-only',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MindOrbWalkForwardPanel(repository: _Repository()),
      )),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('INSUFFICIENT COVERAGE'), findsOneWidget);
    expect(find.textContaining('No paper or broker order'), findsOneWidget);
    await tester.tap(find.text('Show fold coverage'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Fold 1: train 100.0% · holdout 82.0%'),
        findsOneWidget);
  });
}
