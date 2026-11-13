
import 'dart:math';
import '../../../data/authenticated_api_client.dart';

/// Auditable LIVE order preparation. No method here routes to a broker or
/// executes a trade: the backend deliberately stores PRECHECK_BLOCKED only.
class HttpCustomerTradeIntentRepository {
  const HttpCustomerTradeIntentRepository(this.api);
  final AuthenticatedApiClient api;

  static String newKey() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    return 'intent_${bytes.map((x) => x.toRadixString(16).padLeft(2, '0')).join()}';
  }

  String _root(String roomId) {
    if (!RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(roomId)) {
      throw const FormatException('Invalid room identifier');
    }
    return '/api/customer/trading-rooms/$roomId/orders/intents';
  }

  Future<List<CustomerBlockedOrderIntent>> list(String roomId) async {
    final raw = await api.getJson(_root(roomId));
    if (raw is! List) throw const FormatException('Invalid order intent history');
    return raw.map(CustomerBlockedOrderIntent.decode).toList(growable: false);
  }

  Future<CustomerBlockedOrderIntent> create({
    required String roomId, required String idempotencyKey,
    required int instrumentId, required String side,
    required int quantity, required String productType,
    required String limitPrice,
  }) async {
    if (!RegExp(r'^[A-Za-z0-9_-]{8,64}$').hasMatch(idempotencyKey) ||
        instrumentId <= 0 || (side != 'BUY' && side != 'SELL') ||
        (productType != 'DELIVERY' && productType != 'INTRADAY') ||
        quantity < 1 || quantity > 10000 ||
        !RegExp(r'^\d{1,8}(\.\d{1,2})?$').hasMatch(limitPrice)) {
      throw const FormatException('Invalid blocked order intent');
    }
    final price = num.tryParse(limitPrice);
    if (price == null || !price.isFinite || price <= 0 || price > 10000000) {
      throw const FormatException('Invalid limit price');
    }
    return CustomerBlockedOrderIntent.decode(await api.postJson(
      _root(roomId),
      headers: {'Idempotency-Key': idempotencyKey},
      body: {
        'instrumentId': instrumentId, 'side': side,
        'quantity': quantity, 'productType': productType,
        'limitPrice': price,
      },
    ));
  }
}

class CustomerBlockedOrderIntent {
  const CustomerBlockedOrderIntent({
    required this.id, required this.roomId, required this.mode,
    required this.instrumentId, required this.tradingSymbol,
    required this.exchange, required this.side, required this.quantity,
    required this.productType, required this.limitPrice,
    required this.state, required this.blockers,
  });
  final String id, roomId, mode, tradingSymbol, exchange, side, productType, state;
  final int instrumentId, quantity;
  final num limitPrice;
  final List<String> blockers;

  factory CustomerBlockedOrderIntent.decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['id'] is! String || raw['roomId'] is! String ||
        !(raw['mode'] == 'PAPER' || raw['mode'] == 'LIVE') ||
        raw['instrumentId'] is! int || raw['tradingSymbol'] is! String ||
        raw['exchange'] is! String || raw['side'] is! String ||
        raw['quantity'] is! int || raw['productType'] is! String ||
        raw['limitPrice'] is! num || raw['state'] != 'PRECHECK_BLOCKED' ||
        raw['blockers'] is! List ||
        (raw['blockers'] as List).any((x) => x is! String)) {
      throw const FormatException('Unrecognized blocked order intent');
    }
    return CustomerBlockedOrderIntent(
      id: raw['id'] as String, roomId: raw['roomId'] as String,
      mode: raw['mode'] as String, instrumentId: raw['instrumentId'] as int,
      tradingSymbol: raw['tradingSymbol'] as String,
      exchange: raw['exchange'] as String, side: raw['side'] as String,
      quantity: raw['quantity'] as int, productType: raw['productType'] as String,
      limitPrice: raw['limitPrice'] as num,
      state: raw['state'] as String,
      blockers: List.unmodifiable((raw['blockers'] as List).cast<String>()),
    );
  }
}
