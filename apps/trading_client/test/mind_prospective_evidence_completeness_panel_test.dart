import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_evidence_completeness_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_prospective_evidence_completeness_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Repository implements MindProspectiveEvidenceCompletenessRepository {
  const _Repository({this.pending = false, this.failed = false});

  final bool pending;
  final bool failed;

  @override
  Future<MindProspectiveEvidenceCompleteness> load() async {
    if (failed) throw StateError('private database diagnostic');
    return MindProspectiveEvidenceCompleteness(
      generatedAt: DateTime.utc(2026, 11, 27, 10, 40),
      status: pending
          ? 'OBSERVATION_SESSIONS_IN_PROGRESS'
          : 'SIGNAL_RECEIPT_WINDOWS_INCOMPLETE',
      completedSessions: pending ? 4 : 10,
      membersWithSufficientSourceCoverage: pending ? 0 : 20,
      expectedSignalWindowsAtMaturity: pending ? null : 11000,
      recordedSignalWindows: pending ? 4400 : 10999,
      missingSignalWindows: pending ? null : 1,
      precommittedCandidates: 30,
      reconciledOutcomes: 29,
      pendingOutcomes: 1,
      blockers: const ['LIVE_EXECUTION_DISABLED'],
    );
  }
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('integrity panel fits width $width with missing-window warning',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(
          child: MindProspectiveEvidenceCompletenessPanel(
            repository: _Repository()))),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(
        const ValueKey('minttick-research-completeness-panel')),
        findsOneWidget);
      expect(find.text('Missing research observation receipts'),
        findsOneWidget);
      expect(find.byKey(
        const ValueKey('minttick-research-missing-windows')),
        findsOneWidget);
      expect(find.byKey(
        const ValueKey('minttick-research-integrity-caveat')),
        findsOneWidget);
      expect(find.byKey(
        const ValueKey('minttick-research-integrity-no-orders')),
        findsOneWidget);
      expect(find.textContaining('Place order'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('ongoing sessions cannot fake finalized missing-window count',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindProspectiveEvidenceCompletenessPanel(
          repository: _Repository(pending: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(
      const ValueKey('minttick-research-pending-sessions')),
      findsOneWidget);
    expect(find.byKey(
      const ValueKey('minttick-research-missing-windows')),
      findsNothing);
  });

  testWidgets('failure hides private diagnostics and supports refresh',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindProspectiveEvidenceCompletenessPanel(
          repository: _Repository(failed: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(
      const ValueKey('minttick-research-integrity-error')),
      findsOneWidget);
    expect(find.textContaining('private database'), findsNothing);
    expect(find.byTooltip('Refresh research integrity'), findsOneWidget);
  });
}
