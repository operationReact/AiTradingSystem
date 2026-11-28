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

  Future<LiveEquityOrder> submitLiveOrder({
    required String roomId,
    required String idempotencyKey,
    required int instrumentId,
    required String side,
    required int quantity,
    required num limitPrice,
  }) async => LiveEquityOrder.decode(await api.postJson(
    '/api/customer/trading-rooms/$roomId/live/equity/orders',
    headers: {
      'Idempotency-Key': idempotencyKey,
      'X-Confirm-Live-Equity-Order': 'CONFIRM_LIVE_INTRADAY_EQUITY',
    },
    body: {
      'instrumentId': instrumentId,
      'side': side,
      'quantity': quantity,
      'limitPrice': limitPrice,
    },
  ));

  Future<List<LiveEquityOrder>> liveOrders(String roomId) async {
    final raw = await api.getJson(
      '/api/customer/trading-rooms/$roomId/live/equity/orders',
    );
    if (raw is! List) throw const FormatException('Invalid LIVE order history');
    return List.unmodifiable(raw.map(LiveEquityOrder.decode));
  }

  Future<LiveEquityOrder> reconcileLiveOrder({
    required String roomId,
    required String orderId,
  }) async => LiveEquityOrder.decode(await api.postJson(
    '/api/customer/trading-rooms/$roomId/live/equity/orders/$orderId/reconcile',
  ));
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

/// Owner-scoped state returned by the guarded LIVE equity order API.
class LiveEquityOrder {
  const LiveEquityOrder({
    required this.id,
    required this.roomId,
    required this.broker,
    required this.instrumentId,
    required this.tradingSymbol,
    required this.side,
    required this.quantity,
    required this.limitPrice,
    required this.state,
    required this.filledQuantity,
  });

  final String id, roomId, broker, tradingSymbol, side, state;
  final int instrumentId, quantity, filledQuantity;
  final num limitPrice;

  bool get requiresReconciliation =>
      const {'SUBMITTING', 'UNKNOWN', 'ACKNOWLEDGED', 'PARTIALLY_FILLED'}.contains(state);

  factory LiveEquityOrder.decode(Object? raw) {
    const states = {
      'SUBMITTING', 'UNKNOWN', 'ACKNOWLEDGED', 'PARTIALLY_FILLED',
      'FILLED', 'REJECTED', 'CANCELLED',
    };
    if (raw is! Map<String, dynamic> ||
        raw['id'] is! String ||
        raw['roomId'] is! String ||
        !const {'ANGEL_ONE', 'UPSTOX'}.contains(raw['broker']) ||
        raw['instrumentId'] is! int ||
        raw['tradingSymbol'] is! String ||
        !const {'BUY', 'SELL'}.contains(raw['side']) ||
        raw['quantity'] is! int ||
        raw['limitPrice'] is! num ||
        !states.contains(raw['state']) ||
        raw['filledQuantity'] is! int) {
      throw const FormatException('Invalid LIVE equity order');
    }
    return LiveEquityOrder(
      id: raw['id'] as String,
      roomId: raw['roomId'] as String,
      broker: raw['broker'] as String,
      instrumentId: raw['instrumentId'] as int,
      tradingSymbol: raw['tradingSymbol'] as String,
      side: raw['side'] as String,
      quantity: raw['quantity'] as int,
      limitPrice: raw['limitPrice'] as num,
      state: raw['state'] as String,
      filledQuantity: raw['filledQuantity'] as int,
    );
  }
}
