import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

void main() {
  testWidgets('Decision Lab states it is local arithmetic with no orders or saved results', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1440, 2000);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: MintickMindWorkspacePage()));
    await tester.pumpAndSettle();
    final nav = find.byKey(const ValueKey('mind-route-decision-lab'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();
    expect(find.text('LOCAL SCENARIO ARITHMETIC · NOT A SERVER DECISION'), findsOneWidget);
    expect(find.text('NO ORDERS · NO BROKER ACCESS · NOTHING IS SAVED'), findsOneWidget);
    expect(find.text('Live trading'), findsOneWidget);
  });
}
