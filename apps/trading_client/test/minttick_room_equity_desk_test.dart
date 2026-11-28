import 'dart:async';

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

/// Serves verified-shaped market data. Fields mirror what the real HTTP
/// repository decodes; the desk never estimates anything from them.
class _FakeMarket extends Fake implements HttpCustomerMarketRepository {
  _FakeMarket({this.quoteStatus = 'LIVE', this.detailFails = false,
    this.liveLtp = 250.0});

  /// 'LIVE', 'LAST_CLOSE', or a no-price status like 'STALE'/'MARKET_CLOSED'.
  final String quoteStatus;
  final bool detailFails;
  final num liveLtp;
  /// When true, liveQuote throws (simulating a failed refresh).
  bool quoteThrows = false;
  /// When set, liveQuote waits on this gate so a refresh can be observed pending.
  Completer<void>? quoteGate;
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
            volume: 1000 * (i + 1), source: 'STORED_TEST', fetchedAt: now),
      ],
    );
  }

  @override
  Future<CustomerLiveQuote> liveQuote(int instrumentId) async {
    if (quoteGate != null) await quoteGate!.future;
    if (quoteThrows) throw StateError('live quote unavailable');
    final now = DateTime.now();
    return switch (quoteStatus) {
      'LIVE' => CustomerLiveQuote(
          instrumentId: instrumentId, exchange: 'NSE',
          tradingSymbol: 'RELIANCE-EQ', status: 'LIVE', ltp: liveLtp,
          exchangeAt: now, source: 'ANGEL_ONE_SMARTAPI_WS_V2'),
      'LAST_CLOSE' => CustomerLiveQuote(
          instrumentId: instrumentId, exchange: 'NSE',
          tradingSymbol: 'RELIANCE-EQ', status: 'LAST_CLOSE', ltp: 240.0,
          exchangeAt: now, source: 'STORED_LAST_DETAIL', changePercent: -0.8),
      _ => CustomerLiveQuote(
          instrumentId: instrumentId, exchange: 'NSE',
          tradingSymbol: 'RELIANCE-EQ', status: quoteStatus),
    };
  }

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
  testWidgets('intraday LIVE shows the live tick as the price with day context',
      (tester) async {
    await _pump(tester, 'Intraday');
    expect(find.text('INTRADAY'), findsOneWidget);
    // Price is the verified LIVE tick (250), not the day-detail snapshot field.
    expect(find.text('₹250.00'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsOneWidget);
    expect(find.text('DAY RANGE'), findsOneWidget);
    expect(find.text('VOLUME'), findsOneWidget);
    expect(find.textContaining('snapshot'), findsOneWidget); // day stats labelled
    // Swing-only affordances must not appear in an intraday room.
    expect(find.text('52-WEEK RANGE'), findsNothing);
    expect(find.byKey(const ValueKey('room-equity-watch-toggle')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('last-recorded quote is labelled, not shown as LIVE',
      (tester) async {
    await _pump(tester, 'Intraday', market: _FakeMarket(quoteStatus: 'LAST_CLOSE'));
    // The last-recorded tick (240), not the day-detail OPEN (244), is the price.
    expect(find.text('₹240.00'), findsOneWidget);
    expect(find.textContaining('LAST RECORDED ·'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('stale feed falls back to the labelled snapshot and stays visible',
      (tester) async {
    await _pump(tester, 'Intraday', market: _FakeMarket(quoteStatus: 'STALE'));
    // Price comes from the verified day snapshot (250) and is labelled SNAPSHOT.
    expect(find.text('₹250.00'), findsOneWidget);
    expect(find.textContaining('SNAPSHOT ·'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsNothing);
    // The degraded live-feed state stays visible even with a snapshot shown.
    expect(find.byKey(const ValueKey('room-equity-feed')), findsOneWidget);
    expect(find.text('Live feed stale'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('market-closed feed is visible beside the snapshot', (tester) async {
    await _pump(tester, 'Intraday',
        market: _FakeMarket(quoteStatus: 'MARKET_CLOSED'));
    expect(find.textContaining('SNAPSHOT ·'), findsOneWidget);
    expect(find.text('Market closed · live ticks paused'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a failed refresh never keeps a prior LIVE labelled LIVE',
      (tester) async {
    final market = _FakeMarket(quoteStatus: 'LIVE');
    await _pump(tester, 'Intraday', market: market);
    expect(find.textContaining('LIVE ·'), findsOneWidget);
    // The next quote request fails; refresh and settle.
    market.quoteThrows = true;
    await tester.tap(find.byKey(const ValueKey('room-equity-refresh')));
    await tester.pumpAndSettle();
    expect(find.textContaining('LIVE ·'), findsNothing);
    // The prior value is preserved only as last-recorded with its own stamp,
    // and the feed is clearly flagged unavailable.
    expect(find.textContaining('LAST RECORDED ·'), findsOneWidget);
    expect(find.text('₹250.00'), findsOneWidget);
    expect(find.textContaining('Live feed unavailable'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('range marker input is the live price, not the snapshot ltp',
      (tester) async {
    // Live tick (251) differs from the day-detail ltp (250). The marker's
    // semantics expose its actual input, so this verifies the source itself.
    await _pump(tester, 'Intraday', market: _FakeMarket(liveLtp: 251.0));
    expect(find.text('₹251.00'), findsOneWidget);
    expect(find.bySemanticsLabel('Current price marker ₹251.00'), findsOneWidget);
    expect(find.bySemanticsLabel('Current price marker ₹250.00'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('range marker falls back to the snapshot price when no tick exists',
      (tester) async {
    await _pump(tester, 'Intraday', market: _FakeMarket(quoteStatus: 'STALE'));
    // No verified tick, so the header and the marker both use the snapshot.
    expect(find.bySemanticsLabel('Current price marker ₹250.00'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a pending refresh never labels the prior tick LIVE',
      (tester) async {
    final market = _FakeMarket(quoteStatus: 'LIVE', liveLtp: 250.0);
    await _pump(tester, 'Intraday', market: market);
    expect(find.textContaining('LIVE ·'), findsOneWidget);
    market.quoteGate = Completer<void>();
    await tester.tap(find.byKey(const ValueKey('room-equity-refresh')));
    await tester.pump();
    // While the request is pending the prior tick is non-live, with its time.
    expect(find.textContaining('LIVE ·'), findsNothing);
    expect(find.textContaining('REFRESHING · last tick'), findsOneWidget);
    market.quoteGate!.complete();
    await tester.pumpAndSettle();
    // The fresh response restores the honest LIVE label.
    expect(find.textContaining('LIVE ·'), findsOneWidget);
    expect(find.textContaining('REFRESHING'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('swing room surfaces 52-week position, holding and watch toggle',
      (tester) async {
    await _pump(tester, 'Swing', market: _FakeMarket(quoteStatus: 'LAST_CLOSE'));
    expect(find.text('SWING'), findsOneWidget);
    expect(find.text('52-WEEK RANGE'), findsOneWidget);
    expect(find.text('PREV CLOSE'), findsOneWidget);
    expect(find.text('3 sh'), findsOneWidget);
    expect(find.byKey(const ValueKey('room-equity-watch-toggle')), findsOneWidget);
    expect(find.textContaining('Thesis & notes are not yet available'),
        findsOneWidget);
    expect(find.text('DAY RANGE'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('long-term room is not relabelled as swing', (tester) async {
    await _pump(tester, 'Long-term');
    expect(find.text('LONG-TERM'), findsOneWidget);
    expect(find.text('SWING'), findsNothing);
    expect(find.text('52-WEEK RANGE'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unknown/deferred focus falls back to a neutral equity view',
      (tester) async {
    await _pump(tester, 'Help me choose');
    expect(find.text('EQUITY'), findsOneWidget);
    expect(find.text('SWING'), findsNothing);
    // Positional-family layout (52-week context), never an intraday day range.
    expect(find.text('52-WEEK RANGE'), findsOneWidget);
    expect(find.text('DAY RANGE'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('day-detail failure shows a clear unavailable state, no fake data',
      (tester) async {
    await _pump(tester, 'Intraday', market: _FakeMarket(detailFails: true));
    expect(find.textContaining('Day statistics are unavailable'), findsOneWidget);
    expect(find.text('DAY RANGE'), findsNothing);
    // The LIVE tick price can still show even when day stats failed.
    expect(find.text('₹250.00'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('no price anywhere shows an honest unavailable status', (tester) async {
    await _pump(tester, 'Intraday',
        market: _FakeMarket(quoteStatus: 'MARKET_CLOSED', detailFails: true));
    expect(find.text('—'), findsWidgets);
    expect(find.text('MARKET CLOSED'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
