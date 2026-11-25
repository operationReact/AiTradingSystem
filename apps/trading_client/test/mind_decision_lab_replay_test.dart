import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_strategy_replay_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_strategy_replay_repository_test.dart' show payload;

class _ReplayRepo implements MindStrategyReplayRepository {
  _ReplayRepo(this.replay);
  final MindStrategyReplay replay;
  @override
  Future<MindStrategyReplay> load() async => replay;
}

Future<void> openDecisionLab(WidgetTester tester, {MindStrategyReplayRepository? replay}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 2400);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(strategyReplayRepository: replay)));
  await tester.pumpAndSettle();
  final nav = find.byKey(const ValueKey('mind-route-decision-lab'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Decision Lab shows the server replay record above the labelled local simulation', (tester) async {
    await openDecisionLab(tester, replay: _ReplayRepo(MindStrategyReplay.fromJson(payload())));
    expect(find.text('Decision Lab'), findsWidgets);
    expect(find.byKey(const ValueKey('mind-decision-lab-replay')), findsOneWidget);
    expect(find.text('SERVER RECORD · RESEARCH ONLY · NO ORDERS'), findsOneWidget);
    expect(find.text('LOCAL SIMULATION BELOW · NOT A RECORDED DECISION'), findsOneWidget);
    expect(find.text('LOCAL SCENARIO ARITHMETIC · NOT A SERVER DECISION'), findsOneWidget);
  });

  testWidgets('without a replay repository Decision Lab is only the labelled local simulation', (tester) async {
    await openDecisionLab(tester);
    expect(find.byKey(const ValueKey('mind-decision-lab-replay')), findsNothing);
    expect(find.text('Local simulation · no server decision recorded'), findsOneWidget);
    expect(find.text('LOCAL SCENARIO ARITHMETIC · NOT A SERVER DECISION'), findsOneWidget);
  });
}
