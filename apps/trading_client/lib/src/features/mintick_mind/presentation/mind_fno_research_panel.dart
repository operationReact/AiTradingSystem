import 'package:flutter/material.dart';

import '../data/mind_fno_research_repository.dart';
import 'mind_widgets.dart';

/// A live backend research snapshot, deliberately separate from the existing
/// labelled illustrative option-chain prototype. Never exposes order actions.
class MindFnoResearchPanel extends StatefulWidget {
  const MindFnoResearchPanel({required this.repository, super.key});
  final MindFnoResearchRepository repository;

  @override
  State<MindFnoResearchPanel> createState() => _MindFnoResearchPanelState();
}

class _MindFnoResearchPanelState extends State<MindFnoResearchPanel> {
  late Future<MindFnoResearch> _pending;

  @override
  void initState() {
    super.initState();
    _pending = widget.repository.evaluate();
  }

  @override
  void didUpdateWidget(covariant MindFnoResearchPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) {
      _pending = widget.repository.evaluate();
    }
  }

  void _refresh() => setState(() => _pending = widget.repository.evaluate());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('mind-fno-real-research-panel'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.insights_outlined, color: MindPalette.mint, size: 19),
        const SizedBox(width: 8),
        const Expanded(child: Text('NIFTY · Source-backed research',
          style: TextStyle(color: MindPalette.text, fontWeight: FontWeight.w800, fontSize: 13))),
        IconButton(
          key: const ValueKey('mind-fno-research-refresh'),
          tooltip: 'Refresh NIFTY research',
          icon: const Icon(Icons.refresh, color: MindPalette.muted, size: 20),
          onPressed: _refresh),
      ]),
      const Text('Actual completed candles + authorized Upstox V3 quotes. '
        'Read-only; never an order.',
        style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 10),
      FutureBuilder<MindFnoResearch>(
        future: _pending,
        builder: (context, state) {
          if (state.connectionState != ConnectionState.done) {
            return const Text('Evaluating verified F&O evidence…',
              style: TextStyle(color: MindPalette.muted, fontSize: 12));
          }
          if (state.hasError || !state.hasData) {
            return const Text(
              'F&O research unavailable. No trade signal. Refresh to retry.',
              key: ValueKey('mind-fno-research-error'),
              style: TextStyle(color: MindPalette.yellow, fontSize: 12));
          }
          final result = state.data!;
          final candidate = result.hasCandidate;
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(candidate
                  ? (result.signal == 'BUY_CALL_RESEARCH'
                      ? 'LONG CALL · RESEARCH CANDIDATE'
                      : 'LONG PUT · RESEARCH CANDIDATE')
                  : 'NO TRADE · ${result.status}',
              key: const ValueKey('mind-fno-research-decision'),
              style: TextStyle(
                color: candidate ? MindPalette.mint : MindPalette.yellow,
                fontSize: 13, fontWeight: FontWeight.w800)),
            const SizedBox(height: 6),
            Text('Reason: ${result.reasonCode.replaceAll('_', ' ')}',
              style: const TextStyle(color: MindPalette.text, fontSize: 11)),
            const SizedBox(height: 5),
            Text('Completed NIFTY candles: ${result.completedCandles} · Evaluated: ${result.evaluatedAt.toUtc().toIso8601String()}',
              style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
            if (result.latestCompletedCloseAt != null)
              Text('Latest completed candle: ${result.latestCompletedCloseAt!.toUtc().toIso8601String()}',
                style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
            if (candidate) ...[
              const SizedBox(height: 8),
              Text('${result.strategyId!} · ${result.instrumentKey!}',
                style: const TextStyle(color: MindPalette.text, fontSize: 11)),
              Text('Expiry: ${result.expiry!.toIso8601String().substring(0, 10)} · Strike: ${result.strike} · Lot: ${result.lotSize}',
                style: const TextStyle(color: MindPalette.text, fontSize: 11)),
              Text('Exchange bid / ask: ${result.bid} / ${result.ask} · Tick: ${result.exchangeAt!.toUtc().toIso8601String()}',
                style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
              if (result.ltp != null || result.openInterest != null ||
                  result.iv != null || result.delta != null) ...[
                const SizedBox(height: 5),
                Text('V3 LTP: ${result.ltp ?? '—'} · OI: ${result.openInterest ?? '—'} · IV: ${result.iv ?? '—'}',
                  style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
                Text('Greeks · Δ ${result.delta ?? '—'} · θ ${result.theta ?? '—'} · γ ${result.gamma ?? '—'} · ν ${result.vega ?? '—'}',
                  style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
              ],
              const SizedBox(height: 4),
              const Text('ATM is an approximate paired-premium selection. '
                'No broker order, sizing or execution approval.',
                style: TextStyle(color: MindPalette.yellow, fontSize: 10)),
            ],
            const SizedBox(height: 7),
            const Text('STR-181 / STR-182 only · Research, not PAPER or LIVE trading.',
              key: ValueKey('mind-fno-research-only'),
              style: TextStyle(color: MindPalette.muted, fontSize: 10)),
          ]);
        },
      ),
    ]),
  );
}
