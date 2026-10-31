import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/risk_approval_repository.dart';
import 'package:trading_client/src/presentation/risk_approvals_page.dart';

class _FakeRiskRepository implements RiskApprovalRepository {
  _FakeRiskRepository(this.snapshot);

  RiskApprovalSnapshot snapshot;
  bool? halted;
  String? haltReason;
  ApprovalView? decidedApproval;
  String? decision;
  String? actor;

  @override
  Future<RiskApprovalSnapshot> fetch() async => snapshot;

  @override
  Future<RiskConfig> setEmergencyStop({required bool halted, required String reason}) async {
    this.halted = halted;
    haltReason = reason;
    final config = RiskConfig(
      tradingMode: snapshot.config.tradingMode,
      liveTradingEnabled: snapshot.config.liveTradingEnabled,
      policyVersion: snapshot.config.policyVersion,
      startingCapital: snapshot.config.startingCapital,
      riskPerTradePercent: snapshot.config.riskPerTradePercent,
      maxDailyDrawdownPercent: snapshot.config.maxDailyDrawdownPercent,
      maxOpenPositions: snapshot.config.maxOpenPositions,
      signalMaxAgeMinutes: snapshot.config.signalMaxAgeMinutes,
      newOrdersHalted: halted,
    );
    snapshot = RiskApprovalSnapshot(config: config, decisions: snapshot.decisions, approvals: snapshot.approvals);
    return config;
  }

  @override
  Future<ApprovalView> decideApproval({
    required ApprovalView approval,
    required String decision,
    required String actor,
    String? reason,
  }) async {
    decidedApproval = approval;
    this.decision = decision;
    this.actor = actor;
    final updated = ApprovalView(
      id: approval.id,
      orderId: approval.orderId,
      policyId: approval.policyId,
      policyVersion: approval.policyVersion,
      state: decision,
      decisionReason: reason,
      requestedAt: approval.requestedAt,
      expiresAt: approval.expiresAt,
      decidedAt: DateTime.parse('2026-10-31T00:01:00Z'),
      decidedBy: actor,
      rowVersion: approval.rowVersion + 1,
      riskDecisionId: approval.riskDecisionId,
    );
    snapshot = RiskApprovalSnapshot(
      config: snapshot.config,
      decisions: snapshot.decisions,
      approvals: [updated],
    );
    return updated;
  }
}

RiskApprovalSnapshot _snapshot() => RiskApprovalSnapshot(
      config: const RiskConfig(
        tradingMode: 'PAPER',
        liveTradingEnabled: false,
        policyVersion: 'v2',
        startingCapital: 100000,
        riskPerTradePercent: 1,
        maxDailyDrawdownPercent: 3,
        maxOpenPositions: 5,
        signalMaxAgeMinutes: 15,
        newOrdersHalted: false,
      ),
      decisions: [
        RiskDecisionSummaryView(
          id: 'risk-1',
          orderId: 'order-1',
          decision: 'APPROVE',
          reasonCodes: const ['WITHIN_POLICY'],
          policyVersion: 'v2',
          resultClass: 'APPROVE',
          failedRuleCount: 0,
          evaluatedAt: DateTime.parse('2026-10-31T00:00:00Z'),
          correlationId: 'corr-risk',
        ),
      ],
      approvals: [
        ApprovalView(
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
        ),
      ],
    );

void _useTallViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  testWidgets('risk screen renders PAPER authority and version-aware pending approval', (tester) async {
    _useTallViewport(tester);
    final repository = _FakeRiskRepository(_snapshot());
    await tester.pumpWidget(MaterialApp(home: RiskApprovalsPage(repository: repository)));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('risk-config')), findsOneWidget);
    expect(find.text('Mode PAPER'), findsOneWidget);
    expect(find.text('LIVE DISABLED'), findsOneWidget);
    expect(find.text('Policy v2'), findsOneWidget);
    expect(find.byKey(const ValueKey('approval-approval-1')), findsOneWidget);
    expect(find.textContaining('row v7'), findsOneWidget);
    expect(find.byKey(const ValueKey('risk-decision-risk-1')), findsOneWidget);
  });

  testWidgets('emergency halt requires explicit reason before mutation', (tester) async {
    _useTallViewport(tester);
    final repository = _FakeRiskRepository(_snapshot());
    await tester.pumpWidget(MaterialApp(home: RiskApprovalsPage(repository: repository)));
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const ValueKey('risk-halt-orders')));
    await tester.tap(find.byKey(const ValueKey('risk-halt-orders')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('risk-local-error')), findsOneWidget);
    expect(repository.halted, isNull);
  });

  testWidgets('approval confirmation carries actor and original row version', (tester) async {
    _useTallViewport(tester);
    final repository = _FakeRiskRepository(_snapshot());
    await tester.pumpWidget(MaterialApp(home: RiskApprovalsPage(repository: repository)));
    await tester.pumpAndSettle();

    final approve = find.byKey(const ValueKey('approval-approve-approval-1'));
    await tester.ensureVisible(approve);
    await tester.tap(approve);
    await tester.pumpAndSettle();

    expect(find.textContaining('row v7'), findsWidgets);
    await tester.enterText(find.byKey(const ValueKey('approval-actor')), 'operator-a');
    await tester.tap(find.byKey(const ValueKey('confirm-approval-decision')));
    await tester.pumpAndSettle();

    expect(repository.decidedApproval?.rowVersion, 7);
    expect(repository.decision, 'APPROVED');
    expect(repository.actor, 'operator-a');
  });
}
