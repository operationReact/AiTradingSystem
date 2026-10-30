import 'authenticated_api_client.dart';

class RiskConfig {
  const RiskConfig({
    required this.tradingMode,
    required this.liveTradingEnabled,
    required this.policyVersion,
    required this.startingCapital,
    required this.riskPerTradePercent,
    required this.maxDailyDrawdownPercent,
    required this.maxOpenPositions,
    required this.signalMaxAgeMinutes,
    required this.newOrdersHalted,
  });

  final String tradingMode;
  final bool liveTradingEnabled;
  final String policyVersion;
  final num? startingCapital;
  final num? riskPerTradePercent;
  final num? maxDailyDrawdownPercent;
  final int maxOpenPositions;
  final int signalMaxAgeMinutes;
  final bool newOrdersHalted;

  factory RiskConfig.fromJson(Map<String, dynamic> json) => RiskConfig(
        tradingMode: '${json['tradingMode'] ?? 'UNKNOWN'}',
        liveTradingEnabled: json['liveTradingEnabled'] == true,
        policyVersion: '${json['policyVersion'] ?? 'UNKNOWN'}',
        startingCapital: json['startingCapital'] as num?,
        riskPerTradePercent: json['riskPerTradePercent'] as num?,
        maxDailyDrawdownPercent: json['maxDailyDrawdownPercent'] as num?,
        maxOpenPositions: (json['maxOpenPositions'] as num?)?.toInt() ?? 0,
        signalMaxAgeMinutes: (json['signalMaxAgeMinutes'] as num?)?.toInt() ?? 0,
        newOrdersHalted: json['newOrdersHalted'] == true,
      );
}

class RiskDecisionSummaryView {
  const RiskDecisionSummaryView({
    required this.id,
    required this.orderId,
    required this.decision,
    required this.reasonCodes,
    required this.policyVersion,
    required this.resultClass,
    required this.failedRuleCount,
    required this.evaluatedAt,
    required this.correlationId,
  });

  final String id;
  final String orderId;
  final String decision;
  final List<String> reasonCodes;
  final String policyVersion;
  final String? resultClass;
  final int failedRuleCount;
  final DateTime? evaluatedAt;
  final String? correlationId;

  factory RiskDecisionSummaryView.fromJson(Map<String, dynamic> json) => RiskDecisionSummaryView(
        id: '${json['id'] ?? ''}',
        orderId: '${json['orderId'] ?? ''}',
        decision: '${json['decision'] ?? 'UNKNOWN'}',
        reasonCodes: json['reasonCodes'] is List
            ? (json['reasonCodes'] as List).map((value) => '$value').toList(growable: false)
            : const [],
        policyVersion: '${json['policyVersion'] ?? 'UNKNOWN'}',
        resultClass: json['resultClass']?.toString(),
        failedRuleCount: (json['failedRuleCount'] as num?)?.toInt() ?? 0,
        evaluatedAt: DateTime.tryParse('${json['evaluatedAt'] ?? ''}'),
        correlationId: json['correlationId']?.toString(),
      );
}

class ApprovalView {
  const ApprovalView({
    required this.id,
    required this.orderId,
    required this.policyId,
    required this.policyVersion,
    required this.state,
    required this.decisionReason,
    required this.requestedAt,
    required this.expiresAt,
    required this.decidedAt,
    required this.decidedBy,
    required this.rowVersion,
    required this.riskDecisionId,
  });

  final String id;
  final String orderId;
  final String policyId;
  final int policyVersion;
  final String state;
  final String? decisionReason;
  final DateTime? requestedAt;
  final DateTime? expiresAt;
  final DateTime? decidedAt;
  final String? decidedBy;
  final int rowVersion;
  final String? riskDecisionId;

  bool get isPending => state == 'PENDING';

