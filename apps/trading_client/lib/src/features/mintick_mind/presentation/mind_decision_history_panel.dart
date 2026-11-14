import 'package:flutter/material.dart';

import '../data/mind_decision_history_repository.dart';
import 'mind_widgets.dart';

/// Persisted research candidate history. Research evidence only: no order, no
/// execution and no live money. Search filters the loaded records on the client.
class MindDecisionHistoryPanel extends StatefulWidget {
  const MindDecisionHistoryPanel({required this.repository, super.key});

  final MindDecisionHistoryRepository repository;

  @override
  State<MindDecisionHistoryPanel> createState() => _MindDecisionHistoryPanelState();
}

class _MindDecisionHistoryPanelState extends State<MindDecisionHistoryPanel> {
  late Future<MindDecisionHistory> _request;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    return FutureBuilder<MindDecisionHistory>(
      future: _request,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Center(child: CircularProgressIndicator(strokeWidth: 2)));
        }
        if (snapshot.hasError || snapshot.data == null) {
          return Text('Decision history unavailable. Nothing is shown.',
            key: const ValueKey('mind-decision-history-error'), style: type.bodySmall);
        }
        final history = snapshot.data!;
        final needle = _query.trim().toLowerCase();
        final shown = needle.isEmpty
            ? history.decisions
            : history.decisions.where((d) =>
                d.strategy.toLowerCase().contains(needle) ||
                d.tradingSymbol.toLowerCase().contains(needle) ||
                d.candidateFingerprint.toLowerCase().contains(needle)).toList();
        return Column(
          key: const ValueKey('mind-decision-history'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const MindTag('RECORDED CANDIDATES · RESEARCH ONLY · NO ORDER',
              color: MindPalette.blue),
            const SizedBox(height: 8),
            TextField(
              key: const ValueKey('mind-decision-search'),
              decoration: const InputDecoration(
                labelText: 'Search strategy, symbol or fingerprint', isDense: true),
              onChanged: (value) => setState(() => _query = value),
            ),
            const SizedBox(height: 8),
            if (shown.isEmpty)
              Text(history.decisions.isEmpty
                  ? 'No recorded candidates from succeeded scan runs.'
                  : 'No recorded candidates match this search.',
                style: type.bodySmall)
            else
              for (final d in shown)
                Padding(
                  key: ValueKey('mind-decision-${d.candidateFingerprint}'),
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${d.strategy} v${d.strategyVersion ?? '—'} · ${d.tradingSymbol} · ${d.interval}',
                        style: type.labelLarge),
                      Text(
                        'Signal ${d.signalCandleTime.toIso8601String()} · '
                        'data cutoff ${d.dataCutoffAt.toIso8601String()}',
                        style: type.bodySmall),
                      Text(
                        'Reasons: ${d.reasons.isEmpty ? 'none recorded' : d.reasons.join(', ')}',
                        style: type.bodySmall),
                      Text(
                        'Fingerprint ${d.candidateFingerprint.length > 12 ? d.candidateFingerprint.substring(0, 12) : d.candidateFingerprint}…'
                        '${d.catalogueVersion == null ? '' : ' · catalogue ${d.catalogueVersion}'}',
                        style: type.labelSmall),
                    ],
                  ),
                ),
            const SizedBox(height: 4),
            for (final limit in history.limitations)
              Text('• $limit', style: type.labelSmall),
          ],
        );
      },
    );
  }
}
