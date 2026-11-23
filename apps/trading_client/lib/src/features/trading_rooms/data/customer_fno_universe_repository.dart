import '../../../data/authenticated_api_client.dart';

/// Listed exchange identities from the daily, independently rights-gated
/// Upstox BOD file. No quote, brokerage, trade or subscription authority.
class CustomerFnoUniverseRepository {
  const CustomerFnoUniverseRepository(this.api);
  final AuthenticatedApiClient api;
  static const _root = '/api/customer/markets/fno/universe';

  Future<CustomerFnoUniverseStatus> status() async =>
      CustomerFnoUniverseStatus.decode(await api.getJson('$_root/status'));

  Future<CustomerFnoUniversePage> contracts({String? type, int page = 0}) async {
    final params = <String, String>{'page': '$page', 'size': '50'};
    if (type != null) params['type'] = type;
    return CustomerFnoUniversePage.decode(
      await api.getJson('$_root/contracts', query: params));
  }

  Future<CustomerFnoUniversePage> search(
      String query, {String? type, int page = 0}) async {
    final params = <String, String>{
      'q': query, 'page': '$page', 'size': '50',
    };
    if (type != null) params['type'] = type;
    return CustomerFnoUniversePage.decode(
      await api.getJson('$_root/search', query: params));
  }
  /// One verified listed key, fetched only after the trader opens details.
  /// This REST last trade never supplies executable bid/ask depth or an order.
  Future<CustomerFnoLastTrade> lastTrade(String instrumentKey) async =>
      CustomerFnoLastTrade.decode(await api.getJson(
        '$_root/last-trade', query: {'instrumentKey': instrumentKey}));
}

class CustomerFnoLastTrade {
  const CustomerFnoLastTrade({
    required this.instrumentKey,
    required this.status,
    required this.provenance,
    required this.ltp,
    required this.previousClose,
    required this.openInterest,
    required this.exchangeAt,
    required this.observedAt,
    required this.checkedAt,
    required this.freshExchangeTime,
  });

  final String instrumentKey;
  final String status;
  final String? provenance;
  final num? ltp, previousClose;
  final int? openInterest;
  final DateTime? exchangeAt, observedAt, checkedAt;
  final bool freshExchangeTime;

  bool get hasPrice => status == 'SOURCE_LAST_TRADE' &&
      provenance == 'UPSTOX_REST_V3_LAST_TRADE' &&
      ltp != null && exchangeAt != null && observedAt != null;
  /// A source last trade is never a firm executable quote.
  bool get executable => false;

  static CustomerFnoLastTrade decode(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Malformed F&O last trade');
    }
    final key = raw['instrumentKey'], status = raw['status'];
    if (key is! String ||
        !RegExp(r'^NSE_FO\\|[A-Za-z0-9._-]{1,64}
  const CustomerFnoUniverseStatus({
    required this.state,
    required this.fetchedAt,
    required this.sha256,
    required this.contracts,
    required this.futures,
    required this.calls,
    required this.puts,
    required this.underlyings,
    this.lastFailure,
  });
  final String state;
  final String? lastFailure, sha256;
  final DateTime? fetchedAt;
  final int contracts, futures, calls, puts, underlyings;

  bool get usable => state == 'LOADED' ||
      state == 'STALE_LAST_REFRESH_FAILED';

  static CustomerFnoUniverseStatus decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['state'] is! String ||
        !const {
          'LOADED', 'STALE_LAST_REFRESH_FAILED', 'STALE_SNAPSHOT',
          'NOT_LOADED', 'DISCOVERY_NOT_VERIFIED',
        }.contains(raw['state'])) {
      throw const FormatException('Invalid NSE F&O universe state');
    }
    final values = <int>[];
    for (final name in [
      'contracts', 'futures', 'calls', 'puts', 'underlyings',
    ]) {
      final value = raw[name];
      if (value is! int || value < 0 || value > 300000) {
        throw const FormatException('Unverified F&O universe totals');
      }
      values.add(value);
    }
    if (values[0] != values[1] + values[2] + values[3] ||
        (values[0] > 0 && values[4] == 0)) {
      throw const FormatException('Inconsistent F&O universe totals');
    }
    final atValue = raw['fetchedAt'];
    final at = atValue == null
        ? null : DateTime.tryParse(atValue.toString());
    if ((atValue != null && at == null) ||
        (raw['sha256'] != null && raw['sha256'] is! String) ||
        (raw['lastFailure'] != null && raw['lastFailure'] is! String)) {
      throw const FormatException('Malformed universe provenance');
    }
    if ((raw['state'] == 'LOADED' && (at == null ||
        raw['sha256'] == null || values[0] == 0)) ||
        (at == null && values[0] > 0)) {
      throw const FormatException('Unverified loaded F&O universe');
    }
    return CustomerFnoUniverseStatus(
      state: raw['state'] as String,
      fetchedAt: at?.toUtc(),
      sha256: raw['sha256'] as String?,
      lastFailure: raw['lastFailure'] as String?,
      contracts: values[0],
      futures: values[1],
      calls: values[2],
      puts: values[3],
      underlyings: values[4],
    );
  }
}

