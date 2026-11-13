import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_paper_cycle_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

class _Cycles implements MindPaperCycleRepository {
  @override
  Future<List<MindPaperRoom>> rooms() async => const [];
  @override
  Future<List<MindPaperInstrument>> instruments() async =>
      const [MindPaperInstrument(7, 'RELIANCE-EQ · NSE'), MindPaperInstrument(8, 'SBIN-EQ · NSE')];
  @override
  Future<MindPaperCycleResult> run({required String roomId, required int instrumentId, required String interval}) =>
      throw UnimplementedError();
  @override
  Future<List<MindPaperCycle>> recent(String roomId) async => const [];
}

class _Candles implements CustomerCandleRepository {
  _Candles({this.empty = false});
  final bool empty;
  final calls = <(int, String)>[];
  @override
  Future<CustomerCandlePage> candles({required int instrumentId, required String interval,
      required String fromDate, required String toDate}) async {
    calls.add((instrumentId, interval));
    final start = DateTime.utc(2026, 11, 12, 3, 45);
    return CustomerCandlePage(instrumentId: instrumentId, interval: interval, hasNext: false, items: [
      if (!empty)
        for (var i = 0; i < 25; i++)
          CustomerCandle(candleTime: start.add(Duration(minutes: 15 * i)), open: 100 + i, high: 101 + i,
              low: 99 + i, close: 100.5 + i, volume: 1000 + i, source: 'ANGEL_ONE_SMARTAPI', fetchedAt: start),
    ]);
  }
}

Future<void> open(WidgetTester tester, CustomerCandleRepository candles) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 2000);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(
      paperCycleRepository: _Cycles(), candleRepository: candles)));
  await tester.pumpAndSettle();
  final nav = find.byKey(const ValueKey('mind-route-charts'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Chart Intelligence draws stored candles and computed SMAs, never a fictional price', (tester) async {
    final candles = _Candles();
    await open(tester, candles);
    expect(find.byKey(const ValueKey('mind-real-chart')), findsOneWidget);
    expect(find.text('ANGEL_ONE_SMARTAPI'), findsOneWidget);
    expect(find.text('₹124.50'), findsOneWidget); // last stored close
    expect(find.text('25'), findsOneWidget);
    expect(find.text('Above'), findsOneWidget); // rising series
    expect(find.text('₹2,956.40'), findsNothing);
    expect(find.text('SIMULATED'), findsNothing);
    expect(candles.calls.single, (7, 'FIFTEEN_MINUTE'));
    await tester.tap(find.text('1D'));
    await tester.pumpAndSettle();
    expect(candles.calls.last, (7, 'ONE_DAY'));
    await tester.tap(find.text('SBIN-EQ · NSE'));
    await tester.pumpAndSettle();
    expect(candles.calls.last, (8, 'ONE_DAY'));
  });

  testWidgets('empty stored range is reported instead of simulated bars', (tester) async {
    await open(tester, _Candles(empty: true));
    expect(find.byKey(const ValueKey('mind-chart-empty')), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-real-chart')), findsNothing);
  });
}
