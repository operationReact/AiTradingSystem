import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_decision_history_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

Map<String, dynamic> record({
  String strategy = 'MA_RECLAIM_V1',
  String symbol = 'RELIANCE',
  String fingerprint = 'a1b2c3d4e5f60718',
  String signal = '2026-11-13T09:30:00Z',
  String cutoff = '2026-11-13T09:30:00Z',
  bool orderSubmitted = false,
}) => {
      'scanRunId': '3f2c9d1e-0000-4000-8000-000000000001',
      'runCompletedAt': '2026-11-13T09:31:00Z',
      'strategy': strategy,
      'strategyVersion': 1,
      'catalogueVersion': 'cat-v1',
      'tradingSymbol': symbol,
      'exchange': 'NSE',
      'interval': 'FIVE_MINUTE',
      'signalCandleTime': signal,
      'dataCutoffAt': cutoff,
      'candidateFingerprint': fingerprint,
      'reasons': ['TREND', 'VOLUME'],
      'decisionPosture': 'RECORDED_CANDIDATE',
      'paperOrderSubmitted': orderSubmitted,
      'executionEnabled': false,
      'liveMoney': false,
    };

Map<String, dynamic> history(List<Map<String, dynamic>> rows) => {
      'contract': 'mind.decision-history.v1',
      'generatedAt': '2026-11-16T04:00:00Z',
      'limit': 50,
      'decisions': rows,
      'limitations': ['Abstentions are not persisted per decision.'],
    };

class _HistoryRepo implements MindDecisionHistoryRepository {
  _HistoryRepo(this.payload, {this.fail = false});
  final Map<String, dynamic> payload;
  final bool fail;
  @override
  Future<MindDecisionHistory> load() async {
    if (fail) throw StateError('network');
    return MindDecisionHistory.decode(payload);
  }
}

Future<void> openDecisionLab(WidgetTester tester, MindDecisionHistoryRepository repo) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 2400);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(decisionHistoryRepository: repo)));
  await tester.pumpAndSettle();
  final nav = find.byKey(const ValueKey('mind-route-decision-lab'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  test('decodes recorded candidates with provenance and reasons', () {
    final h = MindDecisionHistory.decode(history([record()]));
    expect(h.decisions.single.strategy, 'MA_RECLAIM_V1');
    expect(h.decisions.single.reasons, ['TREND', 'VOLUME']);
    expect(h.decisions.single.catalogueVersion, 'cat-v1');
  });

  test('a record that claims an order is rejected, never shown', () {
    expect(() => MindDecisionHistory.decode(history([record(orderSubmitted: true)])),
      throwsFormatException);
  });

  test('a record whose data cutoff precedes its signal is treated as future-informed', () {
    expect(() => MindDecisionHistory.decode(history([
      record(signal: '2026-11-13T09:30:00Z', cutoff: '2026-11-13T09:29:00Z'),
    ])), throwsFormatException);
  });

  test('an unknown contract is rejected', () {
    final bad = history([record()])..['contract'] = 'mind.decision-history.v2';
    expect(() => MindDecisionHistory.decode(bad), throwsFormatException);
  });

  testWidgets('Decision Lab lists recorded candidates and search narrows them', (tester) async {
    await openDecisionLab(tester, _HistoryRepo(history([
      record(symbol: 'RELIANCE', fingerprint: 'aaaaaaaaaaaaaaaa'),
      record(symbol: 'INFY', strategy: 'SMA_TREND_VOLUME_V1', fingerprint: 'bbbbbbbbbbbbbbbb'),
    ])));
    expect(find.byKey(const ValueKey('mind-decision-history')), findsOneWidget);
    expect(find.text('RECORDED CANDIDATES · RESEARCH ONLY · NO ORDER'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-decision-aaaaaaaaaaaaaaaa')), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-decision-bbbbbbbbbbbbbbbb')), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('mind-decision-search')), 'infy');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-decision-aaaaaaaaaaaaaaaa')), findsNothing);
    expect(find.byKey(const ValueKey('mind-decision-bbbbbbbbbbbbbbbb')), findsOneWidget);
  });

  testWidgets('a failed history load shows no candidates', (tester) async {
    await openDecisionLab(tester, _HistoryRepo(history([]), fail: true));
    expect(find.byKey(const ValueKey('mind-decision-history-error')), findsOneWidget);
    expect(find.textContaining('Decision history unavailable'), findsOneWidget);
  });
}