class CustomerFnoUniverseInstrument {
  const CustomerFnoUniverseInstrument({
    required this.instrumentKey,
    required this.underlyingKey,
    required this.underlyingSymbol,
    required this.tradingSymbol,
    required this.type,
    required this.expiry,
    required this.strikePrice,
    required this.lotSize,
    required this.tickSizePaise,
    required this.freezeQuantity,
    required this.weekly,
  });

  final String instrumentKey, underlyingKey, underlyingSymbol, tradingSymbol, type;
  final DateTime expiry;
  final num strikePrice, tickSizePaise;
  final int lotSize;
  final num? freezeQuantity;
  final bool weekly;

  num get tickSizeRupees => tickSizePaise / 100;

  static final _key = RegExp(r'^NSE_FO\|[A-Za-z0-9._-]{1,64}$');

  static CustomerFnoUniverseInstrument decode(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid F&O instrument');
    }
    final key = raw['instrumentKey'];
    final underlying = raw['underlyingKey'];
    final symbol = raw['underlyingSymbol'];
    final tradeSymbol = raw['tradingSymbol'];
    final type = raw['type'];
    final expire = raw['expiry'];
    final expiry = expire is String ? DateTime.tryParse(expire) : null;
    final strike = raw['strikePrice'];
    final lot = raw['lotSize'];
    final tick = raw['tickSizePaise'];
    final freeze = raw['freezeQuantity'];
    final weekly = raw['weekly'];
    if (key is! String || !_key.hasMatch(key) ||
        underlying is! String || !underlying.startsWith('NSE_') ||
        symbol is! String || symbol.isEmpty || symbol.length > 96 ||
        tradeSymbol is! String || tradeSymbol.isEmpty ||
        tradeSymbol.length > 128 ||
        !const {'CE', 'PE', 'FUT'}.contains(type) ||
        expiry == null ||
        strike is! num || !strike.isFinite ||
        (type == 'FUT' ? strike != 0 : strike <= 0) ||
        lot is! int || lot <= 0 || lot > 1000000 ||
        tick is! num || !tick.isFinite || tick <= 0 ||
        (freeze != null && (freeze is! num ||
            !freeze.isFinite || freeze <= 0)) ||
        weekly is! bool) {
      throw const FormatException('Untrustworthy F&O instrument');
    }
    return CustomerFnoUniverseInstrument(
      instrumentKey: key,
      underlyingKey: underlying,
      underlyingSymbol: symbol,
      tradingSymbol: tradeSymbol,
      type: type as String,
      expiry: expiry,
      strikePrice: strike,
      lotSize: lot,
      tickSizePaise: tick,
      freezeQuantity: freeze as num?,
      weekly: weekly,
    );
  }
}

