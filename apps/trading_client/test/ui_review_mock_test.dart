import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/review/mock_review_app.dart';

void _phoneViewport(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 844);
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

Widget _app() => MaterialApp(
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData.dark(),
      home: const MockReviewApp(),
    );

Future<void> _tapBottomLabel(WidgetTester tester, String label) async {
  final target = find.text(label).last;
  await tester.ensureVisible(target);
  await tester.tap(target);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('review APK exposes populated primary post-login screens', (tester) async {
    _phoneViewport(tester);
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    expect(find.text('AiTradingSystem'), findsOneWidget);
    expect(find.text('AI Market Pulse'), findsOneWidget);
    expect(find.text('RELIANCE'), findsWidgets);

    await _tapBottomLabel(tester, 'Markets');
    expect(find.text('Markets'), findsWidgets);
    expect(find.text('NIFTY'), findsWidgets);

    await _tapBottomLabel(tester, 'AI');
    expect(find.text('AI Research'), findsOneWidget);
    expect(find.text('AI Opportunity Scanner'), findsOneWidget);
    expect(find.text('Top opportunities'), findsOneWidget);

    await _tapBottomLabel(tester, 'Portfolio');
    expect(find.text('PAPER Portfolio'), findsOneWidget);
    expect(find.text('Open Positions'), findsOneWidget);

    await _tapBottomLabel(tester, 'More');
    expect(find.text('Broker Connect'), findsOneWidget);
    expect(find.text('Paper Trading'), findsOneWidget);
    expect(find.text('Risk Controls'), findsOneWidget);
    expect(find.text('Backtests'), findsOneWidget);
    expect(find.text('Notifications'), findsOneWidget);
    expect(find.text('Settings'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('mock interactions update the shared review state', (tester) async {
    _phoneViewport(tester);
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    await _tapBottomLabel(tester, 'AI');
    final reliance = find.text('RELIANCE').last;
    await tester.ensureVisible(reliance);
    await tester.tap(reliance);
    await tester.pumpAndSettle();

    expect(find.text('Opportunity Details'), findsOneWidget);
    expect(find.text('Strong Buy'), findsWidgets);

    final trade = find.text('Add to Paper Trade');
    await tester.ensureVisible(trade);
    await tester.tap(trade);
    await tester.pumpAndSettle();

    expect(find.text('Portfolio'), findsWidgets);
    expect(find.text('PAPER Portfolio'), findsOneWidget);

    await _tapBottomLabel(tester, 'More');
    final broker = find.text('Broker Connect');
    await tester.ensureVisible(broker);
    await tester.tap(broker);
    await tester.pumpAndSettle();
    expect(find.text('Connect Broker'), findsOneWidget);

    final connect = find.text('Connect Angel One');
    await tester.ensureVisible(connect);
    await tester.tap(connect);
    await tester.pumpAndSettle();
    expect(find.text('CONNECTED'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('all secondary mock screens are reachable from More', (tester) async {
    _phoneViewport(tester);
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await _tapBottomLabel(tester, 'More');

    for (final screen in <String>['Paper Trading', 'Risk Controls', 'Backtests', 'Notifications', 'Settings']) {
      final link = find.text(screen).last;
      await tester.ensureVisible(link);
      await tester.tap(link);
      await tester.pumpAndSettle();
      expect(find.text(screen), findsWidgets);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byIcon(Icons.arrow_back_rounded).first);
      await tester.pumpAndSettle();
      expect(find.text('More'), findsWidgets);
    }
  });
}
