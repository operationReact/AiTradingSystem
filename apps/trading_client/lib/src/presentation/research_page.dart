import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/research_repository.dart';

class ResearchPage extends StatefulWidget {
  const ResearchPage({required this.repository, super.key});

  final ResearchRepository repository;

  @override
  State<ResearchPage> createState() => _ResearchPageState();
}

class _ResearchPageState extends State<ResearchPage> {
  String _interval = 'FIVE_MINUTE';
  int _limit = 100;
  ResearchScanSummary? _scan;
  List<ResearchCandidate> _candidates = const [];
  ResearchCandidateDetail? _selected;
  List<ResearchEvaluation> _evaluations = const [];
  String? _error;
  bool _runningScan = false;
  bool _loadingCandidate = false;
  bool _evaluating = false;

  Future<void> _runScan() async {
    setState(() {
      _runningScan = true;
      _error = null;
      _scan = null;
      _candidates = const [];
      _selected = null;
      _evaluations = const [];
    });
    try {
      final scan = await widget.repository.runScan(interval: _interval, limit: _limit);
      final candidates = await widget.repository.candidates(scan.id, limit: 50);
      if (!mounted) return;
      setState(() {
        _scan = scan;
        _candidates = candidates;
      });
    } on ApiFailure {
      if (!mounted) return;
      setState(() => _error = 'AI opportunities are temporarily unavailable.');
    } catch (_) {
      if (!mounted) return;
      setState(() => _error = 'The AI scan could not complete.');
    } finally {
      if (mounted) setState(() => _runningScan = false);
    }
  }

  Future<void> _selectCandidate(ResearchCandidate candidate) async {
    setState(() {
      _loadingCandidate = true;
      _error = null;
      _selected = null;
      _evaluations = const [];
    });
    try {
      final detail = await widget.repository.candidate(candidate.candidateId);
      final evaluations = await widget.repository.evaluations(candidate.candidateId);
      if (!mounted) return;
      setState(() {
        _selected = detail;
        _evaluations = evaluations;
      });
    } on ApiFailure {
      if (!mounted) return;
      setState(() => _error = 'This opportunity could not be loaded.');
    } finally {
      if (mounted) setState(() => _loadingCandidate = false);
    }
  }