class CustomerFnoUniversePage {
  const CustomerFnoUniversePage({
    required this.catalog,
    required this.page,
    required this.pageSize,
    required this.totalMatches,
    required this.contracts,
  });
  final CustomerFnoUniverseStatus catalog;
  final int page, pageSize, totalMatches;
  final List<CustomerFnoUniverseInstrument> contracts;

  static CustomerFnoUniversePage decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['provenance'] != 'UPSTOX_BOD_INSTRUMENT_MASTER' ||
        raw['hasRealtimeQuotes'] != false ||
        raw['liveOrderAuthorized'] != false ||
        raw['page'] is! int || raw['page'] < 0 ||
        raw['pageSize'] is! int ||
        raw['pageSize'] < 1 || raw['pageSize'] > 100 ||
        raw['totalMatches'] is! int || raw['totalMatches'] < 0 ||
        raw['contracts'] is! List) {
      throw const FormatException('Untrustworthy F&O catalog response');
    }
    final list = (raw['contracts'] as List)
        .map(CustomerFnoUniverseInstrument.decode).toList(growable: false);
    if (list.length > (raw['pageSize'] as int) ||
        (raw['totalMatches'] as int) < list.length ||
        list.map((c) => c.instrumentKey).toSet().length != list.length) {
      throw const FormatException('Inconsistent F&O catalog page');
    }
    final status = CustomerFnoUniverseStatus.decode(raw['catalog']);
    if (!status.usable && list.isNotEmpty) {
      throw const FormatException('F&O catalog not authorized');
    }
    return CustomerFnoUniversePage(
      catalog: status,
      page: raw['page'] as int,
      pageSize: raw['pageSize'] as int,
      totalMatches: raw['totalMatches'] as int,
      contracts: List.unmodifiable(list),
    );
  }
}
).hasMatch(key) ||
        status is! String ||
        !const {
          'SOURCE_LAST_TRADE', 'INVALID_INSTRUMENT_KEY',
          'INSTRUMENT_NOT_VERIFIED_IN_CURRENT_MASTER',
          'LAST_PRICE_DISPLAY_RIGHTS_NOT_VERIFIED',
          'LAST_PRICE_SOURCE_UNAVAILABLE', 'LAST_TRADE_NOT_AVAILABLE',
          'SOURCE_RATE_BUDGET_EXHAUSTED',
        }.contains(status) ||
        raw['freshExchangeTime'] is! bool ||
        raw['executable'] != false) {
      throw const FormatException('Unverified F&O last trade');
    }
    DateTime? time(String field) {
      final rawTime = raw[field];
      if (rawTime == null) return null;
      if (rawTime is! String) {
        throw const FormatException('Invalid F&O source trade time');
      }
      final parsed = DateTime.tryParse(rawTime);
      if (parsed == null) {
        throw const FormatException('Invalid F&O source trade time');
      }
      return parsed.toUtc();
    }
    num? price(String field) {
      final value = raw[field];
      if (value == null) return null;
      if (value is! num || !value.isFinite || value <= 0) {
        throw const FormatException('Invalid F&O source last price');
      }
      return value;
    }
    final tradeAt = time('exchangeAt');
    final observedAt = time('observedAt');
    final checkedAt = time('checkedAt');
    final ltp = price('ltp'), previousClose = price('previousClose');
    final oi = raw['openInterest'];
    if (checkedAt == null ||
        (oi != null && (oi is! int || oi < 0)) ||
        (tradeAt != null && observedAt != null &&
            tradeAt.isAfter(observedAt)) ||
        (status == 'SOURCE_LAST_TRADE' &&
            (raw['provenance'] != 'UPSTOX_REST_V3_LAST_TRADE' ||
                ltp == null || tradeAt == null || observedAt == null)) ||
        (status != 'SOURCE_LAST_TRADE' &&
            (raw['provenance'] != null || ltp != null ||
                previousClose != null || oi != null ||
                tradeAt != null || observedAt != null ||
                raw['freshExchangeTime'] != false)) ||
        (raw['freshExchangeTime'] == true &&
            (status != 'SOURCE_LAST_TRADE' ||
                checkedAt.difference(tradeAt!).inSeconds < 0 ||
                checkedAt.difference(tradeAt).inSeconds > 15))) {
      throw const FormatException('Inconsistent F&O last-trade provenance');
    }
    return CustomerFnoLastTrade(
      instrumentKey: key,
      status: status,
      provenance: raw['provenance'] as String?,
      ltp: ltp,
      previousClose: previousClose,
      openInterest: oi as int?,
      exchangeAt: tradeAt,
      observedAt: observedAt,
      checkedAt: checkedAt,
      freshExchangeTime: raw['freshExchangeTime'] as bool,
    );
  }
}

