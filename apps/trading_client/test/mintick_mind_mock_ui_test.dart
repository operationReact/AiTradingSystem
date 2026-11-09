import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_fixtures.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mintick_mind_page.dart';

void main() {
  test('fixture catalog preserves the HTML strategy family and model counts', () {
    expect(MindFixtures.strategyFamilies.length, 20);
    expect(MindFixtures.strategies.length, 232);
    expect(MindFixtures.models.length, 18);
    expect(MindFixtures.strategies.map((e) => e.id).toSet().length, 232);
    expect(MindFixtures.strategies.any((e) => e.name == 'VWAP Reclaim'), isTrue);
    expect(MindFixtures.strategies.any((e) => e.name == 'Long Straddle'), isTrue);
    expect(MindFixtures.strategies.any((e) => e.name == 'Quality Compounders'), isTrue);
  });

  testWidgets('Mintick Mind overview renders the approved mock UI hierarchy', (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: MintickMindPage()));
    expect(find.byKey(const ValueKey('mind-engine-hero')), findsOneWidget);
    expect(find.text('Personal Trading Intelligence'), findsOneWidget);
    expect(find.text('Decision Engine'), findsOneWidget);
    expect(find.text('Top Active Strategies'), findsOneWidget);
    expect(find.text('Mock notifications'), findsNothing); // Tooltip only.
  });

  testWidgets('strategy search and model page stay local and browseable', (tester) async {
    tester.view.physicalSize = const Size(1290, 2796);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: MintickMindPage()));
    await tester.tap(find.byKey(const ValueKey('mind-tab-1')));
    await tester.pumpAndSettle();
    expect(find.text('232 strategy templates across 20 families'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('mind-strategy-search')), 'VWAP');
    await tester.pumpAndSettle();
    expect(find.text('VWAP Reclaim'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('mind-tab-2')));
    await tester.pumpAndSettle();
    expect(find.text('Model Laboratory'), findsOneWidget);
    expect(find.text('LightGBM'), findsWidgets);
  });

  testWidgets('decision explorer stays mock and never exposes a broker action', (tester) async {
    tester.view.physicalSize = const Size(1179, 2556);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: MintickMindPage()));
    await tester.tap(find.byKey(const ValueKey('mind-tab-3')));
    await tester.pumpAndSettle();
    expect(find.text('Decision Explorer'), findsOneWidget);
    expect(find.textContaining('No broker connection'), findsOneWidget);
    expect(find.text('Place Live Order'), findsNothing);
  });
}
