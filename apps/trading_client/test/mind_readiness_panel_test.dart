import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_readiness_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_readiness_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _FixtureRepository implements MindReadinessRepository {
  const _FixtureRepository({required this.failure});
  final bool failure;

  @override
  Future<MindReadiness> load() async {
    if (failure) throw StateError('private backend diagnostics');
    return MindReadiness(
      generatedAt: DateTime.utc(2026, 11, 9, 9),
      market: MindEvidence(
        status: 'STORED', source: 'STORED_LAST_OVERVIEW',
        asOf: DateTime.utc(2026, 11, 9, 8, 55),
        observations: 40, reasonCode: 'STORED_NOT_LIVE',
      ),
      research: MindEvidence(
        status: 'RECORDED', source: 'PERSISTED_RESEARCH_SCAN',
        asOf: DateTime.utc(2026, 11, 9, 8, 50),
        observations: 80, reasonCode: 'RESEARCH_NOT_VALIDATED_FOR_EXECUTION',
      ),
    );
  }
}

void main() {
  for (final width in [320.0, 390.0]) {
    testWidgets('Mind readiness displays persisted evidence, never BUY at $width',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(child:
          MindReadinessPanel(repository: _FixtureRepository(failure: false)))),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Market feed'), findsOneWidget);
      expect(find.text('Stored observation · not live'), findsOneWidget);
      expect(find.text('Completed scan · not a signal'), findsOneWidget);
      expect(find.byKey(const ValueKey('mind-readiness-abstain')), findsOneWidget);
      expect(find.textContaining('BUY CANDIDATE'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('unreachable backend never replays synthetic intelligence',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
          MindReadinessPanel(repository: _FixtureRepository(failure: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-readiness-unavailable')),
        findsOneWidget);
    expect(find.textContaining('private backend diagnostics'), findsNothing);
    expect(find.textContaining('BUY CANDIDATE'), findsNothing);
  });
}
