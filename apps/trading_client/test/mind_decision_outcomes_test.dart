import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_decision_outcomes_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

Map<String, dynamic> outcome({
  String outcome = 'NO_SIGNAL',
  String? cutoff = '2026-11-13T09:30:00Z',
  String symbol = 'RELIANCE',
  int instrument = 7001,
}) => {
      'scanRunId': '3f2c9d1e-0000-4000-8000-000000000001',
      'completedAt': '2026-11-13T09:31:00Z',
      'strategy': 'MA_RECLAIM_V1',
      'outcome': outcome,
      'reasonCode': outcome == 'NOT_EVALUATED' ? 'INSUFFICIENT_HISTORY' : outcome,
      'instrumentId': instrument,
      'tradingSymbol': symbol,
      'dataCutoffAt': cutoff,
    };

Map<String, dynamic> payload(List<Map<String, dynamic>> rows) => {
      'contract': 'mind.decision-outcomes.v1',
      'generatedAt': '2026-11-16T04:00:00Z',
      'runs': [
        {
          'scanRunId': '3f2c9d1e-0000-4000-8000-000000000001',
          'completedAt': '2026-11-13T09:31:00Z',
          'interval': 'FIVE_MINUTE',
          'candidates': 1,
          'noSignal': 1,
          'notEvaluated': 0,
        },
      ],
      'outcomes': rows,
      'limitations': ['A CANDIDATE is research evidence only.'],
    };

class _OutcomesRepo implements MindDecisionOutcomesRepository {
  _OutcomesRepo(this.data);
  final Map<String, dynamic> data;
  @override
  Future<MindDecisionOutcomes> load() async => MindDecisionOutcomes.decode(data);
}

void main() {
  test('decodes persisted outcomes with reasons and the run counts', () {
    final o = MindDecisionOutcomes.decode(payload([outcome(), outcome(outcome: 'NOT_EVALUATED', cutoff: null)]));
    expect(o.runs.single.candidates, 1);
    expect(o.outcomes.map((r) => r.outcome), ['NO_SIGNAL', 'NOT_EVALUATED']);
    expect(o.outcomes.last.reasonCode, 'INSUFFICIENT_HISTORY');
  });

  test('a contract mismatch is rejected', () {
    final bad = payload([outcome()])..['contract'] = 'mind.decision-outcomes.v2';
    expect(() => MindDecisionOutcomes.decode(bad), throwsFormatException);
  });

  test('an unknown outcome value is rejected, not coerced', () {
    expect(() => MindDecisionOutcomes.decode(payload([outcome(outcome: 'BUY')])), throwsFormatException);
  });

  test('a candidate without its data cutoff is rejected', () {
    expect(() => MindDecisionOutcomes.decode(payload([outcome(outcome: 'CANDIDATE', cutoff: null)])),
      throwsFormatException);
  });

  testWidgets('Decision Lab shows persisted outcomes and search narrows them', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1440, 2400);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(
      decisionOutcomesRepository: _OutcomesRepo(payload([
        outcome(symbol: 'RELIANCE', instrument: 7001),
        outcome(symbol: 'INFY', instrument: 7002, outcome: 'NOT_EVALUATED', cutoff: null),
      ])),
    )));
    await tester.pumpAndSettle();
    final nav = find.byKey(const ValueKey('mind-route-decision-lab'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-decision-outcomes')), findsOneWidget);
    expect(find.textContaining('candidates 1 · no signal 1'), findsOneWidget);
    expect(find.textContaining('RELIANCE'), findsOneWidget);
    expect(find.textContaining('INFY'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('mind-outcome-search')), 'infy');
    await tester.pumpAndSettle();
    expect(find.textContaining('RELIANCE'), findsNothing);
    expect(find.textContaining('INFY'), findsOneWidget);
  });
}
