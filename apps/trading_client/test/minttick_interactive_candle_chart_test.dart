import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_interactive_candle_chart.dart';

CustomerCandle bar(int index) {
  final open = 100.0 + index;
  return CustomerCandle(
    candleTime: DateTime.utc(2026, 1, 1).add(Duration(days: index)),
    open: open, high: open + 2, low: open - 2, close: open + 1,
    volume: 1000 + index,
    source: 'VERIFIED_STORED_HISTORY',
    fetchedAt: DateTime.utc(2026, 11, 21),
  );
}

Widget subject(List<CustomerCandle> bars, ValueChanged<int> selected,
    {double? quote}) =>
  MaterialApp(home: Scaffold(body: Center(
    child: SizedBox(width: 390, height: 360,
      child: MintTickInteractiveCandleChart(
        bars: bars, selectedIndex: null, onSelect: selected,
        lastTradedPrice: quote, liveQuote: false,
      )),
  )));

void main() {
  testWidgets('recent viewport, zoom, fit, latest and candle inspection',
      (tester) async {
    final bars = List.generate(120, bar);
    int? tapped;
    await tester.pumpWidget(subject(bars, (index) => tapped = index,
        quote: 1000)); // off-scale quote must not alter stored OHLCV
    await tester.pumpAndSettle();

    expect(find.text('79–120 / 120 bars'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('stock-chart-zoom-in')));
    await tester.pumpAndSettle();
    expect(find.text('93–120 / 120 bars'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('stock-chart-fit')));
    await tester.pumpAndSettle();
    expect(find.text('1–120 / 120 bars'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('stock-chart-latest')));
    await tester.pumpAndSettle();
    expect(find.text('79–120 / 120 bars'), findsOneWidget);

    final gesture = find.byKey(const ValueKey('stock-chart-gesture'));
    await tester.tapAt(tester.getTopLeft(gesture) + const Offset(170, 95));
    // The chart supports double-tap reset. Flush its short recognition
    // timer before the test completes so fake_async reports no pending work.
    await tester.pump(const Duration(seconds: 1));
    expect(tapped, isNotNull);
    expect(tapped, inInclusiveRange(78, 119));
  });

  testWidgets('pan and indicators are local viewport changes only',
      (tester) async {
    final bars = List.generate(120, bar);
    await tester.pumpWidget(subject(bars, (_) {}));
    await tester.pumpAndSettle();
    final gesture = find.byKey(const ValueKey('stock-chart-gesture'));
    await tester.drag(gesture, const Offset(125, 0));
    await tester.pumpAndSettle();
    expect(find.text('79–120 / 120 bars'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('stock-chart-volume')));
    await tester.tap(find.byKey(const ValueKey('stock-chart-sma20')));
    await tester.pumpAndSettle();
    expect(find.text('Volume'), findsOneWidget);
    expect(find.text('SMA20 bars ✓'), findsOneWidget);
    expect(bars, hasLength(120));

    await tester.tap(find.byKey(const ValueKey('stock-chart-fullscreen')));
    await tester.pumpAndSettle();
    expect(find.text('Historical OHLCV · expanded chart'), findsOneWidget);
    await tester.tap(find.byTooltip('Close expanded chart'));
    await tester.pumpAndSettle();
    expect(find.text('Historical OHLCV · expanded chart'), findsNothing);
  });
}
