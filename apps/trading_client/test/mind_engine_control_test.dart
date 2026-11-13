import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_progress_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_progress_repository_test.dart' show item, payload;

class _Repo implements MindProgressRepository {
  _Repo(this.snapshot);
  final MindProgressSnapshot snapshot;
  @override
  Future<MindProgressSnapshot> load() async => snapshot;
  @override
  Future<MindProgressDetail> detail(String id) async =>
      MindProgressDetail(item: snapshot.items.firstWhere((i) => i.id == id), history: const []);
  @override
  Future<void> annotate(String id, {required String type, String? text}) async {}
}

MindProgressSnapshot registry() => MindProgressSnapshot.fromJson(payload([
      item('engine.paper-submit', status: 'RUNTIME_VERIFIED',
          runtime: {'lastVerifiedAt': '2026-11-13T09:00:00Z', 'verifiedRevision': 'abc1234'}),
      item('engine.scheduler', status: 'NOT_STARTED', declared: 'NOT_STARTED'),
      item('paper.vertical-slice', kind: 'PAPER')..['dependsOn'] = ['engine.paper-submit', 'engine.scheduler'],
      item('live.broker-gated', kind: 'LIVE', status: 'NOT_STARTED', declared: 'NOT_STARTED')
        ..['blockerCode'] = 'LIVE_NOT_AUTHORIZED',
      item('data.canonical-contract', kind: 'DATA', status: 'DATA_BLOCKED', declared: 'DATA_BLOCKED'),
    ]));

Future<void> open(WidgetTester tester, String route, {MindProgressRepository? repo}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 2000);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(progressRepository: repo)));
  await tester.pumpAndSettle();
  final nav = find.byKey(ValueKey('mind-route-$route'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Engine Control shows the real pipeline and no fake execution toggles', (tester) async {
    await open(tester, 'controls', repo: _Repo(registry()));
    expect(find.byKey(const ValueKey('mind-engine-status')), findsOneWidget);
    expect(find.text('abc1234'), findsOneWidget);
    expect(find.text('Not authorized'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-engine-engine.paper-submit')), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-status-RUNTIME_VERIFIED')), findsOneWidget);
    expect(find.textContaining('LIVE_NOT_AUTHORIZED'), findsOneWidget);
    for (final fake in ['Auto execution', 'Simulated emergency stop', 'Offline prototype', 'Allow new entries']) {
      expect(find.text(fake), findsNothing, reason: fake);
    }
  });

  testWidgets('Dependency Map shows real dependsOn chains with their status', (tester) async {
    await open(tester, 'dependencies', repo: _Repo(registry()));
    expect(find.byKey(const ValueKey('mind-dependency-paper.vertical-slice')), findsOneWidget);
    expect(find.text('needs engine.scheduler'), findsOneWidget);
    expect(find.text('NOT STARTED'), findsWidgets);
    expect(find.text('Momentum Breakout'), findsNothing);
  });

  testWidgets('without the registry Engine Control keeps the labelled prototype', (tester) async {
    await open(tester, 'controls');
    expect(find.text('MOCK CONTROLS ONLY'), findsOneWidget);
  });
}
