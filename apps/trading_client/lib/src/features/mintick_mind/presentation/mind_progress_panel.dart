import 'package:flutter/material.dart';

import '../data/mind_progress_repository.dart';
import 'mind_widgets.dart';

/// Compact status pill. Only RUNTIME_VERIFIED is ever green.
class MindStatusPill extends StatelessWidget {
  const MindStatusPill(this.status, {super.key});
  final MindProgressStatus status;

  static Color colorFor(MindProgressStatus status) => switch (status) {
        MindProgressStatus.runtimeVerified => MindPalette.green,
        MindProgressStatus.failed => MindPalette.red,
        MindProgressStatus.dataBlocked || MindProgressStatus.rightsBlocked => MindPalette.red,
        MindProgressStatus.tested || MindProgressStatus.deployed => MindPalette.blue,
        MindProgressStatus.implementing || MindProgressStatus.codeComplete => MindPalette.yellow,
        MindProgressStatus.mock => MindPalette.yellow,
        MindProgressStatus.notStarted => MindPalette.muted,
      };

  @override
  Widget build(BuildContext context) {
    final color = colorFor(status);
    return Semantics(
      label: 'Status ${status.label}',
      child: Container(
        key: ValueKey('mind-status-${status.code}'),
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
        decoration: BoxDecoration(
          color: color.withValues(alpha: .10),
          border: Border.all(color: color.withValues(alpha: .5)),
          borderRadius: BorderRadius.circular(100),
        ),
        child: Text(status.label,
            style: TextStyle(color: color, fontSize: 9.5, fontWeight: FontWeight.w800, letterSpacing: .3)),
      ),
    );
  }
}

String _ratio(MindProgressSummary? s) => s == null ? 'Not verified' : '${s.done}/${s.denominator}';

String _ago(DateTime at, DateTime now) {
  final d = now.toUtc().difference(at.toUtc());
  if (d.inMinutes < 1) return 'just now';
  if (d.inHours < 1) return '${d.inMinutes}m ago';
  if (d.inDays < 1) return '${d.inHours}h ago';
  return '${d.inDays}d ago';
}

String _rev(String revision) => revision.length > 9 ? revision.substring(0, 9) : revision;

/// One compact progress summary for Mind Overview (issue #998). Real server
/// values only; while loading or on error it shows "Not verified", never 0.
class MindProgressStrip extends StatefulWidget {
  const MindProgressStrip({required this.repository, this.onOpen, super.key});
  final MindProgressRepository repository;
  final VoidCallback? onOpen;

  @override
  State<MindProgressStrip> createState() => _MindProgressStripState();
}

class _MindProgressStripState extends State<MindProgressStrip> {
  late Future<MindProgressSnapshot> _pending = widget.repository.load();

