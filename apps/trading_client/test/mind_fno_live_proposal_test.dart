import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_fno_live_proposal_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_fno_live_proposal_panel.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_upstox_fno_live_repository.dart';

Map<String, Object?> _blocked() => {
  'contractVersion': 'mind.fno-live-proposal.v1',
  'roomId': '12345678-1234-1234-1234-123456789012',
  'evaluatedAt': '2026-11-20T05:00:00Z',
  'state': 'BLOCKED',
  'reasons': ['LIVE_ORDER_POLICY_NOT_ACTIVATED'],
  'signal': 'NO_TRADE',
  'instrumentKey': null,
  'proposalId': null,
  'limitPrice': null,
  'executionAuthorized': false,
  'orderPlaced': false,
};

Map<String, Object?> _candidate() => {
  ..._blocked(),
  'state': 'RISK_PASSED_DRY_RUN',
  'reasons': <String>[],
  'strategyId': 'strategy.STR-181',
  'signal': 'BUY_CALL_RESEARCH',
  'instrumentKey': 'NSE_FO|61001',
  'quoteSource': 'UPSTOX_V3_EXCHANGE_TIMED',
  'expiry': '2026-11-26',
  'lots': 1,
  'units': 65,
  'limitPrice': 126,
  'premiumInr': 8190,
  'maximumPremiumInr': 25000,
  'stopReference': 115.9,
  'targetReference': 144.9,
  'exchangeAt': '2026-11-20T04:59:58Z',
  'proposalId': 'faba9397-08f4-4b99-987e-f0303e9ec044',
};

class _FakeRepo implements MindFnoLiveProposalRepository {
  static const receiptId='12345678-1234-1234-1234-123456789011';
  static const roomId='12345678-1234-1234-1234-123456789012';
  String status='UNKNOWN';
  int reconciliationCalls=0;
  UpstoxFnoLiveReceipt get receipt=>UpstoxFnoLiveReceipt(
    id:receiptId,roomId:roomId,instrumentKey:'NSE_FO|61001',
    expiry:DateTime.utc(2026,11,26),side:'BUY',quantity:65,
    limitPrice:126,state:status,orderTag:'mt1234567890123456',
    outcomeCode:status=='UNKNOWN'
      ? 'UPSTOX_FNO_PARTIAL_FILL_UNCERTAIN':'UPSTOX_FNO_FULL_FILL_VERIFIED',
    createdAt:DateTime.utc(2026,11,20,5),
  );

  @override
  Future<List<UpstoxFnoLiveReceipt>> orderHistory(String roomId) async =>
      [receipt];

  @override
  Future<UpstoxFnoLiveReceipt> recheckBrokerOrder(
      String roomId,String orderId) async {
    if(orderId!=receiptId)throw const FormatException('Different broker order');
    reconciliationCalls++;
    status='COMPLETE';
    return receipt;
  }


  @override
  Future<List<MindFnoLiveRoom>> rooms() async => [
    const MindFnoLiveRoom(
      '12345678-1234-1234-1234-123456789012', 'NIFTY LIVE F&O'),
  ];

  @override
  Future<MindFnoLiveProposal> evaluate(String roomId) async =>
      MindFnoLiveProposal.parse(_candidate());
}

void main() {
  test('blocked policy yields no order or contract', () {
    final data = MindFnoLiveProposal.parse(_blocked());
    expect(data.isRiskPassed, false);
    expect(data.reasons, contains('LIVE_ORDER_POLICY_NOT_ACTIVATED'));
    expect(data.instrumentKey, isNull);
  });

  test('read-only one-lot provenance with risk cap is accepted', () {
    final data = MindFnoLiveProposal.parse(_candidate());
    expect(data.isRiskPassed, true);
    expect(data.strategyId, 'strategy.STR-181');
    expect(data.units, 65);
    expect(data.premiumInr, 8190);
  });

  test('rejects executable, missing or stale provider evidence', () {
    expect(() => MindFnoLiveProposal.parse(
      _candidate()..['executionAuthorized'] = true,
    ), throwsFormatException);
    expect(() => MindFnoLiveProposal.parse(
      _candidate()..['orderPlaced'] = true,
    ), throwsFormatException);
    expect(() => MindFnoLiveProposal.parse(
      _candidate()..['quoteSource'] = 'SYNTHETIC',
    ), throwsFormatException);
    expect(() => MindFnoLiveProposal.parse(
      _candidate()..['exchangeAt'] = '2026-11-20T04:50:00Z',
    ), throwsFormatException);
    expect(() => MindFnoLiveProposal.parse(
      _candidate()..['maximumPremiumInr'] = 8000,
    ), throwsFormatException);
  });

  testWidgets('LIVE preview shows proposal but never a trading control',
      (tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: SingleChildScrollView(
        child: MindFnoLiveProposalPanel(repository: _FakeRepo()),
      ),
    )));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(
      const ValueKey('mind-fno-live-proposal-room'),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('NIFTY LIVE F&O').last);
    await tester.pumpAndSettle();
    expect(find.textContaining('RISK PASSED'), findsOneWidget);
    expect(find.byKey(
      const ValueKey('mind-fno-live-proposal-no-orders'),
    ), findsOneWidget);
    expect(find.text('Place LIVE Order'), findsNothing);
    expect(find.text('Confirm LIVE'), findsNothing);
  });

  testWidgets('LIVE broker audit never resubmits and requires manual history check',
      (tester) async {
    final repo=_FakeRepo();
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: SingleChildScrollView(
        child: MindFnoLiveProposalPanel(repository: repo),
      ),
    )));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(
      const ValueKey('mind-fno-live-proposal-room'),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('NIFTY LIVE F&O').last);
    await tester.pumpAndSettle();
    expect(find.text('UNKNOWN'),findsOneWidget);
    expect(find.textContaining('PARTIAL FILL UNCERTAIN'),findsOneWidget);
    expect(repo.reconciliationCalls,0);
    final check=find.byKey(const ValueKey(
      'mind-fno-live-recheck-12345678-1234-1234-1234-123456789011'));
    await tester.ensureVisible(check);
    await tester.tap(check);
    await tester.pumpAndSettle();
    expect(repo.reconciliationCalls,1);
    expect(find.text('COMPLETE'),findsOneWidget);
    expect(find.textContaining('no resend'),findsNothing);
    expect(find.text('Place LIVE Order'),findsNothing);
  });

}
