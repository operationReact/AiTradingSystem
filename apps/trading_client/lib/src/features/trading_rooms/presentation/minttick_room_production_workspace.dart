
import 'package:flutter/material.dart';
import '../../../brand/minttick_brand.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_execution_repository.dart';
import '../data/customer_market_repository.dart';
import '../data/customer_paper_trading_repository.dart';
import '../data/customer_room_repository.dart';
import '../data/customer_trade_intent_repository.dart';
import '../domain/trading_room_draft.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_room_candlestick_desk.dart';
import 'minttick_room_order_sheet.dart';

/// Exact structural counterpart of MintTick_Rooms_Production_Parity_Prototype.
/// All colors resolve from MintTickBrand/MintTickPalette through buildTheme.
/// In contrast to demo HTML, every amount/trade/quote here is server-sourced.
class MintTickRoomProductionWorkspace extends StatefulWidget {
  const MintTickRoomProductionWorkspace({
    required this.room,
    required this.onBack,
    required this.onRefresh,
    required this.onFundPaper,
    required this.onSelectBroker,
    this.wallet,
    this.readiness,
    this.events = const [],
    this.market,
    this.paper,
    this.execution,
    this.intents,
    this.onBrokers,
    this.error,
    this.busy = false,
    super.key,
  });

  final TradingRoomDraft room;
  final CustomerPaperWallet? wallet;
  final CustomerExecutionReadiness? readiness;
  final List<CustomerPaperCashEvent> events;
  final HttpCustomerMarketRepository? market;
  final HttpCustomerPaperTradingRepository? paper;
  final HttpCustomerExecutionRepository? execution;
  final HttpCustomerTradeIntentRepository? intents;
  final VoidCallback onBack;
  final Future<void> Function() onRefresh;
  final Future<void> Function() onFundPaper;
  final Future<void> Function(String) onSelectBroker;
  final VoidCallback? onBrokers;
  final String? error;
  final bool busy;

  @override
  State<MintTickRoomProductionWorkspace> createState() =>
      _MintTickRoomProductionWorkspaceState();
}

