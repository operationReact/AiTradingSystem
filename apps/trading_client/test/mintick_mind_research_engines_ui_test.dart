import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_research_engine_fixtures.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

void main() {
  test('all five research candidates remain disabled planning records', () {
    final entries = MindResearchEngineFixtures.all;
    expect(entries.length, 5);
    expect(entries.map((e) => e.id).toSet().length, 5);
    expect(entries.map((e) => e.name).toSet(),
        {'VectorBT', 'Backtesting.py', 'NautilusTrader',
         'QuantConnect LEAN', 'Freqtrade'});
    expect(entries.every((e) => !e.connected && !e.orderAuthority), isTrue);
    for (final engine in entries) {
      expect(engine.prerequisites, isNotEmpty);
      expect(engine.sourceFiles, isNotEmpty);
      expect(engine.mintTickTouchpoints, isNotEmpty);
      expect(engine.targetScreens, isNotEmpty);
      expect(engine.plannedPrs, isNotEmpty);
      expect(engine.license, isNotEmpty);
      for (final id in engine.targetScreens) {
        expect(MintickMindScreens.all.any((screen) => screen.id == id),
            isTrue, reason: 'Invalid target screen $id');
      }
    }
  });

  testWidgets('research engines lab exposes honest placeholders and details',
      (tester) async {
    tester.view.physicalSize = const Size(1440, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(home: MintickMindWorkspacePage()),
    );
    await tester.pumpAndSettle();

    final nav = find.byKey(const ValueKey('mind-route-research-engines'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-screen-research-engines')),
        findsOneWidget);
    expect(find.text('PLANNED · NOT CONNECTED'), findsOneWidget);
    expect(find.text('NO BROKER / ORDER AUTHORITY'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-research-engine-VECTORBT')),
        findsOneWidget);
    expect(find.byKey(const ValueKey('mind-research-engine-NAUTILUS')),
        findsOneWidget);
    expect(find.text('Place Live Order'), findsNothing);

    await tester.ensureVisible(
        find.byKey(const ValueKey('mind-research-engine-NAUTILUS')));
    await tester.tap(
        find.byKey(const ValueKey('mind-research-engine-NAUTILUS')));
    await tester.pumpAndSettle();
    expect(find.text('Selected candidate · NautilusTrader'), findsOneWidget);
    expect(find.text('OSS-01, OSS-09–10, OSS-12–14'), findsOneWidget);
    expect(find.text('NOT CONNECTED'), findsOneWidget);
  });

  testWidgets('research navigation exposes the planned integration route',
      (tester) async {
    tester.view.physicalSize = const Size(1440, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(home: MintickMindWorkspacePage()),
    );
    await tester.pumpAndSettle();

    final research = find.byKey(const ValueKey('mind-route-research'));
    await tester.ensureVisible(research);
    await tester.tap(research);
    await tester.pumpAndSettle();

    final link = find.text('Research Engines Lab');
    await tester.ensureVisible(link.first);
    await tester.tap(link.first);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-screen-research-engines')),
        findsOneWidget);
    expect(find.text('Place Live Order'), findsNothing);
  });
}
