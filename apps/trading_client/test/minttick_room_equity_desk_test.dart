import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_room_equity_desk.dart';
import 'package:trading_client/src/theme/app_theme.dart';

const _reliance = InstrumentRecord(
  id: 101, exchange: 'NSE', exchangeSegment: 'NSE_CM',
  symbolToken: '2885', tradingSymbol: 'RELIANCE-EQ',
  name: 'Reliance Industries', instrumentType: 'EQ',
  expiry: null, strike: null, lotSize: 1, tickSize: .05,
);

/// Serves verified-shaped market data. Every field mirrors what the real
/// HTTP repository would decode; nothing here is estimated by the UI.
class _FakeMarket extends Fake implements HttpCustomerMarketRepository {
  bool detailFails = false;
  List<CustomerWatchlistItem> watched = const [];

  @override
  Future<CustomerCandlePage> candles({
    required int instrumentId, required String interval,
    required String fromDate, required String toDate,
  }) async {
    final now = DateTime.now();
    return CustomerCandlePage(
      instrumentId: instrumentId, interval: interval, hasNext: false,
      items: [
        for (var i = 0; i < 4; i++)
          CustomerCandle(
            candleTime: now.subtract(Duration(days: 4 - i)),
            open: 240 + i, high: 245 + i, low: 238 + i, close: 242 + i,
            volume: 1000 * (i + 1), source: 'STORED_TEST',
            fetchedAt: now),
      ],
    );
  }

  @override
  Future<CustomerLiveQuote> liveQuote(int instrumentId) async =>
      CustomerLiveQuote(
        instrumentId: instrumentId, exchange: 'NSE',
        tradingSymbol: 'RELIANCE-EQ', status: 'LIVE', ltp: 250.0,
        exchangeAt: DateTime.now(), source: 'ANGEL_ONE_SMARTAPI_WS_V2');

  @override
  Future<CustomerDayDetail> dayDetail(int instrumentId) async {
    if (detailFails) throw StateError('Day detail unavailable');
    return CustomerDayDetail(
      instrumentId: instrumentId, exchange: 'NSE',
      tradingSymbol: 'RELIANCE-EQ', ltp: 250, open: 244, high: 252,
      low: 243, previousClose: 246, volume: 1250000,
      exchangeAt: DateTime.now(), fetchedAt: DateTime.now(),
      source: 'STORED_LAST_DETAIL', averagePrice: 248,
      week52High: 310, week52Low: 205);
  }

  @override
  Future<CustomerWatchlist> watchlist() async =>
      CustomerWatchlist(items: watched, maxItems: 50);
}

Future<void> _pump(WidgetTester tester, String focus,
    {_FakeMarket? market}) async {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(
    theme: buildTheme(Brightness.dark),
    home: Scaffold(body: MintTickRoomEquityDesk(
      market: market ?? _FakeMarket(),
      focus: focus,
      initialInstrument: _reliance,
      onInstrument: (_) {},
      holdingFor: (_) => 3,
    )),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('intraday room surfaces session, day range and live change',
      (tester) async {
    await _pump(tester, 'Intraday');
    expect(find.text('INTRADAY'), findsOneWidget);
    expect(find.text('DAY RANGE'), findsOneWidget);
    expect(find.text('LAST'), findsOneWidget);
    // Live session + freshness is shown, never a fabricated "live" when absent.
    expect(find.textContaining('LIVE ·'), findsOneWidget);
    // Swing-only affordances must not appear in an intraday room.
    expect(find.text('52-WEEK RANGE'), findsNothing);
    expect(find.byKey(const ValueKey('room-equity-watch-toggle')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('swing room surfaces 52-week position, holding and watch toggle',
      (tester) async {
    await _pump(tester, 'Swing');
    expect(find.text('SWING'), findsOneWidget);
    expect(find.text('52-WEEK RANGE'), findsOneWidget);
    expect(find.text('PREV CLOSE'), findsOneWidget);
    expect(find.text('3 sh'), findsOneWidget); // holding surfaced
    expect(find.byKey(const ValueKey('room-equity-watch-toggle')), findsOneWidget);
    expect(find.textContaining('Thesis & notes are not yet available'),
        findsOneWidget);
    // Intraday-only day range is not the swing primary.
    expect(find.text('DAY RANGE'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('day-detail failure shows a clear unavailable state, no fake data',
      (tester) async {
    await _pump(tester, 'Intraday', market: _FakeMarket()..detailFails = true);
    expect(find.textContaining('Day statistics are unavailable'), findsOneWidget);
    expect(find.text('DAY RANGE'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