class _MintTickRoomProductionWorkspaceState
    extends State<MintTickRoomProductionWorkspace> {
  String _tab = 'Trade';
  InstrumentRecord? _selected;
  List<CustomerPaperOrder> _paperOrders = const [];
  List<CustomerPaperPosition> _positions = const [];
  List<CustomerBlockedOrderIntent> _liveIntents = const [];
  bool _historyLoading = false;
  String? _historyError;

  @override
  void initState() {
    super.initState();
    _loadHistory();
  }

  Future<void> _loadHistory() async {
    if (widget.room.mode == 'PAPER' && widget.paper != null &&
        (widget.room.status == 'PAPER_FUNDED' || widget.wallet != null)) {
      setState(() { _historyLoading = true; _historyError = null; });
      try {
        final rows = await Future.wait<Object>([
          widget.paper!.orders(widget.room.id),
          widget.paper!.positions(widget.room.id),
        ]);
        if (!mounted) return;
        setState(() {
          _paperOrders = rows[0] as List<CustomerPaperOrder>;
          _positions = rows[1] as List<CustomerPaperPosition>;
        });
      } catch (_) {
        if (mounted) { setState(() {
          _paperOrders = const [];
          _positions = const [];
          _historyError = 'Room activity could not be verified.';
        }); }
      } finally {
        if (mounted) setState(() => _historyLoading = false);
      }
    } else if (widget.room.mode == 'LIVE' && widget.intents != null) {
      setState(() { _historyLoading = true; _historyError = null; });
      try {
        final intents = await widget.intents!.list(widget.room.id);
        if (mounted) { setState(() => _liveIntents = intents.where(
          (item) => item.mode == 'LIVE' && item.state == 'PRECHECK_BLOCKED')
          .toList()); }
      } catch (_) {
        if (mounted) { setState(() {
          _liveIntents = const [];
          _historyError = 'LIVE intent history could not be verified.';
        }); }
      } finally {
        if (mounted) setState(() => _historyLoading = false);
      }
    }
  }

  Future<void> _refresh() async {
    await widget.onRefresh();
    if (mounted) await _loadHistory();
  }

  Future<void> _order(String side) async {
    if (widget.market == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Market data is unavailable.')));
      return;
    }
    if (widget.room.mode == 'PAPER' && widget.wallet == null) {
      final fund = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Fund virtual Paper wallet?'),
          content: const Text(
            'This room must have a verified virtual wallet before '
            'simulated orders can be reviewed. No real funds are used.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
            FilledButton(
              key: const ValueKey('activate-paper-wallet'),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Fund virtual wallet')),
          ],
        ),
      );
      if (fund == true && mounted) await widget.onFundPaper();
      return;
    }
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHigh,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(17))),
      builder: (_) => MintTickRoomOrderSheet(
        roomId: widget.room.id,
        mode: widget.room.mode,
        side: side,
        initialInstrument: _selected,
        market: widget.market!,
        wallet: widget.wallet,
        paper: widget.paper,
        execution: widget.execution,
        intents: widget.intents,
        onChanged: _refresh,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final isPaper = widget.room.mode == 'PAPER';
    final cash = widget.wallet?.availableCashPaise;
    final pnl = widget.wallet?.realizedPnlPaise;
    final count = _positions.where((item) => item.quantity > 0).length;
    final readyNote = isPaper
      ? 'PAPER · Virtual cash · manual simulated orders only'
      : 'LIVE · Broker authorization required · order execution locked';

    return Scaffold(
      body: SafeArea(child: Center(child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Column(children: [
          SizedBox(height: 53, child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 15),
            child: Row(children: [
              const MintTickLockup(markWidth: 29, fontSize: 19),
              const Spacer(),
              Text('ROOMS', style: type.labelSmall?.copyWith(
                color: colors.onSurfaceVariant, fontSize: 10)),
              const SizedBox(width: 7),
              IconButton(
                tooltip: 'Return to Trading Rooms',
                onPressed: widget.onBack,
                icon: const Icon(Icons.more_vert_rounded, size: 19),
                visualDensity: VisualDensity.compact),
            ]),
          )),
          Divider(color: colors.outlineVariant, height: 1),
          SizedBox(height: 57, child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14),
            child: Row(children: [
              IconButton(
                key: const ValueKey('room-back'),
                tooltip: 'Back to Trading Rooms',
                onPressed: widget.onBack,
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.chevron_left_rounded, size: 24)),
              const SizedBox(width: 4),
              Expanded(child: InkWell(
                onTap: widget.onBack,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Row(children: [
                      Flexible(child: Text(widget.room.name, maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: type.titleMedium?.copyWith(
                          fontSize: 17, fontWeight: FontWeight.w800))),
                      const SizedBox(width: 3),
                      Icon(Icons.keyboard_arrow_down_rounded,
                        color: colors.primary, size: 16),
                    ]),
                    Text(
                      '${widget.room.focus} · NSE/BSE equities · MY ROOM',
                      maxLines: 1, overflow: TextOverflow.ellipsis,
                      style: type.labelSmall?.copyWith(
                        fontSize: 10, color: colors.onSurfaceVariant)),
                  ],
                ),
              )),
              const SizedBox(width: 6),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
                decoration: BoxDecoration(
                  color: colors.primaryContainer,
                  border: Border.all(color: colors.primary.withValues(alpha: .45)),
                  borderRadius: BorderRadius.circular(5)),
                child: Text(widget.room.mode, style: type.labelSmall?.copyWith(
                  fontSize: 10, color: colors.primary,
                  fontWeight: FontWeight.w800)),
              ),
            ]),
          )),
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 6, 14, 0),
            child: Container(
              height: 63,
              decoration: BoxDecoration(
                color: colors.surfaceContainerLow,
                border: Border.all(color: colors.outlineVariant),
                borderRadius: BorderRadius.circular(8)),
              child: Row(children: [
                Expanded(flex: 11, child: _CompactMetric(
                  key: isPaper && cash != null
                    ? const ValueKey('paper-wallet-cash') : null,
                  label: isPaper ? 'VIRTUAL CASH' : 'PLANNED CAPITAL',
                  value: isPaper ? cash == null ? '—' : _moneyPaise(cash)
                      : formatPaperRupees(widget.room.paperBudget))),
                _MetricDivider(color: colors.outlineVariant),
                Expanded(flex: 9, child: _CompactMetric(
                  label: 'REALIZED P&L',
                  value: isPaper && pnl != null ? _moneyPaise(pnl) : '—',
                  green: isPaper && pnl != null && pnl >= 0)),
                _MetricDivider(color: colors.outlineVariant),
                Expanded(flex: 6, child: _CompactMetric(
                  label: 'POSITIONS',
                  value: isPaper
                    ? _historyError != null ? '—' : '$count' : '—')),
              ]),
            ),
          ),
          Padding(padding: const EdgeInsets.fromLTRB(14, 12, 14, 0),
            child: SizedBox(height: 39, child: Row(children: [
              for (final tab in const [
                'Trade', 'Positions', 'Orders', 'Mind', 'Activity'
              ])
                Expanded(child: InkWell(
                  key: ValueKey('room-tab-${tab.toLowerCase()}'),
                  onTap: () => setState(() => _tab = tab),
                  child: Container(
                    alignment: Alignment.center,
                    decoration: BoxDecoration(border: Border(
                      bottom: BorderSide(
                        color: _tab == tab ? colors.primary : colors.outlineVariant,
                        width: _tab == tab ? 2 : 1))),
                    child: Text(tab, maxLines: 1,
                      style: type.labelMedium?.copyWith(
                        fontSize: 12,
                        color: _tab == tab ? colors.onSurface
                          : colors.onSurfaceVariant,
                        fontWeight: FontWeight.w700)),
                  ),
                )),
            ])),
          ),
          if (widget.busy)
            const LinearProgressIndicator(minHeight: 2),
          if (widget.error != null)
            Padding(padding: const EdgeInsets.symmetric(horizontal: 14),
              child: Text(widget.error!,
                style: type.bodySmall?.copyWith(color: colors.error))),
          Expanded(child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 0),
            child: switch (_tab) {
              'Trade' => _trade(context),
              'Positions' => _positionsPage(context),
              'Orders' => _ordersPage(context),
              'Mind' => _mindPage(context),
              _ => _activityPage(context),
            },
          )),
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 0, 14, 9),
            child: Align(alignment: Alignment.centerLeft,
              child: Text(readyNote, maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: type.labelSmall?.copyWith(
                  fontSize: 10, color: colors.onSurfaceVariant))),
          ),
        ]),
      ))),
    );
  }

  Widget _trade(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final styles = Theme.of(context).textTheme;
    final count = _positions.where((p) => p.quantity > 0).length;
    return Column(children: [
      if (widget.market != null)
        Expanded(child: MintTickRoomCandlestickDesk(
          market: widget.market!,
          onInstrument: (selected) => _selected = selected))
      else
        Expanded(child: Center(child: Text('Market data unavailable.',
          style: styles.bodySmall))),
      Container(
        height: 57,
        decoration: BoxDecoration(border: Border(
          top: BorderSide(color: colors.outlineVariant))),
        child: Row(children: [
          Expanded(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text('Positions', style: styles.bodySmall?.copyWith(
                fontWeight: FontWeight.w800)),
              Text(widget.room.mode != 'PAPER'
                ? 'Live positions not yet authorized'
                : count == 0 ? 'No open positions'
                  : '$count open positions',
                maxLines: 1, overflow: TextOverflow.ellipsis,
                style: styles.labelSmall?.copyWith(
                  color: colors.onSurfaceVariant)),
            ])),
          TextButton(
            onPressed: () => setState(() => _tab = 'Positions'),
            child: Text('VIEW ALL ›', style: styles.labelSmall?.copyWith(
              color: colors.primary, fontWeight: FontWeight.w700))),
        ]),
      ),
      SizedBox(height: 57, child: Row(children: [
        Expanded(child: _TradeAction(
          label: 'BUY',
          filled: true,
          onPressed: () => _order('BUY'))),
        const SizedBox(width: 9),
        Expanded(child: _TradeAction(
          label: 'SELL',
          filled: false,
          onPressed: () => _order('SELL'))),
      ])),
    ]);
  }

  Widget _positionsPage(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final live = widget.room.mode == 'LIVE';
    final rows = _positions.where((item) => item.quantity > 0).toList();
    return Column(children: [
      _PageTitle('Open positions', trailing: live ? '—' : '${rows.length} holdings',
        onRefresh: _loadHistory),
      const _RowColumns(),
      if (_historyLoading) const LinearProgressIndicator(minHeight: 2),
      Expanded(child: live
        ? const Center(child: Text('No broker-authorized Live position feed.'))
        : _historyError != null
          ? Center(child: Text(_historyError!))
          : rows.isEmpty ? const Center(child: Text('No open Paper positions.'))
          : ListView.builder(itemCount: rows.length, itemBuilder: (context, index) {
              final row = rows[index];
              return _DataLine(
                primary: 'Instrument #${row.instrumentId}',
                secondary: 'PAPER · server position',
                middle: '${row.quantity}',
                right: '₹${(row.avgCostPaise / 100).toStringAsFixed(2)}');
            })),
      Padding(padding: const EdgeInsets.symmetric(vertical: 8),
        child: Text('Only verified positions for this room are shown.',
          style: type.labelSmall)),
      _actions(),
    ]);
  }

  Widget _ordersPage(BuildContext context) {
    final isPaper = widget.room.mode == 'PAPER';
    final rows = isPaper ? _paperOrders.length : _liveIntents.length;
    return Column(children: [
      _PageTitle('Order history', trailing: '$rows', onRefresh: _loadHistory),
      const _RowColumns(),
      if (_historyLoading) const LinearProgressIndicator(minHeight: 2),
      Expanded(child: _historyError != null
        ? Center(child: Text(_historyError!))
        : rows == 0
          ? Center(child: Text(isPaper
            ? 'No confirmed Paper fills.' : 'No blocked Live intents.'))
          : ListView.builder(itemCount: rows, itemBuilder: (context, index) {
            if (isPaper) {
              final order = _paperOrders[index];
              return _DataLine(
                primary: order.tradingSymbol,
                secondary: '${order.quantity} × ₹${order.fillPrice}',
                middle: order.side,
                right: 'PAPER FILL');
            }
            final intent = _liveIntents[index];
            return _DataLine(
              primary: intent.tradingSymbol,
              secondary: '${intent.quantity} × ₹${intent.limitPrice}',
              middle: intent.side,
              right: 'BLOCKED');
          })),
      const _FlatNotice(
        'PAPER rows are virtual fills. LIVE rows are blocked intents, '
        'not real-money executions.'),
      _actions(),
    ]);
  }

  Widget _mindPage(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final read = widget.readiness;
    final live = widget.room.mode == 'LIVE';
    return Column(children: [
      _PageTitle('MintTick Mind', trailing: 'READ ONLY',
        onRefresh: widget.onRefresh),
      const _DataLine(primary: 'Room engine',
        secondary: 'Model-controlled settings',
        middle: '', right: 'NOT RUNNING'),
      _DataLine(primary: 'Execution state',
        secondary: 'Backend broker and policy readiness',
        middle: '', right: read == null ? 'UNKNOWN'
          : read.executionEnabled ? 'READY' : 'BLOCKED'),
      const _DataLine(primary: 'Capital & risk',
        secondary: 'Authoritative MintTick Mind / server',
        middle: '', right: 'AUTOMATIC'),
      if (live) ...[
        Padding(padding: const EdgeInsets.symmetric(vertical: 10),
          child: Wrap(spacing: 7, runSpacing: 6, children: [
            for (final broker in const [
              ('ANGEL_ONE','Angel One'), ('UPSTOX','Upstox')
            ])
              ChoiceChip(
                key: ValueKey('live-room-broker-${broker.$1.toLowerCase()}'),
                selected: read?.selectedBroker == broker.$1,
                label: Text(broker.$2),
                onSelected: (_) => widget.onSelectBroker(broker.$1)),
          ])),
        if (widget.onBrokers != null)
          Align(alignment: Alignment.centerLeft, child: TextButton(
            onPressed: widget.onBrokers,
            child: const Text('Manage broker connection'))),
      ],
      const Spacer(),
      const _FlatNotice(
        'No manual risk configuration. Signals, sizes and limits must '
        'come from authorized server decisions; nothing is inferred here.'),
      Text('Preflight blockers: ${read?.blockers.length ?? 'not verified'}',
        style: type.labelSmall),
      const SizedBox(height: 8),
      OutlinedButton(
        onPressed: widget.onRefresh,
        child: const Text('Refresh readiness')),
    ]);
  }

  Widget _activityPage(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final paper = widget.room.mode == 'PAPER';
    return Column(children: [
      _PageTitle('Room activity', onRefresh: _refresh),
      Expanded(child: ListView(children: [
        if (paper)
          for (final e in widget.events)
            _DataLine(primary: 'Virtual wallet allocation',
              secondary: 'Server ledger · PAPER',
              middle: '', right: _moneyPaise(e.cashDeltaPaise)),
        if (paper)
          for (final order in _paperOrders.take(20))
            _DataLine(primary: '${order.side} ${order.tradingSymbol}',
              secondary: 'Confirmed virtual fill',
              middle: '${order.quantity}', right: 'PAPER'),
        if (!paper)
          for (final intent in _liveIntents.take(20))
            _DataLine(primary: '${intent.side} ${intent.tradingSymbol}',
              secondary: 'Non-executing Live intent',
              middle: '${intent.quantity}', right: 'BLOCKED'),
        if ((paper && widget.events.isEmpty && _paperOrders.isEmpty) ||
            (!paper && _liveIntents.isEmpty))
          Padding(padding: const EdgeInsets.symmetric(vertical: 22),
            child: Text('No verified activity in this room.',
              textAlign: TextAlign.center,
              style: type.bodySmall)),
      ])),
      const _FlatNotice(
        'Account events are owner-scoped. Virtual allocation is not '
        'a real-money deposit or brokerage balance.'),
    ]);
  }

  Widget _actions() => SizedBox(height: 57, child: Row(children: [
    Expanded(child: _TradeAction(label: 'New Buy',
      filled: true, onPressed: () => _order('BUY'))),
    const SizedBox(width: 9),
    Expanded(child: _TradeAction(label: 'New Sell',
      filled: false, onPressed: () => _order('SELL'))),
  ]));
}

