import 'package:flutter/material.dart';

import '../data/mind_ma_walkforward_repository.dart';
import 'mind_widgets.dart';

/// Historical research only: never enables a paper order or a live trade.
class MindMaWalkForwardPanel extends StatefulWidget {
  const MindMaWalkForwardPanel({required this.repository, super.key});

  final MindMaWalkForwardRepository repository;

  @override
  State<MindMaWalkForwardPanel> createState() => _MindMaWalkForwardPanelState();
}

class _MindMaWalkForwardPanelState extends State<MindMaWalkForwardPanel> {
  late Future<MindMaWalkForwardReport> _request;
  bool _showFolds = false;

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindMaWalkForwardPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) {
      _request = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _request = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('mind-ma-walkforward'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.query_stats, size: 20, color: MindPalette.mint),
        const SizedBox(width: 8),
        const Expanded(child: Text('After-cost walk-forward research',
            style: TextStyle(color: MindPalette.text, fontWeight: FontWeight.w800,
                fontSize: 14))),
        IconButton(
          tooltip: 'Refresh walk-forward research',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh, size: 21, color: MindPalette.muted),
        ),
      ]),
      const SizedBox(height: 4),
      const Text('MA Reclaim v1 · fixed 60-day training / 20-day holdout · '
        'no parameter optimization', style: TextStyle(
            color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 12),
      FutureBuilder<MindMaWalkForwardReport>(
        future: _request,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Row(children: [
              SizedBox(width: 16, height: 16,
                child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 9),
              Expanded(child: Text('Evaluating stored research history…',
                  style: TextStyle(color: MindPalette.muted, fontSize: 12))),
            ]);
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Text(
              'Walk-forward evidence unavailable. No trade is authorized.',
              key: ValueKey('mind-wf-unavailable'),
              style: TextStyle(color: MindPalette.yellow, fontSize: 12));
          }
          return _report(snapshot.data!);
        },
      ),
    ]),
  );

  Widget _report(MindMaWalkForwardReport report) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(children: [
        Expanded(child: Text(
          report.instrument ?? 'No source-backed reference candidate',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: MindPalette.text,
              fontSize: 12, fontWeight: FontWeight.w700))),
        const SizedBox(width: 6),
        Text(report.status.replaceAll('_', ' '),
          key: const ValueKey('mind-wf-status'),
          textAlign: TextAlign.end,
          style: const TextStyle(color: MindPalette.yellow,
              fontWeight: FontWeight.w700, fontSize: 10)),
      ]),
      const SizedBox(height: 7),
      Text('${report.completedFolds}/${report.folds.length} evaluated folds · '
          '${report.oosTrades} out-of-sample trades',
          style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
      if (report.meanOosReturnPercent != null) ...[
        const SizedBox(height: 7),
        _metric('Mean holdout net return',
          '${report.meanOosReturnPercent!.toStringAsFixed(2)}%'),
        if (report.worstOosDrawdownPercent != null)
          _metric('Worst holdout drawdown',
            '${report.worstOosDrawdownPercent!.toStringAsFixed(2)}%'),
        if (report.oosNetPnl != null)
          _metric('Sum of independent holdout net P&L',
            '₹${report.oosNetPnl!.toStringAsFixed(2)}'),
      ],
      const SizedBox(height: 8),
      Text('Modeled per-side cost: '
          '${(report.feePerSide * 100).toStringAsFixed(2)}% fees + '
          '${(report.slippagePerSide * 100).toStringAsFixed(2)}% slippage',
          style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
      const SizedBox(height: 3),
      const Text('Composite cost assumptions are estimates, not actual '
          'brokerage or complete statutory charges.',
          style: TextStyle(color: MindPalette.muted, fontSize: 10)),
      const SizedBox(height: 9),
      const Text('ABSTAIN · Not a proven trading edge · Execution disabled',
          key: ValueKey('mind-wf-no-execution'),
          style: TextStyle(color: MindPalette.yellow,
              fontWeight: FontWeight.w700, fontSize: 11)),
      const SizedBox(height: 6),
      const Text('Instrument selected retrospectively. Results have '
          'selection bias and no independent known-at attestation.',
          style: TextStyle(color: MindPalette.muted, fontSize: 10)),
      if (report.folds.isNotEmpty) ...[
        const SizedBox(height: 9),
        TextButton.icon(
          key: const ValueKey('mind-wf-expand-folds'),
          onPressed: () => setState(() => _showFolds = !_showFolds),
          icon: Icon(_showFolds ? Icons.expand_less : Icons.expand_more,
              size: 16, color: MindPalette.mint),
          label: Text(_showFolds ? 'Hide fold details' :
              'Review ${report.folds.length} holdout folds',
              style: const TextStyle(color: MindPalette.mint, fontSize: 11)),
        ),
        if (_showFolds)
          for (final fold in report.folds) _fold(fold),
      ],
      const SizedBox(height: 7),
      for (final reason in report.blockers.take(5))
        Padding(
          padding: const EdgeInsets.only(bottom: 3),
          child: Text('• ${reason.replaceAll('_', ' ')}',
            style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
        ),
    ],
  );

  Widget _metric(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(children: [
      Expanded(child: Text(label,
        style: const TextStyle(color: MindPalette.muted, fontSize: 11))),
      const SizedBox(width: 6),
      Text(value, style: const TextStyle(
          color: MindPalette.text, fontWeight: FontWeight.w700, fontSize: 11)),
    ]),
  );

  Widget _fold(MindMaWalkForwardFold fold) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 5),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text('Fold ${fold.number} · ${fold.status.replaceAll('_', ' ')}',
          style: const TextStyle(color: MindPalette.text,
            fontWeight: FontWeight.w700, fontSize: 11))),
        if (fold.validation != null)
          Text('${fold.validation!.returnPercent.toStringAsFixed(2)}% net',
            style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
      ]),
      Text('Train: ${_date(fold.trainingFrom)}–${_date(fold.trainingTo)}'
          ' · Holdout: ${_date(fold.validationFrom)}–${_date(fold.validationTo)}',
          style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
      Text('Source coverage: ${fold.trainingCoveragePercent.toStringAsFixed(1)}% '
        'train / ${fold.validationCoveragePercent.toStringAsFixed(1)}% holdout'
        '${fold.validation == null ? '' :
            ' · ${fold.validation!.closedTrades} trades · '
            'DD ${fold.validation!.maxDrawdownPercent.toStringAsFixed(1)}%'}',
        style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
    ]),
  );

  static String _date(DateTime value) => value.toIso8601String().substring(0, 10);
}