class CustomerFnoUniverseStatus {
  const CustomerFnoUniverseStatus({
    required this.state,
    required this.fetchedAt,
    required this.sha256,
    required this.contracts,
    required this.futures,
    required this.calls,
    required this.puts,
    required this.underlyings,
    this.lastFailure,
  });
  final String state;
  final String? lastFailure, sha256;
  final DateTime? fetchedAt;
  final int contracts, futures, calls, puts, underlyings;

  bool get usable => state == 'LOADED' ||
      state == 'STALE_LAST_REFRESH_FAILED';

  static CustomerFnoUniverseStatus decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['state'] is! String ||
        !const {
          'LOADED', 'STALE_LAST_REFRESH_FAILED', 'STALE_SNAPSHOT',
          'NOT_LOADED', 'DISCOVERY_NOT_VERIFIED',
        }.contains(raw['state'])) {
      throw const FormatException('Invalid NSE F&O universe state');
    }
    final values = <int>[];
    for (final name in [
      'contracts', 'futures', 'calls', 'puts', 'underlyings',
    ]) {
      final value = raw[name];
      if (value is! int || value < 0 || value > 300000) {
        throw const FormatException('Unverified F&O universe totals');
      }
      values.add(value);
    }
    if (values[0] != values[1] + values[2] + values[3] ||
        (values[0] > 0 && values[4] == 0)) {
      throw const FormatException('Inconsistent F&O universe totals');
    }
    final atValue = raw['fetchedAt'];
    final at = atValue == null
        ? null : DateTime.tryParse(atValue.toString());
    if ((atValue != null && at == null) ||
        (raw['sha256'] != null && raw['sha256'] is! String) ||
        (raw['lastFailure'] != null && raw['lastFailure'] is! String)) {
      throw const FormatException('Malformed universe provenance');
    }
    if ((raw['state'] == 'LOADED' && (at == null ||
        raw['sha256'] == null || values[0] == 0)) ||
        (at == null && values[0] > 0)) {
      throw const FormatException('Unverified loaded F&O universe');
    }
    return CustomerFnoUniverseStatus(
      state: raw['state'] as String,
      fetchedAt: at?.toUtc(),
      sha256: raw['sha256'] as String?,
      lastFailure: raw['lastFailure'] as String?,
      contracts: values[0],
      futures: values[1],
      calls: values[2],
      puts: values[3],
      underlyings: values[4],
    );
  }
}

class CustomerFnoUniverseInstrument {
  const CustomerFnoUniverseInstrument({
    required this.instrumentKey,
    required this.underlyingKey,
    required this.underlyingSymbol,
    required this.tradingSymbol,
    required this.type,
    required this.expiry,
    required this.strikePrice,
    required this.lotSize,
    required this.tickSizePaise,
    required this.freezeQuantity,
    required this.weekly,
  });

  final String instrumentKey, underlyingKey, underlyingSymbol, tradingSymbol, type;
  final DateTime expiry;
  final num strikePrice, tickSizePaise;
  final int lotSize;
  final num? freezeQuantity;
  final bool weekly;

