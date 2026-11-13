import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_progress_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_progress_panel.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_progress_repository_test.dart' show item, payload;


class _Repo implements MindProgressRepository {
  _Repo(this.snapshot, {this.fail = false});
  final MindProgressSnapshot snapshot;
  final bool fail;
  final notes = <(String, String, String?)>[];

  @override
  Future<MindProgressSnapshot> load() async {
    if (fail) throw StateError('private diagnostics');
    return snapshot;
  }

  @override
  Future<MindProgressDetail> detail(String id) async => MindProgressDetail(
      item: snapshot.items.firstWhere((i) => i.id == id), history: const []);

  @override
  Future<void> annotate(String id, {required String type, String? text}) async => notes.add((id, type, text));
}

MindProgressSnapshot snapshot() => MindProgressSnapshot.fromJson(payload([
      for (final s in MintickMindScreens.all)
        item('screen.${s.id}', kind: 'SCREEN', status: s.id == 'overview' ? 'IMPLEMENTING' : 'MOCK',
            declared: s.id == 'overview' ? 'IMPLEMENTING' : 'MOCK'),
      item('strategy.STR-001', kind: 'STRATEGY'),
      item('strategy.STR-150', kind: 'STRATEGY', status: 'DATA_BLOCKED', declared: 'DATA_BLOCKED'),
      item('engine.paper-submit', status: 'RUNTIME_VERIFIED',
          runtime: {'lastVerifiedAt': '2026-11-13T09:00:00Z', 'verifiedRevision': 'abc1234'}),
    ]));

void main() {
  testWidgets('overview strip shows Not verified on failure, never zero', (tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: MindProgressStrip(repository: _Repo(snapshot(), fail: true)))));
    await tester.pumpAndSettle();
    expect(find.textContaining('Not verified'), findsWidgets);
    expect(find.textContaining('0/43'), findsNothing);
    expect(find.textContaining('nothing is shown as verified'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-status-RUNTIME_VERIFIED')), findsNothing);
  });

  testWidgets('overview strip reports server ratios and PAPER stage', (tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: MindProgressStrip(repository: _Repo(snapshot())))));
    await tester.pumpAndSettle();
    expect(find.textContaining('0/43'), findsOneWidget);
    expect(find.textContaining('0/2'), findsOneWidget);
    expect(find.textContaining('PAPER stage: X'), findsOneWidget);
  });

  for (final width in [360.0, 1280.0]) {
    testWidgets('coverage drill-down filters, shows mock as MOCK and records notes without status change at $width',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 1400);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final repo = _Repo(snapshot());
      await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(progressRepository: repo)));
      await tester.pumpAndSettle();
      // Overview carries the compact strip.
      expect(find.byKey(const ValueKey('mind-progress-strip')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('mind-progress-strip')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mind-progress-coverage')), findsOneWidget);
      expect(find.byKey(const ValueKey('mind-status-MOCK')), findsWidgets);
      expect(find.byKey(const ValueKey('mind-status-RUNTIME_VERIFIED')), findsNothing);

      await tester.enterText(find.byKey(const ValueKey('mind-progress-search')), 'coverage');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mind-progress-item-screen.coverage')), findsOneWidget);
      expect(find.byKey(const ValueKey('mind-progress-item-screen.risk')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('mind-progress-item-screen.coverage')));
      await tester.pumpAndSettle();
      expect(find.text('Not verified'), findsWidgets);
      await tester.enterText(find.byKey(const ValueKey('mind-progress-note')), 'looks right');
      await tester.ensureVisible(find.byKey(const ValueKey('mind-progress-add-note')));
      await tester.tap(find.byKey(const ValueKey('mind-progress-add-note')));
      await tester.pumpAndSettle();
      expect(repo.notes.single, ('screen.coverage', 'NOTE', 'looks right'));
      expect(find.byKey(const ValueKey('mind-progress-message')), findsOneWidget);
      expect(find.textContaining('DONE', findRichText: true), findsWidgets); // explanation text only
      expect(find.byKey(const ValueKey('mind-status-RUNTIME_VERIFIED')), findsNothing);
      Navigator.of(tester.element(find.byKey(const ValueKey('mind-progress-detail-screen.coverage')))).pop();
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('mind-progress-search')), '');
      await tester.tap(find.byKey(const ValueKey('mind-progress-section-ENGINE')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mind-status-RUNTIME_VERIFIED')), findsOneWidget);
    });
  }

  testWidgets('coverage without a repository says Not verified instead of claiming connected screens', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1440, 900);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: MintickMindWorkspacePage()));
    await tester.pumpAndSettle();
    final nav = find.byKey(const ValueKey('mind-route-coverage'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-progress-not-connected')), findsOneWidget);
    expect(find.text('Connected screens'), findsNothing);
  });
}