  factory ApprovalView.fromJson(Map<String, dynamic> json) => ApprovalView(
        id: '${json['id'] ?? ''}',
        orderId: '${json['orderId'] ?? ''}',
        policyId: '${json['policyId'] ?? ''}',
        policyVersion: (json['policyVersion'] as num?)?.toInt() ?? 0,
        state: '${json['state'] ?? 'UNKNOWN'}',
        decisionReason: json['decisionReason']?.toString(),
        requestedAt: DateTime.tryParse('${json['requestedAt'] ?? ''}'),
        expiresAt: DateTime.tryParse('${json['expiresAt'] ?? ''}'),
        decidedAt: DateTime.tryParse('${json['decidedAt'] ?? ''}'),
        decidedBy: json['decidedBy']?.toString(),
        rowVersion: (json['rowVersion'] as num?)?.toInt() ?? -1,
        riskDecisionId: json['riskDecisionId']?.toString(),
      );
}

class RiskApprovalSnapshot {
  const RiskApprovalSnapshot({required this.config, required this.decisions, required this.approvals});

  final RiskConfig config;
  final List<RiskDecisionSummaryView> decisions;
  final List<ApprovalView> approvals;
}

abstract interface class RiskApprovalRepository {
  Future<RiskApprovalSnapshot> fetch();
  Future<RiskConfig> setEmergencyStop({required bool halted, required String reason});
  Future<ApprovalView> decideApproval({
    required ApprovalView approval,
    required String decision,
    required String actor,
    String? reason,
  });
}

class HttpRiskApprovalRepository implements RiskApprovalRepository {
  HttpRiskApprovalRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<RiskApprovalSnapshot> fetch() async {
    final results = await Future.wait([
      api.getJson('/api/risk/config'),
      api.getJson('/api/risk/decisions', query: const {'page': '0', 'size': '20'}),
      api.getJson('/api/approvals', query: const {'page': '0', 'size': '20'}),
    ]);
    final configRaw = results[0];
    final decisionsRaw = results[1];
    final approvalsRaw = results[2];
    if (configRaw is! Map || decisionsRaw is! Map || approvalsRaw is! Map) {
      throw const ApiTransportFailure('INVALID_RISK_APPROVAL_RESPONSE', null);
    }
    return RiskApprovalSnapshot(
      config: RiskConfig.fromJson(Map<String, dynamic>.from(configRaw)),
      decisions: _items(decisionsRaw).map(RiskDecisionSummaryView.fromJson).toList(growable: false),
      approvals: _items(approvalsRaw).map(ApprovalView.fromJson).toList(growable: false),
    );
  }

  @override
  Future<RiskConfig> setEmergencyStop({required bool halted, required String reason}) async {
    final decoded = await api.putJson(
      '/api/risk/emergency-stop',
      body: {'halted': halted, 'reason': reason},
    );
    if (decoded is! Map) throw const ApiTransportFailure('INVALID_EMERGENCY_STOP_RESPONSE', null);
    // The mutation response intentionally contains only halt state/reason. Re-read
    // config so the UI never infers policy/mode values from a mutation response.
    final config = await api.getJson('/api/risk/config');
    if (config is! Map) throw const ApiTransportFailure('INVALID_RISK_CONFIG_RESPONSE', null);
    return RiskConfig.fromJson(Map<String, dynamic>.from(config));
  }

  @override
  Future<ApprovalView> decideApproval({
    required ApprovalView approval,
    required String decision,
    required String actor,
    String? reason,
  }) async {
    final action = switch (decision) {
      'APPROVED' => 'approve',
      'REJECTED' => 'reject',
      'CANCELLED' => 'cancel',
      _ => throw ArgumentError.value(decision, 'decision', 'Unsupported approval decision'),
    };
    final decoded = await api.postJson(
      '/api/approvals/${approval.id}/$action',
      body: {
        'expectedRowVersion': approval.rowVersion,
        'actor': actor,
        'reason': reason,
      },
    );
    if (decoded is! Map) throw const ApiTransportFailure('INVALID_APPROVAL_RESPONSE', null);
    return ApprovalView.fromJson(Map<String, dynamic>.from(decoded));
  }

  static Iterable<Map<String, dynamic>> _items(Map<dynamic, dynamic> page) {
    final raw = page['items'];
    if (raw is! List) return const [];
    return raw.whereType<Map>().map((item) => Map<String, dynamic>.from(item));
  }
}
