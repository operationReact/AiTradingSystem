import '../../../data/authenticated_api_client.dart';

/// Listed exchange identities from the daily, independently rights-gated
/// Upstox BOD file. No quote, brokerage, trade or subscription authority.
class CustomerFnoUniverseRepository {
  const CustomerFnoUniverseRepository(this.api);
  final AuthenticatedApiClient api;
  static const _root = '/api/customer/markets/fno/universe';

  Future<CustomerFnoUniverseStatus> status() async =>
      CustomerFnoUniverseStatus.decode(await api.getJson('$_root/status'));

  Future<CustomerFnoRealtimeCoverage> realtimeCoverage() async =>
      CustomerFnoRealtimeCoverage.decode(
          await api.getJson('$_root/realtime-coverage'));

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
}

/// Independent source-evidence coverage. It must never be interpreted as
/// current total NSE coverage, account permission, or LIVE order authorization.
class CustomerFnoRealtimeCoverage {
  const CustomerFnoRealtimeCoverage({
    required this.catalogState,
    required this.catalogSha,
    required this.catalogContracts,
    required this.realtimeState,
    required this.subscribedNseFno,
    required this.freshExchangeLtp,
    required this.freshTwoSidedDepth,
    required this.subscribedWithoutFreshLtp,
    required this.freshWithOi,
    required this.freshWithVolume,
    required this.freshWithGreeks,
    required this.checkedAt,
  });

  final String catalogState, realtimeState;
  final String? catalogSha;
  final int catalogContracts, subscribedNseFno, freshExchangeLtp;
  final int freshTwoSidedDepth, subscribedWithoutFreshLtp;
  final int freshWithOi, freshWithVolume, freshWithGreeks;
  final DateTime checkedAt;

  bool get connected => realtimeState == 'CONNECTED';

  static CustomerFnoRealtimeCoverage decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['catalogState'] is! String ||
        raw['realtimeState'] is! String ||
        (raw['realtimeState'] as String).length > 70 ||
        raw['liveOrdersAuthorized'] != false ||
        (raw['catalogSha'] != null && raw['catalogSha'] is! String)) {
      throw const FormatException('Unverified F&O coverage source');
    }
    const names = [
      'catalogContracts', 'subscribedNseFno', 'freshExchangeLtp',
      'freshTwoSidedDepth', 'subscribedWithoutFreshLtp',
      'freshWithOi', 'freshWithVolume', 'freshWithGreeks',
    ];
    final counts = <String, int>{};
    for (final name in names) {
      final value = raw[name];
      if (value is! int || value < 0 || value > 300000) {
        throw const FormatException('Invalid F&O realtime coverage count');
      }
      counts[name] = value;
    }
    final total = counts['catalogContracts']!;
    final subscribed = counts['subscribedNseFno']!;
    final fresh = counts['freshExchangeLtp']!;
    if (subscribed > 256 ||
        (total > 0 && subscribed > total) ||
        subscribed != fresh + counts['subscribedWithoutFreshLtp']! ||
        counts['freshTwoSidedDepth']! > fresh ||
        counts['freshWithOi']! > fresh ||
        counts['freshWithVolume']! > fresh ||
        counts['freshWithGreeks']! > fresh ||
        (raw['catalogState'] == 'LOADED' &&
            (raw['catalogSha'] == null || total == 0)) ||
        (raw['catalogState'] != 'LOADED' && total > 0) ||
        (raw['realtimeState'] != 'CONNECTED' && subscribed > 0)) {
      throw const FormatException('Inconsistent F&O realtime coverage');
    }
    final date = raw['checkedAt'];
    final timestamp = date is String ? DateTime.tryParse(date) : null;
    if (timestamp == null) {
      throw const FormatException('Missing F&O coverage check time');
    }
    return CustomerFnoRealtimeCoverage(
      catalogState: raw['catalogState'] as String,
      catalogSha: raw['catalogSha'] as String?,
      catalogContracts: total,
      realtimeState: raw['realtimeState'] as String,
      subscribedNseFno: subscribed,
      freshExchangeLtp: fresh,
      freshTwoSidedDepth: counts['freshTwoSidedDepth']!,
      subscribedWithoutFreshLtp: counts['subscribedWithoutFreshLtp']!,
      freshWithOi: counts['freshWithOi']!,
      freshWithVolume: counts['freshWithVolume']!,
      freshWithGreeks: counts['freshWithGreeks']!,
      checkedAt: timestamp.toUtc(),
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
