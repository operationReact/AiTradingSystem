import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/customer_identity.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_ma_walkforward_repository.dart';

class _TestIdentity extends UnavailableCustomerIdentity {
  const _TestIdentity();
  @override
  bool get isConfigured => true;
  @override
  Future<String?> accessToken() async => 'unit-only-token';
}

Map<String, dynamic> payload() => {
  'contractVersion': 'mind.ma-walkforward.v1',
  'generatedAt': '2026-11-09T09:00:00Z',
  'status': 'UNAVAILABLE',
  'strategyCode': 'MA_RECLAIM_V1',
  'strategyVersion': '1',
  'instrument': null,
  'interval': 'FIVE_MINUTE',
  'selectionMethod': 'RECENT_SCANNER_CANDIDATE_SELECTION_BIASED',
  'from': '2026-05-13',
  'to': '2026-11-08',
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
    'LATEST_SUCCESSFUL_SCAN_UNAVAILABLE',
  ],
  'decisionPosture': 'ABSTAIN',
  'sourceKnownAtIndependentlyVerified': false,
  'validatedTradingEdge': false,
  'paperOrderSubmitted': false,
  'executionEnabled': false,
};

Map<String, dynamic> fold(int n) => {
  'number': n,
  'trainingFrom': '2026-05-13',
  'trainingTo': '2026-07-11',
  'validationFrom': '2026-07-12',
  'validationTo': '2026-07-31',
  'trainingCoveragePercent': 100,
  'validationCoveragePercent': 100,
  'status': 'EVALUATED',
  'training': null,
  'validation': {
    'candles': 1100,
    'closedTrades': 8,
    'winningTrades': 4,
    'grossPnl': 110,
    'feeCost': 20,
    'slippageCost': 10,
    'netPnl': 80,
    'returnPercent': 0.08,
    'maxDrawdownPercent': 1.25,
  },
};

void main() {
  test('authenticated GET returns unavailable safely without pretending to profit', () async {
    final original = MintTickIdentity.current;
    MintTickIdentity.current = const _TestIdentity();
    addTearDown(() => MintTickIdentity.current = original);
    final session = OperatorSession()..markCustomerAuthenticated();
    final repository = HttpMindMaWalkForwardRepository(AuthenticatedApiClient(
      session: session,
      baseUri: Uri.parse('https://api.minttick.com'),
      client: MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/customer/mind/v1/ma-walkforward');
        expect(request.headers['Authorization'], 'Bearer unit-only-token');
        expect(request.headers.containsKey('X-API-Key'), isFalse);
        return http.Response(jsonEncode(payload()), 200);
      }),
    ));
    final result = await repository.load();
    expect(result.status, 'UNAVAILABLE');
    expect(result.meanOosReturnPercent, isNull);
    expect(result.blockers, contains('LIVE_EXECUTION_DISABLED'));
    expect(result.folds, isEmpty);
  });

  test('trading permissions or verified performance cannot be inferred from metrics', () {
    for (final flag in ['executionEnabled', 'validatedTradingEdge',
        'paperOrderSubmitted', 'sourceKnownAtIndependentlyVerified']) {
      expect(() => MindMaWalkForwardReport.fromJson(payload()..[flag] = true),
        throwsFormatException);
    }
    expect(() => MindMaWalkForwardReport.fromJson(
      payload()..['decisionPosture'] = 'BUY'), throwsFormatException);
    expect(() => MindMaWalkForwardReport.fromJson(
      payload()..['strategyCode'] = 'SMA_TREND_VOLUME'), throwsFormatException);
    final missingGate = payload();
    (missingGate['blockers'] as List).remove('SELECTION_BIAS_NOT_CORRECTED');
    expect(() => MindMaWalkForwardReport.fromJson(missingGate), throwsFormatException);
  });

  test('after-cost evaluation cannot be certified with insufficient folds', () {
    final raw = payload();
    raw['status'] = 'EVALUATED_RESEARCH_ONLY';
    raw['instrument'] = 'NSE:INFY-EQ';
    raw['folds'] = [fold(1)];
    raw['completedFolds'] = 1;
    raw['oosTrades'] = 8;
    raw['meanOosReturnPercent'] = 0.08;
    expect(() => MindMaWalkForwardReport.fromJson(raw), throwsFormatException);
  });

  test('invalid costs, overlapping folds and malformed dates are rejected', () {
    final raw = payload();
    (raw['assumptions'] as Map<String, dynamic>)['slippagePerSide'] = 'free';
    expect(() => MindMaWalkForwardReport.fromJson(raw), throwsFormatException);

    final invalidFold = fold(1);
    invalidFold['validationCoveragePercent'] = 110;
    expect(() => MindMaWalkForwardFold.fromJson(invalidFold), throwsFormatException);

    final overlapping = payload();
    overlapping['status'] = 'INSUFFICIENT_SAMPLE';
    overlapping['instrument'] = 'NSE:INFY-EQ';
    overlapping['folds'] = [fold(1), fold(2)];
    overlapping['completedFolds'] = 2;
    expect(() => MindMaWalkForwardReport.fromJson(overlapping), throwsFormatException);
  });
}
