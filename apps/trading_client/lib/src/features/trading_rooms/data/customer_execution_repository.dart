import '../../../data/authenticated_api_client.dart';

/// Shared Paper/Live readiness contract. A false executionEnabled field is
/// enforced by the server; selecting Live in the app cannot change it.
class CustomerExecutionReadiness {
  const CustomerExecutionReadiness({
    required this.roomId,
    required this.mode,
    required this.roomStatus,
    this.selectedBroker,
    required this.brokerConnected,
    required this.virtualCashReady,
    required this.executionEnabled,
    required this.blockers,
  });

  final String roomId;
  final String mode;
  final String roomStatus;
  final String? selectedBroker;
  final bool brokerConnected;
  final bool virtualCashReady;
  final bool executionEnabled;
  final List<String> blockers;

  factory CustomerExecutionReadiness.decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['roomId'] is! String ||
        !(raw['mode'] == 'PAPER' || raw['mode'] == 'LIVE') ||
        raw['roomStatus'] is! String ||
        raw['brokerConnected'] is! bool ||
        raw['virtualCashReady'] is! bool ||
        raw['executionEnabled'] is! bool ||
        raw['blockers'] is! List) {
      throw const FormatException('Invalid execution readiness');
    }
    final blockers = raw['blockers'] as List;
    if (blockers.any((element) => element is! String)) {
      throw const FormatException('Invalid execution blockers');
    }
    return CustomerExecutionReadiness(
      roomId: raw['roomId'] as String,
      mode: raw['mode'] as String,
      roomStatus: raw['roomStatus'] as String,
      selectedBroker: raw['selectedBroker'] == 'ANGEL_ONE' || raw['selectedBroker'] == 'UPSTOX'
          ? raw['selectedBroker'] as String : null,
      brokerConnected: raw['brokerConnected'] as bool,
      virtualCashReady: raw['virtualCashReady'] as bool,
      executionEnabled: raw['executionEnabled'] as bool,
      blockers: List.unmodifiable(blockers.cast<String>()),
    );
  }
}

class HttpCustomerExecutionRepository {
  const HttpCustomerExecutionRepository(this.api);
  final AuthenticatedApiClient api;

  Future<CustomerExecutionReadiness> readiness(String roomId) async {
    return CustomerExecutionReadiness.decode(await api.getJson(
      '/api/customer/trading-rooms/$roomId/execution/readiness',
    ));
  }

  Future<LiveEquityCapability> liveEquityCapability(String roomId) async {
    return LiveEquityCapability.decode(await api.getJson(
      '/api/customer/trading-rooms/$roomId/live/equity/capability',
    ));
  }

  /// Saves an explicitly chosen broker for one owner's LIVE Room only.
  /// This does not authenticate a broker or authorize real-money orders.
  Future<CustomerExecutionReadiness> selectLiveBroker(
      String roomId, String broker) async {
    if (broker != 'ANGEL_ONE' && broker != 'UPSTOX') {
      throw ArgumentError.value(broker, 'broker', 'Unsupported LIVE broker');
    }
    return CustomerExecutionReadiness.decode(await api.postJson(
      '/api/customer/trading-rooms/$roomId/execution/broker-selection',
      body: {'broker': broker},
    ));
  }

  /// Validates a proposed order without submitting it. Server always
  /// returns executable=false until its separate execution gates are ready.
  Future<Object?> preflight({
    required String roomId,
    required int instrumentId,
    required String side,
    required int quantity,
    required num limitPrice,
  }) async => api.postJson(
    '/api/customer/trading-rooms/$roomId/orders/preflight',
    body: {
      'instrumentId': instrumentId,
      'side': side,
      'quantity': quantity,
      'limitPrice': limitPrice,
    },
  );

  /// Submits one LIVE intraday cash-equity order through the server's only
  /// dispatch path. The server, not the client, decides whether it is executable:
  /// callers must only invoke this after [LiveEquityCapability.executionEnabled]
  /// is true and the user has given explicit final confirmation. The request is
  /// never retried here. A transport failure or timeout is reported as an
  /// UNKNOWN outcome by the caller, which must reconcile before any new order.
  Future<LiveEquityOrder> submitLiveEquityOrder({
    required String roomId,
    required String idempotencyKey,
    required int instrumentId,
    required String side,
    required int quantity,
    required num limitPrice,
  }) async {
    final raw = await api.postJson(
      '/api/customer/trading-rooms/$roomId/live/equity/orders',
      headers: {
        'Idempotency-Key': idempotencyKey,
        'X-Confirm-Live-Equity-Order': liveEquityConsentPhrase,
      },
      body: {
        'instrumentId': instrumentId,
        'side': side,
        'quantity': quantity,
        'limitPrice': limitPrice,
      },
    );
    return LiveEquityOrder.decode(raw);
  }

  /// Reconciles one reserved order against the customer's own broker order
  /// book. Read-only with respect to trading: it never submits or retries.
  Future<LiveEquityOrder> reconcileLiveEquityOrder(
      String roomId, String orderId) async {
    return LiveEquityOrder.decode(await api.postJson(
      '/api/customer/trading-rooms/$roomId/live/equity/orders/$orderId/reconcile',
    ));
  }

  /// The owner-scoped LIVE equity order history, as the server records it.
  /// Every element is decoded strictly; a single invalid row fails the whole
  /// list so an unverified row is never silently dropped or shown.
  Future<List<LiveEquityOrder>> listLiveEquityOrders(String roomId) async {
    final raw = await api.getJson(
      '/api/customer/trading-rooms/$roomId/live/equity/orders',
    );
    if (raw is! List) {
      throw const FormatException('Invalid LIVE equity order list');
    }
    return List.unmodifiable(raw.map(LiveEquityOrder.decode));
  }
}