String _moneyPaise(int paise) {
  final negative = paise < 0 ? '-' : '';
  final absolute = paise.abs();
  final rupees = formatPaperRupees(absolute ~/ 100);
  return '$negative$rupees.${(absolute % 100).toString().padLeft(2, '0')}';
}

class _CompactMetric extends StatelessWidget {
  const _CompactMetric({required this.label, required this.value,
    this.green = false, super.key});
  final String label, value;
  final bool green;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      child: Column(mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: type.labelSmall?.copyWith(fontSize: 10,
            color: scheme.onSurfaceVariant)),
        const SizedBox(height: 3),
        Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: type.titleMedium?.copyWith(
            fontSize: 17, fontWeight: FontWeight.w800,
            color: green ? scheme.primary : scheme.onSurface)),
      ]),
    );
  }
}

class _MetricDivider extends StatelessWidget {
  const _MetricDivider({required this.color});
  final Color color;
  @override
  Widget build(BuildContext context) =>
    SizedBox(height: 39, child: VerticalDivider(color: color, width: 1));
}

class _TradeAction extends StatelessWidget {
  const _TradeAction({required this.label, required this.filled,
    required this.onPressed});
  final String label;
  final bool filled;
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(height: 44, child: OutlinedButton(
      onPressed: onPressed,
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(0, 44),
        backgroundColor: filled ? scheme.primary : scheme.surface,
        foregroundColor: filled ? scheme.onPrimary : scheme.primary,
        side: BorderSide(color: scheme.primary),
        padding: const EdgeInsets.symmetric(horizontal: 5),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8))),
      child: Text(label, style: const TextStyle(
        fontWeight: FontWeight.w800, fontSize: 13)),
    ));
  }
}

