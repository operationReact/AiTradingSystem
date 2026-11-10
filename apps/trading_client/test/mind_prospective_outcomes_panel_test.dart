import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_outcomes_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_prospective_outcomes_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Repository implements MindProspectiveOutcomesRepository {
  const _Repository({this.missing = false, this.failed = false});
  final bool missing;
  final bool failed;

  @override
  Future<MindProspectiveOutcomesStatus> load() async {
    if (failed) throw StateError('private database connection details');
    return MindProspectiveOutcomesStatus(
      generatedAt: DateTime.utc(2026, 11, 11, 10, 16),
      status: missing ? 'UNREGISTERED' : 'PARTIAL_RESEARCH_OUTCOMES',
      precommittedCandidates: missing ? 0 : 5,
      reconciledOutcomes: missing ? 0 : 3,
      positiveNetObservations: missing ? 0 : 1,
      blockers: MindProspectiveOutcomesStatus.mandatoryGates,
    );
  }
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('research outcome panel fits $width dp without order actions',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(
          child: MindProspectiveOutcomesPanel(repository: _Repository()))),
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('minttick-prospective-outcomes')),
        findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-outcomes-count')),
        findsOneWidget);
      expect(find.textContaining('3 / 5 research candidates'), findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-outcomes-cost-caveat')),
        findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-outcomes-no-execution')),
        findsOneWidget);
      expect(find.byKey(
        const ValueKey('minttick-outcomes-entry-lookahead-warning')),
        findsOneWidget);
      expect(find.textContaining('Place order'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('no cohort displays zero candidates without invented outcomes',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindProspectiveOutcomesPanel(repository: _Repository(missing: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Prospective cohort not registered'), findsOneWidget);
    expect(find.textContaining('0 / 0 research candidates'), findsOneWidget);
  });

  testWidgets('service failure is generic and refresh remains functional',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindProspectiveOutcomesPanel(repository: _Repository(failed: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('minttick-outcomes-error')), findsOneWidget);
    expect(find.textContaining('private database'), findsNothing);
    expect(find.byTooltip('Refresh prospective outcomes'), findsOneWidget);
  });
}