  Future<void> _evaluate() async {
    final selected = _selected;
    if (selected == null) return;
    final historyTo = selected.dataCutoffAt ?? DateTime.now().toUtc();
    final historyFrom = historyTo.subtract(const Duration(days: 90));
    setState(() {
      _evaluating = true;
      _error = null;
    });
    try {
      await widget.repository.evaluate(
        candidateId: selected.id,
        historyFrom: historyFrom,
        historyTo: historyTo,
      );
      final evaluations = await widget.repository.evaluations(selected.id);
      if (!mounted) return;
      setState(() => _evaluations = evaluations);
    } on ApiFailure {
      if (!mounted) return;
      setState(() => _error = 'The opportunity review could not complete.');
    } finally {
      if (mounted) setState(() => _evaluating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('AI Research')),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final wide = constraints.maxWidth >= 960;
            return ListView(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
              children: [
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(24),
                    gradient: LinearGradient(
                      colors: [theme.colorScheme.primaryContainer, theme.colorScheme.surfaceContainerHighest],
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Semantics(
                        header: true,
                        child: Text('AI opportunity scanner', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Let the research engine rank potential setups, then inspect the idea, risk levels, and evaluation history before taking any PAPER action.',
                        style: theme.textTheme.bodyLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: 1.45),
                      ),
                    ],
                  ),
                ),
                if (_error != null) ...[
                  const SizedBox(height: 16),
                  Card(
                    key: const ValueKey('research-error'),
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(_error!),
                    ),
                  ),
                ],
                const SizedBox(height: 18),
                _scanControls(context),
                if (_scan != null) ...[
                  const SizedBox(height: 16),
                  _scanSummary(context, _scan!),
                ],
                const SizedBox(height: 18),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: _candidateList(context)),
                      const SizedBox(width: 16),
                      Expanded(child: _candidateDetail(context)),
                    ],
                  )
                else ...[
                  _candidateList(context),
                  const SizedBox(height: 16),
                  _candidateDetail(context),
                ],
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _scanControls(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Wrap(
            spacing: 12,
            runSpacing: 12,
            crossAxisAlignment: WrapCrossAlignment.end,
            children: [
              ConstrainedBox(
                constraints: const BoxConstraints(minWidth: 140, maxWidth: 190),
                child: DropdownButtonFormField<String>(
                  key: const ValueKey('research-scan-interval'),
                  isExpanded: true,
                  initialValue: _interval,
                  decoration: const InputDecoration(labelText: 'Timeframe'),
                  items: const [
                    DropdownMenuItem(value: 'FIVE_MINUTE', child: Text('5 minutes')),
                    DropdownMenuItem(value: 'FIFTEEN_MINUTE', child: Text('15 minutes')),
                    DropdownMenuItem(value: 'ONE_HOUR', child: Text('1 hour')),
                    DropdownMenuItem(value: 'ONE_DAY', child: Text('1 day')),
                  ],
                  onChanged: (value) {
                    if (value != null) setState(() => _interval = value);
                  },
                ),
              ),
              ConstrainedBox(
                constraints: const BoxConstraints(minWidth: 120, maxWidth: 170),
                child: DropdownButtonFormField<int>(
                  key: const ValueKey('research-scan-limit'),
                  isExpanded: true,
                  initialValue: _limit,
                  decoration: const InputDecoration(labelText: 'Stocks to analyze'),
                  items: const [
                    DropdownMenuItem(value: 50, child: Text('50')),
                    DropdownMenuItem(value: 100, child: Text('100')),
                    DropdownMenuItem(value: 250, child: Text('250')),
                  ],
                  onChanged: (value) {
                    if (value != null) setState(() => _limit = value);
                  },
                ),
              ),
              FilledButton.icon(
                key: const ValueKey('research-run-scan'),
                onPressed: _runningScan ? null : _runScan,
                icon: _runningScan
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.auto_awesome_rounded),
                label: Text(_runningScan ? 'Analyzing market…' : 'Find opportunities'),
              ),
            ],
          ),
        ),
      );

  Widget _scanSummary(BuildContext context, ResearchScanSummary scan) => Card(
        key: const ValueKey('research-scan-summary'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Wrap(
            spacing: 24,
            runSpacing: 12,
            children: [
              _Metric(label: 'Scan status', value: scan.status),
              _Metric(label: 'Stocks analyzed', value: '${scan.scannedCount}'),
              _Metric(label: 'Opportunities', value: '${scan.candidateCount}'),
              _Metric(label: 'Strategy set', value: scan.strategySet.isEmpty ? '—' : scan.strategySet),
            ],
          ),
        ),
      );

  Widget _candidateList(BuildContext context) => Card(
        key: const ValueKey('research-candidates'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(header: true, child: Text('Top opportunities', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800))),
              const SizedBox(height: 10),
              if (_runningScan)
                const Center(child: CircularProgressIndicator())
              else if (_scan == null)
                const Text('Run an AI scan to discover ranked trade ideas.')
              else if (_candidates.isEmpty)
                const Text('No setups met the current research criteria.')
              else
                ..._candidates.map(
                  (candidate) => ListTile(
                    key: ValueKey('research-candidate-${candidate.candidateId}'),
                    selected: _selected?.id == candidate.candidateId,
                    title: Text(candidate.tradingSymbol.isEmpty ? 'Instrument ${candidate.instrumentId}' : candidate.tradingSymbol),
                    subtitle: Text('${candidate.exchange} • ${candidate.strategy}\nAI score ${candidate.score.toStringAsFixed(3)} • ${candidate.reasons.join(', ')}'),
                    isThreeLine: true,
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => _selectCandidate(candidate),
                  ),
                ),
            ],
          ),
        ),
      );

  Widget _candidateDetail(BuildContext context) {
    final selected = _selected;
    return Card(
      key: const ValueKey('research-candidate-detail'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Semantics(header: true, child: Text('Opportunity details', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800))),
            const SizedBox(height: 10),
            if (_loadingCandidate)
              const Center(child: CircularProgressIndicator())
            else if (selected == null)
              const Text('Select an opportunity to review its setup, risk levels, and evaluation history.')
            else ...[
              Text(selected.tradingSymbol.isEmpty ? 'Trade idea ${selected.id}' : selected.tradingSymbol, style: const TextStyle(fontWeight: FontWeight.w800)),
              const SizedBox(height: 8),
              Text('${selected.exchange} • ${selected.interval} • ${selected.strategy}'),
              const SizedBox(height: 12),
              Wrap(
                spacing: 16,
                runSpacing: 8,
                children: [
                  _Metric(label: 'Entry', value: '${selected.entryPrice}'),
                  _Metric(label: 'Stop loss', value: '${selected.stopPrice}'),
                  _Metric(label: 'Target', value: '${selected.targetPrice}'),
                  _Metric(label: 'Analyzed through', value: selected.dataCutoffAt?.toLocal().toString() ?? '—'),
                ],
              ),
              const SizedBox(height: 14),
              FilledButton.icon(
                key: const ValueKey('research-evaluate-candidate'),
                onPressed: _evaluating ? null : _evaluate,
                icon: _evaluating
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.insights_rounded),
                label: Text(_evaluating ? 'Reviewing…' : 'Review this setup'),
              ),
              const SizedBox(height: 16),
              Text('Past evaluations', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
              const SizedBox(height: 8),
              if (_evaluations.isEmpty)
                const Text('No previous evaluation is available for this setup.')
              else
                ..._evaluations.map(
                  (evaluation) => ListTile(
                    dense: true,
                    title: Text(evaluation.decisionLabel),
                    subtitle: Text(evaluation.reasonCodes.join(', ')),
                    trailing: Text(evaluation.createdAt?.toLocal().toString() ?? ''),
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Semantics(
        label: '$label: $value',
        child: SizedBox(
          width: 190,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: Theme.of(context).textTheme.labelMedium),
              const SizedBox(height: 4),
              Text(value, maxLines: 3, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
            ],
          ),
        ),
      );
}
