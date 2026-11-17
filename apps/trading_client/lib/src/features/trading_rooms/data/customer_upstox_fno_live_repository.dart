import '../../../data/authenticated_api_client.dart';
import 'customer_paper_fno_repository.dart';

/// Owner JWT / Upstox LIVE F&O routes. PAPER wallets and Analytics tokens
/// never authorize LIVE order placement. The backend is the risk authority.
class CustomerUpstoxFnoLiveRepository {
  const CustomerUpstoxFnoLiveRepository(this.api);
  final AuthenticatedApiClient api;

  static final _room = RegExp(r'^[0-9a-fA-F-]{36}$');
  static final _contract = RegExp(r'^NSE_FO\|[A-Za-z0-9._-]{1,64}$');
  static final _idem = RegExp(r'^[A-Za-z0-9_-]{8,64}$');

  String _root(String roomId) {
    if (!_room.hasMatch(roomId)) {
      throw const FormatException('Unverified LIVE room identifier');
    }
    return '/api/customer/trading-rooms/$roomId/live/fno';
  }

  Future<UpstoxFnoLiveCapability> capability(String roomId) async =>
      UpstoxFnoLiveCapability.decode(
        await api.getJson('${_root(roomId)}/capability'));

  /// These are read-only selectors, and never imply trade eligibility.
  Future<CustomerFnoExpiries> expiries(String roomId) async =>
      CustomerFnoExpiries.decode(
        await api.getJson('${_root(roomId)}/expiries'));

  Future<CustomerFnoMarket> market(String roomId, DateTime expiry) async =>
      CustomerFnoMarket.decode(await api.getJson(
        '${_root(roomId)}/market', query: {'expiry': _ymd(expiry)}));

  Future<List<UpstoxFnoLiveReceipt>> orders(String roomId) async {
    final raw = await api.getJson('${_root(roomId)}/orders');
    if (raw is! List) throw const FormatException('Unverified LIVE order history');
    return List.unmodifiable(raw.map(UpstoxFnoLiveReceipt.decode));
  }

  /// The caller must show a distinct per-order real-money confirmation dialog.
  /// On a timeout, do not auto-resubmit: list/reconcile by broker tag first.
  Future<UpstoxFnoLiveReceipt> submit({
    required String roomId,
    required String idempotencyKey,
    required String instrumentKey,
    required DateTime expiry,
    required String side,
    required num limitPrice,
    required bool explicitlyConfirmed,
  }) async {
    if (!explicitlyConfirmed || !_idem.hasMatch(idempotencyKey) ||
        !_contract.hasMatch(instrumentKey) ||
        !const {'BUY', 'SELL'}.contains(side) ||
        !limitPrice.isFinite || limitPrice <= 0 || limitPrice > 1000000 ||
        !RegExp(r'^\d{1,7}(\.\d{1,4})?$').hasMatch(limitPrice.toString())) {
      throw const FormatException('Unverified one-lot LIVE order request');
    }
    return UpstoxFnoLiveReceipt.decode(await api.postJson(
      '${_root(roomId)}/orders',
      headers: {
        'Idempotency-Key': idempotencyKey,
        'X-Confirm-Live-Order': 'CONFIRM_LIVE_UPSTOX_NFO',
      },
      body: {
        'instrumentKey': instrumentKey, 'expiry': _ymd(expiry),
        'side': side, 'lots': 1, 'limitPrice': limitPrice,
      },
    ));
  }

  /// Only reads the broker order-history state; no second placement.
  Future<UpstoxFnoLiveReceipt> reconcile(String roomId, String orderId) async {
    if (!_room.hasMatch(orderId)) throw const FormatException('Invalid order ID');
    return UpstoxFnoLiveReceipt.decode(await api.postJson(
      '${_root(roomId)}/orders/$orderId/reconcile',
    ));
  }

  static String _ymd(DateTime date) =>
      '${date.year.toString().padLeft(4, '0')}-'
      '${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')}';
}

class UpstoxFnoLiveCapability {
  const UpstoxFnoLiveCapability({
    required this.configured, required this.brokerConnected,
    required this.nfoPermissionVerified, required this.registeredIpMatches,
    required this.contractSourceReady, required this.sessionOpen,
    required this.freshExchangeTimedQuotes, required this.eligibleForOrderPreflight,
    required this.reasons,
  });

  final bool configured, brokerConnected, nfoPermissionVerified;
  final bool registeredIpMatches, contractSourceReady, sessionOpen;
  final bool eligibleForOrderPreflight;
  final int freshExchangeTimedQuotes;
  final List<String> reasons;

