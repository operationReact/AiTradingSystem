import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_cohort_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_prospective_cohort_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Repository implements MindProspectiveCohortRepository {
  const _Repository({this.unregistered = false, this.fail = false});

  final bool unregistered;
  final bool fail;

  @override
  Future<MindProspectiveCohort> load() async {
    if (fail) throw StateError('private database diagnostics');
    return MindProspectiveCohort(
      generatedAt: DateTime.utc(2026, 11, 10, 16),
      sourceStatus: unregistered ? 'UNREGISTERED' : 'COLLECTING',
      registeredAt: unregistered ? null : DateTime.utc(2026, 11, 9, 16),
      observationStartsAt: unregistered ? null : DateTime.utc(2026, 11, 10, 3, 45),
      maturityAt: unregistered ? null : DateTime.utc(2026, 11, 23, 10),
      targetSessions: 10,
      completedSessions: 0,
      memberCount: unregistered ? 0 : 20,
      readyMembers: 0,
      members: unregistered ? const [] : List.generate(20, (i) =>
        MindProspectiveMember(
          slot: i + 1, symbol: 'STOCK${i + 1}-EQ',
          validCandles: 0, expectedCandles: 0, coveragePercent: 0,
          state: 'OBSERVATION_PENDING',
        )),
      blockers: const [
        'PROSPECTIVE_RETURNS_NOT_EVALUATED',
        'ACCOUNT_RISK_NOT_EVALUATED',
        'PAPER_SHADOW_ENROLLMENT_REQUIRED',
        'LIVE_EXECUTION_DISABLED',
      ],
    );
  }
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('prospective cohort stays read-only at width $width',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(
          child: MindProspectiveCohortPanel(repository: _Repository()))),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('mind-prospective-cohort')), findsOneWidget);
      expect(find.byKey(const ValueKey('mind-prospective-no-execution')),
          findsOneWidget);
      expect(find.text('ABSTAIN'), findsOneWidget);
      expect(find.textContaining('20-stock'), findsWidgets);
      expect(find.textContaining('Place order'), findsNothing);
      await tester.ensureVisible(find.byKey(const ValueKey('mind-prospective-expand')));
      await tester.tap(find.byKey(const ValueKey('mind-prospective-expand')));
      await tester.pumpAndSettle();
      expect(find.text('STOCK1-EQ'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('unregistered cohort cannot pretend coverage exists', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindProspectiveCohortPanel(repository: _Repository(unregistered: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-prospective-unregistered')), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-prospective-expand')), findsNothing);
    expect(find.byKey(const ValueKey('mind-prospective-no-execution')), findsOneWidget);
  });

  testWidgets('backend error does not leak diagnostic text or fake cohort', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindProspectiveCohortPanel(repository: _Repository(fail: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-prospective-error')), findsOneWidget);
    expect(find.textContaining('private database diagnostics'), findsNothing);
    expect(find.byTooltip('Refresh prospective research'), findsOneWidget);
  });
}
