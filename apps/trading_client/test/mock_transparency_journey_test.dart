import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/review/mock_review_data.dart';
import 'package:trading_client/src/presentation/review/mock_transparency_journey.dart';

// Exact-head acceptance for the contextual 10-screen Decision Audit.
void _phoneViewport(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 844);
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

Widget _app({MockTransparencyStart start = MockTransparencyStart.preTrade, double pnl = 1430}) {
  final store = MockReviewStore();
  final quote = store.quotes.firstWhere((item) => item.symbol == 'RELIANCE');
  final opportunity = store.opportunities.firstWhere((item) => item.symbol == 'RELIANCE');
  return MaterialApp(
    debugShowCheckedModeBanner: false,
    themeMode: ThemeMode.dark,
    darkTheme: ThemeData.dark(),
    home: MockTransparencyJourney(
      quote: quote,
      opportunity: opportunity,
      side: 'BUY',
      quantity: 30,
      entryPrice: opportunity.entry,
      exitPrice: pnl >= 0 ? opportunity.target - 8 : opportunity.stop - 6,
      realizedPnl: pnl,
      executed: start != MockTransparencyStart.preTrade,
      closed: start == MockTransparencyStart.outcome,
      monitorStage: 4,
      exitReason: pnl >= 0 ? 'AI target reached' : 'Setup invalidated',
      start: start,
    ),
  );
}

Future<void> _tap(WidgetTester tester, String text) async {
  final target = find.text(text).last;
  expect(target, findsOneWidget);
  await tester.ensureVisible(target);
  await tester.tap(target);
  await tester.pumpAndSettle();
  expect(tester.takeException(), isNull);
}

void main() {
  testWidgets('10-screen decision audit explains placement, holding and outcome at 390x844', (tester) async {
    _phoneViewport(tester);
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    expect(find.text('Pre-Trade Snapshot'), findsOneWidget);
    expect(find.text('Frozen at 10:31:02'), findsOneWidget);
    expect(find.text('Feature snapshot'), findsOneWidget);

    await _tap(tester, 'Next: Thresholds');
    expect(find.text('Decision Thresholds'), findsOneWidget);
    expect(find.text('5 / 5 mandatory decision gates passed'), findsOneWidget);

    await _tap(tester, 'Next: Model Agreement');
    expect(find.text('Model Agreement'), findsOneWidget);
    expect(find.text('5 supporting · 1 neutral · 0 opposing'), findsOneWidget);

    await _tap(tester, 'Next: Trade vs Wait');
    expect(find.text('Why Trade vs Wait?'), findsOneWidget);
    expect(find.text('RISK ENGINE REJECTS'), findsOneWidget);

    await _tap(tester, 'Next: Replay');
    expect(find.text('Trade Replay'), findsOneWidget);
    expect(find.text('Market scan detects setup'), findsOneWidget);

    await _tap(tester, 'Next: Live Thesis');
    expect(find.text('Live Thesis Monitor'), findsOneWidget);
    expect(find.text('Thesis Health'), findsOneWidget);

    await _tap(tester, 'Next: Expected vs Actual');
    expect(find.text('Expected vs Actual'), findsOneWidget);
    expect(find.text('Forecast vs realized path'), findsOneWidget);

    await _tap(tester, 'Next: Outcome Attribution');
    expect(find.text('Profit Attribution'), findsOneWidget);
    expect(find.text('Why this trade likely worked'), findsOneWidget);

    await _tap(tester, 'LOSS EXAMPLE');
    expect(find.text('Loss Attribution'), findsOneWidget);
    expect(find.text('Why this trade likely failed'), findsOneWidget);
    expect(find.textContaining('Confidence is a probability estimate'), findsOneWidget);

    await _tap(tester, 'Next: Execution Quality');
    expect(find.text('Execution Quality'), findsOneWidget);
    expect(find.text('Signal reference price'), findsOneWidget);

    await _tap(tester, 'Next: Learning Record');
    expect(find.text('Learning Record'), findsOneWidget);
    expect(find.text('Automatic retraining'), findsOneWidget);
    expect(find.text('Automatic model promotion'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('loss outcome opens directly into outcome transparency', (tester) async {
    _phoneViewport(tester);
    await tester.pumpWidget(_app(start: MockTransparencyStart.outcome, pnl: -620));
    await tester.pumpAndSettle();

    expect(find.text('Expected vs Actual'), findsOneWidget);
    await _tap(tester, 'Next: Outcome Attribution');
    expect(find.text('Loss Attribution'), findsOneWidget);
    expect(find.text('Why this trade likely failed'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