  factory UpstoxFnoLiveCapability.decode(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid LIVE F&O capability');
    }
    final boolKeys = [
      'configured', 'brokerConnected', 'nfoPermissionVerified',
      'registeredIpMatches', 'contractSourceReady', 'sessionOpen',
      'eligibleForOrderPreflight',
    ];
    if (boolKeys.any((k) => raw[k] is! bool) ||
        raw['freshExchangeTimedQuotes'] is! int ||
        (raw['freshExchangeTimedQuotes'] as int) < 0 ||
        raw['reasons'] is! List ||
        (raw['reasons'] as List).any((r) => r is! String || r.isEmpty || r.length > 100)) {
      throw const FormatException('Untrusted LIVE F&O capability fields');
    }
    final ready = raw['eligibleForOrderPreflight'] as bool;
    if (ready && (boolKeys.take(6).any((k) => raw[k] != true) ||
        (raw['freshExchangeTimedQuotes'] as int) == 0 ||
        (raw['reasons'] as List).isNotEmpty)) {
      throw const FormatException('Inconsistent LIVE F&O readiness');
    }
    return UpstoxFnoLiveCapability(
      configured: raw['configured'] as bool,
      brokerConnected: raw['brokerConnected'] as bool,
      nfoPermissionVerified: raw['nfoPermissionVerified'] as bool,
      registeredIpMatches: raw['registeredIpMatches'] as bool,
      contractSourceReady: raw['contractSourceReady'] as bool,
      sessionOpen: raw['sessionOpen'] as bool,
      freshExchangeTimedQuotes: raw['freshExchangeTimedQuotes'] as int,
      eligibleForOrderPreflight: ready,
      reasons: List.unmodifiable((raw['reasons'] as List).cast<String>()),
    );
  }
}

class UpstoxFnoLiveReceipt {
  const UpstoxFnoLiveReceipt({
    required this.id, required this.roomId, required this.instrumentKey,
    required this.expiry, required this.side, required this.quantity,
    required this.limitPrice, required this.state, required this.orderTag,
    this.brokerOrderId, this.outcomeCode, required this.createdAt,
  });

  final String id, roomId, instrumentKey, side, state, orderTag;
  final String? brokerOrderId, outcomeCode;
  final DateTime expiry, createdAt;
  final int quantity;
  final num limitPrice;

  factory UpstoxFnoLiveReceipt.decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['id'] is! String || raw['roomId'] is! String ||
        !RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(raw['id'] as String) ||
        !RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(raw['roomId'] as String) ||
        raw['instrumentKey'] is! String ||
        !CustomerUpstoxFnoLiveRepository._contract
            .hasMatch(raw['instrumentKey'] as String) ||
        !const {'BUY','SELL'}.contains(raw['side']) ||
        raw['quantity'] is! int || (raw['quantity'] as int) <= 0 ||
        raw['limitPrice'] is! num ||
        !(raw['limitPrice'] as num).isFinite ||
        (raw['limitPrice'] as num) <= 0 ||
        !const {'SUBMITTING','UNKNOWN','ACKNOWLEDGED','COMPLETE','CANCELLED','REJECTED'}
            .contains(raw['state']) ||
        raw['orderTag'] is! String || (raw['orderTag'] as String).isEmpty ||
        (raw['brokerOrderId'] != null && raw['brokerOrderId'] is! String) ||
        (raw['outcomeCode'] != null && raw['outcomeCode'] is! String)) {
      throw const FormatException('Untrusted LIVE F&O order receipt');
    }
    final expiry = DateTime.tryParse(raw['expiry']?.toString() ?? '');
    final createdAt = DateTime.tryParse(raw['createdAt']?.toString() ?? '');
    if (expiry == null || createdAt == null) {
      throw const FormatException('Unverified LIVE order timestamp');
    }
    return UpstoxFnoLiveReceipt(
      id: raw['id'] as String,
      roomId: raw['roomId'] as String,
      instrumentKey: raw['instrumentKey'] as String,
      expiry: expiry,
      side: raw['side'] as String,
      quantity: raw['quantity'] as int,
      limitPrice: raw['limitPrice'] as num,
      state: raw['state'] as String,
      orderTag: raw['orderTag'] as String,
      brokerOrderId: raw['brokerOrderId'] as String?,
      outcomeCode: raw['outcomeCode'] as String?,
      createdAt: createdAt.toUtc(),
    );
  }
}
