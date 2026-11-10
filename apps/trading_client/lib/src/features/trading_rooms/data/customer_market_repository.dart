import '../../../data/authenticated_api_client.dart';
import '../../../data/instrument_repository.dart';

class CustomerMarketQuote {
  const CustomerMarketQuote({
    required this.instrumentId,
    required this.exchange,
    required this.tradingSymbol,
    required this.ltp,
    required this.fetchedAt,
    required this.source,
  });
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;
  final num ltp;
  final DateTime fetchedAt;
  final String source;

  factory CustomerMarketQuote.fromJson(Map<String, dynamic> value) {
    final rawPrice = value['ltp'];
    final price = rawPrice is num ? rawPrice : num.tryParse('$rawPrice');
    final stamp = DateTime.tryParse('${value['fetchedAt'] ?? ''}');
    if (value['instrumentId'] is! num || price == null || !price.isFinite ||
        price <= 0 || stamp == null ||
        value['exchange'] is! String || value['tradingSymbol'] is! String ||
        value['source'] is! String) {
      throw const FormatException('Invalid customer market quote');
    }
    return CustomerMarketQuote(
      instrumentId: (value['instrumentId'] as num).toInt(),
      exchange: value['exchange'] as String,
      tradingSymbol: value['tradingSymbol'] as String,
      ltp: price, fetchedAt: stamp, source: value['source'] as String,
    );
  }
}

/// Only a verified exchange-timestamped quote may be displayed as LIVE.
class CustomerLiveQuote {
  const CustomerLiveQuote({required this.instrumentId, required this.exchange,
    required this.tradingSymbol, required this.status,
    this.ltp, this.exchangeAt, this.source, this.changePercent});
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;
  final String status;
  final num? ltp;
  final DateTime? exchangeAt;
  final String? source;
  /// Change vs the previous stored session close; only set for LAST_CLOSE rows.
  final num? changePercent;

  /// Local fallback after a verified quote was seen earlier in this screen.
  /// Never reuse LIVE when the API fails or the app is backgrounded.
  /// The local source is deliberately distinct from backend-persisted sources.
  CustomerLiveQuote? asLastRecorded() {
    if (status == 'LAST_CLOSE' && ltp != null && exchangeAt != null) {
      return this;
    }
    if (status != 'LIVE' || source != 'ANGEL_ONE_SMARTAPI_WS_V2' ||
        ltp == null || !ltp!.isFinite || ltp! <= 0 || exchangeAt == null) {
      return null;
    }
    return CustomerLiveQuote(
      instrumentId: instrumentId, exchange: exchange, tradingSymbol: tradingSymbol,
      status: 'LAST_CLOSE', ltp: ltp, exchangeAt: exchangeAt,
      source: 'IN_MEMORY_LAST_VERIFIED_TICK');
  }

