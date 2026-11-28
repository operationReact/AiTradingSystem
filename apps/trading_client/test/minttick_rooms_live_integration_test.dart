import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_execution_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_room_production_workspace.dart';
import 'package:trading_client/src/theme/app_theme.dart';

// Phone-size room integration: mode, focus, Orders history, order handoff from
// the selected instrument, and unresolved-order recovery. All market and broker
// data is a fake. No request reaches a broker, and nothing is submitted.

const _roomId = '22222222-2222-2222-2222-222222222222';

const _reliance = InstrumentRecord(
  id: 101, exchange: 'NSE', exchangeSegment: 'NSE_CM',
  symbolToken: '2885', tradingSymbol: 'RELIANCE-EQ',
  name: 'Reliance Industries', instrumentType: 'EQ',
  expiry: null, strike: null, lotSize: 1, tickSize: .05,
);

const _sbin = InstrumentRecord(
  id: 202, exchange: 'NSE', exchangeSegment: 'NSE_CM',
  symbolToken: '3045', tradingSymbol: 'SBIN-EQ',
  name: 'State Bank of India', instrumentType: 'EQ',
  expiry: null, strike: null, lotSize: 1, tickSize: .05,
);

class _Market extends Fake implements HttpCustomerMarketRepository {
  @override
  Future<InstrumentSearchPage> searchPage({
    String? query, String? exchange, required int page,
  }) async =>
      InstrumentSearchPage(
        content: const [_reliance], page: page, size: 20,
        totalElements: 1, totalPages: 1, hasNext: false,
        catalogueVersion: 'test-active',
        activatedAt: DateTime.utc(2026, 11, 12),
      );

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
            candleTime: now.subtract(Duration(minutes: 5 * (4 - i))),
            open: 240 + i, high: 245 + i, low: 238 + i, close: 242 + i,
            volume: 1000 * (i + 1), source: 'STORED_TEST', fetchedAt: now),
      ],
    );
  }

  @override
  Future<CustomerLiveQuote> liveQuote(int instrumentId) async =>
      CustomerLiveQuote(
        instrumentId: instrumentId, exchange: 'NSE',
        tradingSymbol: instrumentId == _reliance.id ? 'RELIANCE-EQ' : 'SBIN-EQ',
        status: 'LIVE', ltp: 250.0, exchangeAt: DateTime.now(),
        source: 'ANGEL_ONE_SMARTAPI_WS_V2');

  @override
  Future<CustomerDayDetail> dayDetail(int instrumentId) async =>
      CustomerDayDetail(
        instrumentId: instrumentId, exchange: 'NSE',
        tradingSymbol: 'RELIANCE-EQ', ltp: 250, open: 244, high: 252,
        low: 243, previousClose: 246, volume: 1250000,
        exchangeAt: DateTime.now(), fetchedAt: DateTime.now(),
        source: 'STORED_LAST_DETAIL', averagePrice: 248,
        week52High: 310, week52Low: 205);
}

LiveEquityOrder _order(String id, LiveEquityOrderStatus status,
    {int filled = 0}) =>
    LiveEquityOrder(
      id: id, roomId: _roomId, instrumentId: 202, tradingSymbol: 'SBIN-EQ',
      side: 'BUY', quantity: 2, limitPrice: 250, status: status,
      filledQuantity: filled, brokerOrderId: null);

class _Execution extends Fake implements HttpCustomerExecutionRepository {
  _Execution(this.history);
  List<LiveEquityOrder> history;
  int reconciles = 0;
  int submits = 0;

  @override
  Future<Object?> preflight({
    required String roomId, required int instrumentId, required String side,
    required int quantity, required num limitPrice,
  }) async =>
      {
        'roomId': roomId, 'mode': 'LIVE', 'instrumentId': instrumentId,
        'executable': true, 'blockers': <String>[],
      };

  @override
  Future<List<LiveEquityOrder>> listLiveEquityOrders(String roomId) async =>
      List.of(history);

