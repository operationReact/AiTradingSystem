import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_fno_universe_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_fno_universe_panel.dart';

Map<String, Object?> _status({bool rights = true}) => {
  'state': rights ? 'LOADED' : 'DISCOVERY_NOT_VERIFIED',
  'fetchedAt': rights ? '2026-11-23T03:00:00Z' : null,
  'sha256': rights ? 'actual-source-sha' : null,
  'lastFailure': rights ? null : 'CONTRACT_DISCOVERY_NOT_VERIFIED',
  'contracts': rights ? 2 : 0,
  'futures': rights ? 1 : 0,
  'calls': rights ? 1 : 0,
  'puts': 0,
  'underlyings': rights ? 2 : 0,
};

Map<String, Object?> _row(String type, String underlying) => {
  'instrumentKey': type == 'CE' ? 'NSE_FO|61001' : 'NSE_FO|61002',
  'underlyingKey': type == 'CE'
      ? 'NSE_INDEX|Nifty 50' : 'NSE_EQ|INE002A01018',
  'underlyingSymbol': underlying,
  'tradingSymbol': type == 'CE' ? 'NIFTY 26000 CE' : 'RELIANCE FUT DEC',
  'type': type,
  'expiry': '2026-12-01',
  'strikePrice': type == 'CE' ? 26000 : 0,
  'lotSize': 65,
  'tickSizePaise': 5,
  'freezeQuantity': 100000,
  'weekly': type == 'CE',
};

CustomerFnoUniversePage _page({
  bool search = false,
  bool denied = false,
}) => CustomerFnoUniversePage.decode({
  'provenance': 'UPSTOX_BOD_INSTRUMENT_MASTER',
  'hasRealtimeQuotes': false,
  'liveOrderAuthorized': false,
  'catalog': _status(rights: !denied),
  'page': 0,
  'pageSize': 50,
  'totalMatches': search ? 1 : 2,
  'contracts': search
      ? [_row('FUT', 'RELIANCE')]
      : [_row('CE', 'NIFTY'), _row('FUT', 'RELIANCE')],
});

class _Repository extends Fake implements CustomerFnoUniverseRepository {
  _Repository({this.rights = true});
  final bool rights;
  String? lastSearch;
  String? lastType;
  int calls = 0;

  @override
  Future<CustomerFnoUniverseStatus> status() async =>
      CustomerFnoUniverseStatus.decode(_status(rights: rights));

  @override
  Future<CustomerFnoRealtimeCoverage> realtimeCoverage() async =>
      CustomerFnoRealtimeCoverage.decode({
        'catalogState': rights ? 'LOADED' : 'DISCOVERY_NOT_VERIFIED',
        'catalogSha': rights ? 'actual-source-sha' : null,
        'catalogContracts': rights ? 2 : 0,
        'realtimeState': rights ? 'CONNECTED'
            : 'DISPLAY_RIGHTS_NOT_VERIFIED',
        'subscribedNseFno': rights ? 1 : 0,
        'freshExchangeLtp': rights ? 1 : 0,
        'freshTwoSidedDepth': 0,
        'subscribedWithoutFreshLtp': 0,
        'freshWithOi': rights ? 1 : 0,
        'freshWithVolume': rights ? 1 : 0,
        'freshWithGreeks': 0,
        'checkedAt': '2026-11-23T08:04:00Z',
        'liveOrdersAuthorized': false,
      });

  @override
  Future<CustomerFnoUniversePage> contracts({
    String? type, int page = 0,
  }) async {
    calls++;
    lastType = type;
    return _page();
  }

  @override
  Future<CustomerFnoUniversePage> search(String query, {
    String? type, int page = 0,
  }) async {
    calls++;
    lastSearch = query;
    lastType = type;
    return _page(search: true);
  }
}

