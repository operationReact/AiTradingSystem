import 'authenticated_api_client.dart';

class PaperOrder {
  const PaperOrder({required this.id, required this.symbol, required this.side, required this.quantity, required this.filledQuantity, required this.status, required this.averageFillPrice, required this.rejectionReason, required this.createdAt});
  final String id;
  final String symbol;
  final String side;
  final int quantity;
  final int filledQuantity;
  final String status;
  final num? averageFillPrice;
  final String? rejectionReason;
  final DateTime? createdAt;

  factory PaperOrder.fromJson(Map<String, dynamic> json) => PaperOrder(
        id: '${json['id'] ?? ''}',
        symbol: '${json['symbol'] ?? ''}',
        side: '${json['side'] ?? ''}',
        quantity: (json['quantity'] as num?)?.toInt() ?? 0,
        filledQuantity: (json['filledQuantity'] as num?)?.toInt() ?? 0,
        status: '${json['status'] ?? 'UNKNOWN'}',
        averageFillPrice: json['averageFillPrice'] as num?,
        rejectionReason: json['rejectionReason']?.toString(),
        createdAt: DateTime.tryParse('${json['createdAt'] ?? ''}'),
      );
}

class PortfolioSnapshot {
  const PortfolioSnapshot({required this.mode, required this.newOrdersHalted, required this.cash, required this.invested, required this.marketValue, required this.equity, required this.realizedPnl, required this.unrealizedPnl, required this.exposurePercent, required this.openPositions, required this.cashReconciled});
  final String mode;
  final bool newOrdersHalted;
  final num cash;
  final num invested;
  final num marketValue;
  final num equity;
  final num realizedPnl;
  final num unrealizedPnl;
  final num exposurePercent;
  final int openPositions;
  final bool cashReconciled;

  factory PortfolioSnapshot.fromJson(Map<String, dynamic> json) {
    num n(String key) => json[key] is num ? json[key] as num : num.tryParse('${json[key] ?? 0}') ?? 0;
    return PortfolioSnapshot(
      mode: '${json['mode'] ?? 'UNKNOWN'}',
      newOrdersHalted: json['newOrdersHalted'] == true,
      cash: n('cash'),
      invested: n('invested'),
      marketValue: n('marketValue'),
      equity: n('equity'),
      realizedPnl: n('realizedPnl'),
      unrealizedPnl: n('unrealizedPnl'),
      exposurePercent: n('exposurePercent'),
      openPositions: n('openPositions').toInt(),
      cashReconciled: json['cashReconciled'] == true,
    );
  }
}

class PositionSizeSuggestion {
  const PositionSizeSuggestion({required this.quantity, required this.riskPerShare, required this.riskBudgetAmount, required this.estimatedRisk, required this.estimatedNotional, required this.estimatedFee, required this.limitingRule, required this.policyVersion});
  final int quantity;
  final num riskPerShare;
  final num riskBudgetAmount;
  final num estimatedRisk;
  final num estimatedNotional;
  final num estimatedFee;
  final String limitingRule;
  final int policyVersion;

  factory PositionSizeSuggestion.fromJson(Map<String, dynamic> json) {
    num n(String key) => json[key] is num ? json[key] as num : num.tryParse('${json[key] ?? 0}') ?? 0;
    return PositionSizeSuggestion(
      quantity: n('quantity').toInt(),
      riskPerShare: n('riskPerShare'),
      riskBudgetAmount: n('riskBudgetAmount'),
      estimatedRisk: n('estimatedRisk'),
      estimatedNotional: n('estimatedNotional'),
      estimatedFee: n('estimatedFee'),
      limitingRule: '${json['limitingRule'] ?? 'UNKNOWN'}',
      policyVersion: n('policyVersion').toInt(),
    );
  }
}

class ApprovalItem {
  const ApprovalItem({required this.id, required this.orderId, required this.state, required this.decisionReason, required this.requestedAt, required this.expiresAt, required this.rowVersion});
  final String id;
  final String orderId;
  final String state;
  final String? decisionReason;
  final DateTime? requestedAt;
  final DateTime? expiresAt;
  final int rowVersion;

  factory ApprovalItem.fromJson(Map<String, dynamic> json) => ApprovalItem(
        id: '${json['id'] ?? ''}',
        orderId: '${json['orderId'] ?? ''}',
        state: '${json['state'] ?? 'UNKNOWN'}',
        decisionReason: json['decisionReason']?.toString(),
        requestedAt: DateTime.tryParse('${json['requestedAt'] ?? ''}'),
        expiresAt: DateTime.tryParse('${json['expiresAt'] ?? ''}'),
        rowVersion: (json['rowVersion'] as num?)?.toInt() ?? 0,
      );
}

