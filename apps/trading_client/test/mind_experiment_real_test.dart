import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_ma_walkforward_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_ma_walkforward_repository_test.dart' show payload;

class _WalkRepo implements MindMaWalkForwardRepository {
  _WalkRepo(this.report);
  final MindMaWalkForwardReport report;
  @override
  Future<MindMaWalkForwardReport> load() async => report;
}

Future<void> openExperiment(WidgetTester tester, {MindMaWalkForwardRepository? walk}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 2000);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(walkForwardRepository: walk)));
  await tester.pumpAndSettle();
  final nav = find.byKey(const ValueKey('mind-route-experiment-detail'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Experiment Results shows the server walk-forward record and no fixture metrics', (tester) async {
    final report = MindMaWalkForwardReport.fromJson(payload());
    await openExperiment(tester, walk: _WalkRepo(report));
    expect(find.text('REAL SERVER RECORD · RESEARCH ONLY'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-experiment-walkforward')), findsOneWidget);
    expect(find.text('CAGR (synthetic)'), findsNothing);
    expect(find.text('MOCK FIXTURES · NOT MEASURED RESULTS'), findsNothing);
  });

  testWidgets('without a server record Experiment Results is labelled as fixtures', (tester) async {
    await openExperiment(tester);
    expect(find.text('MOCK FIXTURES · NOT MEASURED RESULTS'), findsOneWidget);
    expect(find.text('CAGR (synthetic)'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-experiment-walkforward')), findsNothing);
  });
}
