import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_agent_fixtures.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

void main() {
  test('agent research fixtures are complete and internally consistent', () {
    expect(MindAgentFixtures.horizons.length, 8);
    expect(MindAgentFixtures.votes.length, 5);
    expect(MindAgentFixtures.votes.fold<int>(0, (n, v) => n + v.weight), 100);
    expect(MindAgentFixtures.evidence.length, 7);
    expect(MindAgentFixtures.incidents.length, 4);
    expect(MindAgentFixtures.incidents.map((i) => i.id).toSet().length, 4);
    for (final entry in MindAgentFixtures.regimeWeights.entries) {
      expect(entry.value.length, 4);
      expect(entry.value.reduce((a, b) => a + b),
        entry.key == 'Data Uncertain' ? 0 : 100);
    }
  });

  test('all ten enhanced agent screens have native route descriptors', () {
    const ids = <String>[
      'intelligence-hub','agent-council','agent-evidence','regime-weights',
      'signal-scorecard','confidence-lab','shadow-arena',
      'institutional-flow','daily-verdict','culprit-catcher',
    ];
    final all = MintickMindScreens.all.map((s) => s.id).toSet();
    expect(MintickMindScreens.all.length, 42);
    for (final id in ids) {
      expect(all.contains(id), isTrue, reason: 'Missing agent route: $id');
    }
  });

  testWidgets('agent council and culprit catcher navigate without order actions',
    (tester) async {
    tester.view.physicalSize = const Size(1440, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: MintickMindWorkspacePage()));
    await tester.pumpAndSettle();

    final council = find.byKey(const ValueKey('mind-route-agent-council'));
    await tester.ensureVisible(council);
    await tester.tap(council);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-screen-agent-council')), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-agent-symbol')), findsOneWidget);
    expect(find.text('Place Live Order'), findsNothing);

    final catcher = find.byKey(const ValueKey('mind-route-culprit-catcher'));
    await tester.ensureVisible(catcher);
    await tester.tap(catcher);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-screen-culprit-catcher')), findsOneWidget);
    expect(find.textContaining('INC-102'), findsWidgets);
    expect(find.text('Place Live Order'), findsNothing);
  });
}
