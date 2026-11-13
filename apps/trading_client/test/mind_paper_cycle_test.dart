import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_paper_cycle_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_paper_cycle_panel.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

const _room = '12345678-1234-1234-1234-123456789012';

Map<String, dynamic> cycleJson({String outcome = 'FILLED', bool? live = false}) => {
      'id': 'c1', 'roomId': _room, 'instrumentId': 7, 'tradingSymbol': 'MINDA-EQ', 'interval': 'FIFTEEN_MINUTE',
      'strategyCode': 'SMA_TREND_VOLUME_V1', 'signalId': 's1', 'signalAction': 'BUY',
      'candleTime': '2026-11-12T04:45:00Z', 'candleSource': 'ANGEL_ONE_SMARTAPI', 'outcome': outcome,
      'side': outcome == 'FILLED' ? 'BUY' : null, 'quantity': outcome == 'FILLED' ? 37 : null,
      'limitPrice': 105.85, 'stopPrice': 105.27, 'targetPrice': 106.86, 'quoteLtp': 105.85,
      'quoteExchangeAt': '2026-11-12T04:59:58Z', 'orderId': outcome == 'FILLED' ? 'o1' : null,
      'reasons': ['FAST_SMA_ABOVE_SLOW_SMA'],
      'stages': [
        {'code': 'DATA_GATE', 'result': 'PASSED', 'detail': 'ANGEL_ONE_SMARTAPI'},
        {'code': 'PAPER_ORDER', 'result': outcome == 'FILLED' ? 'FILLED' : 'REJECTED', 'detail': ''},
      ],
      'backendRevision': 'abc1234', 'createdAt': '2026-11-12T05:00:00Z',
      'dataLabel': 'STORED_BROKER_CANDLES:ANGEL_ONE_SMARTAPI + VERIFIED_LIVE_TICK',
      'liveOrder': live,
    };

Map<String, dynamic> resultJson({String outcome = 'FILLED'}) => {
      'cycle': cycleJson(outcome: outcome),
      'order': outcome == 'FILLED' ? {'fillPrice': 105.85} : null,
      'availableCashPaise': 1608159, 'realizedPnlPaise': -1250,
      'holdings': [
        {'instrumentId': 7, 'quantity': 37, 'avgCostPaise': 10590, 'markPrice': 105.85, 'unrealizedPnlPaise': -196},
      ],
    };

class _Repo implements MindPaperCycleRepository {
  _Repo({this.fail = false, this.noRooms = false});
  final bool fail;
  final bool noRooms;
  int runs = 0;
  Completer<void>? gate;

  @override
  Future<List<MindPaperRoom>> rooms() async => noRooms ? [] : [const MindPaperRoom(_room, 'Swing room')];
  @override
  Future<List<MindPaperInstrument>> instruments() async => [const MindPaperInstrument(7, 'MINDA-EQ · NSE')];
  @override
  Future<MindPaperCycleResult> run({required String roomId, required int instrumentId, required String interval}) async {
    runs++;
    if (gate != null) await gate!.future;
    if (fail) throw StateError('timeout');
    return MindPaperCycleResult.parse(resultJson());
  }
  @override
  Future<List<MindPaperCycle>> recent(String roomId) async => runs == 0 ? [] : [MindPaperCycle.parse(cycleJson())];
}

void main() {
  test('cycle payloads that are not explicitly PAPER are rejected', () {
    expect(MindPaperCycle.parse(cycleJson()).outcome, MindPaperOutcome.filled);
    expect(() => MindPaperCycle.parse(cycleJson(live: true)), throwsFormatException);
    expect(() => MindPaperCycle.parse(cycleJson(live: null)), throwsFormatException);
    expect(() => MindPaperCycle.parse({...cycleJson(), 'orderId': null}), throwsFormatException);
    expect(() => MindPaperCycle.parse({...cycleJson(), 'outcome': 'LIVE_FILLED'}), throwsFormatException);
  });

  for (final width in [360.0, 1280.0]) {
    testWidgets('owner runs one PAPER cycle and sees server fill, cash and P&L at $width', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 1600);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final repo = _Repo()..gate = Completer<void>();
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(
          child: MindPaperCyclePanel(repository: repo)))));
      await tester.pumpAndSettle();
      expect(find.text('PAPER · VIRTUAL'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('mind-paper-run')));
      await tester.pump();
      // Single-flight: a second tap while running does nothing.
      await tester.tap(find.byKey(const ValueKey('mind-paper-run')));
      await tester.pump();
      expect(repo.runs, 1);
      repo.gate!.complete();
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mind-paper-result')), findsOneWidget);
      expect(find.text('PAPER FILL'), findsWidgets);
      expect(find.text('BUY 37 @ 105.85'), findsOneWidget);
      expect(find.text('₹16081.59'), findsOneWidget);
      expect(find.text('-₹12.50'), findsOneWidget);
      expect(find.text('-₹1.96'), findsOneWidget);
      expect(find.textContaining('VERIFIED_LIVE_TICK'), findsOneWidget);
      expect(find.text('RECENT CYCLES'), findsOneWidget);
    });
  }

  testWidgets('failed or unknown cycle shows an error and is never retried automatically', (tester) async {
    final repo = _Repo(fail: true);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(
        child: MindPaperCyclePanel(repository: repo)))));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('mind-paper-run')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-paper-error')), findsOneWidget);
    expect(repo.runs, 1);
    expect(find.byKey(const ValueKey('mind-paper-result')), findsNothing);
  });

  testWidgets('no funded PAPER room means nothing can run', (tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: MindPaperCyclePanel(repository: _Repo(noRooms: true)))));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-paper-setup-missing')), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-paper-run')), findsNothing);
  });

  testWidgets('Trading Room Detail hosts the real cycle panel and labels remaining cards as mock', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1440, 1200);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(paperCycleRepository: _Repo())));
    await tester.pumpAndSettle();
    final nav = find.byKey(const ValueKey('mind-route-room-detail'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-paper-cycle-panel')), findsOneWidget);
    expect(find.text('ROOM CARDS BELOW: MOCK FIXTURES'), findsOneWidget);
  });
}
