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
  });

  final bool displayRightsVerified, feedConfigured, sourceReady, marketOpen;
  final String status, streamState;
  final int freshExchangeTimedQuotes;
  final DateTime? latestFreshExchangeTradeAt;

  bool get live => status == 'LIVE_QUOTES' &&
      displayRightsVerified && feedConfigured && sourceReady &&
      marketOpen && freshExchangeTimedQuotes > 0;

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
          'FEED_DISCONNECTED',
        }.contains(raw['status'])) {
      throw const FormatException('Unverified F&O source capability');
    }
    final status = raw['status'] as String;
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
        (status == 'LIVE_QUOTES' && (!ready || count == 0 || at == null)) ||
        (!ready && (count > 0 || at != null))) {
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
    );
  }
}