  num get tickSizeRupees => tickSizePaise / 100;

  static final _key = RegExp(r'^NSE_FO\|[A-Za-z0-9._-]{1,64}$');

  static CustomerFnoUniverseInstrument decode(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid F&O instrument');
    }
    final key = raw['instrumentKey'];
    final underlying = raw['underlyingKey'];
    final symbol = raw['underlyingSymbol'];
    final tradeSymbol = raw['tradingSymbol'];
    final type = raw['type'];
    final expire = raw['expiry'];
    final expiry = expire is String ? DateTime.tryParse(expire) : null;
    final strike = raw['strikePrice'];
    final lot = raw['lotSize'];
    final tick = raw['tickSizePaise'];
    final freeze = raw['freezeQuantity'];
    final weekly = raw['weekly'];
    if (key is! String || !_key.hasMatch(key) ||
        underlying is! String || !underlying.startsWith('NSE_') ||
        symbol is! String || symbol.isEmpty || symbol.length > 96 ||
        tradeSymbol is! String || tradeSymbol.isEmpty ||
        tradeSymbol.length > 128 ||
        !const {'CE', 'PE', 'FUT'}.contains(type) ||
        expiry == null ||
        strike is! num || !strike.isFinite ||
        (type == 'FUT' ? strike != 0 : strike <= 0) ||
        lot is! int || lot <= 0 || lot > 1000000 ||
        tick is! num || !tick.isFinite || tick <= 0 ||
        (freeze != null && (freeze is! num ||
            !freeze.isFinite || freeze <= 0)) ||
        weekly is! bool) {
      throw const FormatException('Untrustworthy F&O instrument');
    }
    return CustomerFnoUniverseInstrument(
      instrumentKey: key,
      underlyingKey: underlying,
      underlyingSymbol: symbol,
      tradingSymbol: tradeSymbol,
      type: type as String,
      expiry: expiry,
      strikePrice: strike,
      lotSize: lot,
      tickSizePaise: tick,
      freezeQuantity: freeze as num?,
      weekly: weekly,
    );
  }
}

class CustomerFnoUniversePage {
  const CustomerFnoUniversePage({
    required this.catalog,
    required this.page,
    required this.pageSize,
    required this.totalMatches,
    required this.contracts,
  });
  final CustomerFnoUniverseStatus catalog;
  final int page, pageSize, totalMatches;
  final List<CustomerFnoUniverseInstrument> contracts;

  static CustomerFnoUniversePage decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['provenance'] != 'UPSTOX_BOD_INSTRUMENT_MASTER' ||
        raw['hasRealtimeQuotes'] != false ||
        raw['liveOrderAuthorized'] != false ||
        raw['page'] is! int || raw['page'] < 0 ||
        raw['pageSize'] is! int ||
        raw['pageSize'] < 1 || raw['pageSize'] > 100 ||
        raw['totalMatches'] is! int || raw['totalMatches'] < 0 ||
        raw['contracts'] is! List) {
      throw const FormatException('Untrustworthy F&O catalog response');
    }
    final list = (raw['contracts'] as List)
        .map(CustomerFnoUniverseInstrument.decode).toList(growable: false);
    if (list.length > (raw['pageSize'] as int) ||
        (raw['totalMatches'] as int) < list.length ||
        list.map((c) => c.instrumentKey).toSet().length != list.length) {
      throw const FormatException('Inconsistent F&O catalog page');
    }
    final status = CustomerFnoUniverseStatus.decode(raw['catalog']);
    if (!status.usable && list.isNotEmpty) {
      throw const FormatException('F&O catalog not authorized');
    }
    return CustomerFnoUniversePage(
      catalog: status,
      page: raw['page'] as int,
      pageSize: raw['pageSize'] as int,
      totalMatches: raw['totalMatches'] as int,
      contracts: List.unmodifiable(list),
    );
  }
}
