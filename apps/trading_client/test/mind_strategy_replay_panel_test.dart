import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_strategy_replay_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_strategy_replay_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Repository implements MindStrategyReplayRepository {
  const _Repository({this.fail = false, this.abstain = false});
  final bool fail;
  final bool abstain;

  @override
  Future<MindStrategyReplay> load() async {
    if (fail) throw StateError('private backend data');
    return MindStrategyReplay(
      generatedAt: DateTime.utc(2026, 11, 9, 9),
      strategyCode: 'MA_RECLAIM_V1',
      strategyVersion: '1',
      replayStatus: abstain ? 'UNAVAILABLE' : 'REPRODUCED',
      decisionPosture: abstain ? 'ABSTAIN' : 'WAIT',
      blockingReasons: const [
        'ACCOUNT_RISK_NOT_EVALUATED',
        'PAPER_SHADOW_ENROLLMENT_REQUIRED',
      ],
      paperShadowStatus: 'NOT_ENROLLED',
      instrument: abstain ? null : 'NSE:INFY-EQ',
      interval: abstain ? null : 'FIVE_MINUTE',
      signalAt: abstain ? null : DateTime.utc(2026, 11, 9, 8, 50),
    );
  }
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('replay preserves safe WAIT and no-order disclosure at $width',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(
          child: MindStrategyReplayPanel(repository: _Repository()))),
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mind-live-strategy-replay')), findsOneWidget);
      expect(find.byKey(const ValueKey('mind-replay-no-execution')), findsOneWidget);
      expect(find.text('NSE:INFY-EQ'), findsOneWidget);
      expect(find.text('WAIT'), findsOneWidget);
      expect(find.textContaining('BUY CANDIDATE'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('backend errors fail closed without private exception text',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindStrategyReplayPanel(repository: _Repository(fail: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-replay-unavailable')), findsOneWidget);
    expect(find.textContaining('private backend data'), findsNothing);
    expect(find.byTooltip('Refresh strategy replay'), findsOneWidget);
  });

  testWidgets('no persisted evidence displays ABSTAIN without a symbol',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindStrategyReplayPanel(repository: _Repository(abstain: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.text('ABSTAIN'), findsOneWidget);
    expect(find.text('NSE:INFY-EQ'), findsNothing);
    expect(find.byKey(const ValueKey('mind-replay-no-execution')), findsOneWidget);
  });
}
