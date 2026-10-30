import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/scenario_evidence_repository.dart';
import 'package:trading_client/src/presentation/scenario_evidence_page.dart';

class _FakeScenarioRepository implements ScenarioEvidenceRepository {
  _FakeScenarioRepository(this.evidence);
  final ScenarioRunEvidence evidence;
  String? requestedId;

  @override
  Future<ScenarioRunEvidence> fetch(String scenarioRunId) async {
    requestedId = scenarioRunId;
    return evidence;
  }
}

ScenarioRunEvidence _evidence() => ScenarioRunEvidence(
      id: '11111111-1111-1111-1111-111111111111',
      scanRunId: '22222222-2222-2222-2222-222222222222',
      candidateId: 42,
      instrumentId: 99,
      exchange: 'NSE',
      tradingSymbol: 'INFY-EQ',
      interval: 'FIVE_MINUTE',
      strategy: 'MOMENTUM',
      strategyConfigVersion: 3,
      entryPrice: 2500,
      stopPrice: 2450,
      targetPrice: 2600,
      historyFrom: DateTime.parse('2026-07-01T00:00:00Z'),
      historyTo: DateTime.parse('2026-10-01T00:00:00Z'),
      dataCutoffAt: DateTime.parse('2026-10-01T00:00:00Z'),
      simulationCount: 1000,
      horizonBars: 24,
      blockSize: 4,
      seed: 42,
      slippageBps: 5,
      roundTripCostBps: 10,
      requestFingerprint: 'fingerprint-1',
      methodology: 'BLOCK_BOOTSTRAP_V1',
      regime: 'TRENDING_UP',
      historicalSamples: 800,
      eligibleBlockCount: 120,
      fallbackTier: 'EXACT_REGIME',
      targetFirstCount: 520,
      stopFirstCount: 310,
      neitherCount: 170,
      targetFirstProbability: 0.52,
      stopFirstProbability: 0.31,
      neitherProbability: 0.17,
      expectedR: 0.42,
      p05R: -1,
      p50R: 0.3,
      p95R: 2,
      status: 'COMPLETED',
      errorCode: null,
      errorMessage: null,
      startedAt: DateTime.parse('2026-10-01T00:00:01Z'),
      completedAt: DateTime.parse('2026-10-01T00:00:03Z'),
      createdAt: DateTime.parse('2026-10-01T00:00:00Z'),
    );

void main() {
  testWidgets('renders candidate-bound regime, methodology and outcome evidence', (tester) async {
    final repository = _FakeScenarioRepository(_evidence());
    await tester.pumpWidget(
      MaterialApp(
        home: ScenarioEvidencePage(
          repository: repository,
          scenarioRunId: '11111111-1111-1111-1111-111111111111',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.requestedId, '11111111-1111-1111-1111-111111111111');
    expect(find.byKey(const ValueKey('scenario-identity')), findsOneWidget);
    expect(find.byKey(const ValueKey('scenario-methodology')), findsOneWidget);
    expect(find.byKey(const ValueKey('scenario-outcomes')), findsOneWidget);
    expect(find.byKey(const ValueKey('scenario-r-distribution')), findsOneWidget);
    expect(find.text('Regime: TRENDING_UP'), findsOneWidget);
    expect(find.text('Fallback tier: EXACT_REGIME'), findsOneWidget);
    expect(find.text('Methodology: BLOCK_BOOTSTRAP_V1'), findsOneWidget);
    expect(find.textContaining('Candidate: 42'), findsOneWidget);
    expect(find.textContaining('Data cutoff: 2026-10-01T00:00:00.000Z'), findsOneWidget);
    expect(find.text('Expected R: 0.42'), findsOneWidget);
  });
}
