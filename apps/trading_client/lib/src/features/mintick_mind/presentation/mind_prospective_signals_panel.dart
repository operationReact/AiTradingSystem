import 'package:flutter/material.dart';

import '../data/mind_prospective_signals_repository.dart';
import 'mind_widgets.dart';

/// Immutable prospective scanner receipts, not outcomes or trade signals.
class MindProspectiveSignalsPanel extends StatefulWidget {
  const MindProspectiveSignalsPanel({required this.repository, super.key});
  final MindProspectiveSignalsRepository repository;

  @override
  State<MindProspectiveSignalsPanel> createState() =>
      _MindProspectiveSignalsPanelState();
}

class _MindProspectiveSignalsPanelState
    extends State<MindProspectiveSignalsPanel> {
  late Future<MindProspectiveSignalsStatus> _request;
  bool _expanded = false;

  @override
  void initState() {
    super.initState();
    _request = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindProspectiveSignalsPanel old) {
    super.didUpdateWidget(old);
    if (!identical(old.repository, widget.repository)) {
      _request = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _request = widget.repository.load());

  @override
  Widget build(BuildContext context) => MindPanel(
    key: const ValueKey('minttick-prospective-receipts'),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.receipt_long_outlined, size: 20,
          color: MindPalette.mint),
        const SizedBox(width: 8),
        const Expanded(child: Text('Prospective signal receipts',
          style: TextStyle(color: MindPalette.text,
            fontSize: 14, fontWeight: FontWeight.w800))),
        IconButton(
          tooltip: 'Refresh prospective signal receipts',
          icon: const Icon(Icons.refresh, color: MindPalette.muted, size: 20),
          onPressed: _refresh),
      ]),
      const SizedBox(height: 4),
      const Text('Frozen at candle close · source-backed research only',
        style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 11),
      FutureBuilder<MindProspectiveSignalsStatus>(
        future: _request,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Row(children: [
              SizedBox(width: 16, height: 16,
                child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 8),
              Expanded(child: Text('Checking recorded research receipts…',
                style: TextStyle(color: MindPalette.muted, fontSize: 11))),
            ]);
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Text('Prospective receipt evidence unavailable. '
              'No strategy outcomes or broker orders are verified.',
              key: ValueKey('minttick-receipts-error'),
              style: TextStyle(color: MindPalette.yellow, fontSize: 11));
          }
          return _status(snapshot.data!);
        },
      ),
    ]),
  );

  Widget _status(MindProspectiveSignalsStatus result) => Column(
    crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text(
          switch (result.status) {
            'UNREGISTERED' => 'No frozen prospective cohort',
            'UNAVAILABLE' => 'Receipt source unavailable',
            'AWAITING_RECEIPTS' => 'Awaiting on-time scanner receipts',
            _ => 'Recorded prospective research',
          },
          key: const ValueKey('minttick-receipts-status'),
          style: const TextStyle(color: MindPalette.text,
            fontWeight: FontWeight.w700, fontSize: 12))),
        const Text('ABSTAIN',
          style: TextStyle(color: MindPalette.yellow,
            fontWeight: FontWeight.w800, fontSize: 11)),
      ]),
      const SizedBox(height: 7),
      Text('${result.recordedWindows} completed-window receipts · '
           '${result.researchCandidates} research candidates · '
           '${result.memberCount} cohort stocks',
          style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 8),
      const Text('Signal candidates have not been reconciled with future '
          'fills, transaction costs or realized returns.',
        key: ValueKey('minttick-receipts-no-outcomes'),
        style: TextStyle(color: MindPalette.muted, fontSize: 11)),
      const SizedBox(height: 6),
      const Text('No verified trading edge · no PAPER or LIVE orders',
        key: ValueKey('minttick-receipts-no-execution'),
        style: TextStyle(color: MindPalette.yellow,
          fontSize: 11, fontWeight: FontWeight.w700)),
      if (result.members.isNotEmpty) ...[
        const SizedBox(height: 9),
        TextButton.icon(
          key: const ValueKey('minttick-receipts-expand'),
          onPressed: () => setState(() => _expanded = !_expanded),
          icon: Icon(_expanded ? Icons.expand_less : Icons.expand_more,
            color: MindPalette.mint, size: 17),
          label: Text(_expanded ? 'Hide stock receipt counts' :
              'Review ${result.memberCount} stocks',
            style: const TextStyle(color: MindPalette.mint, fontSize: 11)),
        ),
        if (_expanded)
          for (final member in result.members)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 3),
              child: Row(children: [
                Expanded(child: Text(member.symbol,
                  style: const TextStyle(color: MindPalette.text,
                    fontSize: 11))),
                Text('${member.recordedWindows} checks · '
                     '${member.researchCandidates} candidates',
                  style: const TextStyle(color: MindPalette.muted,
                    fontSize: 10)),
              ]),
            ),
      ],
    ],
  );
}
