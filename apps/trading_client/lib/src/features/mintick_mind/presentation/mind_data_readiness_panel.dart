import 'package:flutter/material.dart';

import '../data/mind_progress_repository.dart';
import 'mind_widgets.dart';

/// A required data family is NOT a verified dataset. The optional capability
/// id only links a broad server progress item; it never proves instrument,
/// timeframe, expiry or provider coverage.
class _DataFamily {
  const _DataFamily(this.id, this.label, this.group, {this.capabilityId});
  final String id;
  final String label;
  final String group;
  final String? capabilityId;
}

/// Mirrors the 24 data-source family identifiers in the checked-in
/// mintick-mind-golden-source-requirements.v1.json contract.
/// A new source must be explicitly mapped; never guess readiness by name.
const _families = <_DataFamily>[
  _DataFamily('security_identity', 'Security identity & listings', 'MARKET'),
  _DataFamily('market_calendar', 'Market calendar', 'MARKET'),
  _DataFamily('daily_bars', 'Historical daily candles', 'MARKET',
      capabilityId: 'data.historical-candles'),
  _DataFamily('intraday_bars', 'Historical intraday candles', 'MARKET',
      capabilityId: 'data.historical-candles'),
  _DataFamily('live_quotes', 'Live quotes & freshness', 'MARKET',
      capabilityId: 'data.fresh-ticks'),
  _DataFamily('index_breadth_vix', 'Indices, breadth & India VIX', 'MARKET'),
  _DataFamily('market_depth_delivery', 'Order-book & delivery statistics',
      'DERIVATIVES', capabilityId: 'data.order-book'),
  _DataFamily('corporate_actions', 'Corporate actions & adjustments', 'MARKET'),
  _DataFamily('fundamental_filings', 'Financial statements & filings',
      'INTELLIGENCE', capabilityId: 'data.fundamentals'),
  _DataFamily('ownership_governance', 'Ownership & governance', 'INTELLIGENCE'),
  _DataFamily('news_events', 'News, events & sentiment', 'INTELLIGENCE'),
  _DataFamily('flows_fii_dii', 'FII / DII flows', 'INTELLIGENCE'),
  _DataFamily('macro_fx_rates', 'Macro, rates & global markets', 'INTELLIGENCE'),
  _DataFamily('derivatives_chain', 'F&O contracts, OI, IV & Greeks',
      'DERIVATIVES', capabilityId: 'data.fno'),
  _DataFamily('derivatives_reference_margin', 'F&O reference & margins',
      'DERIVATIVES'),
  _DataFamily('costs_liquidity', 'Costs, spread & slippage', 'DERIVATIVES'),
  _DataFamily('model_features', 'Point-in-time feature store', 'RESEARCH'),
  _DataFamily('model_registry_receipts', 'Models & inference receipts', 'RESEARCH'),
  _DataFamily('backtest_outcomes', 'Backtest outcomes & holdouts', 'RESEARCH'),
  _DataFamily('trading_room_strategy', 'Trading room strategy settings', 'EXECUTION'),
  _DataFamily('portfolio_ledger', 'Portfolio, fills & reconciliation', 'EXECUTION'),
  _DataFamily('risk_execution_controls', 'Risk & execution controls', 'EXECUTION'),
  _DataFamily('account_preferences', 'Account preferences', 'EXECUTION'),
  _DataFamily('operations_health', 'Feed, worker & source health', 'EXECUTION'),
];

/// A read-only, authenticated drilldown embedded in the existing Mind workspace.
/// Displays only actual progress registry values. This is not an order screen.
class MindDataReadinessPanel extends StatefulWidget {
  const MindDataReadinessPanel({required this.repository, super.key});
  final MindProgressRepository repository;

  @override
  State<MindDataReadinessPanel> createState() => _MindDataReadinessPanelState();
}

class _MindDataReadinessPanelState extends State<MindDataReadinessPanel> {
  late Future<MindProgressSnapshot> _pending;
  String _filter = 'All';
  String _query = '';

  @override
  void initState() {
    super.initState();
    _pending = widget.repository.load();
  }