  @override
  void didUpdateWidget(covariant MindProgressStrip oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) _pending = widget.repository.load();
  }

  @override
  Widget build(BuildContext context) => MindPanel(
        key: const ValueKey('mind-progress-strip'),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        onTap: widget.onOpen,
        child: FutureBuilder<MindProgressSnapshot>(
          future: _pending,
          builder: (context, snap) {
            final data = snap.data;
            final failed = snap.connectionState == ConnectionState.done && (snap.hasError || data == null);
            String cell(String kind) => data == null ? 'Not verified' : _ratio(data.summary(kind));
            final cells = <(String, String)>[
              ('Screens', cell('SCREEN')),
              ('Strategies', cell('STRATEGY')),
              ('Models', cell('MODEL')),
              ('PAPER', data == null ? 'Not verified' : data.paperStage.status.label),
            ];
            return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Icon(Icons.fact_check_outlined, size: 16, color: MindPalette.mint),
                const SizedBox(width: 6),
                const Text('Progress', style: TextStyle(color: MindPalette.text, fontSize: 13, fontWeight: FontWeight.w800)),
                const SizedBox(width: 6),
                const Expanded(child: Text('verified = runtime-proven', maxLines: 1,
                    overflow: TextOverflow.ellipsis, style: TextStyle(color: MindPalette.muted, fontSize: 10))),
                if (snap.connectionState != ConnectionState.done)
                  const SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 1.6))
                else if (widget.onOpen != null)
                  const Icon(Icons.chevron_right, size: 17, color: MindPalette.muted),
              ]),
              const SizedBox(height: 7),
              Wrap(spacing: 14, runSpacing: 4, children: [
                for (final (label, value) in cells)
                  Text.rich(TextSpan(children: [
                    TextSpan(text: '$label ', style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
                    TextSpan(text: value, style: const TextStyle(color: MindPalette.text, fontSize: 11, fontWeight: FontWeight.w800)),
                  ]), key: ValueKey('mind-progress-$label')),
              ]),
              const SizedBox(height: 5),
              Text(
                failed
                    ? 'Progress service unavailable · nothing is shown as verified'
                    : data == null
                        ? 'Loading server evidence…'
                        : 'PAPER stage: ${data.paperStage.name} · backend ${data.revisionKnown ? _rev(data.backendRevision) : 'revision unknown'} · updated ${_ago(data.generatedAt, DateTime.now())}',
                maxLines: 2, overflow: TextOverflow.ellipsis,
                style: TextStyle(color: failed ? MindPalette.red : MindPalette.muted, fontSize: 10),
              ),
            ]);
          },
        ),
      );
}

/// Drill-down tracker rendered inside the existing `coverage` route.
class MindProgressCoverageView extends StatefulWidget {
  const MindProgressCoverageView({required this.repository, this.onOpenRoute, super.key});
  final MindProgressRepository repository;
  /// Opens a Mind route for SCREEN items (`screen.<route>`).
  final ValueChanged<String>? onOpenRoute;

  @override
  State<MindProgressCoverageView> createState() => _MindProgressCoverageViewState();
}

class _MindProgressCoverageViewState extends State<MindProgressCoverageView> {
  static const _sections = <(String, String)>[
    ('SCREEN', 'UI coverage'),
    ('STRATEGY', 'Strategy coverage'),
    ('MODEL', 'Model readiness'),
    ('ENGINE', 'Engine pipeline'),
    ('DATA', 'Data dependencies'),
    ('PAPER', 'PAPER readiness'),
    ('LIVE', 'LIVE (gated)'),
  ];
  late Future<MindProgressSnapshot> _pending = widget.repository.load();
  String _section = 'SCREEN';
  String _query = '';
  MindProgressStatus? _status;
  int _limit = 60;

  void _reload() => setState(() => _pending = widget.repository.load());

