import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_signals_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_prospective_signals_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Repository implements MindProspectiveSignalsRepository {
  const _Repository({this.missing = false, this.failed = false});

  final bool missing;
  final bool failed;

  @override
  Future<MindProspectiveSignalsStatus> load() async {
    if (failed) throw StateError('private database credentials');
    return MindProspectiveSignalsStatus(
      generatedAt: DateTime.utc(2026, 11, 10, 5, 35),
      registeredAt: missing ? null : DateTime.utc(2026, 11, 9, 16),
      observationStartsAt: missing ? null : DateTime.utc(2026, 11, 10, 3, 45),
      status: missing ? 'UNREGISTERED' : 'RESEARCH_RECEIPTS_RECORDED',
      memberCount: missing ? 0 : 20,
      recordedWindows: missing ? 0 : 1,
      researchCandidates: 0,
      members: missing ? const [] : List.generate(20, (i) =>
        MindProspectiveSignalMember(
          slot: i + 1,
          symbol: 'STOCK${i + 1}-EQ',
          recordedWindows: i == 0 ? 1 : 0,
          researchCandidates: 0,
        )),
      blockers: const [
        'PROSPECTIVE_OUTCOMES_NOT_RECONCILED',
        'ORIGINAL_PROVIDER_KNOWN_AT_NOT_INDEPENDENTLY_ATTESTED',
        'ACCOUNT_RISK_NOT_EVALUATED',
        'PAPER_SHADOW_ENROLLMENT_REQUIRED',
        'LIVE_EXECUTION_DISABLED',
      ],
    );
  }
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('receipt status remains research-only at $width dp',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(
          child: MindProspectiveSignalsPanel(repository: _Repository()))),
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('minttick-prospective-receipts')),
          findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-receipts-no-outcomes')),
          findsOneWidget);
      expect(find.byKey(const ValueKey('minttick-receipts-no-execution')),
          findsOneWidget);
      expect(find.text('ABSTAIN'), findsOneWidget);
      expect(find.textContaining('Place order'), findsNothing);
      await tester.ensureVisible(find.byKey(const ValueKey('minttick-receipts-expand')));
      await tester.tap(find.byKey(const ValueKey('minttick-receipts-expand')));
      await tester.pumpAndSettle();
      expect(find.text('STOCK1-EQ'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('unregistered cohort never shows made-up candidates', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindProspectiveSignalsPanel(repository: _Repository(missing: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.text('No frozen prospective cohort'), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-receipts-expand')), findsNothing);
    expect(find.byKey(const ValueKey('minttick-receipts-no-execution')),
      findsOneWidget);
  });

  testWidgets('service outage never leaks backend internals', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindProspectiveSignalsPanel(repository: _Repository(failed: true))),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('minttick-receipts-error')), findsOneWidget);
    expect(find.textContaining('private database credentials'), findsNothing);
    expect(find.byTooltip('Refresh prospective signal receipts'), findsOneWidget);
  });
}