class _PageTitle extends StatelessWidget {
  const _PageTitle(this.title, {this.trailing, this.onRefresh});
  final String title;
  final String? trailing;
  final VoidCallback? onRefresh;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: 40, child: Row(children: [
      Expanded(child: Text(title, style: Theme.of(context).textTheme.titleMedium
        ?.copyWith(fontSize: 15, fontWeight: FontWeight.w800))),
      if (trailing != null) Text(trailing!,
        style: Theme.of(context).textTheme.labelSmall),
      if (onRefresh != null) IconButton(
        onPressed: onRefresh, tooltip: 'Refresh room activity',
        visualDensity: VisualDensity.compact,
        icon: const Icon(Icons.refresh_rounded, size: 19)),
    ]),
  );
}

class _RowColumns extends StatelessWidget {
  const _RowColumns();
  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    final style = type.labelSmall?.copyWith(
      color: scheme.onSurfaceVariant, fontSize: 10);
    return Padding(padding: const EdgeInsets.symmetric(vertical: 7, horizontal: 5),
      child: Row(children: [
        Expanded(flex: 11, child: Text('SYMBOL', style: style)),
        Expanded(flex: 5, child: Text('QTY / SIDE', style: style)),
        Expanded(flex: 8, child: Text('VALUE / STATE',
          textAlign: TextAlign.right, style: style)),
      ]));
  }
}

