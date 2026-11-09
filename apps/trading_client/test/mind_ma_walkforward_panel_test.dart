import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_ma_walkforward_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_ma_walkforward_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Repository implements MindMaWalkForwardRepository {
  const _Repository({this.failed = false, this.historyMissing = false});

  final bool failed;
  final bool historyMissing;

  @override
  Future<MindMaWalkForwardReport> load() async {
    if (failed) throw StateError('private data credentials');
    return MindMaWalkForwardReport(
      generatedAt: DateTime.utc(2026, 11, 9, 9),
      status: historyMissing ? 'UNAVAILABLE' : 'INSUFFICIENT_SAMPLE',
      instrument: historyMissing ? null : 'NSE:INFY-EQ',
      folds: historyMissing ? const [] : [
        MindMaWalkForwardFold(
          number: 1,
          trainingFrom: DateTime.utc(2026, 5, 13),
          trainingTo: DateTime.utc(2026, 7, 11),
          validationFrom: DateTime.utc(2026, 7, 12),
          validationTo: DateTime.utc(2026, 7, 31),
          trainingCoveragePercent: 96,
          validationCoveragePercent: 98,
          status: 'EVALUATED',
          validation: const MindMaWalkForwardMetrics(
            closedTrades: 2, grossPnl: 120,
            feeCost: 28, slippageCost: 14,
            netPnl: 78, returnPercent: 0.078,
            maxDrawdownPercent: 1.8,
          ),
        ),
      ],
      blockers: const [
        'SELECTION_BIAS_NOT_CORRECTED',
        'SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN',
        'ACCOUNT_RISK_NOT_EVALUATED',
        'PAPER_SHADOW_ENROLLMENT_REQUIRED',
        'LIVE_EXECUTION_DISABLED',
      ],
      completedFolds: historyMissing ? 0 : 1,
      oosTrades: historyMissing ? 0 : 2,
      costLabel:
        'ILLUSTRATIVE_COMPOSITE_COSTS_NOT_ACTUAL_BROKER_OR_STATUTORY_CHARGES',
      feePerSide: 0.001,
      slippagePerSide: 0.0005,
      meanOosReturnPercent: historyMissing ? null : 0.078,
      worstOosDrawdownPercent: historyMissing ? null : 1.8,
      oosNetPnl: historyMissing ? null : 78,
    );
  }
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('walk-forward reports cost assumptions without trade buttons at $width',
      (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(
          child: MindMaWalkForwardPanel(repository: _Repository()))),
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mind-ma-walkforward')), findsOneWidget);
      expect(find.byKey(const ValueKey('mind-wf-no-execution')), findsOneWidget);
      expect(find.text('NSE:INFY-EQ'), findsOneWidget);
      expect(find.textContaining('Mean holdout net return'), findsOneWidget);
      expect(find.textContaining('ABSTAIN'), findsOneWidget);
      expect(find.textContaining('Place order'), findsNothing);
      await tester.ensureVisible(find.byKey(const ValueKey('mind-wf-expand-folds')));
      await tester.tap(find.byKey(const ValueKey('mind-wf-expand-folds')));
      await tester.pumpAndSettle();
      expect(find.textContaining('Fold 1'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('missing history has no synthetic trade returns', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindMaWalkForwardPanel(repository: _Repository(historyMissing: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-wf-status')), findsOneWidget);
    expect(find.text('No source-backed reference candidate'), findsOneWidget);
    expect(find.textContaining('Mean holdout net return'), findsNothing);
    expect(find.byKey(const ValueKey('mind-wf-no-execution')), findsOneWidget);
  });

  testWidgets('backend outage is handled without leaking internal errors',
    (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindMaWalkForwardPanel(repository: _Repository(failed: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-wf-unavailable')), findsOneWidget);
    expect(find.textContaining('private data credentials'), findsNothing);
    expect(find.byTooltip('Refresh walk-forward research'), findsOneWidget);
  });
}
