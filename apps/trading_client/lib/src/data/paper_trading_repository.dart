import 'authenticated_api_client.dart';

class PaperPosition {
  const PaperPosition({
    required this.instrumentId,
    required this.exchange,
    required this.symbol,
    required this.productType,
    required this.quantity,
    required this.averageCost,
    required this.costBasis,
    required this.markPrice,
    required this.marketValue,
    required this.unrealizedPnl,
    required this.stopLoss,
    required this.target,
    required this.openedAt,
  });

  final int? instrumentId;
  final String exchange;
  final String symbol;
  final String productType;
  final int quantity;
  final num? averageCost;
  final num? costBasis;
  final num? markPrice;
  final num? marketValue;
  final num? unrealizedPnl;
  final num? stopLoss;
  final num? target;
  final DateTime? openedAt;

  factory PaperPosition.fromJson(Map<String, dynamic> json) => PaperPosition(
        instrumentId: (json['instrumentId'] as num?)?.toInt(),
        exchange: '${json['exchange'] ?? ''}',
        symbol: '${json['symbol'] ?? ''}',
        productType: '${json['productType'] ?? ''}',
        quantity: (json['quantity'] as num?)?.toInt() ?? 0,
        averageCost: json['averageCost'] as num?,
        costBasis: json['costBasis'] as num?,
        markPrice: json['markPrice'] as num?,
        marketValue: json['marketValue'] as num?,
        unrealizedPnl: json['unrealizedPnl'] as num?,
        stopLoss: json['stopLoss'] as num?,
        target: json['target'] as num?,
        openedAt: DateTime.tryParse('${json['openedAt'] ?? ''}'),
      );
}

class PaperPortfolio {
  const PaperPortfolio({
    required this.mode,
    required this.liveTradingEnabled,
    required this.newOrdersHalted,
    required this.startingCapital,
    required this.cash,
    required this.invested,
    required this.marketValue,
    required this.equity,
    required this.realizedPnl,
    required this.unrealizedPnl,
    required this.realizedPnlToday,
    required this.exposurePercent,
    required this.openPositions,
    required this.cashReconciled,
    required this.markPriceSource,
    required this.positions,
    required this.asOf,
  });

  final String mode;
  final bool liveTradingEnabled;
  final bool newOrdersHalted;
  final num? startingCapital;
  final num? cash;
  final num? invested;
  final num? marketValue;
  final num? equity;
  final num? realizedPnl;
  final num? unrealizedPnl;
  final num? realizedPnlToday;
  final num? exposurePercent;
  final int openPositions;
  final bool cashReconciled;
  final String? markPriceSource;
  final List<PaperPosition> positions;
  final DateTime? asOf;

  factory PaperPortfolio.fromJson(Map<String, dynamic> json) {
    final rawPositions = json['positions'];
    return PaperPortfolio(
      mode: '${json['mode'] ?? 'UNKNOWN'}',
      liveTradingEnabled: json['liveTradingEnabled'] == true,
      newOrdersHalted: json['newOrdersHalted'] == true,
      startingCapital: json['startingCapital'] as num?,
      cash: json['cash'] as num?,
      invested: json['invested'] as num?,
      marketValue: json['marketValue'] as num?,
      equity: json['equity'] as num?,
      realizedPnl: json['realizedPnl'] as num?,
      unrealizedPnl: json['unrealizedPnl'] as num?,
      realizedPnlToday: json['realizedPnlToday'] as num?,
      exposurePercent: json['exposurePercent'] as num?,
      openPositions: (json['openPositions'] as num?)?.toInt() ?? 0,
      cashReconciled: json['cashReconciled'] == true,
      markPriceSource: json['markPriceSource']?.toString(),
      positions: rawPositions is List
          ? rawPositions
              .whereType<Map>()
              .map((item) => PaperPosition.fromJson(Map<String, dynamic>.from(item)))
              .toList(growable: false)
          : const [],
      asOf: DateTime.tryParse('${json['asOf'] ?? ''}'),
    );
  }
}

class PaperOrder {
  const PaperOrder({
    required this.id,
    required this.symbol,
    required this.side,
    required this.quantity,
    required this.filledQuantity,
    required this.orderType,
    required this.productType,
    required this.averageFillPrice,
    required this.entryPrice,
    required this.stopLoss,
    required this.target,
    required this.status,
    required this.rejectionReason,
    required this.createdAt,
    required this.updatedAt,
    required this.correlationId,
  });

  final String id;
  final String symbol;
  final String side;
  final int quantity;
  final int filledQuantity;
  final String orderType;
  final String productType;
  final num? averageFillPrice;
  final num? entryPrice;
  final num? stopLoss;
  final num? target;
  final String status;
  final String? rejectionReason;
  final DateTime? createdAt;
  final DateTime? updatedAt;
  final String? correlationId;