  @override
  Future<LiveEquityOrder> reconcileLiveEquityOrder(
      String roomId, String orderId) async {
    reconciles++;
    // The server resolves the unknown order to FILLED: confirmed by the broker.
    final resolved = _order(orderId, LiveEquityOrderStatus.filled, filled: 2);
    history = [for (final o in history) o.id == orderId ? resolved : o];
    return resolved;
  }

  @override
  Future<LiveEquityOrder> submitLiveEquityOrder({
    required String roomId, required String idempotencyKey,
    required int instrumentId, required String side, required int quantity,
    required num limitPrice,
  }) async {
    submits++;
    throw StateError('submit must not run in this test');
  }
}

LiveEquityCapability _executable() => LiveEquityCapability(
      roomId: _roomId, selectedBroker: 'UPSTOX', brokerConnected: true,
      orderRouteImplemented: true, executionEnabled: true,
      blockers: const [],
    );

TradingRoomDraft _room() => const TradingRoomDraft(
      id: _roomId, name: 'Morning live', focus: 'Intraday', paperBudget: 20000,
      riskPreference: 'Balanced', mode: 'LIVE', marketScope: 'EQUITY',
      status: 'DRAFT');

void main() {
  testWidgets('phone-size LIVE room: mode, history, handoff and unresolved recovery',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final execution = _Execution([
      _order('a1', LiveEquityOrderStatus.acknowledged),
      _order('u1', LiveEquityOrderStatus.unknown),
    ]);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickRoomProductionWorkspace(
        room: _room(),
        onBack: () {},
        onRefresh: () async {},
        onFundPaper: () async {},
        onSelectBroker: (_) async {},
        market: _Market(),
        execution: execution,
        liveEquityCapability: _executable(),
      ),
    ));
    await tester.pumpAndSettle();

    // Mode and focus are visible and unmistakable.
    expect(find.text('LIVE'), findsWidgets);
    expect(find.text('Intraday · NSE/BSE equities · MY ROOM'), findsOneWidget);
    expect(find.textContaining('LIVE · Broker authorization required'),
        findsOneWidget);
    expect(tester.takeException(), isNull);

    // The Orders tab shows server-confirmed states only.
    await tester.tap(find.byKey(const ValueKey('room-tab-orders')));
    await tester.pumpAndSettle();
    expect(find.text('ACCEPTED'), findsOneWidget);
    expect(find.text('UNKNOWN · RECONCILE'), findsOneWidget);
    expect(find.text('FILLED'), findsNothing);

    // Back to the trade desk. The chart's selected instrument feeds the ticket.
    await tester.tap(find.byKey(const ValueKey('room-tab-trade')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('BUY'));
    await tester.pumpAndSettle();

    // Order handoff: the ticket opens on the instrument chosen on the desk,
    // and the unresolved order blocks review until the server resolves it.
    expect(find.text('RELIANCE-EQ'), findsOneWidget);
    expect(find.byKey(const ValueKey('room-live-unresolved')), findsOneWidget);
    expect(tester.widget<FilledButton>(
        find.byKey(const ValueKey('room-ticket-review'))).onPressed, isNull);
    expect(execution.submits, 0);

    // Recovery: reconcile, then review unlocks.
    await tester.tap(find.byKey(const ValueKey('room-live-reconcile-u1')));
    await tester.pumpAndSettle();
    expect(execution.reconciles, 1);
    expect(find.byKey(const ValueKey('room-live-unresolved')), findsNothing);
    expect(tester.widget<FilledButton>(
        find.byKey(const ValueKey('room-ticket-review'))).onPressed, isNotNull);

    // Close the ticket. The workspace history now shows the server's FILLED.
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('room-tab-orders')));
    await tester.pumpAndSettle();
    expect(find.text('FILLED'), findsOneWidget);
    expect(find.text('UNKNOWN · RECONCILE'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
