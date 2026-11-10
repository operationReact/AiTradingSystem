import 'package:flutter/material.dart';

import '../data/mind_prospective_evidence_completeness_repository.dart';
import 'mind_widgets.dart';

/// Read-only quality gate. "Complete" means internally counted, never a
/// validated strategy, real P&L, paper-trade approval or live execution.
class MindProspectiveEvidenceCompletenessPanel extends StatefulWidget {
  const MindProspectiveEvidenceCompletenessPanel({
    required this.repository,
    super.key,
  });

  final MindProspectiveEvidenceCompletenessRepository repository;

  @override
  State<MindProspectiveEvidenceCompletenessPanel> createState() =>
      _MindProspectiveEvidenceCompletenessPanelState();
}

class _MindProspectiveEvidenceCompletenessPanelState
    extends State<MindProspectiveEvidenceCompletenessPanel> {
  late Future<MindProspectiveEvidenceCompleteness> _request;

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  void didUpdateWidget(
      covariant MindProspectiveEvidenceCompletenessPanel old) {
    super.didUpdateWidget(old);
    if (!identical(old.repository, widget.repository)) {
      _request = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _request = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('minttick-research-completeness-panel'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.fact_check_outlined, color: MindPalette.mint, size: 20),
        const SizedBox(width: 8),
        const Expanded(child: Text('Prospective research integrity',
          style: TextStyle(color: MindPalette.text,
            fontSize: 14, fontWeight: FontWeight.w800))),
        IconButton(
          tooltip: 'Refresh research integrity',
          icon: const Icon(Icons.refresh, color: MindPalette.muted, size: 20),
          onPressed: _refresh),
      ]),
      const SizedBox(height: 4),
      const Text('20-stock NSE cohort · 10 future sessions · on-time evidence',
        style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 10),
      FutureBuilder<MindProspectiveEvidenceCompleteness>(
        future: _request,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Row(children: [
              SizedBox(width: 16, height: 16,
                child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 9),
              Expanded(child: Text('Checking persisted research records…',
                style: TextStyle(color: MindPalette.muted, fontSize: 11))),
            ]);
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Text('Research integrity unavailable. '
              'No source completeness or trading outcomes can be verified.',
              key: ValueKey('minttick-research-integrity-error'),
              style: TextStyle(color: MindPalette.yellow, fontSize: 11));
          }
          return _report(snapshot.data!);
        },
      ),
    ]),
  );

  Widget _report(MindProspectiveEvidenceCompleteness data) {
    final label = switch (data.status) {
      'UNAVAILABLE' => 'Source records unavailable',
      'OBSERVATION_SESSIONS_IN_PROGRESS' => 'Observation window in progress',
      'SOURCE_COVERAGE_INCOMPLETE' => 'Market source coverage incomplete',
      'SIGNAL_RECEIPT_WINDOWS_INCOMPLETE' => 'Missing research observation receipts',
      'NO_PROSPECTIVE_CANDIDATES' => 'No eligible strategy candidates recorded',
      'OUTCOME_RECONCILIATION_INCOMPLETE' => 'Candidate outcomes awaiting reconciliation',
      _ => 'Internal research accounting complete — not validated',
    };
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text(label,
          key: const ValueKey('minttick-research-integrity-state'),
          style: const TextStyle(color: MindPalette.text,
            fontWeight: FontWeight.w700, fontSize: 12))),
        const SizedBox(width: 8),
        const Text('ABSTAIN',
          style: TextStyle(color: MindPalette.yellow,
            fontSize: 11, fontWeight: FontWeight.w800)),
      ]),
      const SizedBox(height: 9),
      _line('Sessions observed', '${data.completedSessions} / 10'),
      _line('Stocks with source coverage',
        '${data.membersWithSufficientSourceCoverage} / 20'),
      if (data.expectedSignalWindowsAtMaturity != null) ...[
        _line('Eligible scanner receipts',
          '${data.recordedSignalWindows} / '
          '${data.expectedSignalWindowsAtMaturity}',
          key: const ValueKey('minttick-research-window-count')),
        _line('Missing research windows',
          '${data.missingSignalWindows}',
          key: const ValueKey('minttick-research-missing-windows')),
      ] else
        const Text('Final receipt coverage can only be checked after '
          'all 10 sessions close.',
          key: ValueKey('minttick-research-pending-sessions'),
          style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 6),
      _line('Outcomes recorded',
        '${data.reconciledOutcomes} / ${data.precommittedCandidates}'),
      _line('Outcomes pending', '${data.pendingOutcomes}'),
      const SizedBox(height: 9),
      const Text('Missing NO_SIGNAL records can bias results. Even a complete '
        'internal ledger is not independently exchange-attested or evidence '
        'of a profitable strategy.',
        key: ValueKey('minttick-research-integrity-caveat'),
        style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 7),
      const Text('No account-risk authorization, PAPER or LIVE orders.',
        key: ValueKey('minttick-research-integrity-no-orders'),
        style: TextStyle(color: MindPalette.yellow,
          fontSize: 11, fontWeight: FontWeight.w700)),
      const SizedBox(height: 7),
      Text('Recorded: ${data.generatedAt.toIso8601String()}',
        style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
    ]);
  }

  Widget _line(String label, String value, {Key? key}) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(children: [
      Expanded(child: Text(label,
        style: const TextStyle(color: MindPalette.muted, fontSize: 11))),
      const SizedBox(width: 8),
      Text(value, key: key,
        style: const TextStyle(color: MindPalette.text,
          fontSize: 11, fontWeight: FontWeight.w700)),
    ]),
  );
}