  factory PaperOrder.fromJson(Map<String, dynamic> json) => PaperOrder(
        id: '${json['id'] ?? ''}',
        symbol: '${json['symbol'] ?? ''}',
        side: '${json['side'] ?? ''}',
        quantity: (json['quantity'] as num?)?.toInt() ?? 0,
        filledQuantity: (json['filledQuantity'] as num?)?.toInt() ?? 0,
        orderType: '${json['orderType'] ?? ''}',
        productType: '${json['productType'] ?? ''}',
        averageFillPrice: json['averageFillPrice'] as num?,
        entryPrice: json['entryPrice'] as num?,
        stopLoss: json['stopLoss'] as num?,
        target: json['target'] as num?,
        status: '${json['status'] ?? 'UNKNOWN'}',
        rejectionReason: json['rejectionReason']?.toString(),
        createdAt: DateTime.tryParse('${json['createdAt'] ?? ''}'),
        updatedAt: DateTime.tryParse('${json['updatedAt'] ?? ''}'),
        correlationId: json['correlationId']?.toString(),
      );
}

class PaperOrderPage {
  const PaperOrderPage({required this.items, required this.page, required this.totalPages, required this.totalItems});

  final List<PaperOrder> items;
  final int page;
  final int totalPages;
  final int totalItems;

  factory PaperOrderPage.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'];
    return PaperOrderPage(
      items: rawItems is List
          ? rawItems
              .whereType<Map>()
              .map((item) => PaperOrder.fromJson(Map<String, dynamic>.from(item)))
              .toList(growable: false)
          : const [],
      page: (json['page'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      totalItems: (json['totalItems'] as num?)?.toInt() ?? 0,
    );
  }
}

class PaperOrderResult {
  const PaperOrderResult({required this.order, required this.replayed, required this.reasonCodes});

  final PaperOrder order;
  final bool replayed;
  final List<String> reasonCodes;

  factory PaperOrderResult.fromJson(Map<String, dynamic> json) {
    final order = json['order'];
    final decision = json['decision'];
    final reasons = decision is Map ? decision['reasonCodes'] : null;
    if (order is! Map) {
      throw const FormatException('Missing PAPER order result');
    }
    return PaperOrderResult(
      order: PaperOrder.fromJson(Map<String, dynamic>.from(order)),
      replayed: json['replayed'] == true,
      reasonCodes: reasons is List ? reasons.map((value) => '$value').toList(growable: false) : const [],
    );
  }
}

class CreatePaperOrderCommand {
  const CreatePaperOrderCommand({
    required this.symbol,
    required this.side,
    required this.quantity,
    required this.referencePrice,
    required this.productType,
    required this.signalGeneratedAt,
    this.stopLoss,
    this.target,
    this.trailingPercent,
  });

  final String symbol;
  final String side;
  final int quantity;
  final num referencePrice;
  final String productType;
  final DateTime signalGeneratedAt;
  final num? stopLoss;
  final num? target;
  final num? trailingPercent;

  Map<String, Object?> toJson() => {
        'symbol': symbol,
        'side': side,
        'quantity': quantity,
        'referencePrice': referencePrice,
        'stopLoss': stopLoss,
        'target': target,
        'trailingPercent': trailingPercent,
        'signalGeneratedAt': signalGeneratedAt.toUtc().toIso8601String(),
        'productType': productType,
        'orderType': 'MARKET',
        'limitPrice': null,
      };
}

abstract interface class PaperTradingRepository {
  Future<PaperPortfolio> fetchPortfolio();
  Future<PaperOrderPage> fetchOrders({int page = 0, int size = 20, String? status, String? symbol});
  Future<PaperOrderResult> createOrder(CreatePaperOrderCommand command, {required String idempotencyKey});
  Future<PaperOrderResult> cancelOrder(String orderId);
}

class HttpPaperTradingRepository implements PaperTradingRepository {
  HttpPaperTradingRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<PaperPortfolio> fetchPortfolio() async {
    final decoded = await api.getJson('/api/paper-portfolio');
    if (decoded is! Map) throw const ApiTransportFailure('INVALID_PAPER_PORTFOLIO_RESPONSE', null);
    return PaperPortfolio.fromJson(Map<String, dynamic>.from(decoded));
  }

  @override
  Future<PaperOrderPage> fetchOrders({int page = 0, int size = 20, String? status, String? symbol}) async {
    final decoded = await api.getJson(
      '/api/paper-trades',
      query: {'page': '$page', 'size': '$size', 'status': status, 'symbol': symbol},
    );
    if (decoded is! Map) throw const ApiTransportFailure('INVALID_PAPER_ORDER_LIST_RESPONSE', null);
    return PaperOrderPage.fromJson(Map<String, dynamic>.from(decoded));
  }

  @override
  Future<PaperOrderResult> createOrder(CreatePaperOrderCommand command, {required String idempotencyKey}) async {
    final decoded = await api.postJson(
      '/api/paper-orders',
      body: command.toJson(),
      headers: {'Idempotency-Key': idempotencyKey},
    );
    if (decoded is! Map) throw const ApiTransportFailure('INVALID_PAPER_ORDER_RESPONSE', null);
    return PaperOrderResult.fromJson(Map<String, dynamic>.from(decoded));
  }

  @override
  Future<PaperOrderResult> cancelOrder(String orderId) async {
    final decoded = await api.postJson('/api/paper-orders/$orderId/cancel');
    if (decoded is! Map) throw const ApiTransportFailure('INVALID_PAPER_ORDER_RESPONSE', null);
    return PaperOrderResult.fromJson(Map<String, dynamic>.from(decoded));
  }
}