class _DataLine extends StatelessWidget {
  const _DataLine({required this.primary, required this.secondary,
    required this.middle, required this.right});
  final String primary, secondary, middle, right;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 9),
      decoration: BoxDecoration(border: Border(
        bottom: BorderSide(color: colors.outlineVariant))),
      child: Row(children: [
        Expanded(flex: 11, child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(primary, maxLines: 1, overflow: TextOverflow.ellipsis,
              style: type.bodySmall?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 3),
            Text(secondary, maxLines: 1, overflow: TextOverflow.ellipsis,
              style: type.labelSmall?.copyWith(
                fontSize: 10, color: colors.onSurfaceVariant)),
          ])),
        Expanded(flex: 5, child: Text(middle, maxLines: 1,
          overflow: TextOverflow.ellipsis, style: type.bodySmall)),
        Expanded(flex: 8, child: Text(right, textAlign: TextAlign.right,
          maxLines: 1, overflow: TextOverflow.ellipsis,
          style: type.bodySmall?.copyWith(fontWeight: FontWeight.w700))),
      ]),
    );
  }
}

class _FlatNotice extends StatelessWidget {
  const _FlatNotice(this.message);
  final String message;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 7),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: scheme.outlineVariant)),
      child: Text(message, style: Theme.of(context).textTheme.bodySmall
        ?.copyWith(color: scheme.onSurfaceVariant)),
    );
  }
}
