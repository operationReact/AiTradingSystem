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
}