  factory CustomerLiveQuote.fromJson(Map<String, dynamic> value) {
    final id = value['instrumentId'];
    final status = value['status'];
    final exchange = value['exchange'];
    final tradingSymbol = value['tradingSymbol'];
    if (id is! int || id < 1 ||
        (exchange != 'NSE' && exchange != 'BSE') ||
        tradingSymbol is! String || tradingSymbol.isEmpty ||
        status is! String ||
        !const {'LIVE','LAST_CLOSE','CONNECTING','RECONNECTING','STALE','UNAVAILABLE',
          'MARKET_CLOSED','BROKER_CONNECTION_REQUIRED'}.contains(status)) {
      throw const FormatException('Invalid live quote state');
    }
    if (status == 'LAST_CLOSE') {
      // A stored last traded price, never presented as live; the source must name the stored path.
      final price = value['ltp'] is num ? value['ltp'] as num :
          num.tryParse('${value['ltp'] ?? ''}');
      final tickAt = DateTime.tryParse('${value['exchangeAt'] ?? ''}');
      final change = value['changePercent'];
      if (price == null || !price.isFinite || price <= 0 || tickAt == null ||
          !const {'STORED_LAST_LIVE_TICK', 'STORED_LAST_DETAIL'}
              .contains(value['source']) ||
          (change != null && (change is! num || !change.isFinite))) {
        throw const FormatException('Unverified last traded price');
      }
      // Preserve which independently stored source was used. PR #674 can
      // correctly return a verified FULL detail snapshot even when the
      // WebSocket tick table is still empty. Never relabel either as LIVE
      // or as an official exchange closing price.
      return CustomerLiveQuote(instrumentId: id,
          exchange: exchange as String, tradingSymbol: tradingSymbol,
          status: status, ltp: price, exchangeAt: tickAt,
          source: value['source'] as String, changePercent: change as num?);
    }
    if (status != 'LIVE') {
      // A disconnected or stale response must never carry a price to Flutter.
      if (value['ltp'] != null || value['exchangeAt'] != null) {
        throw const FormatException('Non-live price was supplied');
      }
      return CustomerLiveQuote(instrumentId: id,
          exchange: exchange as String, tradingSymbol: tradingSymbol,
          status: status);
    }
    final price = value['ltp'] is num ? value['ltp'] as num :
        num.tryParse('${value['ltp'] ?? ''}');
    final tickAt = DateTime.tryParse('${value['exchangeAt'] ?? ''}');
    final source = value['source'];
    if (price == null || !price.isFinite || price <= 0 ||
        tickAt == null || source != 'ANGEL_ONE_SMARTAPI_WS_V2') {
      throw const FormatException('Unverified live quote');
    }
    return CustomerLiveQuote(instrumentId: id,
        exchange: exchange as String, tradingSymbol: tradingSymbol,
        status: status, ltp: price, exchangeAt: tickAt,
        source: source as String);
  }
}

abstract interface class CustomerLiveMarketRepository {
  Future<CustomerLiveQuote> liveQuote(int instrumentId);
}

/// One backend response for a visible page of equities; each row carries its own status.
class CustomerMarketSnapshot {
  const CustomerMarketSnapshot({required this.generatedAt, required this.rows});
  final DateTime generatedAt;
  final List<CustomerLiveQuote> rows;

  factory CustomerMarketSnapshot.fromJson(Map<String, dynamic> value) {
    final stamp = DateTime.tryParse('${value['generatedAt'] ?? ''}');
    final raw = value['rows'];
    if (stamp == null || raw is! List) {
      throw const FormatException('Invalid market snapshot');
    }
    return CustomerMarketSnapshot(
      generatedAt: stamp,
      rows: [
        for (final row in raw)
          row is Map<String, dynamic>
              ? CustomerLiveQuote.fromJson(row)
              : throw const FormatException('Invalid market snapshot row'),
      ],
    );
  }
}

abstract interface class CustomerSnapshotMarketRepository {
  /// Returns rows in the same order as [instrumentIds]; at most 30 unique ids.
  Future<CustomerMarketSnapshot> snapshot(List<int> instrumentIds);
}

/// A stored historical bar. It is never a live tick and carries its own fetch time.
class CustomerCandle {
  const CustomerCandle({required this.candleTime, required this.open,
    required this.high, required this.low, required this.close,
    required this.volume, required this.source, required this.fetchedAt});
  final DateTime candleTime;
  final num open;
  final num high;
  final num low;
  final num close;
  final int volume;
  final String source;
  final DateTime fetchedAt;

