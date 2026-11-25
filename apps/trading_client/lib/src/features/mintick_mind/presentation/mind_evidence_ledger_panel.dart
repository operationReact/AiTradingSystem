import 'package:flutter/material.dart';
import '../data/mind_evidence_repository.dart';
import 'mind_widgets.dart';

/// Server-backed history, never a trade approval. Fetch or schema failure
/// shows an explicit unavailable state without replaying mock evidence.
class MindEvidenceLedgerPanel extends StatefulWidget {
  const MindEvidenceLedgerPanel({required this.repository, super.key});
  final MindEvidenceRepository repository;

  @override
  State<MindEvidenceLedgerPanel> createState() => _MindEvidenceLedgerPanelState();
}

class _MindEvidenceLedgerPanelState extends State<MindEvidenceLedgerPanel> {
  late Future<MindEvidenceLedger> _pending;

  @override
  void initState() {
    super.initState();
    _pending = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindEvidenceLedgerPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(widget.repository, oldWidget.repository)) {
      _pending = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _pending = widget.repository.load());

  @override
  Widget build(BuildContext context) => Column(
    key: const ValueKey('mind-server-evidence-ledger'),
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      MindPanel(child: Row(children: [
        const Icon(Icons.fact_check_outlined, color: MindPalette.mint, size: 21),
        const SizedBox(width: 8),
        const Expanded(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Persisted research evidence',
              style: TextStyle(color: MindPalette.text,
                fontSize: 14, fontWeight: FontWeight.w700)),
            SizedBox(height: 4),
            Text('Source and timestamps, not model predictions or orders',
              style: TextStyle(color: MindPalette.muted, fontSize: 11)),
          ],
        )),
        IconButton(
          tooltip: 'Refresh evidence ledger',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh, color: MindPalette.muted),
        ),
      ])),
      const SizedBox(height: 12),
      FutureBuilder<MindEvidenceLedger>(
        future: _pending,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const MindPanel(child: Row(children: [
              SizedBox(width: 17, height: 17,
                child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 10),
              Expanded(child: Text('Reading persisted evidence…',
                style: TextStyle(color: MindPalette.muted))),
            ]));
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const MindPanel(
              child: Text('Evidence unavailable. No verified decisions. '
                'Check the secure connection and retry.',
                key: ValueKey('mind-evidence-error'),
                style: TextStyle(color: MindPalette.yellow, fontSize: 12)));
          }
          return _ledger(snapshot.data!);
        },
      ),
    ],
  );

  Widget _ledger(MindEvidenceLedger ledger) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      MindPanel(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Scan status: ${ledger.sourceStatus}',
            key: const ValueKey('mind-ledger-source-status'),
            style: const TextStyle(color: MindPalette.text,
              fontWeight: FontWeight.w700)),
          const SizedBox(height: 5),
          Text('Generated: ${ledger.generatedAt.toUtc().toIso8601String()} UTC',
            style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
          if (ledger.scanCompletedAt != null)
            Text('Scan completed: ${ledger.scanCompletedAt!.toUtc().toIso8601String()} UTC',
              style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
          const SizedBox(height: 6),
          const Text('ABSTAIN · no verified signal · execution disabled',
            key: ValueKey('mind-ledger-abstain'),
            style: TextStyle(color: MindPalette.yellow,
              fontWeight: FontWeight.w700, fontSize: 11)),
        ],
      )),
      const SizedBox(height: 10),
      if (ledger.blockingReasons.isNotEmpty) MindPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Outstanding validation gates',
            style: TextStyle(color: MindPalette.text, fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          for (final reason in ledger.blockingReasons)
            Padding(padding: const EdgeInsets.only(bottom: 4),
              child: Text('• ${reason.replaceAll('_', ' ')}',
                style: const TextStyle(color: MindPalette.yellow, fontSize: 11))),
          if (ledger.rejectedCandidates > 0)
            Text('${ledger.rejectedCandidates} records withheld for incomplete provenance',
              style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
        ]),
      ),
      const SizedBox(height: 10),
      if (ledger.records.isEmpty)
        const MindPanel(child: Text(
          'No usable provenance records in the latest completed scan.',
          key: ValueKey('mind-evidence-empty'),
          style: TextStyle(color: MindPalette.muted, fontSize: 12))),
      for (final record in ledger.records)
        Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: MindPanel(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Icon(Icons.article_outlined, color: MindPalette.mint, size: 19),
                const SizedBox(width: 8),
                Expanded(child: Text(
                  record.instrument ?? record.referenceCode,
                  style: const TextStyle(color: MindPalette.text,
                    fontSize: 14, fontWeight: FontWeight.w700))),
                Text(record.kind == 'RESEARCH_DIAGNOSTIC' ? 'BLOCKED' : 'RESEARCH',
                  style: const TextStyle(color: MindPalette.yellow,
                    fontSize: 10, fontWeight: FontWeight.w700)),
              ]),
              const SizedBox(height: 6),
              Text('Source: ${record.source}',
                style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
              Text('Observed at: ${record.observedAt.toUtc().toIso8601String()} UTC',
                style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
              if (record.strategy != null)
                Text('Strategy: ${record.strategy}',
                  style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
              if (record.dataCutoffAt != null)
                Text('Data cutoff: ${record.dataCutoffAt!.toUtc().toIso8601String()} UTC',
                  style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
              if (record.fingerprint != null)
                Text('Candidate fingerprint: ${record.fingerprint}',
                  maxLines: 2, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
              const SizedBox(height: 4),
              const Text('Known-at independently verified: NO',
                style: TextStyle(color: MindPalette.yellow, fontSize: 10)),
            ],
          )),
        ),
    ],
  );
}
