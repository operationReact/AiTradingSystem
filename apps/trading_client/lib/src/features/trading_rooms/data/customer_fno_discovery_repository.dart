import '../../../data/authenticated_api_client.dart';
import 'customer_paper_fno_repository.dart';

/// App-wide read-only customer F&O market feed. No room ID, broker credentials,
/// PAPER wallet, order authority or OAuth session is required by this API.
class CustomerFnoDiscoveryRepository {
  const CustomerFnoDiscoveryRepository(this.api);

  final AuthenticatedApiClient api;
  static const _root = '/api/customer/markets/fno';

  Future<CustomerFnoDiscoveryCapability> capability() async =>
      CustomerFnoDiscoveryCapability.decode(
        await api.getJson('$_root/capability'));

  Future<CustomerFnoExpiries> expiries() async =>
      CustomerFnoExpiries.decode(await api.getJson('$_root/expiries'));

  Future<CustomerFnoMarket> market(DateTime expiry) async =>
      CustomerFnoMarket.decode(await api.getJson(
        '$_root/market',
        query: {'expiry': _ymd(expiry)},
      ));

  static String _ymd(DateTime date) =>
      '${date.year.toString().padLeft(4, '0')}-'
      '${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')}';
}

class CustomerFnoDiscoveryCapability {
  const CustomerFnoDiscoveryCapability({
    required this.displayRightsVerified,
    required this.feedConfigured,
    required this.sourceReady,
    required this.marketOpen,
    required this.status,
    required this.streamState,
    required this.freshExchangeTimedQuotes,
    this.latestFreshExchangeTradeAt,
    this.contractDiscoveryVerified = false,
    this.freshDisplayQuotes = 0,
    this.latestFreshDisplayTradeAt,
    this.subscribedInstruments = 0,
  });

  final bool displayRightsVerified, feedConfigured, sourceReady, marketOpen;
  /// Expiry and contract-identity listing permission. Display rights imply it.
  /// Prices, OI, IV and Greeks still require [displayRightsVerified].
  final bool contractDiscoveryVerified;
  final String status, streamState;
  /// Depth-qualified V3 observations; a read-only LTP alone cannot place orders.
  final int freshExchangeTimedQuotes;
  final DateTime? latestFreshExchangeTradeAt;
  /// Distinct exchange-timed V3 LTP coverage, independent of bid/ask depth.
  final int freshDisplayQuotes, subscribedInstruments;
  final DateTime? latestFreshDisplayTradeAt;

  bool get live => status == 'LIVE_QUOTES' &&
      displayRightsVerified && feedConfigured && sourceReady &&
      marketOpen && (freshDisplayQuotes > 0 || freshExchangeTimedQuotes > 0);

  bool get executableDepthObserved =>
      displayRightsVerified && marketOpen &&
      freshExchangeTimedQuotes > 0 && latestFreshExchangeTradeAt != null;
  /// This is feed evidence only; broker account and risk gates remain separate.
  bool get freshDisplayObserved =>
      live && freshDisplayQuotes > 0 && latestFreshDisplayTradeAt != null;