  factory CustomerCandle.fromJson(Map<String, dynamic> value) {
    num? price(String key) {
      final raw = value[key];
      final parsed = raw is num ? raw : num.tryParse('$raw');
      return parsed == null || !parsed.isFinite || parsed <= 0 ? null : parsed;
    }
    final time = DateTime.tryParse('${value['candleTime'] ?? ''}');
    final fetched = DateTime.tryParse('${value['fetchedAt'] ?? ''}');
    final open = price('open');
    final high = price('high');
    final low = price('low');
    final close = price('close');
    final volume = value['volume'];
    if (time == null || fetched == null || open == null || high == null ||
        low == null || close == null || high < low ||
        volume is! num || volume < 0 || value['source'] is! String) {
      throw const FormatException('Invalid stored candle');
    }
    return CustomerCandle(candleTime: time, open: open, high: high, low: low,
      close: close, volume: volume.toInt(), source: value['source'] as String,
      fetchedAt: fetched);
  }
}

class CustomerCandlePage {
  const CustomerCandlePage({required this.instrumentId, required this.interval,
    required this.items, required this.hasNext});
  final int instrumentId;
  final String interval;
  final List<CustomerCandle> items;
  final bool hasNext;

  factory CustomerCandlePage.fromJson(Map<String, dynamic> value) {
    final id = value['instrumentId'];
    final interval = value['interval'];
    final raw = value['items'];
    if (id is! num || interval is! String || raw is! List ||
        value['hasNext'] is! bool) {
      throw const FormatException('Invalid stored candle page');
    }
    return CustomerCandlePage(
      instrumentId: id.toInt(),
      interval: interval,
      items: [
        for (final item in raw)
          item is Map<String, dynamic>
              ? CustomerCandle.fromJson(item)
              : throw const FormatException('Invalid stored candle row'),
      ],
      hasNext: value['hasNext'] as bool,
    );
  }
}

abstract interface class CustomerCandleRepository {
  /// [fromDate] and [toDate] are IST calendar dates in yyyy-MM-dd form.
  Future<CustomerCandlePage> candles({
    required int instrumentId,
    required String interval,
    required String fromDate,
    required String toDate,
  });
}

/// Day OHLC and volume from one on-demand FULL quote. [previousClose] is the provider's
/// previous-session close, not today's close. Every field here is provider data; none is derived.
class CustomerDepthLevel {
  const CustomerDepthLevel({required this.price, required this.quantity, required this.orders});
  final num price;
  final int quantity;
  final int orders;
}

class CustomerDayDetail {
  const CustomerDayDetail({required this.instrumentId, required this.exchange,
    required this.tradingSymbol, required this.ltp, required this.open,
    required this.high, required this.low, required this.previousClose,
    required this.volume, required this.exchangeAt, required this.fetchedAt,
    required this.source, this.averagePrice, this.week52High, this.week52Low,
    this.totalBuyQuantity = 0, this.totalSellQuantity = 0,
    this.bids = const [], this.asks = const []});
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;
  final num ltp;
  final num open;
  final num high;
  final num low;
  final num previousClose;
  final int volume;
  final DateTime exchangeAt;
  final DateTime fetchedAt;
  final String source;
  /// Provider VWAP (`avgPrice`); null when the provider sent no value.
  final num? averagePrice;
  final num? week52High;
  final num? week52Low;
  final int totalBuyQuantity;
  final int totalSellQuantity;
  final List<CustomerDepthLevel> bids;
  final List<CustomerDepthLevel> asks;

