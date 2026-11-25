import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_opening_range_replay_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_opening_range_replay_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Repository implements MindOpeningRangeReplayRepository {
  const _Repository({this.fail = false, this.abstain = false});
  final bool fail;
  final bool abstain;

  @override
  Future<MindOpeningRangeReplay> load() async {
    if (fail) throw StateError('private backend detail');
    return MindOpeningRangeReplay(
      generatedAt: DateTime.utc(2026, 11, 25, 9, 10),
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
      signalAt: abstain ? null : DateTime.utc(2026, 11, 25, 9),
    );
  }
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('ORB replay shows safe evidence at $width', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(
          child: MindOpeningRangeReplayPanel(repository: _Repository()))),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('mind-opening-range-replay')),
          findsOneWidget);
      expect(find.byKey(const ValueKey('mind-opening-range-no-execution')),
          findsOneWidget);
      expect(find.text('NSE:INFY-EQ · FIVE_MINUTE'), findsOneWidget);
      expect(find.text('WAIT'), findsOneWidget);
      expect(find.textContaining('BUY SIGNAL'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('backend errors fail closed without exception details',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindOpeningRangeReplayPanel(repository: _Repository(fail: true))),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-opening-range-unavailable')),
        findsOneWidget);
    expect(find.textContaining('private backend detail'), findsNothing);
  });

  testWidgets('no persisted candidate renders ABSTAIN without an instrument',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindOpeningRangeReplayPanel(repository: _Repository(abstain: true))),
    ));
    await tester.pumpAndSettle();

    expect(find.text('ABSTAIN'), findsOneWidget);
    expect(find.text('NSE:INFY-EQ · FIVE_MINUTE'), findsNothing);
    expect(find.byKey(const ValueKey('mind-opening-range-no-execution')),
        findsOneWidget);
  });
}
