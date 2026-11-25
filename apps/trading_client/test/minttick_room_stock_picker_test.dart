import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_trading_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_room_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_room_stock_picker.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_room_order_sheet.dart';
import 'package:trading_client/src/theme/app_theme.dart';

const _id = '11111111-1111-1111-1111-111111111111';

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
const _derivative = InstrumentRecord(
  id: 303, exchange: 'NFO', exchangeSegment: 'NFO',
  symbolToken: '20', tradingSymbol: 'RELIANCE25NOVFUT',
  name: 'Reliance Futures', instrumentType: 'FUTSTK',
  expiry: null, strike: null, lotSize: 75, tickSize: .05,
);

class _FakeMarket extends Fake implements HttpCustomerMarketRepository {
  final queries = <String>[];
  bool fail = false;
  List<InstrumentRecord> results = const [_reliance, _sbin, _derivative];

  @override
  Future<InstrumentSearchPage> searchPage({
    String? query, String? exchange, required int page,
  }) async {
    queries.add(query ?? '');
    if (fail) throw StateError('Backend unavailable');
    final text = (query ?? '').toUpperCase();
    final hits = results.where((item) =>
      item.tradingSymbol.toUpperCase().contains(text) ||
      item.name.toUpperCase().contains(text)).toList();
    return InstrumentSearchPage(
      content: hits, page: page, size: 20,
      totalElements: hits.length,
      totalPages: hits.isEmpty ? 0 : 1, hasNext: false,
      catalogueVersion: 'test-active',
      activatedAt: DateTime.utc(2026, 11, 12),
    );
  }

  @override
  Future<CustomerLiveQuote> liveQuote(int instrumentId) async {
    final symbol = instrumentId == _sbin.id ? _sbin : _reliance;
    return CustomerLiveQuote(
      instrumentId: instrumentId, exchange: 'NSE',
      tradingSymbol: symbol.tradingSymbol,
      status: 'LIVE', ltp: 250.0, exchangeAt: DateTime.now(),
      source: 'ANGEL_ONE_SMARTAPI_WS_V2',
    );
  }
}

class _FakePaper extends Fake implements HttpCustomerPaperTradingRepository {
  int submits = 0;
  String? symbolSide;
  int? quantitySubmitted;
  @override
  Future<CustomerPaperOrder> submit({
    required String roomId,
    required String idempotencyKey,
    required int instrumentId,
    required String side,
    required int quantity,
    required String limitPrice,
  }) async {
    submits++;
    symbolSide = side;
    quantitySubmitted = quantity;
    return CustomerPaperOrder(
      id: 'fill', roomId: roomId, instrumentId: instrumentId,
      tradingSymbol: instrumentId == 202 ? 'SBIN-EQ' : 'RELIANCE-EQ',
      exchange: 'NSE', side: side, quantity: quantity,
      fillPrice: 250, limitPrice: num.parse(limitPrice),
      notionalPaise: 25000 * quantity, feesPaise: 100,
      cashAfterPaise: 1000000, exchangeAt: DateTime.now(),
    );
  }
}

CustomerPaperWallet _wallet() => CustomerPaperWallet(
  roomId: _id, roomStatus: 'PAPER_FUNDED',
  initialCashPaise: 2000000, availableCashPaise: 2000000,
  reservedCashPaise: 0, realizedPnlPaise: 0, halted: false,
  executionEnabled: false, updatedAt: DateTime.now(),
);

void main() {
  test('stock picker rejects derivatives and invalid NSE rows', () {
    expect(MintTickRoomStockPicker.cashEquity(_reliance), isTrue);
    expect(MintTickRoomStockPicker.cashEquity(_derivative), isFalse);
    expect(MintTickRoomStockPicker.cashEquity(const InstrumentRecord(
      id: 9, exchange: 'NSE', exchangeSegment: 'NFO',
      symbolToken: '9', tradingSymbol: 'NOT-EQ',
      name: 'Wrong Segment', instrumentType: 'EQ',
      expiry: null, strike: null, lotSize: 1, tickSize: .05,
    )), isFalse);
  });

  testWidgets('searching as user types returns selectable verified equity',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final market = _FakeMarket();
    InstrumentRecord? chosen;
    await tester.pumpWidget(MaterialApp(theme: buildTheme(Brightness.dark),
      home: Scaffold(body: Builder(builder: (context) => FilledButton(
        onPressed: () async {
          chosen = await showModalBottomSheet<InstrumentRecord>(
            context: context, isScrollControlled: true,
            builder: (_) => MintTickRoomStockPicker(market: market));
        },
        child: const Text('Pick stock')))),
    ));
    await tester.tap(find.text('Pick stock'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('room-symbol-search')), 'rel');
    await tester.pump(const Duration(milliseconds: 320));
    await tester.pumpAndSettle();
    expect(market.queries, contains('rel'));
    expect(find.text('RELIANCE-EQ'), findsOneWidget);
    expect(find.text('RELIANCE25NOVFUT'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('room-stock-result-101')));
    await tester.pumpAndSettle();
    expect(chosen?.id, 101);
  });

  testWidgets('empty search and service errors are explicitly visible',
      (tester) async {
    final market = _FakeMarket();
    await tester.pumpWidget(MaterialApp(theme: buildTheme(Brightness.dark),
      home: Scaffold(body: Builder(builder: (context) => FilledButton(
        onPressed: () => showModalBottomSheet<void>(
          context: context, isScrollControlled: true,
          builder: (_) => MintTickRoomStockPicker(market: market)),
        child: const Text('Pick')))),
    ));
    await tester.tap(find.text('Pick'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('room-symbol-search')), 'no-stock');
    await tester.pump(const Duration(milliseconds: 320));
    await tester.pumpAndSettle();
    expect(find.textContaining('No matching cash equities'), findsOneWidget);
    market.fail = true;
    await tester.enterText(find.byKey(const ValueKey('room-symbol-search')), 'sbi');
    await tester.pump(const Duration(milliseconds: 320));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('room-stock-search-error')), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
  });

  testWidgets('ticket stock selection prepares and submits one virtual Paper fill',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final market = _FakeMarket();
    final orders = _FakePaper();
    var refreshCalls = 0;
    await tester.pumpWidget(MaterialApp(theme: buildTheme(Brightness.dark),
      home: Scaffold(body: Builder(builder: (context) => FilledButton(
        onPressed: () => showModalBottomSheet<void>(
          context: context, isScrollControlled: true,
          builder: (_) => MintTickRoomOrderSheet(
            roomId: _id, mode: 'PAPER', side: 'BUY',
            market: market, paper: orders, wallet: _wallet(),
            onChanged: () async {refreshCalls++;},
          )),
        child: const Text('Open ticket')))),
    ));
    await tester.tap(find.text('Open ticket'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('room-ticket-stock')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('room-symbol-search')), 'sbi');
    await tester.pump(const Duration(milliseconds: 320));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('room-stock-result-202')));
    await tester.pumpAndSettle();
    expect(find.text('SBIN-EQ'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsWidgets);
    await tester.enterText(find.byKey(const ValueKey('room-ticket-quantity')), '2');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('room-ticket-review')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Virtual cash only.'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('room-order-final-confirm')));
    await tester.pumpAndSettle();
    expect(orders.submits, 1);
    expect(orders.symbolSide, 'BUY');
    expect(orders.quantitySubmitted, 2);
    expect(refreshCalls, 1);
  });
}
