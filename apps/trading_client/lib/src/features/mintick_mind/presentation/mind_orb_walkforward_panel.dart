import 'package:flutter/material.dart';
import '../data/mind_orb_walkforward_repository.dart';
import 'mind_widgets.dart';

/// Research-only ORB report; never authorizes paper or live execution.
class MindOrbWalkForwardPanel extends StatefulWidget {
  const MindOrbWalkForwardPanel({required this.repository, super.key});
  final MindOrbWalkForwardRepository repository;

  @override
  State<MindOrbWalkForwardPanel> createState() => _MindOrbWalkForwardPanelState();
}

class _MindOrbWalkForwardPanelState extends State<MindOrbWalkForwardPanel> {
  late Future<MindOrbWalkForwardReport> _request;
  bool _showFolds = false;

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindOrbWalkForwardPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) {
      _request = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _request = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('mind-orb-walkforward'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.query_stats, size: 20, color: MindPalette.mint),
        const SizedBox(width: 8),
        const Expanded(child: Text('ORB after-cost walk-forward',
            style: TextStyle(color: MindPalette.text,
                fontWeight: FontWeight.w800, fontSize: 14))),
        IconButton(
          tooltip: 'Refresh ORB walk-forward report',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh, size: 21, color: MindPalette.muted),
        ),
      ]),
      const SizedBox(height: 4),
      const Text('Opening Range Breakout · 180-day history · fixed 60/20-day folds · '
          '1, 3 or 5-minute bars',
          style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 12),
      FutureBuilder<MindOrbWalkForwardReport>(
        future: _request,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Row(children: [
              SizedBox(width: 16, height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 9),
              Expanded(child: Text('Checking stored history and fold coverage…',
                  style: TextStyle(color: MindPalette.muted, fontSize: 12))),
            ]);
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Text('ORB walk-forward evidence unavailable. '
                'No trade is authorized.',
                key: ValueKey('mind-orb-wf-unavailable'),
                style: TextStyle(color: MindPalette.yellow, fontSize: 12));
          }
          return _report(snapshot.data!);
        },
      ),
    ]),
  );

  Widget _report(MindOrbWalkForwardReport report) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(children: [
        Expanded(child: Text(
          report.instrument == null ? 'No source-backed ORB candidate'
              : '${report.instrument} · ${report.interval ?? 'interval unknown'}',
          maxLines: 1, overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: MindPalette.text,
              fontSize: 12, fontWeight: FontWeight.w700))),
        const SizedBox(width: 6),
        Text(report.status.replaceAll('_', ' '),
          key: const ValueKey('mind-orb-wf-status'),
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
          _metric('Sum of holdout net P&L',
              '₹${report.oosNetPnl!.toStringAsFixed(2)}'),
      ],
      const SizedBox(height: 8),
      Text('Per-side assumptions: ${(report.feePerSide * 100).toStringAsFixed(2)}% fees + '
          '${(report.slippagePerSide * 100).toStringAsFixed(2)}% slippage',
          style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
      const SizedBox(height: 3),
      const Text('Illustrative costs only; not broker or statutory charges. '
          'Source availability at signal time is unverified.',
          style: TextStyle(color: MindPalette.muted, fontSize: 10)),
      const SizedBox(height: 7),
      const Text('ABSTAIN · Research only · No paper or broker order',
          style: TextStyle(color: MindPalette.yellow,
              fontSize: 10, fontWeight: FontWeight.w700)),
      const SizedBox(height: 7),
      TextButton(
        onPressed: () => setState(() => _showFolds = !_showFolds),
        child: Text(_showFolds ? 'Hide fold details' : 'Show fold coverage'),
      ),
      if (_showFolds)
        for (final fold in report.folds)
          Padding(
            padding: const EdgeInsets.only(bottom: 5),
            child: Text('Fold ${fold.number}: train '
                '${fold.trainingCoveragePercent.toStringAsFixed(1)}% · holdout '
                '${fold.validationCoveragePercent.toStringAsFixed(1)}% · '
                '${fold.status.replaceAll('_', ' ')}'
                '${fold.validationTrades == null ? '' : ' · ${fold.validationTrades} trades'}',
                style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
          ),
      if (report.blockers.isNotEmpty) ...[
        const SizedBox(height: 5),
        Text('Gates: ${report.blockers.take(5).join(' · ')}'
            '${report.blockers.length > 5 ? ' · +${report.blockers.length - 5} more' : ''}',
            style: const TextStyle(color: MindPalette.muted, fontSize: 9)),
      ],
    ],
  );

  Widget _metric(String label, String value) => Padding(
    padding: const EdgeInsets.only(bottom: 3),
    child: Row(children: [
      Expanded(child: Text(label,
          style: const TextStyle(color: MindPalette.muted, fontSize: 11))),
      Text(value, style: const TextStyle(color: MindPalette.text,
          fontSize: 11, fontWeight: FontWeight.w700)),
    ]),
  );
}
