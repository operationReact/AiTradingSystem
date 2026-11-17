import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_fno_discovery_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_fno_discovery_page.dart';

class _NoDiscovery extends Fake implements CustomerFnoDiscoveryRepository {}

Map<String, Object?> _leg(String side, {bool fresh = true, num? ltp = 110}) => {
  'instrumentKey': 'NSE_FO|NIFTY_25300_$side',
  'tradingSymbol': 'NIFTY 25300 $side',
  'side': side,
  'lotSize': 65,
  'tickSize': 0.05,
  'bid': fresh ? 109.5 : null,
  'ask': fresh ? 110.5 : null,
  'exchangeAt': fresh ? '2026-11-17T08:00:00Z' : null,
  'observedAt': fresh ? '2026-11-17T08:00:01Z' : null,
  'quoteFresh': fresh,
  'paperEligible': false,
  'ltp': fresh ? ltp : null,
  'previousClose': fresh ? 100 : null,
  'openInterest': fresh ? 1200 : null,
};

CustomerFnoMarket _market({bool fresh = true}) => CustomerFnoMarket.decode({
  'underlyingKey': 'NSE_INDEX|Nifty 50',
  'expiry': '2026-11-19',
  'source': 'UPSTOX_ANALYTICS',
  'status': fresh ? 'FRESH_QUOTES' : 'MARKET_CLOSED',
  'snapshotAt': '2026-11-17T08:00:01Z',
  'displayRightsVerified': true,
  'liveOrderEnabled': false,
  'strikes': [{
    'strikePrice': 25300,
    'call': _leg('CE', fresh: fresh, ltp: 110),
    'put': _leg('PE', fresh: fresh, ltp: 93),
  }],
});

void main() {
  test('NSE option list splits actual CE/PE without synthetic contracts', () {
    final market = _market();
    final calls = MintTickFnoDiscoveryFacts.optionLegs(market, 'CE');
    final puts = MintTickFnoDiscoveryFacts.optionLegs(market, 'PE');
    expect(calls, hasLength(1));
    expect(calls.single.instrumentKey, 'NSE_FO|NIFTY_25300_CE');
    expect(puts.single.side, 'PE');
    expect(MintTickFnoDiscoveryFacts.optionLegs(market, 'FUT'), isEmpty);
  });

  test('close-to-close changes only appear for fresh verified V3 quotes', () {
    final market = _market();
    final changes = MintTickFnoDiscoveryFacts.previousCloseMovers(market);
    expect(changes.map((leg) => leg.side).toList(), ['CE','PE']);
    expect(changes.first.priceChangePct, 10);
    expect(MintTickFnoDiscoveryFacts.previousCloseMovers(
        _market(fresh: false)), isEmpty);
  });

  testWidgets('empty F&O discover offers room creation, not invented prices',
      (tester) async {
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    var createCount = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms,
          discovery: _NoDiscovery(),
          onOpenRoom: (_) {},
          onCreateRoom: () => createCount++,
        ),
      )),
    ));
    await tester.pump();
    expect(find.text('F&O · Discover'), findsOneWidget);
    expect(find.textContaining('Market discovery works without a room.'), findsOneWidget);
    expect(find.textContaining('No verified'), findsWidgets);
    expect(find.text('Create F&O room'), findsWidgets);
    final create = find.text('Create F&O room').first;
    await tester.ensureVisible(create);
    await tester.tap(create);
    expect(createCount, 1);
  });
}
