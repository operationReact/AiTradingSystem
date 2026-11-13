import '../../../data/authenticated_api_client.dart';
import 'customer_room_repository.dart';

/// Customer-owned, manual-only virtual fills. This API can never submit an
/// Angel One or Upstox brokerage order; LIVE room IDs are rejected by backend.
class HttpCustomerPaperTradingRepository {
  HttpCustomerPaperTradingRepository(this.api);
  final AuthenticatedApiClient api;

  String _root(String roomId) {
    final room = RegExp(r'^[0-9a-fA-F-]{36}$');
    if (!room.hasMatch(roomId)) throw const FormatException('Invalid Paper Room');
    return '/api/customer/trading-rooms/$roomId/paper';
  }

  Future<CustomerPaperWallet> resume(String roomId) async =>
      CustomerPaperWallet.decode(await api.postJson('${_root(roomId)}/resume'));

  Future<CustomerPaperWallet> pause(String roomId) async =>
      CustomerPaperWallet.decode(await api.postJson('${_root(roomId)}/pause'));

  Future<List<CustomerPaperOrder>> orders(String roomId) async {
    final value = await api.getJson('${_root(roomId)}/orders');
    if (value is! List) throw const FormatException('Invalid Paper fill history');
    return value.map((row) => CustomerPaperOrder.decode(row)).toList(growable: false);
  }

  Future<List<CustomerPaperPosition>> positions(String roomId) async {
    final value = await api.getJson('${_root(roomId)}/positions');
    if (value is! List) throw const FormatException('Invalid Paper positions');
    return value.map((row) => CustomerPaperPosition.decode(row)).toList(growable: false);
  }

  Future<CustomerPaperOrder> submit({
    required String roomId,
    required String idempotencyKey,
    required int instrumentId,
    required String side,
    required int quantity,
    required String limitPrice,
  }) async {
    if (!RegExp(r'^[A-Za-z0-9_-]{8,64}$').hasMatch(idempotencyKey) ||
        instrumentId <= 0 || (side != 'BUY' && side != 'SELL') ||
        quantity <= 0 || quantity > 10000 ||
        !RegExp(r'^\d{1,7}(\.\d{1,4})?$').hasMatch(limitPrice)) {
      throw const FormatException('Invalid Paper order');
    }
    // Decimal JSON number is accepted by backend. Only the validated server
    // broker price is used for the actual simulated fill.
    final limit = num.tryParse(limitPrice);
    if (limit == null || !limit.isFinite || limit <= 0) {
      throw const FormatException('Invalid Paper limit');
    }
    final raw = await api.postJson('${_root(roomId)}/orders',
        headers: {'Idempotency-Key': idempotencyKey},
        body: {
          'instrumentId': instrumentId,
          'side': side,
          'quantity': quantity,
          'limitPrice': limit,
        });
    return CustomerPaperOrder.decode(raw);
  }
}

class CustomerPaperOrder {
  const CustomerPaperOrder({
    required this.id, required this.roomId, required this.instrumentId,
    required this.tradingSymbol, required this.exchange, required this.side,
    required this.quantity, required this.fillPrice,
    required this.limitPrice, required this.notionalPaise,
    required this.feesPaise, required this.cashAfterPaise,
    required this.exchangeAt,
  });

  final String id, roomId, tradingSymbol, exchange, side;
  final int instrumentId, quantity, notionalPaise, feesPaise, cashAfterPaise;
  final num fillPrice, limitPrice;
  final DateTime exchangeAt;

  static CustomerPaperOrder decode(Object? raw) {
    if (raw is! Map<String, dynamic>) throw const FormatException('Invalid Paper receipt');
    final exchangeAt = DateTime.tryParse('${raw['exchangeAt'] ?? ''}');
    final id = raw['id'], roomId = raw['roomId'];
    final instrumentId = raw['instrumentId'];
    final symbol = raw['tradingSymbol'], exchange = raw['exchange'];
    final side = raw['side'], qty = raw['quantity'];
    final fill = raw['fillPrice'], limit = raw['limitPrice'];
    final notional = raw['notionalPaise'], fees = raw['feesPaise'];
    final cash = raw['cashAfterPaise'];
    if (id is! String || roomId is! String ||
        instrumentId is! int || instrumentId <= 0 ||
        symbol is! String || symbol.isEmpty ||
        !(exchange == 'NSE' || exchange == 'BSE') ||
        !(side == 'BUY' || side == 'SELL') ||
        qty is! int || qty < 1 || qty > 10000 ||
        fill is! num || fill <= 0 || limit is! num || limit <= 0 ||
        notional is! int || notional <= 0 ||
        fees is! int || fees <= 0 || cash is! int || cash < 0 ||
        exchangeAt == null) {
      throw const FormatException('Invalid Paper fill receipt');
    }
    return CustomerPaperOrder(
      id: id, roomId: roomId, instrumentId: instrumentId,
      tradingSymbol: symbol, exchange: exchange,
      side: side, quantity: qty,
      fillPrice: fill, limitPrice: limit, notionalPaise: notional,
      feesPaise: fees, cashAfterPaise: cash, exchangeAt: exchangeAt,
    );
  }
}

class CustomerPaperPosition {
  const CustomerPaperPosition({
    required this.instrumentId,
    required this.quantity,
    required this.avgCostPaise,
  });
  final int instrumentId, quantity, avgCostPaise;

  static CustomerPaperPosition decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['instrumentId'] is! int || (raw['instrumentId'] as int) <= 0 ||
        raw['quantity'] is! int || (raw['quantity'] as int) < 0 ||
        raw['avgCostPaise'] is! int || (raw['avgCostPaise'] as int) < 0) {
      throw const FormatException('Invalid Paper position');
    }
    return CustomerPaperPosition(
      instrumentId: raw['instrumentId'] as int,
      quantity: raw['quantity'] as int,
      avgCostPaise: raw['avgCostPaise'] as int,
    );
  }
}
