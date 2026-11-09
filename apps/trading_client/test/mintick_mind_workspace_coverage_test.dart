import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_fixtures.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

void main() {
  test('all 32 interactive HTML prototype screens have native route descriptors', () {
    expect(MintickMindScreens.all.length, 32);
    expect(MintickMindScreens.all.map((r)=>r.id).toSet().length, 32);
    expect(MintickMindScreens.all.any((r)=>r.id=='options-chain'), isTrue);
    expect(MintickMindScreens.all.any((r)=>r.id=='multibagger'), isTrue);
    expect(MintickMindScreens.all.any((r)=>r.id=='decision-lab'), isTrue);
    expect(MintickMindScreens.all.any((r)=>r.id=='dependency-map'), isFalse);
    expect(MintickMindScreens.all.any((r)=>r.id=='dependencies'), isTrue);
  });

  test('mock strategies and ML models retain complete catalogue', () {
    expect(MindFixtures.strategies.length, 232);
    expect(MindFixtures.strategyFamilies.length, 20);
    expect(MindFixtures.models.length, 18);
    expect(MindFixtures.strategies.map((s)=>s.name).toSet().length, 232);
    for (final family in MindFixtures.strategyFamilies) {
      expect(MindFixtures.strategies.where((s)=>s.family==family), isNotEmpty);
    }
    expect(MindFixtures.strategies.any((s)=>s.name=='Long Straddle'), isTrue);
    expect(MindFixtures.strategies.any((s)=>s.name=='Quality Compounders'), isTrue);
    expect(MindFixtures.strategies.any((s)=>s.name=='VWAP Reclaim'), isTrue);
  });

  testWidgets('owner workspace opens Mintick Mind overview with mock disclaimer', (tester) async {
    tester.view.physicalSize=const Size(1179,2556);
    tester.view.devicePixelRatio=3;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(const MaterialApp(home:MintickMindWorkspacePage()));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-original-overview')), findsOneWidget);
    expect(find.text('Personal Trading Intelligence'), findsOneWidget);
    expect(find.text('Decision Engine'), findsOneWidget);
    expect(find.text('Place Live Order'), findsNothing);
  });

  testWidgets('desktop workspace navigates to strategy universe', (tester) async {
    tester.view.physicalSize=const Size(1440,900);
    tester.view.devicePixelRatio=1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home:MintickMindWorkspacePage()));
    await tester.pumpAndSettle();

    final nav=find.byKey(const ValueKey('mind-route-strategies'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-screen-strategies')),findsOneWidget);
    expect(find.textContaining('232 strategy templates'),findsNothing);
    expect(find.textContaining('232'),findsWidgets);
  });

  testWidgets('customer can inspect non-executable derivatives and risk pages', (tester) async {
    tester.view.physicalSize=const Size(1440,900);
    tester.view.devicePixelRatio=1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home:MintickMindWorkspacePage()));
    await tester.pumpAndSettle();

    final chain=find.byKey(const ValueKey('mind-route-options-chain'));
    await tester.ensureVisible(chain);
    await tester.tap(chain);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-screen-options-chain')),findsOneWidget);
    expect(find.text('Call LTP'),findsOneWidget);

    final risk=find.byKey(const ValueKey('mind-route-risk'));
    await tester.ensureVisible(risk);
    await tester.tap(risk);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-screen-risk')),findsOneWidget);
    expect(find.text('Place Live Order'),findsNothing);
  });
}
