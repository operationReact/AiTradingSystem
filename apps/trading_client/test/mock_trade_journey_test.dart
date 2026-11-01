import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/review/mock_review_data.dart';
import 'package:trading_client/src/presentation/review/mock_trade_journey.dart';

void _phoneViewport(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 844);
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

Widget _app(MockReviewStore store) => MaterialApp(
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData.dark(),
      home: Scaffold(
        backgroundColor: const Color(0xFF020817),
        body: SafeArea(
          child: MockTradeJourney(
            store: store,
            quote: store.quotes.firstWhere((item) => item.symbol == 'RELIANCE'),
            opportunity: store.opportunities.firstWhere((item) => item.symbol == 'RELIANCE'),
            onExit: () {},
          ),
        ),
      ),
    );

Future<void> _tap(WidgetTester tester, String text) async {
  final target = find.text(text).last;
  expect(target, findsOneWidget);
  await tester.ensureVisible(target);
  await tester.tap(target);
  await tester.pumpAndSettle();
  expect(tester.takeException(), isNull);
}

void main() {
  testWidgets('11-screen mock trading journey is complete and stateful', (tester) async {
    _phoneViewport(tester);
    final store = MockReviewStore();
    addTearDown(store.dispose);
    final initialOrders = store.orders.length;
    final initialPositions = store.positions.length;
    final initialNotifications = store.notifications.length;

    await tester.pumpWidget(_app(store));
    await tester.pumpAndSettle();

    expect(find.text('Stock Detail'), findsOneWidget);
    expect(find.text('RELIANCE'), findsWidgets);
    expect(find.text('MOCK DATA'), findsOneWidget);

    await _tap(tester, 'Analyze with AI');
    expect(find.text('AI Analysis'), findsOneWidget);
    expect(find.text('Model signals'), findsOneWidget);

    await _tap(tester, 'View AI Opportunity');
    expect(find.text('AI Opportunity'), findsOneWidget);
    expect(find.text('Why AI picked this'), findsOneWidget);

    await _tap(tester, 'Build PAPER Trade');
    expect(find.text('PAPER Trade'), findsOneWidget);
    expect(find.text('Estimated capital'), findsOneWidget);

    await _tap(tester, 'Review Order');
    expect(find.text('Review Order'), findsOneWidget);
    expect(find.text('Max loss'), findsOneWidget);

    await _tap(tester, 'Run Risk Check');
    expect(find.text('Risk Check'), findsOneWidget);
    expect(find.text('Risk Approved'), findsOneWidget);

    await _tap(tester, 'Place PAPER Order');
    expect(find.text('Order Executed'), findsOneWidget);
    expect(store.orders.length, initialOrders + 1);
    expect(store.positions.length, initialPositions + 1);
    expect(store.notifications.length, initialNotifications + 1);

    await _tap(tester, 'View Position');
    expect(find.text('Position Detail'), findsOneWidget);
    expect(find.text('Unrealized P&L'), findsOneWidget);

    await _tap(tester, 'Monitor with AI');
    expect(find.text('AI Trade Monitor'), findsOneWidget);
    expect(find.text('AI Monitoring Active'), findsOneWidget);

    await _tap(tester, 'Simulate Target Approaching');
    expect(find.text('TARGET NEAR'), findsOneWidget);
    await _tap(tester, 'Exit Position');
    expect(find.text('Exit Position'), findsOneWidget);

    await _tap(tester, 'Confirm PAPER Exit');
    expect(find.text('Trade Result'), findsOneWidget);
    expect(find.text('AI learning summary'), findsOneWidget);
    expect(store.orders.length, initialOrders + 2);
    expect(store.positions.length, initialPositions);
    expect(store.notifications.length, initialNotifications + 2);
    expect(tester.takeException(), isNull);
  });
}
