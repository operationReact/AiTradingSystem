import 'package:flutter/material.dart';

import '../data/mind_decision_outcomes_repository.dart';
import 'mind_widgets.dart';

/// Persisted per-strategy outcomes: CANDIDATE, NO_SIGNAL and NOT_EVALUATED with reasons.
/// Research evidence only. Search filters the loaded rows on the client.
class MindDecisionOutcomesPanel extends StatefulWidget {
  const MindDecisionOutcomesPanel({required this.repository, super.key});

  final MindDecisionOutcomesRepository repository;

  @override
  State<MindDecisionOutcomesPanel> createState() => _MindDecisionOutcomesPanelState();
}

class _MindDecisionOutcomesPanelState extends State<MindDecisionOutcomesPanel> {
  late Future<MindDecisionOutcomes> _request;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    return FutureBuilder<MindDecisionOutcomes>(
      future: _request,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Center(child: CircularProgressIndicator(strokeWidth: 2)));
        }
        if (snapshot.hasError || snapshot.data == null) {
          return Text('Strategy outcomes unavailable. Nothing is shown.',
            key: const ValueKey('mind-outcomes-error'), style: type.bodySmall);
        }
        final data = snapshot.data!;
        final needle = _query.trim().toLowerCase();
        final shown = needle.isEmpty
            ? data.outcomes
            : data.outcomes.where((o) =>
                o.strategy.toLowerCase().contains(needle) ||
                (o.tradingSymbol ?? '').toLowerCase().contains(needle) ||
                o.outcome.toLowerCase().contains(needle)).toList();
        return Column(
          key: const ValueKey('mind-decision-outcomes'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const MindTag('PERSISTED OUTCOMES · RESEARCH ONLY · NO ORDER', color: MindPalette.blue),
            const SizedBox(height: 8),
            for (final run in data.runs)
              Padding(
                key: ValueKey('mind-outcome-run-${run.scanRunId}'),
                padding: const EdgeInsets.only(bottom: 6),
                child: Text(
                  'Run ${run.completedAt.toIso8601String()} · ${run.interval} · '
                  'candidates ${run.candidates} · no signal ${run.noSignal} · '
                  'not evaluated ${run.notEvaluated}',
                  style: type.bodySmall),
              ),
            const SizedBox(height: 6),
            TextField(
              key: const ValueKey('mind-outcome-search'),
              decoration: const InputDecoration(
                labelText: 'Search strategy, symbol or outcome', isDense: true),
              onChanged: (value) => setState(() => _query = value),
            ),
            const SizedBox(height: 8),
            if (shown.isEmpty)
              Text(data.outcomes.isEmpty
                  ? 'No persisted outcomes from succeeded scan runs yet.'
                  : 'No outcomes match this search.',
                style: type.bodySmall)
            else
              for (final o in shown)
                Padding(
                  key: ValueKey('mind-outcome-${o.scanRunId}-${o.instrumentId}-${o.strategy}'),
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text(
                    '${o.strategy} · ${o.tradingSymbol ?? 'instrument ${o.instrumentId}'} · '
                    '${o.outcome} · ${o.reasonCode}'
                    '${o.dataCutoffAt == null ? '' : ' · cutoff ${o.dataCutoffAt!.toIso8601String()}'}',
                    style: type.bodySmall),
                ),
            const SizedBox(height: 4),
            for (final limit in data.limitations)
              Text('• $limit', style: type.labelSmall),
          ],
        );
      },
    );
  }
}
