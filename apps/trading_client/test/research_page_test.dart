import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/research_repository.dart';
import 'package:trading_client/src/presentation/research_page.dart';

class _FakeResearchRepository implements ResearchRepository {
  @override
  Future<ResearchScanSummary> runScan({String interval = 'FIVE_MINUTE', int limit = 100}) async => ResearchScanSummary(
        id: 'scan-1', interval: interval, status: 'SUCCEEDED', requestedLimit: limit, scannedCount: 80, candidateCount: 1,
        strategySet: 'baseline-v1', startedAt: DateTime.utc(2026, 10, 31), completedAt: DateTime.utc(2026, 10, 31, 0, 0, 1), errorCode: null, errorMessage: null,
      );

  @override
  Future<List<ResearchCandidate>> candidates(String scanId, {int limit = 50}) async => [ResearchCandidate(
        candidateId: 7, scanRunId: scanId, instrumentId: 42, tradingSymbol: 'INFY-EQ', exchange: 'NSE', interval: 'FIVE_MINUTE', strategy: 'momentum', score: 0.82,
        referencePrice: 1515, suggestedStop: 1495, suggestedTarget: 1555, candleTime: DateTime.utc(2026, 10, 31), reasons: const ['TREND_CONFIRMED'], fingerprint: 'fp-1',
      )];

  @override
  Future<ResearchCandidateDetail> candidate(int candidateId) async => ResearchCandidateDetail(
        id: candidateId, scanRunId: 'scan-1', instrumentId: 42, tradingSymbol: 'INFY-EQ', exchange: 'NSE', interval: 'FIVE_MINUTE', strategy: 'momentum',
        entryPrice: 1515, stopPrice: 1495, targetPrice: 1555, signalCandleTime: DateTime.utc(2026, 10, 31), dataCutoffAt: DateTime.utc(2026, 10, 31),
      );

  bool _evaluated = false;

  @override
  Future<List<ResearchEvaluation>> evaluations(int candidateId) async => _evaluated
      ? [ResearchEvaluation(evaluationId: 'eval-1', candidateId: candidateId, scenarioRunId: 'scenario-1', decisionLabel: 'RESEARCH_SUPPORTED', reasonCodes: const ['SCENARIO_POSITIVE'], createdAt: DateTime.utc(2026, 10, 31, 1))]
      : const [];

  @override
  Future<ResearchEvaluation> evaluate({required int candidateId, required DateTime historyFrom, required DateTime historyTo, int simulations = 1000, int horizonBars = 24, int blockSize = 4, int seed = 42, int slippageBps = 5, int roundTripCostBps = 10}) async {
    _evaluated = true;
    return ResearchEvaluation(evaluationId: 'eval-1', candidateId: candidateId, scenarioRunId: 'scenario-1', decisionLabel: 'RESEARCH_SUPPORTED', reasonCodes: const ['SCENARIO_POSITIVE'], createdAt: DateTime.utc(2026, 10, 31, 1));
  }
}

void main() {
  testWidgets('AI opportunity scan flows into setup review and evaluation', (tester) async {
    final repository = _FakeResearchRepository();
    await tester.pumpWidget(MaterialApp(home: ResearchPage(repository: repository)));

    expect(find.text('Run an AI scan to discover ranked trade ideas.'), findsOneWidget);
    expect(find.text('Find opportunities'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('research-run-scan')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('research-scan-summary')), findsOneWidget);
    expect(find.text('INFY-EQ'), findsOneWidget);
    expect(find.textContaining('AI score 0.820'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('research-candidate-7')));
    await tester.pumpAndSettle();
    final list = find.descendant(of: find.byType(ListView), matching: find.byType(Scrollable));
    await tester.scrollUntilVisible(find.text('Entry'), 200, scrollable: list);
    expect(find.text('Entry'), findsOneWidget);
    expect(find.text('No previous evaluation is available for this setup.'), findsOneWidget);

    final evaluate = find.byKey(const ValueKey('research-evaluate-candidate'));
    await tester.scrollUntilVisible(evaluate, 200, scrollable: list);
    await tester.ensureVisible(evaluate);
    await tester.tap(evaluate);
    await tester.pumpAndSettle();

    expect(find.text('RESEARCH_SUPPORTED'), findsOneWidget);
    expect(find.text('SCENARIO_POSITIVE'), findsOneWidget);
  });
}
