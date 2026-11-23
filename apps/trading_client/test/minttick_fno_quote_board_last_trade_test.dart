import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_fno_quote_board.dart';

CustomerFnoMarket _market({
  bool rights = true,
  num? lastPrice = 110.25,
  int? lastOi = 500,
  int? liveVolume,
  bool freshQuote = false,
  bool withChain = false,
  Duration age = const Duration(hours: 1),
}) {
  final now = DateTime.now().toUtc();
  final observed = now.subtract(age);
  return CustomerFnoMarket.decode({
    'underlyingKey': 'NSE_INDEX|Nifty 50',
    'expiry': '2026-12-08',
    'status': freshQuote ? 'FRESH_QUOTES' : 'MARKET_CLOSED',
    'snapshotAt': now.toIso8601String(),
    'displayRightsVerified': rights,
    'liveOrderEnabled': false,
    'strikes': [
      {
        'strikePrice': 25000,
        'call': {
          'instrumentKey': 'NSE_FO|61001',
          'tradingSymbol': 'NIFTY 25000 CE',
          'side': 'CE',
          'lotSize': 65,
          'tickSize': 0.05,
          'bid': null,
          'ask': null,
          'exchangeAt': observed.toIso8601String(),
          'observedAt': observed.toIso8601String(),
          'quoteFresh': freshQuote,
          'paperEligible': false,
          'ltp': lastPrice,
          'previousClose': 100,
          'openInterest': lastOi,
          if (liveVolume != null) 'volume': liveVolume,
        },
      },
    ],
    if (withChain)
      'chainSnapshotIndicators': {
        'NSE_FO|61001': {
          'ltp': 122.5,
          'openInterest': 700,
        },
      },
  });
}

Future<void> _pumpBoard(WidgetTester tester, CustomerFnoMarket market,
    {bool fresh = false}) async {
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: SingleChildScrollView(
        child: MintTickFnoQuoteBoard(
          market: market,
          fresh: fresh,
          strike: 25000,
          selected: null,
          onStrike: (_) {},
          onLeg: (_) {},
          onTicket: (leg, side) {
            fail('Retained/chain quote must not open $side order for ${leg.instrumentKey}');
          },
          canClose: false,
        ),
      ),
    ),
  ));
  await tester.pump();
}

void main() {
  testWidgets('authorized last trade is visible but PAPER ticket remains blocked',
      (tester) async {
    await _pumpBoard(tester, _market());
    expect(find.text('Listed Options'), findsOneWidget);
    expect(find.textContaining('Last ₹110.25'), findsOneWidget);
    expect(find.textContaining('Last trade ·'), findsOneWidget);
    expect(find.text('OI 500  ·  Volume —'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(
        find.byKey(const ValueKey('fno-premium-buy-call'))).onPressed,
      isNull,
    );
  });

  testWidgets('actual V3 exchange-time volume is shown but cannot enable PAPER',
      (tester) async {
    await _pumpBoard(tester, _market(
        freshQuote: true, liveVolume: 125000,
        age: const Duration(seconds: 1)), fresh: true);
    expect(find.text('OI 500  ·  Volume 125000'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(
        find.byKey(const ValueKey('fno-premium-buy-call'))).onPressed,
      isNull,
    );
  });

  test('malformed V3 negative volume cannot enter the options model', () {
    expect(() => CustomerFnoLeg.decode({
      'instrumentKey': 'NSE_FO|61001',
      'tradingSymbol': 'NIFTY 25000 CE',
      'side': 'CE',
      'lotSize': 65,
      'tickSize': 0.05,
      'bid': null, 'ask': null,
      'exchangeAt': null, 'observedAt': null,
      'quoteFresh': false, 'paperEligible': false,
      'volume': -1,
    }), throwsFormatException);
  });

  testWidgets('read-only chain snapshot has its own label and cannot trade',
      (tester) async {
    await _pumpBoard(tester,
        _market(lastPrice: null, lastOi: null, withChain: true));
    expect(find.textContaining('Chain ₹122.50'), findsOneWidget);
    expect(find.textContaining('Chain snapshot · read only'), findsOneWidget);
    expect(find.text('OI 700  ·  Volume —'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(
        find.byKey(const ValueKey('fno-premium-buy-call'))).onPressed,
      isNull,
    );
  });

  testWidgets('missing display rights hides last trade and chain values',
      (tester) async {
    await _pumpBoard(tester, _market(rights: false, withChain: true));
    expect(find.textContaining('Last ₹'), findsNothing);
    expect(find.textContaining('Chain ₹'), findsNothing);
    expect(find.text('OI 500  ·  Volume —'), findsNothing);
    expect(find.text('OI —  ·  Volume —'), findsWidgets);
  });

  testWidgets('old last trade is withheld even when a malformed response supplies it',
      (tester) async {
    await _pumpBoard(tester, _market(age: const Duration(days: 8)));
    expect(find.textContaining('Last ₹'), findsNothing);
    expect(find.text('OI —  ·  Volume —'), findsWidgets);
    expect(
      tester.widget<FilledButton>(
        find.byKey(const ValueKey('fno-premium-buy-call'))).onPressed,
      isNull,
    );
  });
}