  factory CustomerDayDetail.fromJson(Map<String, dynamic> value) {
    num? price(String key) {
      final raw = value[key];
      final parsed = raw is num ? raw : num.tryParse('$raw');
      return parsed == null || !parsed.isFinite || parsed <= 0 ? null : parsed;
    }
    List<CustomerDepthLevel> levels(String key) {
      final raw = value[key];
      if (raw is! List) return const [];
      return [
        for (final item in raw)
          if (item is Map && item['price'] is num && (item['price'] as num) > 0)
            CustomerDepthLevel(
              price: item['price'] as num,
              quantity: (item['quantity'] is num ? item['quantity'] as num : 0).toInt(),
              orders: (item['orders'] is num ? item['orders'] as num : 0).toInt(),
            ),
      ];
    }
    final id = value['instrumentId'];
    final ltp = price('ltp');
    final open = price('open');
    final high = price('high');
    final low = price('low');
    final previousClose = price('previousClose');
    final volume = value['volume'];
    final exchangeAt = DateTime.tryParse('${value['exchangeAt'] ?? ''}');
    final fetchedAt = DateTime.tryParse('${value['fetchedAt'] ?? ''}');
    if (id is! num || value['exchange'] is! String ||
        value['tradingSymbol'] is! String || ltp == null || open == null ||
        high == null || low == null || previousClose == null || high < low ||
        volume is! num || volume < 0 || exchangeAt == null ||
        fetchedAt == null || value['source'] is! String) {
      throw const FormatException('Invalid day detail quote');
    }
    final buy = value['totalBuyQuantity'];
    final sell = value['totalSellQuantity'];
    return CustomerDayDetail(
      instrumentId: id.toInt(), exchange: value['exchange'] as String,
      tradingSymbol: value['tradingSymbol'] as String, ltp: ltp, open: open,
      high: high, low: low, previousClose: previousClose, volume: volume.toInt(),
      exchangeAt: exchangeAt, fetchedAt: fetchedAt,
      source: value['source'] as String,
      averagePrice: price('averagePrice'),
      week52High: price('week52High'),
      week52Low: price('week52Low'),
      totalBuyQuantity: buy is num && buy >= 0 ? buy.toInt() : 0,
      totalSellQuantity: sell is num && sell >= 0 ? sell.toInt() : 0,
      bids: levels('bids'),
      asks: levels('asks'),
    );
  }
}

abstract interface class CustomerDetailRepository {
  Future<CustomerDayDetail> dayDetail(int instrumentId);
}

/// Owner-scoped watchlist. Membership is read from the backend, never cached locally.
class CustomerWatchlistItem {
  const CustomerWatchlistItem({required this.instrumentId, required this.exchange,
    required this.tradingSymbol});
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;

  factory CustomerWatchlistItem.fromJson(Map<String, dynamic> value) {
    final id = value['instrumentId'];
    if (id is! num || id < 1 || value['exchange'] is! String ||
        value['tradingSymbol'] is! String) {
      throw const FormatException('Invalid watchlist item');
    }
    return CustomerWatchlistItem(instrumentId: id.toInt(),
        exchange: value['exchange'] as String,
        tradingSymbol: value['tradingSymbol'] as String);
  }
}

class CustomerWatchlist {
  const CustomerWatchlist({required this.items, required this.maxItems});
  final List<CustomerWatchlistItem> items;
  final int maxItems;

  factory CustomerWatchlist.fromJson(Map<String, dynamic> value) {
    final raw = value['items'];
    final max = value['maxItems'];
    if (raw is! List || max is! num) {
      throw const FormatException('Invalid watchlist');
    }
    return CustomerWatchlist(
      items: [
        for (final item in raw)
          item is Map<String, dynamic>
              ? CustomerWatchlistItem.fromJson(item)
              : throw const FormatException('Invalid watchlist row'),
      ],
      maxItems: max.toInt(),
    );
  }
}

abstract interface class CustomerWatchlistRepository {
  Future<CustomerWatchlist> watchlist();
  Future<void> add(int instrumentId);
  Future<void> remove(int instrumentId);
}

num _overviewNumber(Object? raw, {bool positive = false}) {
  final parsed = raw is num ? raw : num.tryParse('$raw');
  if (parsed == null || !parsed.isFinite || (positive && parsed <= 0)) {
    throw const FormatException('Invalid market overview number');
  }
  return parsed;
}

int _overviewCount(Object? raw) {
  final parsed = _overviewNumber(raw);
  if (parsed < 0 || parsed != parsed.truncate()) {
    throw const FormatException('Invalid market overview count');
  }
  return parsed.toInt();
}

DateTime _overviewTime(Object? raw) {
  final parsed = DateTime.tryParse('${raw ?? ''}');
  if (parsed == null) throw const FormatException('Invalid market overview time');
  return parsed;
}

