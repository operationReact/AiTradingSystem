import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_orb_walkforward_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();
  @override
  bool get isConfigured => true;
  @override
  Future<String?> accessToken() async => 'test-only-token';
}

Map<String, dynamic> payload() => {
  'contractVersion': 'mind.orb-walkforward.v1',
  'generatedAt': '2026-11-25T09:10:00Z',
  'status': 'INSUFFICIENT_COVERAGE',
  'strategyCode': 'OPENING_RANGE_BREAKOUT_V1',
  'strategyVersion': '1',
  'instrument': null,
  'interval': null,
  'selectionMethod': 'RECENT_SCANNER_CANDIDATE_SELECTION_BIASED',
  'from': '2026-05-29',
  'to': '2026-11-24',
  'assumptions': {
    'initialCapital': 100000,
    'feePerSide': 0.001,
    'slippagePerSide': 0.0005,
    'riskPerTradePercent': 0.5,
    'maxNotionalPercent': 20,
    'label': 'ILLUSTRATIVE_COMPOSITE_COSTS_NOT_ACTUAL_BROKER_OR_STATUTORY_CHARGES',
  },
  'folds': [],
  'completedFolds': 0,
  'oosTrades': 0,
  'meanOosReturnPercent': null,
  'worstOosDrawdownPercent': null,
  'oosGrossPnl': null,
  'oosFeeCost': null,
  'oosSlippageCost': null,
  'oosNetPnl': null,
  'blockers': [
    'SELECTION_BIAS_NOT_CORRECTED',
    'SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN',
    'COST_MODEL_ESTIMATE_NOT_STATUTORY_CHARGES',
    'ACCOUNT_RISK_NOT_EVALUATED',
    'PAPER_SHADOW_ENROLLMENT_REQUIRED',
    'LIVE_EXECUTION_DISABLED',
    'OUT_OF_SAMPLE_COVERAGE_INCOMPLETE',
    'NO_VALIDATED_PERFORMANCE_CLAIM',
  ],
  'decisionPosture': 'ABSTAIN',
  'sourceKnownAtIndependentlyVerified': false,
  'validatedTradingEdge': false,
  'paperOrderSubmitted': false,
  'executionEnabled': false,
};

void main() {
  test('loads the authenticated read-only ORB report', () async {
    final previous = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = previous);
    final session = OperatorSession()..markCustomerAuthenticated();
    final repository = HttpMindOrbWalkForwardRepository(AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://api.minttick.com'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/customer/mind/v1/orb-walkforward');
        expect(request.headers['Authorization'], 'Bearer test-only-token');
        expect(request.headers.containsKey('X-API-Key'), isFalse);
        return http.Response(jsonEncode(payload()), 200);
      }),
    ));

    final report = await repository.load();

    expect(report.decisionPosture, 'ABSTAIN');
    expect(report.status, 'INSUFFICIENT_COVERAGE');
    expect(report.costLabel, contains('ILLUSTRATIVE'));
    expect(report.executionEnabled, isFalse);
  });

  test('rejects order authority and missing provenance gates', () {
    final executable = payload()..['executionEnabled'] = true;
    expect(() => MindOrbWalkForwardReport.fromJson(executable),
        throwsFormatException);
    final missingGate = payload()
      ..['blockers'] = ['PAPER_SHADOW_ENROLLMENT_REQUIRED'];
    expect(() => MindOrbWalkForwardReport.fromJson(missingGate),
        throwsFormatException);
    final promoted = payload()..['validatedTradingEdge'] = true;
    expect(() => MindOrbWalkForwardReport.fromJson(promoted),
        throwsFormatException);
  });
}