abstract interface class ExecutionRepository {
  Future<PortfolioSnapshot> portfolio();
  Future<List<PaperOrder>> orders({String? status});
  Future<PositionSizeSuggestion> positionSize({required int instrumentId, required String exchange, required String symbol, required num entryPrice, required num stopLoss, required num targetPrice, required String strategy, String tradingStyle = 'SWING'});
  Future<PaperOrder> createOrder({required String idempotencyKey, required String symbol, required String side, required int quantity, required num referencePrice, num? stopLoss, num? target, String productType = 'DELIVERY'});
  Future<List<ApprovalItem>> approvals({String? state});
  Future<ApprovalItem> decideApproval({required ApprovalItem approval, required String decision, required String actor, String? reason});
  Future<bool> setEmergencyStop({required bool halted, required String reason});
}

class HttpExecutionRepository implements ExecutionRepository {
  HttpExecutionRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<PortfolioSnapshot> portfolio() async {
    final decoded = await api.getJson('/api/paper-portfolio');
    if (decoded is! Map<String, dynamic>) throw const ApiTransportFailure('INVALID_PORTFOLIO_RESPONSE', null);
    return PortfolioSnapshot.fromJson(decoded);
  }

  @override
  Future<List<PaperOrder>> orders({String? status}) async {
    final decoded = await api.getJson('/api/paper-trades', query: {'page': '0', 'size': '50', 'status': status});
    if (decoded is! Map<String, dynamic>) throw const ApiTransportFailure('INVALID_ORDER_HISTORY_RESPONSE', null);
    final content = decoded['content'];
    if (content is! List<dynamic>) throw const ApiTransportFailure('INVALID_ORDER_HISTORY_RESPONSE', null);
    return content.whereType<Map<String, dynamic>>().map(PaperOrder.fromJson).toList(growable: false);
  }

  @override
  Future<PositionSizeSuggestion> positionSize({required int instrumentId, required String exchange, required String symbol, required num entryPrice, required num stopLoss, required num targetPrice, required String strategy, String tradingStyle = 'SWING'}) async {
    final decoded = await api.postJson('/api/risk/position-size', body: {
      'instrumentId': instrumentId,
      'exchange': exchange,
      'symbol': symbol,
      'entryPrice': entryPrice,
      'stopLoss': stopLoss,
      'targetPrice': targetPrice,
      'strategy': strategy,
      'tradingStyle': tradingStyle,
    });
    if (decoded is! Map<String, dynamic>) throw const ApiTransportFailure('INVALID_POSITION_SIZE_RESPONSE', null);
    return PositionSizeSuggestion.fromJson(decoded);
  }

  @override
  Future<PaperOrder> createOrder({required String idempotencyKey, required String symbol, required String side, required int quantity, required num referencePrice, num? stopLoss, num? target, String productType = 'DELIVERY'}) async {
    final decoded = await api.postJson('/api/paper-orders', headers: {'Idempotency-Key': idempotencyKey}, body: {
      'symbol': symbol,
      'side': side,
      'quantity': quantity,
      'referencePrice': referencePrice,
      'stopLoss': stopLoss,
      'target': target,
      'signalGeneratedAt': DateTime.now().toUtc().toIso8601String(),
      'productType': productType,
      'orderType': 'MARKET',
      'limitPrice': null,
    });
    if (decoded is! Map<String, dynamic> || decoded['order'] is! Map<String, dynamic>) throw const ApiTransportFailure('INVALID_ORDER_RESPONSE', null);
    return PaperOrder.fromJson(decoded['order'] as Map<String, dynamic>);
  }

  @override
  Future<List<ApprovalItem>> approvals({String? state}) async {
    final decoded = await api.getJson('/api/approvals', query: {'state': state, 'page': '0', 'size': '50'});
    if (decoded is! Map<String, dynamic> || decoded['content'] is! List<dynamic>) throw const ApiTransportFailure('INVALID_APPROVAL_RESPONSE', null);
    return (decoded['content'] as List<dynamic>).whereType<Map<String, dynamic>>().map(ApprovalItem.fromJson).toList(growable: false);
  }

  @override
  Future<ApprovalItem> decideApproval({required ApprovalItem approval, required String decision, required String actor, String? reason}) async {
    final decoded = await api.postJson('/api/approvals/${approval.id}/$decision', body: {
      'expectedRowVersion': approval.rowVersion,
      'actor': actor,
      'reason': reason,
    });
    if (decoded is! Map<String, dynamic>) throw const ApiTransportFailure('INVALID_APPROVAL_DECISION_RESPONSE', null);
    return ApprovalItem.fromJson(decoded);
  }

  @override
  Future<bool> setEmergencyStop({required bool halted, required String reason}) async {
    final decoded = await api.putJson('/api/risk/emergency-stop', body: {'halted': halted, 'reason': reason});
    if (decoded is! Map<String, dynamic>) throw const ApiTransportFailure('INVALID_EMERGENCY_STOP_RESPONSE', null);
    return decoded['newOrdersHalted'] == true;
  }
}