String _overviewText(Object? raw, {bool allowEmpty = false}) {
  if (raw is! String || (!allowEmpty && raw.isEmpty)) {
    throw const FormatException('Invalid market overview text');
  }
  return raw;
}

/// Nifty 50 constituent with a verified FULL quote. Percentages are provider-derived, server-rounded.
class CustomerMover {
  const CustomerMover({required this.tradingSymbol, required this.companyName,
    required this.sector, required this.ltp, required this.previousClose,
    required this.changePct, required this.gapPct, required this.exchangeAt});
  final String tradingSymbol;
  final String companyName;
  final String sector;
  final num ltp;
  final num previousClose;
  final num changePct;
  final num gapPct;
  final DateTime exchangeAt;

  factory CustomerMover.fromJson(Map<String, dynamic> value) => CustomerMover(
    tradingSymbol: _overviewText(value['tradingSymbol']),
    companyName: _overviewText(value['companyName'], allowEmpty: true),
    sector: _overviewText(value['sector']),
    ltp: _overviewNumber(value['ltp'], positive: true),
    previousClose: _overviewNumber(value['previousClose'], positive: true),
    changePct: _overviewNumber(value['changePct']),
    gapPct: _overviewNumber(value['gapPct']),
    exchangeAt: _overviewTime(value['exchangeAt']),
  );
}

class CustomerIndexRow {
  const CustomerIndexRow({required this.indexCode, required this.displayName,
    required this.ltp, required this.previousClose, required this.changePct,
    required this.open, required this.high, required this.low,
    required this.volume, required this.exchangeAt, required this.freshness});
  final String indexCode;
  final String displayName;
  final num ltp;
  final num previousClose;
  final num changePct;
  final num open;
  final num high;
  final num low;
  final int volume;
  final DateTime exchangeAt;
  /// LIVE, STALE or LAST_CLOSE. STALE rows never show their values.
  final String freshness;

  factory CustomerIndexRow.fromJson(Map<String, dynamic> value) => CustomerIndexRow(
    indexCode: _overviewText(value['indexCode']),
    displayName: _overviewText(value['displayName']),
    ltp: _overviewNumber(value['ltp'], positive: true),
    previousClose: _overviewNumber(value['previousClose'], positive: true),
    changePct: _overviewNumber(value['changePct']),
    open: _overviewNumber(value['open'], positive: true),
    high: _overviewNumber(value['high'], positive: true),
    low: _overviewNumber(value['low'], positive: true),
    volume: _overviewCount(value['volume']),
    exchangeAt: _overviewTime(value['exchangeAt']),
    freshness: _overviewFreshness(value['freshness']),
  );
}

String _overviewFreshness(Object? value) {
  final text = _overviewText(value);
  if (text == 'LIVE' || text == 'STALE' || text == 'LAST_CLOSE') return text;
  throw const FormatException('Unknown index freshness');
}

class CustomerSectorSummary {
  const CustomerSectorSummary({required this.sector, required this.members,
    required this.advancers, required this.decliners, required this.avgChangePct});
  final String sector;
  final int members;
  final int advancers;
  final int decliners;
  final num avgChangePct;

  factory CustomerSectorSummary.fromJson(Map<String, dynamic> value) => CustomerSectorSummary(
    sector: _overviewText(value['sector']),
    members: _overviewCount(value['members']),
    advancers: _overviewCount(value['advancers']),
    decliners: _overviewCount(value['decliners']),
    avgChangePct: _overviewNumber(value['avgChangePct']),
  );
}

class CustomerBreadth {
  const CustomerBreadth({required this.advancers, required this.decliners,
    required this.unchanged, required this.total});
  final int advancers;
  final int decliners;
  final int unchanged;
  final int total;

