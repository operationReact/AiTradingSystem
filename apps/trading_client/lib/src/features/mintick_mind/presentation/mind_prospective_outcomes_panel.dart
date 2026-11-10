import 'package:flutter/material.dart';

import '../data/mind_prospective_outcomes_repository.dart';
import 'mind_widgets.dart';

/// Read-only outcomes panel. The account never places an order through Mind.
class MindProspectiveOutcomesPanel extends StatefulWidget {
  const MindProspectiveOutcomesPanel({required this.repository, super.key});
  final MindProspectiveOutcomesRepository repository;

  @override
  State<MindProspectiveOutcomesPanel> createState() =>
      _MindProspectiveOutcomesPanelState();
}

class _MindProspectiveOutcomesPanelState
    extends State<MindProspectiveOutcomesPanel> {
  late Future<MindProspectiveOutcomesStatus> _request;

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindProspectiveOutcomesPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) {
      _request = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _request = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('minttick-prospective-outcomes'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.fact_check_outlined, size: 20, color: MindPalette.mint),
        const SizedBox(width: 8),
        const Expanded(child: Text('Prospective outcome evidence',
          style: TextStyle(color: MindPalette.text,
            fontSize: 14, fontWeight: FontWeight.w800))),
        IconButton(
          tooltip: 'Refresh prospective outcomes',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh, color: MindPalette.muted, size: 20)),
      ]),
      const SizedBox(height: 4),
      const Text('Precommitted signals · later market bars · immutable research ledger',
        style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 10),
      FutureBuilder<MindProspectiveOutcomesStatus>(
        future: _request,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Row(children: [
              SizedBox(width: 16, height: 16,
                child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 8),
              Expanded(child: Text('Reading reconciled research…',
                style: TextStyle(color: MindPalette.muted, fontSize: 11))),
            ]);
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Text('Outcome evidence unavailable. '
              'No trading performance or execution is verified.',
              key: ValueKey('minttick-outcomes-error'),
              style: TextStyle(color: MindPalette.yellow, fontSize: 11));
          }
          return _body(snapshot.data!);
        },
      ),
    ]),
  );

  Widget _body(MindProspectiveOutcomesStatus report) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(children: [
        Expanded(child: Text(
          switch (report.status) {
            'UNREGISTERED' => 'Prospective cohort not registered',
            'UNAVAILABLE' => 'Outcome ledger unavailable',
            'AWAITING_CANDIDATES' => 'No precommitted research candidates',
            'AWAITING_OUTCOMES' => 'Awaiting future market observations',
            'PARTIAL_RESEARCH_OUTCOMES' => 'Outcome reconciliation incomplete',
            _ => 'Research outcomes recorded',
          },
          key: const ValueKey('minttick-outcomes-status'),
          style: const TextStyle(color: MindPalette.text,
            fontSize: 12, fontWeight: FontWeight.w700))),
        const Text('ABSTAIN', style: TextStyle(
          color: MindPalette.yellow, fontSize: 11,
          fontWeight: FontWeight.w800)),
      ]),
      const SizedBox(height: 8),
      Text('${report.reconciledOutcomes} / '
           '${report.precommittedCandidates} research candidates reconciled',
        key: const ValueKey('minttick-outcomes-count'),
        style: const TextStyle(
          color: MindPalette.text, fontSize: 12, fontWeight: FontWeight.w600)),
      const SizedBox(height: 4),
      Text('${report.positiveNetObservations} positive after illustrative '
           'costs; not a win-rate, portfolio return or performance claim',
        style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 8),
      const Text('Estimated costs: 0.10% fees + 0.05% slippage on each side. '
        'Not a full Indian broker/tax/charge calculation.',
        key: ValueKey('minttick-outcomes-cost-caveat'),
        style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 7),
      const Text('Provider provenance is not independently attested. '
        'No account risk approval, PAPER or LIVE orders.',
        key: ValueKey('minttick-outcomes-no-execution'),
        style: TextStyle(color: MindPalette.yellow,
          fontSize: 11, fontWeight: FontWeight.w700)),
      const SizedBox(height: 7),
      Text('Evidence generated: ${report.generatedAt.toIso8601String()}',
        style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
    ],
  );
}