/// Exact per-order consent phrase the server requires. Must match the backend
/// LiveEquityOrderCoordinator.CONSENT constant.
const liveEquityConsentPhrase = 'CONFIRM_LIVE_INTRADAY_EQUITY';

/// Server-owned state of one LIVE equity order. Mirrors the backend
/// LiveEquityOrderState enum. An unrecognised state is a decode failure, never
/// a display value.
enum LiveEquityOrderStatus {
  submitting, unknown, acknowledged, partiallyFilled, filled, rejected, cancelled;

  static LiveEquityOrderStatus? parse(Object? raw) => switch (raw) {
        'SUBMITTING' => submitting,
        'UNKNOWN' => unknown,
        'ACKNOWLEDGED' => acknowledged,
        'PARTIALLY_FILLED' => partiallyFilled,
        'FILLED' => filled,
        'REJECTED' => rejected,
        'CANCELLED' => cancelled,
        _ => null,
      };

  /// True when the server has not yet proven the broker state. The UI must
  /// show these as unresolved and block new orders for this room until
  /// reconciliation.
  bool get needsReconciliation => this == submitting || this == unknown;
}

/// Immutable view of one LIVE equity order as the server reports it. Broker
/// order IDs are shown only as the server supplies them, never reconstructed.
class LiveEquityOrder {
  const LiveEquityOrder({
    required this.id,
    required this.roomId,
    required this.instrumentId,
    required this.tradingSymbol,
    required this.side,
    required this.quantity,
    required this.limitPrice,
    required this.status,
    required this.filledQuantity,
    this.brokerOrderId,
    this.outcomeCode,
  });

  final String id;
  final String roomId;
  final int instrumentId;
  final String tradingSymbol;
  final String side;
  final int quantity;
  final num limitPrice;
  final LiveEquityOrderStatus status;
  final int filledQuantity;
  final String? brokerOrderId;
  final String? outcomeCode;

  factory LiveEquityOrder.decode(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid LIVE equity order');
    }
    final id = raw['id'];
    final roomId = raw['roomId'];
    final instrumentId = raw['instrumentId'];
    final symbol = raw['tradingSymbol'];
    final side = raw['side'];
    final quantity = raw['quantity'];
    final limitPrice = raw['limitPrice'];
    final filled = raw['filledQuantity'];
    final status = LiveEquityOrderStatus.parse(raw['state']);
    if (id is! String || id.isEmpty || roomId is! String ||
        instrumentId is! int || instrumentId < 1 ||
        symbol is! String || symbol.isEmpty ||
        (side != 'BUY' && side != 'SELL') ||
        quantity is! int || quantity < 1 ||
        limitPrice is! num || !limitPrice.isFinite || limitPrice <= 0 ||
        filled is! int || filled < 0 || filled > quantity ||
        status == null) {
      throw const FormatException('Unverified LIVE equity order state');
    }
    final broker = raw['brokerOrderId'];
    final code = raw['outcomeCode'];
    return LiveEquityOrder(
      id: id, roomId: roomId, instrumentId: instrumentId,
      tradingSymbol: symbol, side: side as String, quantity: quantity,
      limitPrice: limitPrice, status: status, filledQuantity: filled,
      brokerOrderId: broker is String && broker.isNotEmpty ? broker : null,
      outcomeCode: code is String ? code : null,
    );
  }
}

/// Server-owned readiness for the live cash intraday order surface. A broker
/// connection alone never marks this path executable.
class LiveEquityCapability {
  const LiveEquityCapability({
    required this.roomId,
    this.selectedBroker,
    required this.brokerConnected,
    required this.orderRouteImplemented,
    required this.executionEnabled,
    required this.blockers,
  });

  final String roomId;
  final String? selectedBroker;
  final bool brokerConnected;
  final bool orderRouteImplemented;
  final bool executionEnabled;
  final List<String> blockers;

  factory LiveEquityCapability.decode(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['roomId'] is! String ||
        raw['brokerConnected'] is! bool ||
        raw['orderRouteImplemented'] is! bool ||
        raw['executionEnabled'] is! bool || raw['blockers'] is! List) {
      throw const FormatException('Invalid live cash-equity capability');
    }
    final blockers = raw['blockers'] as List;
    if (blockers.any((item) => item is! String)) {
      throw const FormatException('Invalid live cash-equity blockers');
    }
    final broker = raw['selectedBroker'];
    final selectedBroker =
        broker == 'ANGEL_ONE' || broker == 'UPSTOX' ? broker as String : null;
    final brokerConnected = raw['brokerConnected'] as bool;
    final orderRouteImplemented = raw['orderRouteImplemented'] as bool;
    final executionEnabled = raw['executionEnabled'] as bool;
    if (executionEnabled &&
        (!orderRouteImplemented ||
            !brokerConnected ||
            selectedBroker == null ||
            blockers.isNotEmpty)) {
      throw const FormatException('Inconsistent executable cash-equity capability');
    }
    return LiveEquityCapability(
      roomId: raw['roomId'] as String,
      selectedBroker: selectedBroker,
      brokerConnected: brokerConnected,
      orderRouteImplemented: orderRouteImplemented,
      executionEnabled: executionEnabled,
      blockers: List.unmodifiable(blockers.cast<String>()),
    );
  }
}
