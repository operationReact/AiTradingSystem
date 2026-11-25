import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/data/risk_approval_repository.dart';

void main() {
  test('fetches risk config, decisions and approvals from backend', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    final api = AuthenticatedApiClient(
      session: session,
      client: MockClient((request) async {
        if (request.url.path == '/api/risk/config') {
          return http.Response(jsonEncode({
            'tradingMode': 'PAPER',
            'liveTradingEnabled': false,
            'policyVersion': 'v2',
            'startingCapital': 100000,
            'riskPerTradePercent': 1,
            'maxDailyDrawdownPercent': 3,
            'maxOpenPositions': 5,
            'signalMaxAgeMinutes': 15,
            'newOrdersHalted': false,
          }), 200);
        }
        if (request.url.path == '/api/risk/decisions') {
          expect(request.url.queryParameters, {'page': '0', 'size': '20'});
          return http.Response(jsonEncode({
            'items': [
              {
                'id': 'risk-1',
                'orderId': 'order-1',
                'decision': 'APPROVE',
                'reasonCodes': ['WITHIN_POLICY'],
                'policyVersion': 'v2',
                'resultClass': 'APPROVE',
                'failedRuleCount': 0,
                'evaluatedAt': '2026-10-31T00:00:00Z',
                'correlationId': 'corr-risk',
              }
            ],
            'page': 0,
            'size': 20,
            'totalItems': 1,
            'totalPages': 1,
          }), 200);
        }
        if (request.url.path == '/api/approvals') {
          return http.Response(jsonEncode({
            'items': [
              {
                'id': 'approval-1',
                'orderId': 'order-1',
                'policyId': 'policy-1',
                'policyVersion': 2,
                'state': 'PENDING',
                'requestedAt': '2026-10-31T00:00:00Z',
                'expiresAt': '2026-10-31T00:10:00Z',
                'rowVersion': 7,
                'riskDecisionId': 'risk-1',
              }
            ],
            'page': 0,
            'size': 20,
            'totalItems': 1,
            'totalPages': 1,
          }), 200);
        }
        throw StateError('unexpected ${request.url}');
      }),
      baseUri: Uri.parse('http://example.test'),
    );

    final snapshot = await HttpRiskApprovalRepository(api).fetch();

    expect(snapshot.config.tradingMode, 'PAPER');
    expect(snapshot.config.liveTradingEnabled, isFalse);
    expect(snapshot.decisions.single.reasonCodes, ['WITHIN_POLICY']);
    expect(snapshot.approvals.single.rowVersion, 7);
  });

  test('emergency stop uses PUT then re-reads authoritative risk config', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    var calls = 0;
    final repository = HttpRiskApprovalRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          calls++;
          if (calls == 1) {
            expect(request.method, 'PUT');
            expect(request.url.path, '/api/risk/emergency-stop');
            expect(jsonDecode(request.body), {'halted': true, 'reason': 'operator safety halt'});
            return http.Response(jsonEncode({'newOrdersHalted': true, 'reason': 'operator safety halt'}), 200);
          }
          expect(request.method, 'GET');
          expect(request.url.path, '/api/risk/config');
          return http.Response(jsonEncode({
            'tradingMode': 'PAPER',
            'liveTradingEnabled': false,
            'policyVersion': 'v2',
            'startingCapital': 100000,
            'riskPerTradePercent': 1,
            'maxDailyDrawdownPercent': 3,
            'maxOpenPositions': 5,
            'signalMaxAgeMinutes': 15,
            'newOrdersHalted': true,
          }), 200);
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );

    final config = await repository.setEmergencyStop(halted: true, reason: 'operator safety halt');
    expect(config.newOrdersHalted, isTrue);
    expect(calls, 2);
  });

  test('approval decision sends exact expected row version', () async {
    final session = OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repository = HttpRiskApprovalRepository(
      AuthenticatedApiClient(
        session: session,
        client: MockClient((request) async {
          captured = request;
          return http.Response(jsonEncode({
            'id': 'approval-1',
            'orderId': 'order-1',
            'policyId': 'policy-1',
            'policyVersion': 2,
            'state': 'APPROVED',
            'decisionReason': 'reviewed',
            'requestedAt': '2026-10-31T00:00:00Z',
            'expiresAt': '2026-10-31T00:10:00Z',
            'decidedAt': '2026-10-31T00:01:00Z',
            'decidedBy': 'operator-a',
            'rowVersion': 8,
            'riskDecisionId': 'risk-1',
          }), 200);
        }),
        baseUri: Uri.parse('http://example.test'),
      ),
    );
    final approval = ApprovalView(
      id: 'approval-1',
      orderId: 'order-1',
      policyId: 'policy-1',
      policyVersion: 2,
      state: 'PENDING',
      decisionReason: null,
      requestedAt: DateTime.parse('2026-10-31T00:00:00Z'),
      expiresAt: DateTime.parse('2026-10-31T00:10:00Z'),
      decidedAt: null,
      decidedBy: null,
      rowVersion: 7,
      riskDecisionId: 'risk-1',
    );

    final result = await repository.decideApproval(
      approval: approval,
      decision: 'APPROVED',
      actor: 'operator-a',
      reason: 'reviewed',
    );

    expect(captured.method, 'POST');
    expect(captured.url.path, '/api/approvals/approval-1/approve');
    expect(jsonDecode(captured.body), {
      'expectedRowVersion': 7,
      'actor': 'operator-a',
      'reason': 'reviewed',
    });
    expect(result.state, 'APPROVED');
    expect(result.rowVersion, 8);
  });
}