  @override
  void didUpdateWidget(covariant MindDataReadinessPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) {
      _pending = widget.repository.load();
    }
  }

  void _refresh() => setState(() => _pending = widget.repository.load());

  String _state(MindProgressItem? item) {
    if (item == null) return 'NOT VERIFIED';
    return switch (item.status) {
      MindProgressStatus.runtimeVerified => 'RUNTIME EVENT',
      MindProgressStatus.dataBlocked => 'DATA BLOCKED',
      MindProgressStatus.rightsBlocked => 'RIGHTS BLOCKED',
      MindProgressStatus.tested => 'CODE TESTED',
      MindProgressStatus.codeComplete => 'CODE COMPLETE',
      MindProgressStatus.deployed => 'DEPLOYED',
      MindProgressStatus.failed => 'FAILED',
      MindProgressStatus.implementing => 'IMPLEMENTING',
      MindProgressStatus.mock => 'MOCK',
      MindProgressStatus.notStarted => 'NOT STARTED',
    };
  }

  Color _stateColor(MindProgressItem? item) {
    if (item == null) return MindPalette.muted;
    if (item.status.isBlocked || item.status == MindProgressStatus.failed) {
      return MindPalette.red;
    }
    if (item.status == MindProgressStatus.runtimeVerified) return MindPalette.mint;
    if (item.status == MindProgressStatus.tested ||
        item.status == MindProgressStatus.deployed) {
      return MindPalette.blue;
    }
    return MindPalette.yellow;
  }

  bool _show(_DataFamily family, MindProgressItem? source) {
    if (_query.isNotEmpty &&
        !(family.label.toLowerCase().contains(_query) ||
          family.id.toLowerCase().contains(_query) ||
          (source?.blockerCode ?? '').toLowerCase().contains(_query))) {
      return false;
    }
    return switch (_filter) {
      'Blocked' => source?.status.isBlocked == true ||
          source?.status == MindProgressStatus.failed,
      'No evidence' => source == null,
      'Verified event' => source?.status == MindProgressStatus.runtimeVerified,
      _ => true,
    };
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<MindProgressSnapshot>(
    future: _pending,
    builder: (context, snapshot) {
      final loading = snapshot.connectionState != ConnectionState.done;
      final data = loading || snapshot.hasError ? null : snapshot.data;
      final registry = {
        if (data != null)
          for (final item in data.items.where((e) => e.kind == 'DATA'))
            item.id: item,
      };
      final filtered = _families.where((family) =>
          _show(family, registry[family.capabilityId])).toList();

      return Column(
        key: const ValueKey('mind-data-readiness-console'),
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          MindPanel(
            padding: const EdgeInsets.all(12),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Icon(Icons.dns_outlined, color: MindPalette.mint, size: 19),
                const SizedBox(width: 8),
                const Expanded(child: Text('Source readiness',
                  style: TextStyle(color: MindPalette.text, fontWeight: FontWeight.w800,
                      fontSize: 14))),
                IconButton(
                  key: const ValueKey('mind-data-readiness-refresh'),
                  tooltip: 'Refresh source evidence',
                  onPressed: _refresh,
                  icon: const Icon(Icons.refresh, size: 20,
                    color: MindPalette.muted),
                ),
              ]),
              const SizedBox(height: 5),
              const Text(
                'DATA evidence, not strategy profitability or permission to trade.',
                style: TextStyle(color: MindPalette.muted, fontSize: 11),
              ),
              const SizedBox(height: 12),
              Wrap(spacing: 8, runSpacing: 8, children: [
                _metric('Required families', _families.length.toString()),
                _metric('Registry controls',
                    data == null ? 'Not verified' :
                    registry.length.toString()),
                _metric('Runtime events',
                    data == null ? 'Not verified' :
                    registry.values.where((item) =>
                        item.status == MindProgressStatus.runtimeVerified)
                        .length.toString()),
              ]),
              const SizedBox(height: 10),
              Text(
                loading
                  ? 'Loading authenticated registry evidence…'
                  : data == null
                    ? 'Registry unavailable. Required families are shown without verified status.'
                    : 'Manifest ${data.manifestVersion} · backend ${data.revisionKnown ? data.backendRevision : 'unknown'} · observed ${data.generatedAt.toUtc().toIso8601String()}',
                style: TextStyle(color: data == null && !loading
                    ? MindPalette.red : MindPalette.muted, fontSize: 10.5),
              ),
              if (!loading && data == null)
                Align(alignment: Alignment.centerLeft,
                  child: TextButton(onPressed: _refresh,
                      child: const Text('Retry registry'))),
            ]),
          ),
          const SizedBox(height: 10),
          const Text(
            'An evaluator may still be DATA_BLOCKED even if its code is tested. '
            'Family-level completeness needs separate per-provider, instrument, '
            'timeframe and expiry receipts; no percentage is inferred here.',
            style: TextStyle(color: MindPalette.muted, fontSize: 11),
          ),
          const SizedBox(height: 10),
          TextField(
            key: const ValueKey('mind-data-readiness-search'),
            onChanged: (value) => setState(() =>
                _query = value.trim().toLowerCase()),
            style: const TextStyle(color: MindPalette.text, fontSize: 12),
            decoration: InputDecoration(
              isDense: true,
              hintText: 'Search data family or blocker',
              hintStyle: const TextStyle(color: MindPalette.muted, fontSize: 11),
              prefixIcon: const Icon(Icons.search, color: MindPalette.muted, size: 17),
              filled: true, fillColor: MindPalette.card,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(11),
                borderSide: const BorderSide(color: MindPalette.border),
              ),
            ),
          ),
          const SizedBox(height: 8),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(children: [
              for (final filter in const [
                'All', 'Blocked', 'No evidence', 'Verified event'
              ])
                Padding(
                  padding: const EdgeInsets.only(right: 6),
                  child: ChoiceChip(
                    key: ValueKey('mind-data-filter-$filter'),
                    label: Text(filter),
                    selected: _filter == filter,
                    onSelected: (_) => setState(() => _filter = filter),
                    visualDensity: VisualDensity.compact,
                    backgroundColor: MindPalette.card,
                    selectedColor: MindPalette.panel,
                    side: const BorderSide(color: MindPalette.border),
                    labelStyle: TextStyle(fontSize: 10.5,
                      color: _filter == filter ? MindPalette.mint : MindPalette.text),
                  ),
                ),
            ]),
          ),
          const SizedBox(height: 9),
          for (final group in const [
            'MARKET', 'DERIVATIVES', 'INTELLIGENCE', 'RESEARCH', 'EXECUTION'
          ])
            if (filtered.any((family) => family.group == group)) ...[
              Padding(
                padding: const EdgeInsets.fromLTRB(3, 9, 3, 6),
                child: Text(group,
                  style: const TextStyle(color: MindPalette.muted,
                    fontSize: 10, letterSpacing: 1.1, fontWeight: FontWeight.w800)),
              ),
              for (final family in filtered.where((e) => e.group == group))
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: _familyTile(family, registry[family.capabilityId]),
                ),
            ],
          if (filtered.isEmpty)
            const MindPanel(child: Text('No data families match this filter.',
              style: TextStyle(color: MindPalette.muted, fontSize: 12))),
          const SizedBox(height: 12),
          MindPanel(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Canonical feed and data contracts',
                style: TextStyle(color: MindPalette.text, fontSize: 13,
                    fontWeight: FontWeight.w800)),
              const SizedBox(height: 5),
              Text("Shared source registry: ${_state(registry['data.canonical-contract'])}",
                style: TextStyle(
                  color: _stateColor(registry['data.canonical-contract']),
                  fontSize: 11)),
              const SizedBox(height: 5),
              const Text('This console reads existing server progress evidence. '
                'The 24-family requirement list is a contract, not 24 live data '
                'feeds. Dataset-level receipt/coverage API is still needed.',
                style: TextStyle(color: MindPalette.muted, fontSize: 11)),
            ],
          )),
        ],
      );
    },
  );

  Widget _metric(String label, String value) => MindPanel(
    padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
    child: SizedBox(width: 93, child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: MindPalette.text, fontSize: 17,
            fontWeight: FontWeight.w800)),
        const SizedBox(height: 3),
        Text(label, style: const TextStyle(
          color: MindPalette.muted, fontSize: 10)),
      ],
    )),
  );

  Widget _familyTile(_DataFamily family, MindProgressItem? item) {
    final state = _state(item);
    final color = _stateColor(item);
    return MindPanel(
      key: ValueKey('mind-data-family-${family.id}'),
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
      onTap: () => _showDetail(family, item),
      child: Row(children: [
        Icon(item == null ? Icons.help_outline :
          item.status.isBlocked ? Icons.error_outline :
          item.status == MindProgressStatus.runtimeVerified
              ? Icons.check_circle_outline : Icons.info_outline,
          size: 17, color: color),
        const SizedBox(width: 10),
        Expanded(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(family.label, maxLines: 2, overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: MindPalette.text,
                fontWeight: FontWeight.w700, fontSize: 12)),
            const SizedBox(height: 3),
            Text(item == null ? 'No mapped server capability' :
              (item.blockerCode ?? item.id),
              maxLines: 1, overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
          ],
        )),
        const SizedBox(width: 7),
        Flexible(child: Text(state, maxLines: 2, textAlign: TextAlign.end,
          style: TextStyle(color: color, fontSize: 9.5,
            fontWeight: FontWeight.w800))),
        const Icon(Icons.chevron_right, size: 16, color: MindPalette.muted),
      ]),
    );
  }

  void _showDetail(_DataFamily family, MindProgressItem? item) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      backgroundColor: MindPalette.panel,
      builder: (context) => SafeArea(child: FractionallySizedBox(
        heightFactor: .70,
        child: ListView(padding: const EdgeInsets.all(17), children: [
          Text(family.label, style: const TextStyle(color: MindPalette.text,
            fontWeight: FontWeight.w800, fontSize: 18)),
          const SizedBox(height: 6),
          Text(family.id, style: const TextStyle(color: MindPalette.muted,
            fontSize: 11)),
          const SizedBox(height: 12),
          MindTag(_state(item), color: _stateColor(item)),
          const SizedBox(height: 12),
          const Text('Dataset readiness is NOT established solely by '
            'a registry capability state. Verify actual persisted receipts, '
            'date coverage, provider rights, lineage and freshness.',
            style: TextStyle(color: MindPalette.muted, fontSize: 12)),
          const SizedBox(height: 13),
          if (item == null)
            const Text('No source capability maps directly to this family. '
              'Its production evidence remains unknown.',
              style: TextStyle(color: MindPalette.text, fontSize: 12))
          else ...[
            _detailLine('Server capability', item.id),
            _detailLine('Declared status', item.declaredStatus.label),
            _detailLine('Blocker', item.blockerCode ?? 'Not reported'),
            _detailLine('Dataset version',
                item.runtime.datasetVersion ?? 'Not reported'),
            _detailLine('Runtime evidence',
                item.runtime.evidenceRef ?? 'Not reported'),
            _detailLine('Last verified',
                item.runtime.lastVerifiedAt?.toUtc().toIso8601String() ??
                    'Not reported'),
            if (item.missing.isNotEmpty) ...[
              const SizedBox(height: 10),
              const Text('Known missing evidence',
                style: TextStyle(color: MindPalette.text,
                    fontWeight: FontWeight.w700, fontSize: 12)),
              const SizedBox(height: 5),
              for (final missing in item.missing)
                Padding(padding: const EdgeInsets.only(bottom: 5),
                  child: Text('• $missing',
                    style: const TextStyle(color: MindPalette.muted,
                      fontSize: 11))),
            ],
          ],
        ]),
      )),
    );
  }

  Widget _detailLine(String label, String value) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(color: MindPalette.muted,
          fontSize: 10)),
        const SizedBox(height: 2),
        SelectableText(value, style: const TextStyle(
          color: MindPalette.text, fontSize: 12)),
      ]),
  );
}
