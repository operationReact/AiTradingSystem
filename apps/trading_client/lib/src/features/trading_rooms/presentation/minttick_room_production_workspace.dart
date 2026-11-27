
import 'package:flutter/material.dart';
import '../../../brand/minttick_brand.dart';
import '../../../data/instrument_repository.dart';
import '../data/customer_execution_repository.dart';
import '../data/customer_paper_fno_repository.dart';
import '../data/customer_market_repository.dart';
import '../data/customer_paper_trading_repository.dart';
import '../data/customer_portfolio_models.dart';
import '../data/customer_room_repository.dart';
import '../data/customer_trade_intent_repository.dart';
import '../domain/trading_room_draft.dart';
import 'minttick_customer_widgets.dart';
import '../data/customer_upstox_fno_live_repository.dart';
import 'minttick_upstox_fno_live_panel.dart';
import 'minttick_paper_fno_panel.dart';
import 'minttick_room_candlestick_desk.dart';
import 'minttick_room_order_sheet.dart';

/// Exact structural counterpart of MintTick_Rooms_Production_Parity_Prototype.
/// All colors resolve from MintTickBrand/MintTickPalette through buildTheme.
/// In contrast to demo HTML, every amount/trade/quote here is server-sourced.
class MintTickRoomProductionWorkspace extends StatefulWidget {
  const MintTickRoomProductionWorkspace({
    required this.room,
    required this.onBack,
    this.onRename,
    required this.onRefresh,
    required this.onFundPaper,
    required this.onSelectBroker,
    this.wallet,
    this.readiness,
    this.liveEquityCapability,
    this.events = const [],
    this.market,
    this.paper,
    this.fno,
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
  final LiveEquityCapability? liveEquityCapability;
  final List<CustomerPaperCashEvent> events;
  final HttpCustomerMarketRepository? market;
  final HttpCustomerPaperTradingRepository? paper;
  /// PAPER-only NIFTY options. Null hides the F&O section.
  final CustomerPaperFnoRepository? fno;
  final HttpCustomerExecutionRepository? execution;
  final HttpCustomerTradeIntentRepository? intents;
  final VoidCallback onBack;
  final VoidCallback? onRename;
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
  String _tradeMarket = 'EQUITY';
  String get _roomScopeLabel => switch (widget.room.marketScope) {
    'FNO' => 'NIFTY Options',
    'ALL' => 'Equity + F&O',
    _ => 'NSE/BSE equities',
  };
  List<String> get _equityRouteBlockers =>
      (widget.liveEquityCapability?.blockers ?? const <String>[])
          .where((blocker) => blocker ==
                  'LIVE_INTRADAY_EQUITY_ORDER_ROUTE_NOT_IMPLEMENTED' ||
              blocker == 'AUTHORITATIVE_QUOTE_NOT_VERIFIED_FOR_ORDER' ||
              blocker == 'SERVER_SIDE_ORDER_RISK_NOT_EVALUATED' ||
              blocker == 'BROKER_ORDER_DISPATCH_NOT_IMPLEMENTED' ||
              blocker ==
                  'BROKER_ORDER_DISPATCH_AND_RECONCILIATION_NOT_IMPLEMENTED' ||
              blocker == 'ANGEL_ONE_EQUITY_DISPATCH_NOT_COORDINATED' ||
              blocker == 'UPSTOX_EQUITY_DISPATCH_NOT_COORDINATED')
          .toList(growable: false);
  InstrumentRecord? _selected;
  int _chartVersion = 0;
  List<CustomerPaperOrder> _paperOrders = const [];
  List<CustomerPaperPosition> _positions = const [];
  List<CustomerFnoPosition> _fnoPositions = const [];
  bool _fnoInventoryVerified = false;
  Map<int, CustomerLiveQuote> _positionQuotes = const {};
  int _historyGeneration = 0;
  List<CustomerBlockedOrderIntent> _liveIntents = const [];
  bool _historyLoading = false;
  String? _historyError;

  @override
  void initState() {
    super.initState();
    _loadHistory();
  }

  @override
  void dispose() {
    ++_historyGeneration;
    super.dispose();
  }

  Future<void> _readPaperQuotes(List<CustomerPaperPosition> rows, int request) async {
    final market = widget.market;
    if (market == null || rows.isEmpty) {
      if (mounted && request == _historyGeneration) {
        setState(() => _positionQuotes = const {});
      }
      return;
    }
    final ids = rows.where((row) => row.quantity > 0)
        .map((row) => row.instrumentId).toSet().toList();
    final quotes = <int, CustomerLiveQuote>{};
    // The backend accepts at most 30 IDs per authenticated snapshot request.
    // Process all holdings in bounded batches so no instrument is skipped.
    for (var index = 0; index < ids.length; index += 30) {
      if (!mounted || request != _historyGeneration) return;
      final end = (index + 30).clamp(0, ids.length);
      try {
        final page = await market.snapshot(ids.sublist(index, end));
        for (final quote in page.rows) {
          if (quote.ltp != null &&
              const {'LIVE', 'LAST_CLOSE'}.contains(quote.status)) {
            quotes[quote.instrumentId] = quote;
          }
        }
      } catch (_) {
        // Unknown prices must remain unavailable, never estimated from cost.
      }
    }
    if (mounted && request == _historyGeneration) {
      setState(() => _positionQuotes = quotes);
    }
  }

  String _positionSymbol(int instrumentId) {
    for (final order in _paperOrders) {
      if (order.instrumentId == instrumentId) return order.tradingSymbol;
    }
    return _positionQuotes[instrumentId]?.tradingSymbol ??
        'Instrument #$instrumentId';
  }

  Future<void> _loadHistory() async {
    final request = ++_historyGeneration;
    // F&O has a separate owner-scoped positions ledger. Never count cash
    // equities in the option KPI ribbon, and never present failed reads as 0.
    if (widget.room.marketScope == 'FNO' &&
        widget.room.mode == 'PAPER' && widget.fno != null) {
      setState(() {
        _historyLoading = true;
        _historyError = null;
        _fnoInventoryVerified = false;
      });
      try {
        final rows = await widget.fno!.positions(widget.room.id);
        if (!mounted || request != _historyGeneration) return;
        setState(() {
          _fnoPositions = rows;
          _fnoInventoryVerified = true;
        });
      } catch (_) {
        if (mounted && request == _historyGeneration) {
          setState(() {
            _fnoPositions = const [];
            _fnoInventoryVerified = false;
            _historyError = 'F&O positions could not be verified.';
          });
        }
      } finally {
        if (mounted && request == _historyGeneration) {
          setState(() => _historyLoading = false);
        }
      }
      return;
    }
    if (widget.room.mode == 'PAPER' && widget.paper != null &&
        (widget.room.status == 'PAPER_FUNDED' || widget.wallet != null)) {
      setState(() { _historyLoading = true; _historyError = null;
        _positionQuotes = const {}; });
      try {
        final rows = await Future.wait<Object>([
          widget.paper!.orders(widget.room.id),
          widget.paper!.positions(widget.room.id),
        ]);
        if (!mounted) return;
        if (request != _historyGeneration) return;
        setState(() {
          _paperOrders = rows[0] as List<CustomerPaperOrder>;
          _positions = rows[1] as List<CustomerPaperPosition>;
        });
        await _readPaperQuotes(_positions, request);
      } catch (_) {
        if (mounted) { setState(() {
          _paperOrders = const [];
          _positions = const [];
          _historyError = 'Room activity could not be verified.';
        }); }
      } finally {
        if (mounted && request == _historyGeneration) {
          setState(() => _historyLoading = false);
        }
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
    // Funding is an explicit virtual-wallet action, never dependent on
    // market data. Do not show a separate, duplicate confirmation dialog.
    if (widget.room.mode == 'PAPER' && widget.wallet == null) {
      await widget.onFundPaper();
      return;
    }
    if (widget.market == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Market data is unavailable.')));
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
        onInstrumentSelected: (item) {
          if (mounted) {
            setState(() {
              _selected = item;
              ++_chartVersion;
            });
          }
        },
      ),
    );
  }

  int? get _fnoIndicativePnlPaise {
    if (!_fnoInventoryVerified) return null;
    var total = 0;
    for (final p in _fnoPositions) {
      if (p.quantity == 0) continue;
      if (!p.priceFresh || p.indicativeUnrealizedPnlPaise == null) return null;
      total += p.indicativeUnrealizedPnlPaise!;
    }
    return total;
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    final isPaper = widget.room.mode == 'PAPER';
    final cash = widget.wallet?.availableCashPaise;
    final pnl = widget.wallet?.realizedPnlPaise;
    final count = _positions.where((item) => item.quantity > 0).length;
    final fnoPnl = _fnoIndicativePnlPaise;
    final fnoBasis = _fnoPositions.fold<int>(0,
        (sum, p) => sum + (p.quantity > 0 ? p.costBasisPaise : 0));
    final fnoReturn = fnoPnl != null && fnoBasis > 0
        ? fnoPnl * 100 / fnoBasis : null;
    final readyNote = isPaper
      ? widget.room.marketScope == 'FNO'
          ? 'PAPER · No real-money orders · server-verified execution only'
          : 'PAPER · Virtual cash · manual simulated orders only'
      : 'LIVE · Broker authorization required · order execution locked';

    return Scaffold(
      body: SafeArea(child: Center(child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Column(children: [
          SizedBox(height: 53, child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 15),
            child: Row(children: [
              const Flexible(fit: FlexFit.loose,
                child: MintTickLockup(markWidth: 29, fontSize: 19)),
              const Spacer(),
              Text('ROOMS', style: type.labelSmall?.copyWith(
                color: colors.onSurfaceVariant, fontSize: 10)),
              const SizedBox(width: 7),
              PopupMenuButton<String>(
                key: const ValueKey('room-action-menu'),
                tooltip: 'Room actions',
                icon: const Icon(Icons.more_vert_rounded, size: 19),
                onSelected: (action) {
                  if (action == 'rename') {
                    widget.onRename?.call();
                  } else if (action == 'rooms') {
                    widget.onBack();
                  }
                },
                itemBuilder: (_) => [
                  if (widget.onRename != null)
                    const PopupMenuItem(
                      value: 'rename',
                      child: Row(children: [
                        Icon(Icons.edit_outlined, size: 18),
                        SizedBox(width: 9),
                        Text('Rename room'),
                      ]),
                    ),
                  const PopupMenuItem(
                    value: 'rooms',
                    child: Text('Back to Trading Rooms'),
                  ),
                ],
              ),
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
                onTap: widget.onRename,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Row(children: [
                      Flexible(child: Text(widget.room.name, maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: type.titleMedium?.copyWith(
                          fontSize: 17, fontWeight: FontWeight.w800))),
                      if (widget.onRename != null) ...[
                        const SizedBox(width: 5),
                        Icon(Icons.edit_outlined,
                          color: colors.primary, size: 15),
                      ],
                    ]),
                    Text(
                      widget.room.marketScope == 'FNO'
                          ? 'Options · NIFTY · MY ROOM'
                          : '${widget.room.focus} · $_roomScopeLabel · MY ROOM',
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
          // Three essential F&O KPIs remain visible on narrow phones.
          // Additional financial diagnostics belong in Orders / Positions.
          if (widget.room.marketScope == 'FNO' && isPaper)
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 6, 14, 0),
              child: Container(
                height: 70,
                decoration: BoxDecoration(
                  color: colors.surfaceContainerLow,
                  border: Border.all(color: colors.outlineVariant),
                  borderRadius: BorderRadius.circular(10)),
                child: Row(children: [
                  Expanded(flex: 12, child: _CompactMetric(
                    key: cash != null ? const ValueKey('paper-wallet-cash') : null,
                    label: 'VIRTUAL CASH', dense: true,
                    value: cash == null ? '—' : _moneyPaise(cash))),
                  _MetricDivider(color: colors.outlineVariant),
                  Expanded(flex: 11, child: _CompactMetric(
                    label: 'OPTIONS P&L', dense: true,
                    value: fnoPnl == null ? '—' : _moneyPaise(fnoPnl),
                    secondary: fnoReturn == null ? 'Not valued'
                      : '${fnoReturn >= 0 ? '+' : ''}${fnoReturn.toStringAsFixed(2)}%',
                    green: fnoPnl != null && fnoPnl >= 0)),
                  _MetricDivider(color: colors.outlineVariant),
                  Expanded(flex: 8, child: _CompactMetric(
                    label: 'POSITIONS', dense: true,
                    value: !_fnoInventoryVerified ? '—'
                        : _fnoPositions.where((p) => p.quantity > 0)
                            .length.toString())),
                ]),
              ),
            )
          else
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
                    child: FittedBox(fit: BoxFit.scaleDown,
                      child: Text(tab, maxLines: 1,
                        style: type.labelMedium?.copyWith(
                          fontSize: 12,
                          color: _tab == tab ? colors.onSurface
                            : colors.onSurfaceVariant,
                          fontWeight: FontWeight.w700))),
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
    final scope = widget.room.marketScope;
    if (scope == 'FNO') return _fnoTrade(context);
    if (scope != 'ALL') {
      return _equityTrade(context);
    }
    return Column(children: [
      SizedBox(height: 45, child: SegmentedButton<String>(
        key: const ValueKey('room-trade-market-switch'),
        segments: const [
          ButtonSegment(value: 'EQUITY', label: Text('Equity')),
          ButtonSegment(value: 'FNO', label: Text('Options')),
        ],
        selected: {_tradeMarket},
        onSelectionChanged: (value) =>
            setState(() => _tradeMarket = value.first),
      )),
      Expanded(child: _tradeMarket == 'FNO'
          ? _fnoTrade(context) : _equityTrade(context)),
    ]);
  }

  Widget _fnoTrade(BuildContext context) {
    if (widget.room.mode == 'LIVE') {
      if (widget.readiness?.selectedBroker != 'UPSTOX') {
        return const Center(child: Text(
          'Select Upstox for this LIVE F&O room in the Mind tab to '
          'inspect real broker permissions and order readiness.'));
      }
      if (widget.execution == null) {
        return const Center(child: Text(
          'Authenticated LIVE F&O connection is unavailable.'));
      }
      return MintTickUpstoxFnoLivePanel(
        key: ValueKey('room-live-fno-${widget.room.id}'),
        roomId: widget.room.id,
        repository: CustomerUpstoxFnoLiveRepository(widget.execution!.api),
      );
    }
    if (widget.room.mode != 'PAPER' || widget.fno == null) {
      return const Center(child: Text('F&O PAPER account unavailable.'));
    }
    return SingleChildScrollView(
      child: MintTickPaperFnoPanel(
        key: ValueKey('room-fno-trade-${widget.room.id}'),
        roomId: widget.room.id,
        repository: widget.fno!,
        onFilled: () async {
          await _loadHistory();
          await widget.onRefresh();
        },
      ),
    );
  }

  Widget _equityTrade(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final styles = Theme.of(context).textTheme;
    final count = _positions.where((p) => p.quantity > 0).length;
    return Column(children: [
      if (widget.market != null)
        Expanded(child: MintTickRoomCandlestickDesk(
          key: ValueKey('room-chart-$_chartVersion'),
          market: widget.market!,
          initialInstrument: _selected,
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
      if (widget.room.mode == 'PAPER' && widget.wallet == null)
        SizedBox(height: 57, width: double.infinity,
          child: Padding(padding: const EdgeInsets.symmetric(vertical: 6),
            child: FilledButton.icon(
              key: const ValueKey('activate-paper-wallet'),
              onPressed: widget.busy ? null : widget.onFundPaper,
              icon: const Icon(Icons.account_balance_wallet_outlined, size: 17),
              label: const Text('Fund virtual Paper wallet'))))
      else
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
    // Strict F&O room: never display an equity holdings desk or its generic
    // cash-equity BUY/SELL shortcuts. Existing recorded F&O positions remain
    // reachable through the dedicated options panel.
    if (widget.room.marketScope == 'FNO') {
      if (widget.room.mode != 'PAPER') {
        return const Center(child: Text(
          'LIVE F&O positions require verified broker access and execution readiness.'));
      }
      if (widget.fno == null) {
        return const Center(child: Text(
          'F&O position service unavailable. No equity orders are permitted.'));
      }
      return SingleChildScrollView(child: MintTickPaperFnoPanel(
        key: ValueKey('paper-fno-${widget.room.id}'),
        roomId: widget.room.id,
        repository: widget.fno!,
        view: 'positions',
        onFilled: () async {
          await _loadHistory();
          await widget.onRefresh();
        },
      ));
    }
    final type = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    final live = widget.room.mode == 'LIVE';
    final rows = _positions.where((item) => item.quantity > 0).toList();
    final investedPaise = rows.fold<int>(0,
        (sum, row) => sum + row.avgCostPaise * row.quantity);
    num? marketValue = 0;
    for (final row in rows) {
      final quote = _positionQuotes[row.instrumentId];
      if (quote == null || quote.ltp == null) {
        marketValue = null;
        break;
      }
      marketValue = marketValue! + quote.ltp! * row.quantity;
    }
    final unrealized = marketValue == null
        ? null : marketValue - investedPaise / 100;
    return Column(children: [
      _PageTitle(live ? 'Live positions' : 'Paper portfolio',
        trailing: live ? '—' : '${rows.length} holdings',
        onRefresh: _loadHistory),
      if (!live && widget.room.mode == 'PAPER' &&
          widget.room.marketScope != 'EQUITY' && widget.fno != null) ...[
        MintTickPaperFnoPanel(
          key: ValueKey('paper-fno-${widget.room.id}'),
          roomId: widget.room.id,
          repository: widget.fno!,
          onFilled: () async {
            await _loadHistory();
            await widget.onRefresh();
          },
        ),
        const SizedBox(height: 10),
      ],
      if (!live) Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 10),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: scheme.outlineVariant)),
        child: Row(children: [
          Expanded(child: _PortfolioMetric(
            label: 'COST BASIS', value: portfolioRupees(investedPaise / 100))),
          Expanded(child: _PortfolioMetric(
            label: 'MARKED VALUE', value:
              _historyLoading || _historyError != null ? '—'
                : portfolioRupees(marketValue))),
          Expanded(child: _PortfolioMetric(
            label: 'UNREALIZED', value:
              _historyLoading || _historyError != null ? '—'
                : portfolioRupees(unrealized))),
        ]),
      ),
      const SizedBox(height: 8),
      if (_historyLoading) const LinearProgressIndicator(minHeight: 2),
      if (!live)
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 5, vertical: 7),
          child: Row(children: [
            Expanded(flex: 11, child: Text('SYMBOL / AVG',
              style: TextStyle(fontSize: 10))),
            Expanded(flex: 4, child: Text('QTY', style: TextStyle(fontSize: 10))),
            Expanded(flex: 9, child: Text('UNREALIZED',
              textAlign: TextAlign.right, style: TextStyle(fontSize: 10))),
          ]),
        ),
      Expanded(child: live
        ? const Center(child: Text('No broker-authorized Live position feed.'))
        : _historyError != null
          ? Center(child: Text(_historyError!))
          : rows.isEmpty
            ? const Center(child: Text('No open Paper positions.'))
            : ListView.builder(
                itemCount: rows.length,
                itemBuilder: (context, index) {
                  final row = rows[index];
                  final quote = _positionQuotes[row.instrumentId];
                  final result = quote?.ltp == null ? null
                    : (quote!.ltp! - row.avgCostPaise / 100) * row.quantity;
                  return _DataLine(
                    primary: _positionSymbol(row.instrumentId),
                    secondary: 'Avg ${portfolioRupees(row.avgCostPaise / 100)}'
                      '${quote == null ? ' · price unavailable'
                        : quote.status == 'LIVE' ? ' · LIVE'
                          : ' · LAST RECORDED'}',
                    middle: '${row.quantity}',
                    right: portfolioRupees(result),
                  );
                },
              )),
      Padding(padding: const EdgeInsets.symmetric(vertical: 7),
        child: Text(
          'Paper cash is separate. Marked value and unrealized P&L '
          'use only verified Live / last-recorded prices; missing prices show —.',
          maxLines: 2, overflow: TextOverflow.ellipsis,
          style: type.labelSmall?.copyWith(color: scheme.onSurfaceVariant))),
      _actions(),
    ]);
  }

  Widget _ordersPage(BuildContext context) {
    if (widget.room.marketScope == 'FNO') {
      if (widget.room.mode != 'PAPER' || widget.fno == null) {
        return const Center(child: Text(
          'F&O order history is unavailable until the verified broker or PAPER '
          'service is connected.'));
      }
      return SingleChildScrollView(child: MintTickPaperFnoPanel(
        key: ValueKey('fno-order-history-${widget.room.id}'),
        roomId: widget.room.id,
        repository: widget.fno!,
        view: 'orders',
        onFilled: () async {
          await _loadHistory();
          await widget.onRefresh();
        },
      ));
    }
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
    return SingleChildScrollView(child: Column(
      mainAxisSize: MainAxisSize.min, children: [
      _PageTitle('MintTick Mind', trailing: 'READ ONLY',
        onRefresh: widget.onRefresh),
      const _DataLine(primary: 'Room engine',
        secondary: 'No verified engine telemetry',
        middle: '', right: 'NOT VERIFIED'),
      _DataLine(primary: 'Execution state',
        secondary: 'Backend broker and policy readiness',
        middle: '', right: read == null ? 'UNKNOWN'
          : read.executionEnabled ? 'READY' : 'BLOCKED'),
      if (live && widget.room.marketScope != 'FNO')
        _DataLine(
          primary: 'Cash intraday order route',
          secondary: widget.liveEquityCapability?.orderRouteImplemented == true
            ? 'Server route reported implemented'
            : 'Broker dispatch and execution are unavailable',
          middle: '',
          right: widget.liveEquityCapability == null ? 'UNKNOWN'
            : widget.liveEquityCapability!.executionEnabled ? 'READY' : 'BLOCKED'),
      if (live && widget.room.marketScope != 'FNO')
        for (final blocker in _equityRouteBlockers)
          Padding(
            padding: const EdgeInsets.only(top: 7),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                blocker.replaceAll('_', ' '),
                key: ValueKey('live-equity-blocker-$blocker'),
                style: type.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          ),
      const _DataLine(primary: 'Capital & risk',
        secondary: 'Authoritative MintTick Mind / server',
        middle: '', right: 'AUTOMATIC'),
      if (live) ...[
        _DataLine(
          primary: 'Broker session',
          secondary: read?.selectedBroker == null
            ? 'No assigned broker account'
            : read!.selectedBroker == 'UPSTOX' ? 'Upstox' : 'Angel One',
          middle: '',
          right: read?.brokerConnected == true ? 'CONNECTED' : 'OFFLINE'),
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
      const SizedBox(height: 10),
      Align(alignment: Alignment.centerLeft,
        child: Text('Risk and position sizes are controlled by the server.',
          style: type.bodySmall?.copyWith(
            color: Theme.of(context).colorScheme.onSurfaceVariant))),
      Text('Preflight blockers: ${read?.blockers.length ?? 'not verified'}',
        style: type.labelSmall),
      // The server already provides the exact reasons. Never show only a
      // count or turn an unverified broker/risk state into a green indicator.
      for (final blocker in read?.blockers ?? const <String>[])
        Padding(
          padding: const EdgeInsets.only(top: 7),
          child: Align(
            alignment: Alignment.centerLeft,
            child: Text(
              blocker.replaceAll('_', ' '),
              key: ValueKey('mind-preflight-blocker-$blocker'),
              style: type.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
        ),
      const SizedBox(height: 8),
      OutlinedButton(
        onPressed: widget.onRefresh,
        child: const Text('Refresh readiness')),
    ]));
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
        const SizedBox(height: 12),
        Text('Virtual allocations are not real-money deposits.',
          style: type.labelSmall?.copyWith(
            color: Theme.of(context).colorScheme.onSurfaceVariant)),
      ])),
    ]);
  }

  Widget _actions() => widget.room.marketScope == 'FNO'
      ? const SizedBox.shrink() // only the options-specific ticket can trade
      : SizedBox(height: 57, child: Row(children: [
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
    this.secondary, this.green = false, this.dense = false, super.key});
  final String label, value;
  final String? secondary;
  final bool green;
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    return Padding(
      padding: EdgeInsets.symmetric(horizontal: dense ? 6 : 10, vertical: 6),
      child: Column(mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: type.labelSmall?.copyWith(fontSize: 10,
            color: scheme.onSurfaceVariant)),
        const SizedBox(height: 3),
        Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: type.titleMedium?.copyWith(
            fontSize: dense ? 14 : 17, fontWeight: FontWeight.w800,
            color: green ? scheme.primary : scheme.onSurface)),
        if (secondary != null) Text(secondary!, maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: type.labelSmall?.copyWith(
            color: green ? scheme.primary : scheme.onSurfaceVariant,
            fontSize: 10)),
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

class _PortfolioMetric extends StatelessWidget {
  const _PortfolioMetric({required this.label, required this.value});
  final String label, value;
  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: type.labelSmall?.copyWith(
        color: scheme.onSurfaceVariant, fontSize: 9)),
      const SizedBox(height: 4),
      Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: type.bodySmall?.copyWith(fontWeight: FontWeight.w800)),
    ]);
  }
}