  @override
  Widget build(BuildContext context) => FutureBuilder<MindProgressSnapshot>(
        future: _pending,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const MindPanel(child: Row(children: [
              SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
              SizedBox(width: 9),
              Expanded(child: Text('Loading audited progress…', maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: MindPalette.muted, fontSize: 12))),
            ]));
          }
          final data = snap.data;
          if (snap.hasError || data == null) {
            return MindPanel(
              key: const ValueKey('mind-progress-error'),
              child: Row(children: [
                const Icon(Icons.error_outline, color: MindPalette.red, size: 18),
                const SizedBox(width: 8),
                const Expanded(child: Text('Progress not verified: the server registry is unavailable or failed validation.',
                    style: TextStyle(color: MindPalette.text, fontSize: 12))),
                TextButton(onPressed: _reload, child: const Text('Retry')),
              ]),
            );
          }
          return _content(data);
        },
      );

  Widget _content(MindProgressSnapshot data) {
    final items = data.items
        .where((i) => i.kind == _section && (_status == null || i.status == _status) && i.matches(_query))
        .toList();
    final groups = <String, List<MindProgressItem>>{};
    for (final item in items.take(_limit)) {
      groups.putIfAbsent(item.group.isEmpty ? item.kind : item.group, () => []).add(item);
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      MindPanel(
        key: const ValueKey('mind-progress-summary'),
        highlight: true,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Wrap(spacing: 16, runSpacing: 8, children: [
            for (final (kind, label) in _sections)
              if (data.summary(kind) != null)
                _summaryCell(label, data.summary(kind)!, selected: kind == _section,
                    onTap: () => setState(() { _section = kind; _status = null; _limit = 60; })),
          ]),
          const SizedBox(height: 10),
          Text('PAPER stage: ${data.paperStage.name} (${data.paperStage.status.label}) · '
              'manifest ${data.manifestVersion} · audited ${data.auditedAt} · '
              'backend ${data.revisionKnown ? _rev(data.backendRevision) : 'revision unknown — nothing can be verified'} · '
              'updated ${_ago(data.generatedAt, DateTime.now())}',
              style: const TextStyle(color: MindPalette.muted, fontSize: 10.5)),
        ]),
      ),
      const SizedBox(height: 10),
      TextField(
        key: const ValueKey('mind-progress-search'),
        onChanged: (v) => setState(() { _query = v.trim(); _limit = 60; }),
        style: const TextStyle(color: MindPalette.text, fontSize: 13),
        decoration: InputDecoration(
          isDense: true,
          prefixIcon: const Icon(Icons.search, size: 18, color: MindPalette.muted),
          hintText: 'Search route, family, model, blocker, owner…',
          hintStyle: const TextStyle(color: MindPalette.muted, fontSize: 12),
          filled: true, fillColor: MindPalette.card,
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(12),
              borderSide: const BorderSide(color: MindPalette.border)),
        ),
      ),
      const SizedBox(height: 8),
      SizedBox(height: 36, child: ListView(scrollDirection: Axis.horizontal, children: [
        _statusChip(null, 'All'),
        for (final status in MindProgressStatus.values)
          if (data.items.any((i) => i.kind == _section && i.status == status)) _statusChip(status, status.label),
      ])),
      const SizedBox(height: 8),
      if (items.isEmpty)
        const MindPanel(child: Text('No items match this filter.', style: TextStyle(color: MindPalette.muted, fontSize: 12))),
      for (final entry in groups.entries) ...[
        Padding(padding: const EdgeInsets.fromLTRB(2, 8, 2, 5),
          child: Text('${entry.key} · ${entry.value.where((i) => i.done).length}/${data.items.where((i) => i.kind == _section && (i.group.isEmpty ? i.kind : i.group) == entry.key).length} verified',
              style: const TextStyle(color: MindPalette.muted, fontSize: 10.5, letterSpacing: .6, fontWeight: FontWeight.w700))),
        for (final item in entry.value)
          Padding(padding: const EdgeInsets.only(bottom: 6), child: _row(item)),
      ],
      if (items.length > _limit)
        TextButton(onPressed: () => setState(() => _limit += 60),
            child: Text('Show more (${items.length - _limit} remaining)')),
    ]);
  }

  Widget _summaryCell(String label, MindProgressSummary s, {required bool selected, required VoidCallback onTap}) =>
      InkWell(
        key: ValueKey('mind-progress-section-${s.kind}'),
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(padding: const EdgeInsets.all(3), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: TextStyle(color: selected ? MindPalette.mint : MindPalette.muted, fontSize: 10.5,
              fontWeight: selected ? FontWeight.w800 : FontWeight.w600)),
          Text('${s.done}/${s.denominator}', style: const TextStyle(color: MindPalette.text, fontSize: 17, fontWeight: FontWeight.w800)),
          Text('${s.tested} tested · ${s.blocked} blocked · ${s.mock} mock',
              style: const TextStyle(color: MindPalette.muted, fontSize: 9.5)),
        ])),
      );

  Widget _statusChip(MindProgressStatus? status, String label) => Padding(
        padding: const EdgeInsets.only(right: 6),
        child: ChoiceChip(
          label: Text(label),
          selected: _status == status,
          onSelected: (_) => setState(() => _status = status),
          backgroundColor: MindPalette.card,
          selectedColor: MindPalette.panel,
          side: const BorderSide(color: MindPalette.border),
          labelStyle: TextStyle(fontSize: 10.5, color: _status == status ? MindPalette.mint : MindPalette.text),
        ),
      );

  Widget _row(MindProgressItem item) => MindPanel(
        key: ValueKey('mind-progress-item-${item.id}'),
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
        onTap: () => _openDetail(item),
        child: Row(children: [
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(item.name, maxLines: 1, overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: MindPalette.text, fontSize: 12.5, fontWeight: FontWeight.w700)),
            const SizedBox(height: 2),
            Text(
              [item.id, if (item.implementationCode != null) item.implementationCode!,
                if (item.blockerCode != null) item.blockerCode!, item.owner].join(' · '),
              maxLines: 1, overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
          ])),
          const SizedBox(width: 8),
          MindStatusPill(item.status),
        ]),
      );

  Future<void> _openDetail(MindProgressItem item) => showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        showDragHandle: true,
        backgroundColor: MindPalette.panel,
        builder: (sheetContext) => SafeArea(child: FractionallySizedBox(
          heightFactor: .86,
          child: _MindProgressDetailSheet(
            item: item,
            repository: widget.repository,
            onOpenRoute: item.kind == 'SCREEN' && widget.onOpenRoute != null
                ? () {
                    Navigator.pop(sheetContext);
                    widget.onOpenRoute!(item.id.substring('screen.'.length));
                  }
                : null,
          ),
        )),
      );
}

