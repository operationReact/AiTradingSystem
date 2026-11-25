import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/scenario_evidence_repository.dart';

class ScenarioEvidencePage extends StatefulWidget {
  const ScenarioEvidencePage({
    required this.repository,
    required this.scenarioRunId,
    super.key,
  });

  final ScenarioEvidenceRepository repository;
  final String scenarioRunId;

  @override
  State<ScenarioEvidencePage> createState() => _ScenarioEvidencePageState();
}

class _ScenarioEvidencePageState extends State<ScenarioEvidencePage> {
  ScenarioRunEvidence? _evidence;
  ApiFailure? _failure;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _failure = null;
    });
    try {
      final evidence = await widget.repository.fetch(widget.scenarioRunId);
      if (!mounted) return;
      setState(() => _evidence = evidence);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _failure = failure);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final evidence = _evidence;
    return Scaffold(
      appBar: AppBar(title: const Text('Scenario & regime evidence')),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _load,
          child: ListView(
            key: const ValueKey('scenario-evidence-list'),
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
            children: [
              Semantics(
                header: true,
                child: Text(
                  'Persisted scenario run',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'This screen reads the persisted scenario generated for a candidate evaluation. It does not run a new simulation or infer missing provenance.',
              ),
              const SizedBox(height: 16),
              if (_loading && evidence == null)
                const Center(
                  key: ValueKey('scenario-evidence-loading'),
                  child: Padding(
                    padding: EdgeInsets.all(32),
                    child: CircularProgressIndicator(),
                  ),
                )
              else if (_failure != null && evidence == null)
                _errorCard(_failure!)
              else if (evidence != null) ...[
                if (_failure != null) _staleCard(_failure!),
                _identityCard(evidence),
                const SizedBox(height: 16),
                _methodologyCard(evidence),
                const SizedBox(height: 16),
                _outcomesCard(evidence),
                const SizedBox(height: 16),
                _distributionCard(evidence),
                if (evidence.errorCode != null || evidence.errorMessage != null) ...[
                  const SizedBox(height: 16),
                  Card(
                    key: const ValueKey('scenario-backend-error-evidence'),
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(
                        'Backend lifecycle error: ${evidence.errorCode ?? 'UNKNOWN'} • ${evidence.errorMessage ?? 'No message'}',
                      ),
                    ),
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const ValueKey('scenario-evidence-refresh'),
        onPressed: _loading ? null : _load,
        icon: const Icon(Icons.refresh),
        label: const Text('Refresh'),
      ),
    );
  }

  Widget _identityCard(ScenarioRunEvidence value) => Card(
        key: const ValueKey('scenario-identity'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Identity & binding', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 10),
              _line('Scenario run', value.id),
              _line('Scan run', value.scanRunId),
              _line('Candidate', value.candidateId?.toString() ?? 'UNKNOWN'),
              _line('Instrument', '${value.exchange} ${value.tradingSymbol} (#${value.instrumentId})'),
              _line('Interval', value.interval),
              _line('Strategy', '${value.strategy} • config ${value.strategyConfigVersion ?? 'UNKNOWN'}'),
              _line('Entry / stop / target', '${value.entryPrice ?? 'UNKNOWN'} / ${value.stopPrice ?? 'UNKNOWN'} / ${value.targetPrice ?? 'UNKNOWN'}'),
              _line('Status', value.status),
            ],
          ),
        ),
      );

  Widget _methodologyCard(ScenarioRunEvidence value) => Card(
        key: const ValueKey('scenario-methodology'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Methodology & regime', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 10),
              _line('Regime', value.regime),
              _line('Fallback tier', value.fallbackTier ?? 'NONE'),
              _line('Methodology', value.methodology),
              _line('History window', '${value.historyFrom?.toIso8601String() ?? 'UNKNOWN'} → ${value.historyTo?.toIso8601String() ?? 'UNKNOWN'}'),
              _line('Data cutoff', value.dataCutoffAt?.toIso8601String() ?? 'UNKNOWN'),
              _line('Historical samples', '${value.historicalSamples}'),
              _line('Eligible blocks', '${value.eligibleBlockCount}'),
              _line('Simulations', '${value.simulationCount}'),
              _line('Horizon / block', '${value.horizonBars} / ${value.blockSize} bars'),
              _line('Seed', '${value.seed}'),
              _line('Costs', '${value.slippageBps} bps slippage • ${value.roundTripCostBps} bps round trip'),
              _line('Fingerprint', value.requestFingerprint.isEmpty ? 'UNKNOWN' : value.requestFingerprint),
            ],
          ),
        ),
      );

  Widget _outcomesCard(ScenarioRunEvidence value) => Card(
        key: const ValueKey('scenario-outcomes'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Outcome frequencies', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 10),
              _line('Target first', '${value.targetFirstCount} • ${value.targetFirstProbability ?? 'UNKNOWN'}'),
              _line('Stop first', '${value.stopFirstCount} • ${value.stopFirstProbability ?? 'UNKNOWN'}'),
              _line('Neither', '${value.neitherCount} • ${value.neitherProbability ?? 'UNKNOWN'}'),
              const SizedBox(height: 8),
              const Text('Probabilities are persisted backend evidence. The client does not normalize or fill missing values.'),
            ],
          ),
        ),
      );

  Widget _distributionCard(ScenarioRunEvidence value) => Card(
        key: const ValueKey('scenario-r-distribution'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('R distribution', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 10),
              _line('Expected R', '${value.expectedR ?? 'UNKNOWN'}'),
              _line('P05 R', '${value.p05R ?? 'UNKNOWN'}'),
              _line('P50 R', '${value.p50R ?? 'UNKNOWN'}'),
              _line('P95 R', '${value.p95R ?? 'UNKNOWN'}'),
              _line('Started', value.startedAt?.toIso8601String() ?? 'UNKNOWN'),
              _line('Completed', value.completedAt?.toIso8601String() ?? 'UNKNOWN'),
              _line('Created', value.createdAt?.toIso8601String() ?? 'UNKNOWN'),
            ],
          ),
        ),
      );

  Widget _errorCard(ApiFailure failure) => Card(
        key: const ValueKey('scenario-evidence-error'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Scenario evidence unavailable: ${failure.code}'),
              if (failure.correlationId != null) Text('Correlation: ${failure.correlationId}'),
              const SizedBox(height: 12),
              FilledButton(onPressed: _load, child: const Text('Retry')),
            ],
          ),
        ),
      );

  Widget _staleCard(ApiFailure failure) => Card(
        key: const ValueKey('scenario-evidence-stale'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text('Refresh failed (${failure.code}). Scenario values below are last-known persisted evidence.'),
        ),
      );

  Widget _line(String label, String value) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: SelectableText('$label: $value'),
      );
}
