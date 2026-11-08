import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_market_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _CustomerMarketFake implements CustomerMarketRepository {
  int quoteRequests = 0;
  bool denyQuote = false;
  bool mismatchQuote = false;
  @override
  Future<InstrumentCatalogueStatus> catalogueStatus() async =>
      InstrumentCatalogueStatus(version: 'catalogue-v1', activatedAt: DateTime.utc(2026, 11, 7), activeCount: 1);

  @override
  Future<InstrumentSearchPage> search({String? query, String? exchange}) async =>
      InstrumentSearchPage(
        content: [
          if (exchange == null || exchange == 'NSE')
            const InstrumentRecord(
              id: 101,
              exchange: 'NSE',
              exchangeSegment: 'NSE',
              symbolToken: 'public-broker-token',
              tradingSymbol: 'TEST-EQ',
              name: 'Test Company',
              instrumentType: 'EQ',
              expiry: null,
              strike: null,
              lotSize: 1,
              tickSize: 0.05,
            ),
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
        catalogueVersion: 'catalogue-v1',
        activatedAt: DateTime.utc(2026, 11, 7),
      );

  @override
  Future<CustomerMarketQuote> quote(int instrumentId) async {
    quoteRequests++;
    if (denyQuote) throw StateError('provider unavailable');
    return CustomerMarketQuote(
      instrumentId: mismatchQuote ? instrumentId + 1 : instrumentId,
      exchange: 'NSE',
      tradingSymbol: 'TEST-EQ',
      ltp: 125.55,
      fetchedAt: DateTime.utc(2026, 11, 7, 6, 30),
      source: 'ANGEL_ONE_SMARTAPI',
    );
  }
}


class _LiveMarketFake extends _CustomerMarketFake implements CustomerLiveMarketRepository {
  String nextStatus = 'MARKET_CLOSED';
  int liveRequests = 0;

  @override
  Future<CustomerLiveQuote> liveQuote(int instrumentId) async {
    liveRequests++;
    final live = nextStatus == 'LIVE';
    return CustomerLiveQuote(
      instrumentId: instrumentId,
      exchange: 'NSE',
      tradingSymbol: 'TEST-EQ',
      status: nextStatus,
      ltp: live ? 125.55 : null,
      exchangeAt: live ? DateTime.now().toUtc() : null,
      source: live ? 'ANGEL_ONE_SMARTAPI_WS_V2' : null,
    );
  }
}

void main() {
  test('rejects a broker quote without a valid price and fetched timestamp', () {
    expect(() => CustomerMarketQuote.fromJson({
      'instrumentId': 101,
      'exchange': 'NSE',
      'tradingSymbol': 'TEST-EQ',
      'ltp': 0,
      'fetchedAt': 'not-a-date',
      'source': 'ANGEL_ONE_SMARTAPI',
    }), throwsFormatException);
  });

  for (final width in [320.0, 390.0]) {
    for (final brightness in [Brightness.dark, Brightness.light]) {
      testWidgets('customer Markets shows no fabricated quote at ${width.toInt()}dp ${brightness.name}',
          (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = Size(width, 844);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final fake = _CustomerMarketFake();
        await tester.pumpWidget(MaterialApp(
          theme: buildTheme(brightness),
          home: MintTickCustomerMarketPage(repository: fake),
        ));
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('customer-market-stock-101')), findsOneWidget);
        expect(find.byKey(const ValueKey('customer-market-verified-price')), findsNothing);
        expect(fake.quoteRequests, 0);
        await tester.ensureVisible(find.byKey(const ValueKey('customer-market-stock-101')));
        await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
        await tester.pumpAndSettle();
        expect(fake.quoteRequests, 1);
        expect(find.text('₹125.55'), findsOneWidget);
        expect(find.textContaining('ANGEL_ONE_SMARTAPI'), findsOneWidget);
        expect(find.textContaining('Retrieved time is not'), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('provider failures show unavailable, not invented market data', (tester) async {
    final fake = _CustomerMarketFake()..denyQuote = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Unable to retrieve an authenticated quote'), findsOneWidget);
    expect(find.byKey(const ValueKey('customer-market-verified-price')), findsNothing);
  });

  testWidgets('mismatched stock identity never displays on-demand broker price', (tester) async {
    final fake = _CustomerMarketFake()..mismatchQuote = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pumpAndSettle();
    expect(fake.quoteRequests, 1);
    expect(find.byKey(const ValueKey('customer-market-verified-price')), findsNothing);
    expect(find.textContaining('Unable to retrieve an authenticated quote'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('market reopening and app resume refresh verified ticks without manual reselect', (tester) async {
    final fake = _LiveMarketFake();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pump();
    await tester.pump();
    expect(fake.liveRequests, greaterThanOrEqualTo(1));
    expect(find.byKey(const ValueKey('customer-market-live-price')), findsNothing);

    fake.nextStatus = 'LIVE';
    for (var i = 0; i < 16; i++) {
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
    }
    expect(fake.liveRequests, greaterThanOrEqualTo(2));
    expect(find.byKey(const ValueKey('customer-market-live-price')), findsOneWidget);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    expect(find.byKey(const ValueKey('customer-market-live-price')), findsNothing);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('customer-market-live-price')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
