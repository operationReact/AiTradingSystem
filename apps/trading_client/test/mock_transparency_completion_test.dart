import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/review/mock_review_data.dart';
import 'package:trading_client/src/presentation/review/mock_trade_intelligence_model.dart';
import 'package:trading_client/src/presentation/review/mock_transparency_completion.dart';

void _phoneViewport(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 844);
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

Future<void> _visibleBack(WidgetTester tester) async {
  final back = find.byIcon(Icons.arrow_back_rounded).first;
  expect(back, findsOneWidget);
  await tester.tap(back);
  await tester.pumpAndSettle();
}

Widget _hub(MockTradeIntelligenceRecord record, {Key? key}) => MaterialApp(
      key: key,
      theme: ThemeData.dark(useMaterial3: true),
      home: MockTransparencyCompletionHub(record: record),
    );

void main() {
  testWidgets('Transparency 360 exposes every final completeness surface at phone width', (tester) async {
    _phoneViewport(tester);
    final store = MockReviewStore();
    addTearDown(store.dispose);
    final record = MockTradeIntelligenceRecord.fromStore(
      store: store,
      symbol: 'RELIANCE',
      entryPrice: 2948,
      executed: true,
      closed: false,
      exitReason: 'Position still open',
    );

    final pages = <String>[
      'Portfolio Risk',
      'Scenario Lab',
      'Model Health',
      'Similar Trades',
      'Position Sizing',
      'Event Risk',
      'Evidence Alerts',
      'Trade Journal',
      'Audit Export',
    ];

    for (final page in pages) {
      await tester.pumpWidget(_hub(record, key: UniqueKey()));
      await tester.pumpAndSettle();
      expect(find.text('Transparency 360'), findsOneWidget);
      expect(find.textContaining('frozen pre-trade snapshot'), findsOneWidget);

      final finder = find.text(page).first;
      await tester.ensureVisible(finder);
      await tester.pumpAndSettle();
      await tester.tap(finder);
      await tester.pumpAndSettle();

      expect(find.text(page), findsOneWidget);
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets('Scenario, sizing, alerts and export remain interactive', (tester) async {
    _phoneViewport(tester);
    final store = MockReviewStore();
    addTearDown(store.dispose);
    final record = MockTradeIntelligenceRecord.fromStore(
      store: store,
      symbol: 'RELIANCE',
      entryPrice: 2948,
      executed: true,
    );

    await tester.pumpWidget(_hub(record));
    await tester.pumpAndSettle();

    Future<void> open(String title) async {
      final finder = find.text(title).first;
      await tester.ensureVisible(finder);
      await tester.pumpAndSettle();
      await tester.tap(finder);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }

    await open('Scenario Lab');
    await tester.tap(find.text('Gap -2%'));
    await tester.pumpAndSettle();
    expect(find.text('STOP / GAP RISK'), findsOneWidget);
    await _visibleBack(tester);

    await open('Position Sizing');
    expect(find.textContaining('Quantity comes from'), findsOneWidget);
    expect(find.textContaining('Calculated quantity'), findsOneWidget);
    await _visibleBack(tester);

    await open('Evidence Alerts');
    expect(find.textContaining('Confidence 81% → 72%'), findsOneWidget);
    expect(find.textContaining('Regime turned adverse'), findsOneWidget);
    await _visibleBack(tester);

    await open('Audit Export');
    await tester.ensureVisible(find.text('Generate review report'));
    await tester.tap(find.text('Generate review report'));
    await tester.pump();
    expect(find.textContaining('Review export generated'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