  factory CustomerBreadth.fromJson(Map<String, dynamic> value) => CustomerBreadth(
    advancers: _overviewCount(value['advancers']),
    decliners: _overviewCount(value['decliners']),
    unchanged: _overviewCount(value['unchanged']),
    total: _overviewCount(value['total']),
  );
}

class CustomerUniverse {
  const CustomerUniverse({required this.code, required this.sourceAsOf,
    required this.members, required this.resolved, required this.returned});
  final String code;
  final DateTime sourceAsOf;
  final int members;
  final int resolved;
  final int returned;

  factory CustomerUniverse.fromJson(Map<String, dynamic> value) => CustomerUniverse(
    code: _overviewText(value['code']),
    sourceAsOf: _overviewTime(value['sourceAsOf']),
    members: _overviewCount(value['members']),
    resolved: _overviewCount(value['resolved']),
    returned: _overviewCount(value['returned']),
  );
}

class CustomerMarketIdea {
  const CustomerMarketIdea({required this.tradingSymbol, required this.companyName,
    required this.ltp, required this.changePct, required this.gapPct,
    required this.signal, required this.exchangeAt});
  final String tradingSymbol;
  final String companyName;
  final num ltp;
  final num changePct;
  final num gapPct;
  /// BREAKOUT, MOMENTUM or GAP_UP. A rule-screen label, never a recommendation.
  final String signal;
  final DateTime exchangeAt;

  factory CustomerMarketIdea.fromJson(Map<String, dynamic> value) => CustomerMarketIdea(
    tradingSymbol: _overviewText(value['tradingSymbol']),
    companyName: _overviewText(value['companyName'], allowEmpty: true),
    ltp: _overviewNumber(value['ltp'], positive: true),
    changePct: _overviewNumber(value['changePct']),
    gapPct: _overviewNumber(value['gapPct']),
    signal: _overviewSignal(value['signal']),
    exchangeAt: _overviewTime(value['exchangeAt']),
  );
}

String _overviewSignal(Object? value) {
  final text = _overviewText(value);
  if (text == 'BREAKOUT' || text == 'MOMENTUM' || text == 'GAP_UP') return text;
  throw const FormatException('Unknown market idea signal');
}

class CustomerMarketOverview {
  const CustomerMarketOverview({required this.generatedAt, required this.source,
    required this.breadthScope, required this.exchangeAsOf, required this.universe,
    required this.breadth, required this.gainers, required this.losers,
    required this.gapUp, required this.gapDown, required this.sectors,
    required this.indices, required this.ideas});
  final DateTime generatedAt;
  final String source;
  final String breadthScope;
  final DateTime? exchangeAsOf;
  final CustomerUniverse universe;
  final CustomerBreadth breadth;
  final List<CustomerMover> gainers;
  final List<CustomerMover> losers;
  final List<CustomerMover> gapUp;
  final List<CustomerMover> gapDown;
  final List<CustomerSectorSummary> sectors;
  final List<CustomerIndexRow> indices;
  final List<CustomerMarketIdea> ideas;

  /// A server-verified overview retained in memory during a transient API outage.
  /// Downgrade every index from LIVE and discard time-sensitive trade ideas.
  CustomerMarketOverview asLastRecorded() => CustomerMarketOverview(
    generatedAt: generatedAt,
    source: 'STORED_LAST_OVERVIEW',
    breadthScope: breadthScope,
    exchangeAsOf: exchangeAsOf,
    universe: universe,
    breadth: breadth,
    gainers: gainers,
    losers: losers,
    gapUp: gapUp,
    gapDown: gapDown,
    sectors: sectors,
    indices: [
      for (final index in indices)
        CustomerIndexRow(
          indexCode: index.indexCode, displayName: index.displayName,
          ltp: index.ltp, previousClose: index.previousClose,
          changePct: index.changePct, open: index.open,
          high: index.high, low: index.low, volume: index.volume,
          exchangeAt: index.exchangeAt, freshness: 'LAST_CLOSE'),
    ],
    ideas: const [],
  );

