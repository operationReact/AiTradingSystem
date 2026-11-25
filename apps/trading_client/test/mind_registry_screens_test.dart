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

MindProgressSnapshot registry() {
  final strategy = item('strategy.STR-001', kind: 'STRATEGY')
    ..['implementation'] = {'code': 'SMA_TREND_VOLUME_V1', 'version': '1', 'match': 'VARIANT', 'runtime': 'JAVA_DETERMINISTIC'}
    ..['blockerCode'] = 'NOT_WIRED_TO_PAPER'
    ..['missing'] = ['Backtest + walk-forward evidence per strategy'];
  final blocked = item('strategy.STR-002', kind: 'STRATEGY', status: 'DATA_BLOCKED', declared: 'DATA_BLOCKED');
  final lightgbm = item('model.lightgbm', kind: 'MODEL')..['blockerCode'] = 'NO_DEPLOYED_TRAINED_ARTIFACT';
  return MindProgressSnapshot.fromJson(payload([strategy, blocked, lightgbm]));
}

Future<void> open(WidgetTester tester, String route, {MindProgressRepository? repo}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 1600);
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
  testWidgets('Strategy Universe shows server status and implementation, not fixture lifecycle', (tester) async {
    await open(tester, 'strategies', repo: _Repo(registry()));
    expect(find.text('TESTED'), findsWidgets);
    expect(find.text('DATA BLOCKED'), findsWidgets);
    expect(find.textContaining('SMA_TREND_VOLUME_V1'), findsOneWidget);
    expect(find.text('Paper candidates'), findsNothing);
    for (final fake in ['Paper', 'Watch', 'Paused']) {
      expect(find.text(fake), findsNothing, reason: fake);
    }
    // Strategy detail: blocker and before-DONE checklist.
    await tester.tap(find.text('SMA Cross'));
    await tester.pumpAndSettle();
    expect(find.text('NOT_WIRED_TO_PAPER'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-strategy-missing')), findsOneWidget);
    expect(find.textContaining('fictional'), findsNothing);
  });

  testWidgets('Model Laboratory replaces fixture metrics with registry status', (tester) async {
    await open(tester, 'models', repo: _Repo(registry()));
    expect(find.text('0.81'), findsNothing);
    expect(find.text('AUC 0.81'), findsNothing);
    expect(find.textContaining('NO_DEPLOYED_TRAINED_ARTIFACT'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('LightGBM · AUC'), 300,
        scrollable: find.descendant(of: find.byKey(const ValueKey('mind-screen-models')),
            matching: find.byType(Scrollable)).first);
    expect(find.text('Not measured'), findsWidgets);
    expect(find.text('0.81'), findsNothing);
  });

  testWidgets('Model Deep Dive shows no invented health or latency', (tester) async {
    await open(tester, 'models', repo: _Repo(registry()));
    await tester.tap(find.text('LightGBM').first);
    await tester.pumpAndSettle();
    expect(find.textContaining('ms (mock)'), findsNothing);
    expect(find.text('No serving path'), findsOneWidget);
  });

  testWidgets('without a registry the screens say Not verified instead of inventing status', (tester) async {
    await open(tester, 'strategies');
    expect(find.text('Not verified'), findsWidgets);
    expect(find.text('TESTED'), findsNothing);
  });
}
