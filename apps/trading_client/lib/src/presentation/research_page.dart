import 'package:flutter/material.dart';

import '../core/widgets/responsive_layout.dart';
import '../data/authenticated_api_client.dart';
import '../data/research_repository.dart';
import 'customer_lifecycle_state.dart';

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
            final width = constraints.maxWidth;
            final wide = width >= 960;
            final mobile = width < ResponsiveLayout.mobileBreakpoint;
            final pagePadding = ResponsiveLayout.horizontalPadding(width);
            final sectionGap = ResponsiveLayout.sectionGap(width);
            return ListView(
              padding: EdgeInsets.fromLTRB(pagePadding, ResponsiveLayout.topPadding(width), pagePadding, 24),
              children: [
                Container(
                  padding: EdgeInsets.all(ResponsiveLayout.heroPadding(width)),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(ResponsiveLayout.heroRadius(width)),
                    gradient: LinearGradient(
                      colors: [theme.colorScheme.primaryContainer, theme.colorScheme.surfaceContainerHighest],
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Semantics(
                        header: true,
                        child: Text(
                          'AI opportunity scanner',
                          style: (mobile ? theme.textTheme.titleLarge : theme.textTheme.headlineSmall)?.copyWith(fontWeight: FontWeight.w900),
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        'Rank potential setups, inspect risk, and review evidence before any PAPER action.',
                        style: (mobile ? theme.textTheme.bodyMedium : theme.textTheme.bodyLarge)?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                          height: mobile ? 1.3 : 1.45,
                        ),
                      ),
                    ],
                  ),
                ),
                if (_error != null) ...[
                  SizedBox(height: mobile ? 10 : 16),
                  Card(
                    key: const ValueKey('research-error'),
                    child: Padding(
                      padding: EdgeInsets.all(mobile ? 12 : 16),
                      child: Text(_error!),
                    ),
                  ),
                ],
                SizedBox(height: sectionGap),
                _scanControls(context, mobile: mobile),
                if (_scan != null) ...[
                  SizedBox(height: mobile ? 10 : 16),
                  _scanSummary(context, _scan!, mobile: mobile),
                ],
                SizedBox(height: sectionGap),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: _candidateList(context, mobile: false)),
                      const SizedBox(width: 16),
                      Expanded(child: _candidateDetail(context, mobile: false)),
                    ],
                  )
                else ...[
                  _candidateList(context, mobile: mobile),
                  if (_selected != null || _loadingCandidate) ...[
                    SizedBox(height: mobile ? 10 : 16),
                    _candidateDetail(context, mobile: mobile),
                  ],
                ],
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _scanControls(BuildContext context, {required bool mobile}) {
    final intervalField = DropdownButtonFormField<String>(
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
    );
    final limitField = DropdownButtonFormField<int>(
      key: const ValueKey('research-scan-limit'),
      isExpanded: true,
      initialValue: _limit,
      decoration: const InputDecoration(labelText: 'Stocks'),
      items: const [
        DropdownMenuItem(value: 50, child: Text('50')),
        DropdownMenuItem(value: 100, child: Text('100')),
        DropdownMenuItem(value: 250, child: Text('250')),
      ],
      onChanged: (value) {
        if (value != null) setState(() => _limit = value);
      },
    );
    final action = FilledButton.icon(
      key: const ValueKey('research-run-scan'),
      onPressed: _runningScan ? null : _runScan,
      icon: _runningScan
          ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
          : const Icon(Icons.auto_awesome_rounded, size: 19),
      label: Text(_runningScan ? 'Analyzing market…' : 'Find opportunities'),
    );

    return Card(
      child: Padding(
        padding: EdgeInsets.all(mobile ? 12 : 16),
        child: mobile
            ? Column(
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Expanded(child: intervalField),
                      const SizedBox(width: 8),
                      Expanded(child: limitField),
                    ],
                  ),
                  const SizedBox(height: 8),
                  SizedBox(width: double.infinity, child: action),
                ],
              )
            : Wrap(
                spacing: 12,
                runSpacing: 12,
                crossAxisAlignment: WrapCrossAlignment.end,
                children: [
                  ConstrainedBox(constraints: const BoxConstraints(minWidth: 140, maxWidth: 190), child: intervalField),
                  ConstrainedBox(constraints: const BoxConstraints(minWidth: 120, maxWidth: 170), child: limitField),
                  action,
                ],
              ),
      ),
    );
  }

  Widget _scanSummary(BuildContext context, ResearchScanSummary scan, {required bool mobile}) => Card(
        key: const ValueKey('research-scan-summary'),
        child: Padding(
          padding: EdgeInsets.all(mobile ? 12 : 16),
          child: Wrap(
            spacing: mobile ? 16 : 24,
            runSpacing: mobile ? 8 : 12,
            children: [
              _Metric(label: 'Scan status', value: scan.status, compact: mobile),
              _Metric(label: 'Stocks analyzed', value: '${scan.scannedCount}', compact: mobile),
              _Metric(label: 'Opportunities', value: '${scan.candidateCount}', compact: mobile),
              _Metric(label: 'Strategy set', value: scan.strategySet.isEmpty ? '—' : scan.strategySet, compact: mobile),
            ],
          ),
        ),
      );

  Widget _candidateList(BuildContext context, {required bool mobile}) => Card(
        key: const ValueKey('research-candidates'),
        child: Padding(
          padding: EdgeInsets.all(mobile ? 12 : 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                header: true,
                child: Text('Top opportunities', style: (mobile ? Theme.of(context).textTheme.titleMedium : Theme.of(context).textTheme.titleLarge)?.copyWith(fontWeight: FontWeight.w800)),
              ),
              const SizedBox(height: 8),
              if (_runningScan)
                const Center(child: CircularProgressIndicator())
              else if (_scan == null)
                Text('Run a scan to discover ranked trade ideas.', style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant))
              else if (_candidates.isEmpty)
                const Text('No setups met the current research criteria.')
              else
                ..._candidates.map(
                  (candidate) => ListTile(
                    key: ValueKey('research-candidate-${candidate.candidateId}'),
                    dense: true,
                    selected: _selected?.id == candidate.candidateId,
                    title: Row(
                      children: [
                        Expanded(child: Text(candidate.tradingSymbol.isEmpty ? 'Instrument ${candidate.instrumentId}' : candidate.tradingSymbol)),
                        const SizedBox(width: 8),
                        const CustomerLifecycleBadge(state: CustomerLifecycleState.watching),
                      ],
                    ),
                    subtitle: Text('${candidate.exchange} • ${candidate.strategy}\nAI ${candidate.score.toStringAsFixed(3)} • ${candidate.reasons.join(', ')}'),
                    isThreeLine: true,
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => _selectCandidate(candidate),
                  ),
                ),
            ],
          ),
        ),
      );

  Widget _candidateDetail(BuildContext context, {required bool mobile}) {
    final selected = _selected;
    return Card(
      key: const ValueKey('research-candidate-detail'),
      child: Padding(
        padding: EdgeInsets.all(mobile ? 12 : 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Semantics(
              header: true,
              child: Text('Opportunity details', style: (mobile ? Theme.of(context).textTheme.titleMedium : Theme.of(context).textTheme.titleLarge)?.copyWith(fontWeight: FontWeight.w800)),
            ),
            const SizedBox(height: 8),
            if (_loadingCandidate)
              const Center(child: CircularProgressIndicator())
            else if (selected == null)
              const Text('Select an opportunity to review its setup and risk levels.')
            else ...[
              Text(selected.tradingSymbol.isEmpty ? 'Trade idea ${selected.id}' : selected.tradingSymbol, style: const TextStyle(fontWeight: FontWeight.w800)),
              const SizedBox(height: 6),
              Text('${selected.exchange} • ${selected.interval} • ${selected.strategy}'),
              const SizedBox(height: 10),
              Wrap(
                spacing: mobile ? 12 : 16,
                runSpacing: 8,
                children: [
                  _Metric(label: 'Entry', value: '${selected.entryPrice}', compact: mobile),
                  _Metric(label: 'Stop loss', value: '${selected.stopPrice}', compact: mobile),
                  _Metric(label: 'Target', value: '${selected.targetPrice}', compact: mobile),
                  _Metric(label: 'Analyzed through', value: selected.dataCutoffAt?.toLocal().toString() ?? '—', compact: mobile),
                ],
              ),
              const SizedBox(height: 10),
              SizedBox(
                width: mobile ? double.infinity : null,
                child: FilledButton.icon(
                  key: const ValueKey('research-evaluate-candidate'),
                  onPressed: _evaluating ? null : _evaluate,
                  icon: _evaluating
                      ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.insights_rounded, size: 19),
                  label: Text(_evaluating ? 'Reviewing…' : 'Review this setup'),
                ),
              ),
              const SizedBox(height: 12),
              Text('Past evaluations', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
              const SizedBox(height: 6),
              if (_evaluations.isEmpty)
                const Text('No previous evaluation is available for this setup.')
              else
                ..._evaluations.map(
                  (evaluation) => ListTile(
                    dense: true,
                    title: Row(
                      children: [
                        Expanded(child: Text(CustomerLifecycleState.forEvaluation(evaluation.decisionLabel).label)),
                        const SizedBox(width: 8),
                        CustomerLifecycleBadge(state: CustomerLifecycleState.forEvaluation(evaluation.decisionLabel)),
                      ],
                    ),
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
  const _Metric({required this.label, required this.value, this.compact = false});

  final String label;
  final String value;
  final bool compact;

  @override
  Widget build(BuildContext context) => Semantics(
        label: '$label: $value',
        child: SizedBox(
          width: compact ? 138 : 190,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: Theme.of(context).textTheme.labelMedium),
              const SizedBox(height: 2),
              Text(value, maxLines: compact ? 2 : 3, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
            ],
          ),
        ),
      );
}