  factory CustomerMarketOverview.fromJson(Map<String, dynamic> value) {
    List<T> rows<T>(String key, T Function(Map<String, dynamic>) parse) {
      final raw = value[key];
      if (raw is! List) throw FormatException('Invalid market overview $key');
      return [
        for (final row in raw)
          row is Map<String, dynamic> ? parse(row)
              : throw FormatException('Invalid market overview $key row'),
      ];
    }
    final universe = value['universe'];
    final breadth = value['breadth'];
    final exchangeAsOf = value['exchangeAsOf'];
    return CustomerMarketOverview(
      generatedAt: _overviewTime(value['generatedAt']),
      source: _overviewText(value['source']),
      breadthScope: _overviewText(value['breadthScope']),
      exchangeAsOf: exchangeAsOf == null ? null : _overviewTime(exchangeAsOf),
      universe: universe is Map<String, dynamic>
          ? CustomerUniverse.fromJson(universe)
          : throw const FormatException('Invalid market overview universe'),
      breadth: breadth is Map<String, dynamic>
          ? CustomerBreadth.fromJson(breadth)
          : throw const FormatException('Invalid market overview breadth'),
      gainers: rows('gainers', CustomerMover.fromJson),
      losers: rows('losers', CustomerMover.fromJson),
      gapUp: rows('gapUp', CustomerMover.fromJson),
      gapDown: rows('gapDown', CustomerMover.fromJson),
      sectors: rows('sectors', CustomerSectorSummary.fromJson),
      indices: rows('indices', CustomerIndexRow.fromJson),
      ideas: rows('ideas', CustomerMarketIdea.fromJson),
    );
  }
}

/// Nifty 50 overview from the backend's 30-second cache. Values are provider data, never estimates.
abstract interface class CustomerOverviewRepository {
  Future<CustomerMarketOverview> overview();
}

abstract interface class CustomerMarketRepository {
  Future<InstrumentCatalogueStatus> catalogueStatus();
  Future<InstrumentSearchPage> search({String? query, String? exchange});
  Future<CustomerMarketQuote> quote(int instrumentId);
}

abstract interface class CustomerPaginatedMarketRepository {
  Future<InstrumentSearchPage> searchPage({
    String? query, String? exchange, required int page,
  });
}