void main() {
  test('source catalogue rejects invented live/ordering permission', () {
    expect(() => CustomerFnoUniversePage.decode({
      'provenance': 'UPSTOX_BOD_INSTRUMENT_MASTER',
      'hasRealtimeQuotes': true,
      'liveOrderAuthorized': false,
      'catalog': _status(),
      'page': 0, 'pageSize': 50, 'totalMatches': 2,
      'contracts': [_row('CE', 'NIFTY')],
    }), throwsFormatException);
    expect(() => CustomerFnoUniverseInstrument.decode(
        _row('CE', 'NIFTY')..['lotSize'] = 0), throwsFormatException);
    expect(() => CustomerFnoUniverseStatus.decode(
        _status()..['contracts'] = 50), throwsFormatException);
  });

  testWidgets('lists genuine source identities without any order controls',
      (tester) async {
    final repo = _Repository();
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: SingleChildScrollView(
        child: MintTickFnoUniversePanel(repository: repo)))));
    await tester.pumpAndSettle();
    expect(find.text('F&O Universe'), findsOneWidget);
    expect(find.text('Listed 2'), findsOneWidget);
    expect(find.text('CE 1'), findsOneWidget);
    expect(find.text('FUT 1'), findsOneWidget);
    expect(find.text('NIFTY 26000 CE'), findsOneWidget);
    expect(find.text('RELIANCE FUT DEC'), findsOneWidget);
    expect(find.text('V3 sub 1'), findsOneWidget);
    expect(find.text('Fresh LTP 1'), findsOneWidget);
    expect(find.text('Depth 0'), findsOneWidget);
    expect(find.textContaining('1 / 2 subscribed'), findsOneWidget);
    expect(find.byKey(const ValueKey('fno-universe-result-count')),
        findsOneWidget);
    expect(find.textContaining('no live quote implied'), findsOneWidget);
    expect(find.text('Buy'), findsNothing);
    expect(find.text('Sell'), findsNothing);
  });

  test('realtime model rejects fake quotes, inconsistent counts and LIVE orders', () {
    Map<String, Object?> receipt() => {
      'catalogState': 'LOADED',
      'catalogSha': 'verified-master-sha',
      'catalogContracts': 45451,
      'realtimeState': 'CONNECTED',
      'subscribedNseFno': 256,
      'freshExchangeLtp': 38,
      'freshTwoSidedDepth': 31,
      'subscribedWithoutFreshLtp': 218,
      'freshWithOi': 37,
      'freshWithVolume': 36,
      'freshWithGreeks': 29,
      'checkedAt': '2026-11-23T08:04:00Z',
      'liveOrdersAuthorized': false,
    };
    final parsed = CustomerFnoRealtimeCoverage.decode(receipt());
    expect(parsed.subscribedNseFno, 256);
    expect(parsed.freshExchangeLtp, 38);
    expect(parsed.connected, isTrue);
    expect(() => CustomerFnoRealtimeCoverage.decode(
        receipt()..['liveOrdersAuthorized'] = true), throwsFormatException);
    expect(() => CustomerFnoRealtimeCoverage.decode(
        receipt()..['freshTwoSidedDepth'] = 39), throwsFormatException);
    expect(() => CustomerFnoRealtimeCoverage.decode(
        receipt()..['subscribedWithoutFreshLtp'] = 0), throwsFormatException);
    expect(() => CustomerFnoRealtimeCoverage.decode(
        receipt()..['realtimeState'] = 'STOPPED'), throwsFormatException);
    expect(() => CustomerFnoRealtimeCoverage.decode(
        receipt()..['catalogState'] = 'STALE_SNAPSHOT'), throwsFormatException);
    expect(() => CustomerFnoRealtimeCoverage.decode(
        receipt()..['subscribedNseFno'] = 45000), throwsFormatException);
  });

  testWidgets('search and type filter uses local catalog only',
      (tester) async {
    final repo = _Repository();
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: SingleChildScrollView(
        child: MintTickFnoUniversePanel(repository: repo)))));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(
      const ValueKey('fno-universe-search')), 'RELIANCE');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(repo.lastSearch, 'RELIANCE');
    expect(find.text('RELIANCE FUT DEC'), findsOneWidget);
    await tester.tap(find.byKey(
        const ValueKey('fno-universe-type-fut')));
    await tester.pumpAndSettle();
    expect(repo.lastType, 'FUT');
  });

  testWidgets('missing catalog rights never requests or shows real contracts',
      (tester) async {
    final repo = _Repository(rights: false);
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: SingleChildScrollView(
        child: MintTickFnoUniversePanel(repository: repo)))));
    await tester.pumpAndSettle();
    expect(find.textContaining('Catalog unavailable'), findsOneWidget);
    expect(find.text('NIFTY 26000 CE'), findsNothing);
    expect(repo.calls, 0);
  });
}
