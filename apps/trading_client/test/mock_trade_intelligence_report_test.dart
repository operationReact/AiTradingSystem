import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/review/mock_review_data.dart';
import 'package:trading_client/src/presentation/review/mock_trade_intelligence_model.dart';
import 'package:trading_client/src/presentation/review/mock_trade_intelligence_report.dart';

void _phoneViewport(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 844);
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

Widget _app(MockTradeIntelligenceRecord record) => MaterialApp(
      theme: ThemeData.dark(useMaterial3: true),
      home: MockTradeIntelligenceReport(record: record),
    );

Future<void> _tapTab(WidgetTester tester, String label) async {
  final finder = find.text(label);
  expect(finder, findsOneWidget);
  await tester.tap(finder);
  await tester.pumpAndSettle();
  expect(tester.takeException(), isNull);
}

Future<void> _visibleBack(WidgetTester tester) async {
  final back = find.byIcon(Icons.arrow_back_rounded).first;
  expect(back, findsOneWidget);
  await tester.tap(back);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('closed losing trade explains decision evidence failure and provenance', (tester) async {
    _phoneViewport(tester);
    final store = MockReviewStore();
    addTearDown(store.dispose);
    final record = MockTradeIntelligenceRecord.fromStore(
      store: store,
      symbol: 'RELIANCE',
      entryPrice: 2948,
      exitPrice: 2912,
      realizedPnl: -360,
      executed: true,
      closed: true,
      exitReason: 'Stop loss triggered',
    );

    await tester.pumpWidget(_app(record));
    await tester.pumpAndSettle();

    expect(find.text('Trade Intelligence Report'), findsOneWidget);
    expect(find.text('Why the system made this decision'), findsOneWidget);
    expect(find.text('Risk Engine justification'), findsOneWidget);
    expect(find.text('PAPER'), findsOneWidget);
    expect(find.text('MANUAL'), findsOneWidget);
    expect(find.text('User-confirmed AI opportunity'), findsOneWidget);
    expect(find.text('Open Transparency 360'), findsOneWidget);

    await tester.ensureVisible(find.text('Open Transparency 360'));
    await tester.tap(find.text('Open Transparency 360'));
    await tester.pumpAndSettle();
    expect(find.text('Transparency 360'), findsOneWidget);
    expect(find.text('Portfolio Risk'), findsOneWidget);
    expect(find.text('Model Health'), findsOneWidget);
    await _visibleBack(tester);

    await _tapTab(tester, 'Evidence');
    expect(find.text('Evidence used before / during the decision'), findsOneWidget);
    expect(find.text('Learned model'), findsOneWidget);
    expect(find.text('News / event intelligence'), findsOneWidget);
    expect(find.text('Fundamental / session context'), findsOneWidget);
    expect(find.text('Decision influence: 16% of fused evidence'), findsOneWidget);
    expect(find.text('Decision role: mandatory gate (not part of fused score)'), findsOneWidget);

    await _tapTab(tester, 'Confidence');
    expect(find.text('Confidence evolution'), findsOneWidget);
    expect(find.text('What would invalidate the trade?'), findsOneWidget);

    await _tapTab(tester, 'Outcome');
    expect(find.text('Why this trade failed'), findsOneWidget);
    expect(find.textContaining('Post-entry regime/thesis deterioration'), findsOneWidget);
    expect(find.text('Likely contributing factors'), findsOneWidget);

    await _tapTab(tester, 'Audit');
    expect(find.text('Immutable provenance'), findsOneWidget);
    expect(find.text('Pre-trade snapshot'), findsOneWidget);
    expect(find.text('Feature schema'), findsOneWidget);
    expect(find.text('PyTorch model'), findsOneWidget);
    expect(find.text('Gemini evidence'), findsOneWidget);
    expect(find.text('Risk decision'), findsOneWidget);
    expect(find.text('Data cutoff'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('open underwater trade is not incorrectly labeled failed', (tester) async {
    _phoneViewport(tester);
    final store = MockReviewStore();
    addTearDown(store.dispose);
    final record = MockTradeIntelligenceRecord.fromStore(
      store: store,
      symbol: 'HDFCBANK',
      entryPrice: 1651,
      executed: true,
      closed: false,
      exitReason: 'Open · thesis under pressure',
    );

    await tester.pumpWidget(_app(record));
    await tester.pumpAndSettle();
    await _tapTab(tester, 'Outcome');

    expect(find.text('Current thesis health'), findsOneWidget);
    expect(find.textContaining('Not failed yet'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('cancelled trade preserves pre-trade reasoning without inventing an outcome', (tester) async {
    _phoneViewport(tester);
    final store = MockReviewStore();
    addTearDown(store.dispose);
    final record = MockTradeIntelligenceRecord.fromStore(
      store: store,
      symbol: 'INFY',
      entryPrice: 1542,
      executed: false,
      closed: false,
      cancelled: true,
      exitReason: 'Order cancelled before execution',
    );

    await tester.pumpWidget(_app(record));
    await tester.pumpAndSettle();
    expect(find.text('User cancelled before execution'), findsOneWidget);
    await _tapTab(tester, 'Outcome');

    expect(find.text('Why the trade did not execute'), findsOneWidget);
    expect(find.textContaining('capital was not exposed'), findsWidgets);
    expect(tester.takeException(), isNull);
  });
}