class HttpCustomerMarketRepository implements CustomerMarketRepository,
    CustomerLiveMarketRepository, CustomerSnapshotMarketRepository,
    CustomerPaginatedMarketRepository, CustomerCandleRepository,
    CustomerDetailRepository, CustomerWatchlistRepository, CustomerOverviewRepository {
  HttpCustomerMarketRepository(this.api);
  final AuthenticatedApiClient api;

  static const String _root = '/api/customer/market-data';

  @override
  Future<InstrumentCatalogueStatus> catalogueStatus() async {
    final data = await api.getJson('$_root/instruments/status');
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid instrument catalogue status');
    }
    return InstrumentCatalogueStatus.fromJson(data);
  }

  @override
  Future<InstrumentSearchPage> search({String? query, String? exchange}) =>
      searchPage(query: query, exchange: exchange, page: 0);

  @override
  Future<InstrumentSearchPage> searchPage({
    String? query, String? exchange, required int page,
  }) async {
    if (page < 0 || page > 10000) throw ArgumentError.value(page);
    final data = await api.getJson('$_root/instruments', query: {
      'query': query,
      'exchange': exchange,
      'page': '$page',
      'size': '20',
    });
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid instrument catalogue');
    }
    return InstrumentSearchPage.fromJson(data);
  }

  @override
  Future<CustomerLiveQuote> liveQuote(int instrumentId) async {
    if (instrumentId < 1) throw ArgumentError.value(instrumentId);
    final data = await api.getJson('$_root/quotes', query: {
      'instrumentId': '$instrumentId',
      'live': 'true',
    }, timeout: const Duration(seconds: 8));
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid live quote response');
    }
    final quote = CustomerLiveQuote.fromJson(data);
    if (quote.instrumentId != instrumentId) {
      throw const FormatException('Live quote instrument identity mismatch');
    }
    return quote;
  }

  @override
  Future<CustomerMarketSnapshot> snapshot(List<int> instrumentIds) async {
    if (instrumentIds.isEmpty || instrumentIds.length > 30 ||
        instrumentIds.any((id) => id < 1) ||
        instrumentIds.toSet().length != instrumentIds.length) {
      throw ArgumentError.value(instrumentIds);
    }
    final data = await api.getJson('$_root/snapshot', query: {
      'instrumentIds': instrumentIds.join(','),
    }, timeout: const Duration(seconds: 8));
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid market snapshot response');
    }
    final snapshot = CustomerMarketSnapshot.fromJson(data);
    if (snapshot.rows.length != instrumentIds.length) {
      throw const FormatException('Market snapshot row count mismatch');
    }
    for (var i = 0; i < instrumentIds.length; i++) {
      if (snapshot.rows[i].instrumentId != instrumentIds[i]) {
        throw const FormatException('Market snapshot identity mismatch');
      }
    }
    return snapshot;
  }

  @override
  Future<CustomerMarketQuote> quote(int instrumentId) async {
    if (instrumentId < 1) throw ArgumentError.value(instrumentId);
    final data = await api.getJson('$_root/quotes', query: {
      'instrumentId': '$instrumentId',
    });
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid broker quote');
    }
    final quote = CustomerMarketQuote.fromJson(data);
    if (quote.instrumentId != instrumentId) {
      throw const FormatException('On-demand quote instrument identity mismatch');
    }
    return quote;
  }

  @override
  Future<CustomerCandlePage> candles({
    required int instrumentId,
    required String interval,
    required String fromDate,
    required String toDate,
  }) async {
    if (instrumentId < 1) throw ArgumentError.value(instrumentId);
    final data = await api.getJson('$_root/candles', query: {
      'instrumentId': '$instrumentId',
      'interval': interval,
      'fromDate': fromDate,
      'toDate': toDate,
      'size': '500',
    }, timeout: const Duration(seconds: 8));
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid stored candle response');
    }
    final page = CustomerCandlePage.fromJson(data);
    if (page.instrumentId != instrumentId || page.interval != interval) {
      throw const FormatException('Stored candle identity mismatch');
    }
    return page;
  }

  @override
  Future<CustomerDayDetail> dayDetail(int instrumentId) async {
    if (instrumentId < 1) throw ArgumentError.value(instrumentId);
    final data = await api.getJson('$_root/quotes/detail', query: {
      'instrumentId': '$instrumentId',
    }, timeout: const Duration(seconds: 8));
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid day detail response');
    }
    final detail = CustomerDayDetail.fromJson(data);
    if (detail.instrumentId != instrumentId) {
      throw const FormatException('Day detail instrument identity mismatch');
    }
    return detail;
  }

  static const String _watchRoot = '/api/customer/watchlist';

  @override
  Future<CustomerWatchlist> watchlist() async {
    final data = await api.getJson(_watchRoot);
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid watchlist response');
    }
    return CustomerWatchlist.fromJson(data);
  }

  @override
  Future<void> add(int instrumentId) async {
    if (instrumentId < 1) throw ArgumentError.value(instrumentId);
    await api.postJson(_watchRoot, body: {'instrumentId': instrumentId});
  }

  @override
  Future<void> remove(int instrumentId) async {
    if (instrumentId < 1) throw ArgumentError.value(instrumentId);
    await api.deleteJson('$_watchRoot/items/$instrumentId');
  }

  @override
  Future<CustomerMarketOverview> overview() async {
    final data = await api.getJson('$_root/overview', timeout: const Duration(seconds: 15));
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid market overview response');
    }
    return CustomerMarketOverview.fromJson(data);
  }
}