  factory CustomerFnoDiscoveryCapability.decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['displayRightsVerified'] is! bool ||
        raw['feedConfigured'] is! bool ||
        raw['sourceReady'] is! bool ||
        raw['marketOpen'] is! bool ||
        !const {
          'DISPLAY_RIGHTS_NOT_VERIFIED', 'FNO_V3_FEED_UNAVAILABLE',
          'FNO_CONTRACT_SOURCE_UNAVAILABLE', 'MARKET_CLOSED',
          'SOURCE_CONFIGURED', 'LIVE_QUOTES', 'WAITING_FOR_QUOTES',
          'FEED_DISCONNECTED', 'CONTRACTS_LISTED_PRICES_NOT_AUTHORIZED',
        }.contains(raw['status'])) {
      throw const FormatException('Unverified F&O source capability');
    }
    final status = raw['status'] as String;
    final hasDiscovery = raw.containsKey('contractDiscoveryVerified');
    if (hasDiscovery && raw['contractDiscoveryVerified'] is! bool) {
      throw const FormatException('Malformed F&O contract discovery flag');
    }
    final displayRights = raw['displayRightsVerified'] == true;
    final hasStream = raw.containsKey('streamState');
    if (hasStream && raw['streamState'] is! String) {
      throw const FormatException('Malformed F&O stream status');
    }
    final hasFresh = raw.containsKey('freshExchangeTimedQuotes');
    if (hasFresh && (raw['freshExchangeTimedQuotes'] is! int ||
        (raw['freshExchangeTimedQuotes'] as int) < 0 ||
        (raw['freshExchangeTimedQuotes'] as int) > 2000)) {
      throw const FormatException('Malformed F&O quote count');
    }
    final count = hasFresh ? raw['freshExchangeTimedQuotes'] as int : 0;
    for (final key in ['freshDisplayQuotes', 'subscribedInstruments']) {
      final value = raw[key];
      if (value != null && (value is! int || value < 0 || value > 5000)) {
        throw const FormatException('Invalid F&O live coverage count');
      }
    }
    final displays = raw['freshDisplayQuotes'] as int? ?? 0;
    final subscriptions = raw['subscribedInstruments'] as int? ?? 0;
    if (displays > subscriptions ||
        (count > subscriptions && raw.containsKey('subscribedInstruments'))) {
      throw const FormatException('F&O feed coverage exceeds subscriptions');
    }
    final displayAtRaw = raw['latestFreshDisplayTradeAt'];
    if (displayAtRaw != null && displayAtRaw is! String) {
      throw const FormatException('Malformed F&O display trade timestamp');
    }
    final displayAt = displayAtRaw == null
        ? null : DateTime.tryParse(displayAtRaw);
    if (displayAtRaw != null && displayAt == null) {
      throw const FormatException('Malformed F&O display trade timestamp');
    }
    final atRaw = raw['latestFreshExchangeTradeAt'];
    if (atRaw != null && atRaw is! String) {
      throw const FormatException('Malformed F&O exchange timestamp');
    }
    final at = atRaw == null ? null : DateTime.tryParse(atRaw);
    if (atRaw != null && at == null) {
      throw const FormatException('Unverified F&O exchange timestamp');
    }
    final ready = raw['displayRightsVerified'] == true &&
        raw['feedConfigured'] == true && raw['sourceReady'] == true &&
        raw['marketOpen'] == true;
    if ((status == 'SOURCE_CONFIGURED' && !ready) ||
        (status == 'LIVE_QUOTES' &&
            (!ready ||
                !((count > 0 && at != null) ||
                    (displays > 0 && displayAt != null)))) ||
        (displays > 0 && displayAt == null) ||
        (displays == 0 && displayAt != null) ||
        (!ready && (count > 0 || at != null ||
            displays > 0 || displayAt != null ||
            subscriptions > 0))) {
      throw const FormatException('Inconsistent F&O source readiness');
    }
    return CustomerFnoDiscoveryCapability(
      displayRightsVerified: raw['displayRightsVerified'] as bool,
      feedConfigured: raw['feedConfigured'] as bool,
      sourceReady: raw['sourceReady'] as bool,
      marketOpen: raw['marketOpen'] as bool,
      status: status,
      streamState: hasStream ? raw['streamState'] as String : 'UNVERIFIED',
      freshExchangeTimedQuotes: count,
      latestFreshExchangeTradeAt: at?.toUtc(),
      freshDisplayQuotes: displays,
      latestFreshDisplayTradeAt: displayAt?.toUtc(),
      subscribedInstruments: subscriptions,
      contractDiscoveryVerified: hasDiscovery
          ? raw['contractDiscoveryVerified'] as bool
          : displayRights,
    );
  }
}