class _MindProgressDetailSheet extends StatefulWidget {
  const _MindProgressDetailSheet({required this.item, required this.repository, this.onOpenRoute});
  final MindProgressItem item;
  final MindProgressRepository repository;
  final VoidCallback? onOpenRoute;

  @override
  State<_MindProgressDetailSheet> createState() => _MindProgressDetailSheetState();
}

class _MindProgressDetailSheetState extends State<_MindProgressDetailSheet> {
  late Future<MindProgressDetail> _detail = widget.repository.detail(widget.item.id);
  final _note = TextEditingController();
  bool _sending = false;
  String? _message;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _annotate(String type) async {
    if (_sending) return;
    if (type == 'NOTE' && _note.text.trim().isEmpty) return;
    setState(() { _sending = true; _message = null; });
    try {
      await widget.repository.annotate(widget.item.id, type: type, text: _note.text);
      if (!mounted) return;
      setState(() {
        _sending = false;
        _note.clear();
        _message = type == 'NOTE' ? 'Note saved. Status is unchanged.' : 'Recheck requested. Status changes only with new runtime evidence.';
        _detail = widget.repository.detail(widget.item.id);
      });
    } catch (_) {
      if (!mounted) return;
      setState(() { _sending = false; _message = 'Could not save. Nothing was changed.'; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    Widget kv(String k, String? v) => v == null || v.isEmpty ? const SizedBox.shrink() : Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(width: 118, child: Text(k, style: const TextStyle(color: MindPalette.muted, fontSize: 11))),
            Expanded(child: Text(v, style: const TextStyle(color: MindPalette.text, fontSize: 11.5))),
          ]));
    Widget heading(String t) => Padding(padding: const EdgeInsets.only(top: 12, bottom: 4),
        child: Text(t, style: const TextStyle(color: MindPalette.mint, fontSize: 11, fontWeight: FontWeight.w800, letterSpacing: .6)));
    return ListView(
      key: ValueKey('mind-progress-detail-${item.id}'),
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
      children: [
        Row(children: [
          Expanded(child: Text(item.name, style: const TextStyle(color: MindPalette.text, fontSize: 18, fontWeight: FontWeight.w800))),
          MindStatusPill(item.status),
        ]),
        const SizedBox(height: 4),
        Text('${item.id} · ${item.kind} · ${item.group} · owner ${item.owner}',
            style: const TextStyle(color: MindPalette.muted, fontSize: 10.5)),
        if (item.note != null) ...[const SizedBox(height: 8),
          Text(item.note!, style: const TextStyle(color: MindPalette.text, fontSize: 12))],
        heading('STATUS'),
        kv('Current', item.status.label),
        kv('Declared by audit', item.declaredStatus.label),
        kv('Blocker', item.blockerCode),
        kv('Implementation', item.implementationCode == null ? 'None in repository'
            : '${item.implementationCode} (${item.implementationMatch ?? '-'}, ${item.implementationRuntime ?? '-'})'),
        if (item.requiredInputs.isNotEmpty) kv('Required inputs', item.requiredInputs.join(', ')),
        if (item.dependsOn.isNotEmpty) kv('Depends on', item.dependsOn.join(', ')),
        heading('RUNTIME PROOF'),
        kv('Last verified', item.runtime.lastVerifiedAt?.toUtc().toIso8601String() ?? 'Not verified'),
        kv('Verified revision', item.runtime.verifiedRevision),
        kv('Last failure', item.runtime.lastFailedAt?.toUtc().toIso8601String()),
        kv('Dataset version', item.runtime.datasetVersion),
        kv('Evidence ref', item.runtime.evidenceRef),
        kv('Runtime note', item.runtime.runtimeNote),
        if (item.missing.isNotEmpty) ...[
          heading('MISSING BEFORE DONE'),
          for (final m in item.missing)
            Padding(padding: const EdgeInsets.symmetric(vertical: 2), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Icon(Icons.check_box_outline_blank, size: 14, color: MindPalette.muted),
              const SizedBox(width: 6),
              Expanded(child: Text(m, style: const TextStyle(color: MindPalette.text, fontSize: 11.5))),
            ])),
        ],
        if (item.evidence.isNotEmpty) ...[
          heading('EVIDENCE'),
          for (final e in item.evidence)
            Padding(padding: const EdgeInsets.symmetric(vertical: 2),
              child: SelectableText('${e.kind}  ${e.display}', style: const TextStyle(color: MindPalette.muted, fontSize: 10.5))),
        ],
        heading('HISTORY'),
        FutureBuilder<MindProgressDetail>(
          future: _detail,
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) {
              return const Text('Loading history…', style: TextStyle(color: MindPalette.muted, fontSize: 11));
            }
            if (snap.hasError || snap.data == null) {
              return const Text('History unavailable.', style: TextStyle(color: MindPalette.red, fontSize: 11));
            }
            final history = snap.data!.history;
            if (history.isEmpty) {
              return const Text('No runtime evidence recorded yet.', style: TextStyle(color: MindPalette.muted, fontSize: 11));
            }
            return Column(children: [
              for (final e in history)
                kv(e.observedAt.toUtc().toIso8601String().substring(0, 16),
                    '${e.type} · ${_rev(e.backendRevision)}${e.detail == null ? '' : ' · ${e.detail}'}'),
            ]);
          },
        ),
        heading('OWNER ACTIONS'),
        const Text('Notes and recheck requests never change status. DONE requires runtime evidence.',
            style: TextStyle(color: MindPalette.muted, fontSize: 10.5)),
        const SizedBox(height: 6),
        TextField(
          key: const ValueKey('mind-progress-note'),
          controller: _note,
          maxLength: 500,
          maxLines: 2,
          style: const TextStyle(color: MindPalette.text, fontSize: 12),
          decoration: const InputDecoration(isDense: true, hintText: 'Add a note',
              hintStyle: TextStyle(color: MindPalette.muted, fontSize: 12)),
        ),
        Wrap(spacing: 8, children: [
          OutlinedButton(key: const ValueKey('mind-progress-add-note'),
              onPressed: _sending ? null : () => _annotate('NOTE'), child: const Text('Add note')),
          OutlinedButton(key: const ValueKey('mind-progress-recheck'),
              onPressed: _sending ? null : () => _annotate('RECHECK_REQUESTED'), child: const Text('Request recheck')),
          if (widget.onOpenRoute != null)
            TextButton(onPressed: widget.onOpenRoute, child: const Text('Open screen')),
        ]),
        if (_message != null)
          Text(_message!, key: const ValueKey('mind-progress-message'),
              style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
      ],
    );
  }
}
