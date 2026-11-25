import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_evidence_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_evidence_ledger_panel.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _LedgerRepository implements MindEvidenceRepository {
  const _LedgerRepository({this.fail = false, this.empty = false});
  final bool fail;
  final bool empty;

  @override
  Future<MindEvidenceLedger> load({int limit = 20}) async {
    if (fail) throw StateError('private database path');
    return MindEvidenceLedger(
      generatedAt: DateTime.utc(2026, 11, 9, 9),
      sourceStatus: empty ? 'UNAVAILABLE' : 'RECORDED',
      scanRunId: empty ? null : 'run-1',
      scanCompletedAt: empty ? null : DateTime.utc(2026, 11, 9, 8, 58),
      blockingReasons: empty
          ? const ['NO_COMPLETED_RESEARCH_SCAN']
          : const ['NO_VERIFIED_DECISION_EVIDENCE'],
      rejectedCandidates: 0,
      records: empty ? const [] : [
        MindEvidenceRecord(
          id: 'candidate-7', kind: 'RESEARCH_CANDIDATE',
          source: 'PERSISTED_RESEARCH_SCAN_CANDIDATE',
          instrument: 'NSE:INFY-EQ',
          strategy: 'MA_RECLAIM', referenceCode: 'SCAN_CANDIDATE',
          observedAt: DateTime.utc(2026, 11, 9, 8, 55),
          dataCutoffAt: DateTime.utc(2026, 11, 9, 8, 56),
          fingerprint: 'candidate-hash',
          usability: 'RESEARCH_ONLY',
        ),
      ],
    );
  }
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('evidence history stays readable and non-executable at $width',
      (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: const Scaffold(body: SingleChildScrollView(
          child: MindEvidenceLedgerPanel(repository: _LedgerRepository()))),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('mind-server-evidence-ledger')),
        findsOneWidget);
      expect(find.byKey(const ValueKey('mind-ledger-abstain')),
        findsOneWidget);
      expect(find.text('NSE:INFY-EQ'), findsOneWidget);
      expect(find.text('Known-at independently verified: NO'), findsOneWidget);
      expect(find.textContaining('BUY CANDIDATE'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('missing store does not create or replay evidence', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindEvidenceLedgerPanel(repository: _LedgerRepository(empty: true))),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-evidence-empty')), findsOneWidget);
    expect(find.text('NSE:INFY-EQ'), findsNothing);
    expect(find.byKey(const ValueKey('mind-ledger-abstain')), findsOneWidget);
  });

  testWidgets('failed request shows an explicit safe error and refresh',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body:
        MindEvidenceLedgerPanel(repository: _LedgerRepository(fail: true))),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-evidence-error')), findsOneWidget);
    expect(find.textContaining('private database path'), findsNothing);
    expect(find.byTooltip('Refresh evidence ledger'), findsOneWidget);
  });
}
