import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_fno_discovery_page.dart';

Map<String, Object?> _leg(String key, String side,
    {DateTime? at, int? oi}) => {
  'instrumentKey': 'NSE_FO|$key',
  'tradingSymbol': 'NIFTY $key $side',
  'side': side,
  'lotSize': 65,
  'tickSize': 0.05,
  'bid': null,
  'ask': null,
  'exchangeAt': at?.toIso8601String(),
  'observedAt': at?.toIso8601String(),
  'quoteFresh': at != null,
  'paperEligible': false,
  'openInterest': oi,
};

CustomerFnoMarket _market(DateTime now,
    {bool displayRights = true,
     Duration snapshotAge = Duration.zero,
     bool fresh = false}) {
  final at = fresh ? now : null;
  return CustomerFnoMarket.decode({
    'underlyingKey': 'NSE_INDEX|Nifty 50',
    'expiry': '2026-12-08',
    'status': fresh ? 'FRESH_QUOTES' : 'MARKET_CLOSED',
    'snapshotAt': now.subtract(snapshotAge).toIso8601String(),
    'displayRightsVerified': displayRights,
    'liveOrderEnabled': false,
    'strikes': [
      {'strikePrice': 25000, 'call': _leg('61001', 'CE', at: at, oi: fresh ? 800 : null),
       'put': _leg('61002', 'PE', at: at, oi: fresh ? 900 : null)},
      {'strikePrice': 25050, 'call': _leg('61003', 'CE', at: at, oi: fresh ? 1400 : null),
       'put': _leg('61004', 'PE', at: at, oi: fresh ? 600 : null)},
    ],
    'chainSnapshotIndicators': {
      'NSE_FO|61001': {
        'openInterest': 400, 'previousOpenInterest': 500,
        'volume': 300, 'ltp': 110.25},
      'NSE_FO|61002': {
        'openInterest': 800, 'previousOpenInterest': 600,
        'volume': 900, 'ltp': 120.00},
      'NSE_FO|61003': {
        'openInterest': 1300, 'previousOpenInterest': 1200,
        'volume': 600, 'ltp': 98.50},
      'NSE_FO|61004': {
        'openInterest': 100, 'previousOpenInterest': 100,
        'volume': 0, 'ltp': 85.75},
    },
  });
}

void main() {
  test('highest OI ranks recent authorized V2 chain counts, not trade volume', () {
    final now = DateTime.now().toUtc();
    final market = _market(now);
    final calls = MintTickFnoDiscoveryFacts.highestOpenInterest(
        market, now, 'CE');
    expect(calls.map((leg) => leg.instrumentKey).toList(),
        ['NSE_FO|61003', 'NSE_FO|61001']);
    expect(calls.every((leg) => !leg.quoteFresh && !leg.paperEligible), isTrue);
    final puts = MintTickFnoDiscoveryFacts.highestOpenInterest(
        market, now, 'PE');
    expect(puts.first.instrumentKey, 'NSE_FO|61002');
  });

  test('top traded ranks actual V2 provider volume, excluding zero volume', () {
    final now = DateTime.now().toUtc();
    final legs = MintTickFnoDiscoveryFacts.snapshotLeaders(
        _market(now), now, 'ALL', metric: 'VOLUME');
    expect(legs.map((leg) => leg.instrumentKey).toList(),
        ['NSE_FO|61002', 'NSE_FO|61003', 'NSE_FO|61001']);
    expect(legs.every((leg) => !leg.paperEligible), isTrue);
  });

  test('OI gainers use positive current-minus-prior V2 OI, not price change', () {
    final now = DateTime.now().toUtc();
    final legs = MintTickFnoDiscoveryFacts.snapshotLeaders(
        _market(now), now, 'ALL', metric: 'OI_GAIN');
    expect(legs.map((leg) => leg.instrumentKey).toList(),
        ['NSE_FO|61002', 'NSE_FO|61003']);
  });

  test('invalid, stale or denied V2 snapshot cannot support volume rankings', () {
    final now = DateTime.now().toUtc();
    expect(MintTickFnoDiscoveryFacts.snapshotLeaders(
        _market(now, displayRights: false), now, 'ALL',
        metric: 'VOLUME'), isEmpty);
    expect(MintTickFnoDiscoveryFacts.snapshotLeaders(
        _market(now, snapshotAge: const Duration(minutes: 6)),
        now, 'ALL', metric: 'VOLUME'), isEmpty);
    expect(MintTickFnoDiscoveryFacts.snapshotLeaders(
        _market(now), now, 'ALL', metric: 'UNKNOWN'), isEmpty);
    expect(() => CustomerFnoChainSnapshotIndicator.decode({
      'openInterest': 20, 'previousOpenInterest': 21, 'volume': -1,
    }), throwsFormatException);
  });

  test('V2 chain OI is not ranked after snapshot age exceeds five minutes', () {
    final now = DateTime.now().toUtc();
    final market = _market(now,
        snapshotAge: const Duration(minutes: 6));
    expect(MintTickFnoDiscoveryFacts.highestOpenInterest(
        market, now, 'CE'), isEmpty);
  });

  test('denied display rights must not reveal even source-backed OI', () {
    final now = DateTime.now().toUtc();
    expect(MintTickFnoDiscoveryFacts.highestOpenInterest(
        _market(now, displayRights: false), now, 'ALL'), isEmpty);
  });

  test('fresh exchange-time V3 OI outranks the V2 snapshot when available', () {
    final now = DateTime.now().toUtc();
    final calls = MintTickFnoDiscoveryFacts.highestOpenInterest(
        _market(now, fresh: true), now, 'CE');
    expect(calls.first.instrumentKey, 'NSE_FO|61003');
    expect(calls.first.openInterest, 1400);
  });

  test('no ATM and no reference bounds a long real strike list to nine', () {
    final strikes = List.generate(142,
        (i) => CustomerFnoStrike(strikePrice: 20000 + 50 * i));
    final visible = MintTickFnoDiscoveryFacts.visibleStrikes(
        strikes, null);
    expect(visible.length, 9);
    expect(visible.first.strikePrice, strikes[67].strikePrice);
    expect(visible.last.strikePrice, strikes[75].strikePrice);
    expect(MintTickFnoDiscoveryFacts.visibleStrikes(
        strikes, null, showAll: true).length, 142);
  });

  test('a source reference centers the bounded window without invented ATM', () {
    final strikes = List.generate(142,
        (i) => CustomerFnoStrike(strikePrice: 20000 + 50 * i));
    final visible = MintTickFnoDiscoveryFacts.visibleStrikes(
        strikes, 25500);
    expect(visible.length, 9);
    expect(visible[4].strikePrice, 25500);
  });
}
