import 'package:flutter/material.dart';

import '../data/customer_angel_one_repository.dart';
import '../data/customer_portfolio_models.dart';
import '../data/customer_portfolio_repository.dart';
import '../data/customer_room_repository.dart';
import '../data/customer_upstox_repository.dart';
import '../domain/trading_room_draft.dart';
import '../domain/trading_workspace_mode.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_mode_switcher.dart';

/// A broker-scoped snapshot. A failed read is never treated as a zero balance.
class _BrokerPortfolio {
  const _BrokerPortfolio({
    required this.name, required this.connected, required this.statusKnown,
    this.account, this.cash, this.holdings, this.positions,
  });
  final String name;
  final bool connected;
  final bool statusKnown;
  final String? account;
  final num? cash;
  final List<PortfolioInstrument>? holdings;
  final List<PortfolioInstrument>? positions;
}

class _PaperAccount {
  const _PaperAccount(this.room, this.wallet);
  final TradingRoomDraft room;
  final CustomerPaperWallet wallet;
}

/// Customer Portfolio: independent LIVE broker accounts and virtual PAPER rooms.
/// No live trade is attributed to MintTick without a reconciled order receipt.
class MintTickCustomerPortfolioPage extends StatefulWidget {
  const MintTickCustomerPortfolioPage({
    required this.mode,
    required this.rooms,
    required this.paperRepository,
    required this.brokerRepository,
    required this.upstoxRepository,
    this.portfolioRepository,
    required this.onRooms,
    required this.onBroker,
    super.key,
  });

  final TradingWorkspaceMode mode;
  final TradingRoomDraftStore rooms;
  final HttpCustomerRoomRepository paperRepository;
  final HttpCustomerAngelOneRepository brokerRepository;
  final HttpCustomerUpstoxRepository upstoxRepository;
  final HttpCustomerPortfolioRepository? portfolioRepository;
  final VoidCallback onRooms;
  final VoidCallback onBroker;

  @override
  State<MintTickCustomerPortfolioPage> createState() =>
      _MintTickCustomerPortfolioPageState();
}

class _MintTickCustomerPortfolioPageState
    extends State<MintTickCustomerPortfolioPage> {
  bool _loading = true;
  String? _error;
  int _generation = 0;
  String _tab = 'Overall';
  String _selectedBroker = 'ANGEL_ONE';
  List<_BrokerPortfolio> _brokers = [];
  List<_PaperAccount> _paperAccounts = [];

  @override
  void initState() {
    super.initState();
    widget.mode.addListener(_modeChanged);
    _refresh();
  }

  void _modeChanged() {
    if (!mounted) return;
    setState(() => _tab = 'Overall');
    _refresh();
  }

  @override
  void dispose() {
    ++_generation;
    widget.mode.removeListener(_modeChanged);
    super.dispose();
  }

  Future<Object?> _read(Future<Object?> Function() reader) async {
    try {
      return await reader();
    } catch (_) {
      // The source is unknown, not verified empty. Do not expose provider
      // exceptions or credentials through the customer interface.
      return null;
    }
  }

  Future<Object?> _recordsRead(
      String broker, bool positions, Future<Object?> Function() legacy) async {
    final normalized = widget.portfolioRepository;
    if (normalized != null) {
      try {
        return await normalized.records(broker, positions: positions);
      } catch (_) {
        // During API-edge rollout, use the existing protected provider route.
      }
    }
    return _read(legacy);
  }

  Future<_BrokerPortfolio> _angel() async {
    final CustomerAngelOneConnection status;
    try {
      status = await widget.brokerRepository.status();
    } catch (_) {
      return const _BrokerPortfolio(
        name: 'ANGEL_ONE', connected: false, statusKnown: false,
      );
    }
    if (!status.connected) {
      return _BrokerPortfolio(
        name: 'ANGEL_ONE', connected: false, statusKnown: true,
      );
    }
    final values = await Future.wait<Object?>([
      _read(widget.brokerRepository.funds),
      _recordsRead('ANGEL_ONE', false, widget.brokerRepository.holdings),
      _recordsRead('ANGEL_ONE', true, widget.brokerRepository.positions),
    ]);
    return _BrokerPortfolio(
      name: 'ANGEL_ONE', connected: true, statusKnown: true,
      account: status.clientCodeMasked,
      cash: portfolioAmount(values[0], broker: 'ANGEL_ONE'),
      holdings: PortfolioInstrument.decode(values[1], broker: 'ANGEL_ONE'),
      positions: PortfolioInstrument.decode(
        values[2], broker: 'ANGEL_ONE', position: true,
      ),
    );
  }

  Future<_BrokerPortfolio> _upstox() async {
    final CustomerUpstoxStatus status;
    try {
      status = await widget.upstoxRepository.status();
    } catch (_) {
      return const _BrokerPortfolio(
        name: 'UPSTOX', connected: false, statusKnown: false,
      );
    }
    if (!status.connected) {
      return _BrokerPortfolio(
        name: 'UPSTOX', connected: false, statusKnown: true,
      );
    }
    final values = await Future.wait<Object?>([
      _read(widget.upstoxRepository.funds),
      _recordsRead('UPSTOX', false, widget.upstoxRepository.holdings),
      _recordsRead('UPSTOX', true, widget.upstoxRepository.positions),
    ]);
    return _BrokerPortfolio(
      name: 'UPSTOX', connected: true, statusKnown: true,
      account: status.accountMasked,
      cash: portfolioAmount(values[0], broker: 'UPSTOX'),
      holdings: PortfolioInstrument.decode(values[1], broker: 'UPSTOX'),
      positions: PortfolioInstrument.decode(
        values[2], broker: 'UPSTOX', position: true,
      ),
    );
  }

  Future<void> _refresh() async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      if (widget.mode.isPaper) {
        if (!widget.rooms.loaded) await widget.rooms.load();
        if (widget.rooms.lastError != null) {
          throw StateError('Paper room list unavailable');
        }
        final funded = widget.rooms.drafts.where(
          (room) => room.mode == 'PAPER' && room.status == 'PAPER_FUNDED',
        );
        final wallets = await Future.wait(
          funded.map((room) async => _PaperAccount(
            room, await widget.paperRepository.paperAccount(room.id),
          )),
        );
        if (!mounted || generation != _generation) return;
        setState(() => _paperAccounts = wallets);
      } else {
        final sources = await Future.wait([_angel(), _upstox()]);
        if (!mounted || generation != _generation) return;
        setState(() {
          _brokers = sources;
          if (sources.first.connected == false && sources.last.connected) {
            _selectedBroker = 'UPSTOX';
          }
        });
      }
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error = widget.mode.isPaper
          ? 'Paper room balances are unavailable. Please retry.'
          : 'Broker portfolio data is unavailable. Please retry.');
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  _BrokerPortfolio _source(String name) => _brokers.firstWhere(
    (b) => b.name == name,
    orElse: () => _BrokerPortfolio(
      name: name, connected: false, statusKnown: false,
    ),
  );

  String _name(String code) => code == 'UPSTOX' ? 'Upstox' : 'Angel One';
  List<PortfolioInstrument> _records(bool positions) {
    return _brokers.expand((broker) =>
      (positions ? broker.positions : broker.holdings) ??
          const <PortfolioInstrument>[]).toList();
  }

  /// Do not treat one unavailable broker as a verified zero in totals.
  num? _totalHoldingValue() {
    final connected = _brokers.where((broker) => broker.connected).toList();
    if (connected.isEmpty || _brokers.any((b) => !b.statusKnown)) return null;
    num total = 0;
    for (final broker in connected) {
      final value = portfolioHoldingValue(broker.holdings);
      if (value == null) return null;
      total += value;
    }
    return total;
  }

  int? _totalCount(bool positions) {
    if (_brokers.any((b) => !b.statusKnown)) return null;
    var count = 0;
    for (final broker in _brokers.where((b) => b.connected)) {
      final rows = positions ? broker.positions : broker.holdings;
      if (rows == null) return null;
      count += rows.length;
    }
    return count;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 10),
      Row(children: [
        const Expanded(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            MintTickEyebrow('Your portfolio'),
            SizedBox(height: 5),
            Text('Portfolio', style: TextStyle(
              fontSize: 29, fontWeight: FontWeight.w800,
            )),
          ],
        )),
        IconButton(
          key: const ValueKey('portfolio-refresh'),
          onPressed: _loading ? null : _refresh,
          tooltip: 'Refresh portfolio',
          icon: const Icon(Icons.refresh_rounded),
        ),
      ]),
      const SizedBox(height: 3),
      Text(widget.mode.isPaper
        ? 'Virtual investments · Not real money'
        : 'Your broker investments and MintTick activity',
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
          color: scheme.onSurfaceVariant,
        )),
      const SizedBox(height: 8),
      MintTickModeSwitcher(mode: widget.mode),
      const SizedBox(height: 10),
      if (!widget.mode.isPaper) _tabs(),
      if (_loading) ...[
        const SizedBox(height: 14),
        const LinearProgressIndicator(minHeight: 2),
      ] else if (_error != null) ...[
        const SizedBox(height: 12),
        MintTickInfoText(_error!, icon: Icons.error_outline_rounded),
        const SizedBox(height: 9),
        OutlinedButton(onPressed: _refresh, child: const Text('Retry portfolio')),
      ] else if (widget.mode.isPaper) ...[
        _paperContent(),
      ] else ...[
        if (_tab == 'Overall') _liveOverview(),
        if (_tab == 'MintTick managed') _managedTab(),
        if (_tab == 'Broker accounts') _brokersTab(),
      ],
    ]);
  }

  Widget _tabs() {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(children: [
          for (final name in ['Overall', 'MintTick managed', 'Broker accounts'])
            Padding(
              padding: const EdgeInsets.only(right: 7),
              child: ChoiceChip(
                label: Text(name),
                selected: _tab == name,
                onSelected: (_) => setState(() => _tab = name),
                labelStyle: Theme.of(context).textTheme.labelMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
                backgroundColor: scheme.surfaceContainerLow,
                selectedColor: scheme.primary.withValues(alpha: .16),
                side: BorderSide(color: _tab == name
                    ? scheme.primary.withValues(alpha: .45)
                    : scheme.outlineVariant),
                visualDensity: VisualDensity.compact,
              ),
            ),
        ]),
      ),
    );
  }

  Widget _section(String title, List<Widget> children, {
      IconData icon = Icons.account_balance_wallet_outlined,
      VoidCallback? action, String? actionText}) {
    final scheme = Theme.of(context).colorScheme;
    return MintTickSurface(
      padding: const EdgeInsets.fromLTRB(13, 12, 13, 10),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(icon, color: scheme.primary, size: 19),
          const SizedBox(width: 9),
          Expanded(child: Text(title, style: Theme.of(context).textTheme.titleSmall
              ?.copyWith(fontWeight: FontWeight.w800))),
          if (action != null)
            TextButton(
              onPressed: action,
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 2),
                visualDensity: VisualDensity.compact,
              ),
              child: Text(actionText ?? 'View all', style:
                  Theme.of(context).textTheme.labelMedium
                      ?.copyWith(color: scheme.primary)),
            ),
        ]),
        ...children,
      ]),
    );
  }

  Widget _row(String title, String trailing, IconData icon, VoidCallback onTap) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(9),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 11, horizontal: 2),
        child: Row(children: [
          Icon(icon, size: 18, color: scheme.primary),
          const SizedBox(width: 11),
          Expanded(child: Text(title, style: Theme.of(context).textTheme.bodyMedium)),
          const SizedBox(width: 5),
          Flexible(child: Text(trailing,
              maxLines: 1, overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                color: scheme.onSurfaceVariant,
              ))),
          const SizedBox(width: 6),
          Icon(Icons.chevron_right_rounded,
              size: 17, color: scheme.onSurfaceVariant),
        ]),
      ),
    );
  }

  Widget _metric(String label, String value) {
    final scheme = Theme.of(context).colorScheme;
    return Expanded(child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 10),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(11),
        color: scheme.surface.withValues(alpha: .55),
        border: Border.all(color: scheme.outlineVariant.withValues(alpha: .65)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: Theme.of(context).textTheme.labelSmall?.copyWith(
            color: scheme.onSurfaceVariant)),
        const SizedBox(height: 5),
        Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w800,
          )),
      ]),
    ));
  }

  Widget _liveOverview() {
    final scheme = Theme.of(context).colorScheme;
    final connected = _brokers.where((b) => b.connected).length;
    final count = _totalCount(false);
    final positions = _totalCount(true);
    final value = _totalHoldingValue();
    final partial = _brokers.any((b) => !b.statusKnown ||
        (b.connected && (b.holdings == null || b.positions == null)));
    return Column(children: [
      MintTickSurface(
        accent: true, padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const MintTickEyebrow('Total holdings value'),
          const SizedBox(height: 6),
          Text(portfolioRupees(value),
            key: const ValueKey('portfolio-total-holdings'),
            style: Theme.of(context).textTheme.headlineMedium?.copyWith(
              fontWeight: FontWeight.w800,
            )),
          const SizedBox(height: 3),
          Text(connected == 0
            ? 'Connect a broker to verify live investments'
            : partial || value == null
              ? 'Valuation incomplete · no estimated total'
              : 'Broker-reported holdings · read-only',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            )),
          const SizedBox(height: 12),
          Row(children: [
            _metric('Holdings', count?.toString() ?? '—'),
            const SizedBox(width: 9),
            _metric('Open positions', positions?.toString() ?? '—'),
          ]),
        ]),
      ),
      const SizedBox(height: 10),
      _section('Connected brokers', [
        for (final broker in _brokers)
          _row(_name(broker.name),
            !broker.statusKnown ? 'Status unavailable'
              : broker.connected ? 'Connected' : 'Not connected',
            Icons.link_rounded,
            () => setState(() { _tab = 'Broker accounts'; _selectedBroker = broker.name; })),
      ], icon: Icons.link_rounded, action: widget.onBroker,
          actionText: 'Manage'),
      const SizedBox(height: 10),
      _section('Portfolio breakdown', [
        _row('All holdings', count == null ? 'Unavailable' : '$count assets',
          Icons.inventory_2_outlined, () => _openInstruments(false)),
        _row('Open positions', positions == null ? 'Unavailable' : '$positions positions',
          Icons.candlestick_chart_rounded, () => _openInstruments(true)),
        _row('Cash & margin', 'By broker', Icons.account_balance_wallet_outlined,
          _openCash),
      ], icon: Icons.layers_outlined),
      const SizedBox(height: 10),
      _section('MintTick managed exposure', [
        Padding(
          padding: const EdgeInsets.only(top: 8, bottom: 4),
          child: Text('No reconciled MintTick-initiated live fills are available. '
              'Broker holdings must not be labeled managed without order evidence.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant, height: 1.35,
              )),
        ),
      ], icon: Icons.psychology_outlined, action: () =>
        setState(() => _tab = 'MintTick managed'), actionText: 'Details'),
      const SizedBox(height: 11),
      SizedBox(width: double.infinity, child: FilledButton.icon(
        onPressed: () => _openInstruments(false),
        icon: const Icon(Icons.inventory_2_outlined),
        label: const Text('View all holdings'),
      )),
      const SizedBox(height: 8),
      SizedBox(width: double.infinity, child: OutlinedButton.icon(
        onPressed: widget.onBroker,
        icon: const Icon(Icons.account_balance_outlined),
        label: const Text('Manage connected brokers'),
      )),
    ]);
  }

  Widget _managedTab() => Column(children: [
    _section('MintTick managed portfolio', [
      const SizedBox(height: 7),
      const Text('No verified live MintTick executions yet.'),
      const SizedBox(height: 9),
      const MintTickInfoText(
        'MintTick-managed trades are a subset of holdings at your broker. '
        'We will label them only after matching actual order and fill IDs '
        'to a Trading Room. Other holdings remain broker-reported.',
        icon: Icons.verified_user_outlined,
      ),
    ], icon: Icons.psychology_outlined),
    const SizedBox(height: 10),
    SizedBox(width: double.infinity, child: OutlinedButton.icon(
      onPressed: widget.onRooms,
      icon: const Icon(Icons.layers_outlined),
      label: const Text('View Trading Rooms'),
    )),
    const SizedBox(height: 8),
    const MintTickInfoText('Live order placement is not authorized. '
      'Connecting a broker does not enable MintTick to trade.'),
  ]);

  Widget _brokersTab() {
    final broker = _source(_selectedBroker);
    final scheme = Theme.of(context).colorScheme;
    return Column(children: [
      _section('Broker accounts', [
        const SizedBox(height: 8),
        SegmentedButton<String>(
          key: const ValueKey('live-broker-selector'),
          showSelectedIcon: false,
          segments: const [
            ButtonSegment(value: 'ANGEL_ONE', label: Text('Angel One')),
            ButtonSegment(value: 'UPSTOX', label: Text('Upstox')),
          ],
          selected: {_selectedBroker},
          onSelectionChanged: (value) => setState(() => _selectedBroker = value.first),
        ),
        const SizedBox(height: 13),
        MintTickEyebrow('Live · ${_name(broker.name)}'),
        const SizedBox(height: 7),
        Text(!broker.statusKnown ? 'Connection status unavailable'
          : broker.connected ? 'Connected · Read-only' : 'Not connected',
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w800)),
        if (broker.account != null)
          Text('Account ${broker.account!}', style:
            Theme.of(context).textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant)),
        if (broker.connected) ...[
          const SizedBox(height: 11),
          Row(children: [
            _metric('Available funds', portfolioRupees(broker.cash)),
            const SizedBox(width: 9),
            _metric('Holdings', broker.holdings?.length.toString() ?? '—'),
          ]),
          const SizedBox(height: 10),
          _row('View broker holdings', 'Read-only', Icons.inventory_2_outlined,
              () => _openInstruments(false, brokerName: broker.name)),
          _row('View broker positions', 'Read-only', Icons.candlestick_chart_rounded,
              () => _openInstruments(true, brokerName: broker.name)),
          if (broker.cash == null || broker.holdings == null || broker.positions == null)
            const MintTickInfoText('Some broker responses are unavailable. '
                'Missing account data is not a zero balance.',
                icon: Icons.info_outline_rounded),
        ] else ...[
          const SizedBox(height: 8),
          Text(broker.statusKnown
              ? 'Connect this account from the secure broker page.'
              : 'Retry to check the account connection.',
              style: Theme.of(context).textTheme.bodySmall),
        ],
      ], icon: Icons.account_balance_outlined),
      const SizedBox(height: 10),
      SizedBox(width: double.infinity, child: FilledButton.icon(
        onPressed: widget.onBroker,
        icon: const Icon(Icons.link_rounded),
        label: const Text('Manage broker connections'),
      )),
      const SizedBox(height: 8),
      const MintTickInfoText('These are broker-reported account records. '
          'MintTick never combines Paper cash with real broker funds.'),
    ]);
  }

  Widget _paperContent() {
    final scheme = Theme.of(context).colorScheme;
    final cash = _paperAccounts.fold<int>(
      0, (sum, account) => sum + account.wallet.availableCashPaise);
    final reserved = _paperAccounts.fold<int>(
      0, (sum, account) => sum + account.wallet.reservedCashPaise);
    final realized = _paperAccounts.fold<int>(
      0, (sum, account) => sum + account.wallet.realizedPnlPaise);
    return Column(children: [
      MintTickSurface(accent: true, padding: const EdgeInsets.all(14), child:
        Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const MintTickEyebrow('Virtual Paper cash · Not real money'),
          const SizedBox(height: 6),
          Text(_formatPaise(cash),
            key: const ValueKey('paper-portfolio-available'),
            style: Theme.of(context).textTheme.headlineMedium?.copyWith(
              fontWeight: FontWeight.w800)),
          const SizedBox(height: 4),
          Text('Available across ${_paperAccounts.length} funded Paper rooms',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant)),
          const SizedBox(height: 12),
          Row(children: [
            _metric('Reserved cash', _formatPaise(reserved)),
            const SizedBox(width: 9),
            _metric('Realized P&L', _formatPaise(realized)),
          ]),
        ]),
      ),
      const SizedBox(height: 10),
      _section('Paper trading rooms', [
        if (_paperAccounts.isEmpty)
          Padding(padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text('No funded Paper rooms. Create and fund a virtual room '
              'to begin testing.',
              style: Theme.of(context).textTheme.bodySmall)),
        for (final account in _paperAccounts)
          _row(account.room.name, _formatPaise(account.wallet.availableCashPaise),
            Icons.layers_outlined, () => _openPaperRoom(account)),
      ], icon: Icons.layers_outlined, action: widget.onRooms),
      const SizedBox(height: 10),
      _section('Paper portfolio breakdown', [
        _row('Available virtual cash', _formatPaise(cash),
          Icons.account_balance_wallet_outlined, () => _openPaperBalances()),
        _row('Reserved virtual cash', _formatPaise(reserved),
          Icons.lock_outline_rounded, () => _openPaperBalances()),
        _row('Paper transaction ledger', 'By room',
          Icons.receipt_long_outlined, () => _openPaperBalances()),
      ], icon: Icons.pie_chart_outline_rounded),
      const SizedBox(height: 10),
      const MintTickInfoText('No customer Paper orders or strategy execution '
          'are enabled yet. Virtual cash must never be displayed as real funds.'),
      const SizedBox(height: 10),
      SizedBox(width: double.infinity, child: FilledButton.icon(
        onPressed: widget.onRooms,
        icon: const Icon(Icons.layers_outlined),
        label: const Text('Manage Paper Trading Rooms'),
      )),
    ]);
  }

  String _formatPaise(int paise) => portfolioRupees(paise / 100);

  void _open(String title, List<Widget> content) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (routeContext) => MintTickCustomerScaffold(showHeader: false, children: [
        Row(children: [
          IconButton(onPressed: () => Navigator.of(routeContext).pop(),
            icon: const Icon(Icons.arrow_back_rounded),
            tooltip: 'Back to Portfolio'),
          Expanded(child: Text(title, style: Theme.of(routeContext).textTheme.titleLarge
            ?.copyWith(fontWeight: FontWeight.w800))),
        ]),
        const SizedBox(height: 7),
        ...content,
      ]),
    ));
  }

  void _openInstruments(bool positions, {String? brokerName}) {
    final all = _records(positions).where(
      (row) => brokerName == null || row.broker == brokerName).toList();
    final unavailable = _brokers.any((b) =>
      (brokerName == null || b.name == brokerName) &&
      (!b.statusKnown || (b.connected &&
          (positions ? b.positions : b.holdings) == null)));
    _open(positions ? 'Open positions' : 'Holdings', [
      const MintTickInfoText('All rows below are broker-reported. '
          'Source attribution to MintTick requires reconciled execution IDs.'),
      const SizedBox(height: 10),
      if (unavailable)
        const MintTickInfoText('Some accounts could not be verified. '
            'This list may be incomplete.',
            icon: Icons.error_outline_rounded),
      if (all.isEmpty)
        MintTickSurface(child: Text(unavailable
          ? 'Broker data unavailable. Try refreshing Portfolio.'
          : 'No verified records in connected broker accounts.')),
      for (final item in all) ...[
        const SizedBox(height: 8),
        InkWell(onTap: () => _openInstrument(item), child:
          MintTickSurface(padding: const EdgeInsets.all(13),
            child: Row(children: [
              Expanded(child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(item.symbol, style: Theme.of(context).textTheme.titleSmall
                    ?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 4),
                  Text('${_name(item.broker)} · BROKER REPORTED',
                    style: Theme.of(context).textTheme.labelSmall),
                  const SizedBox(height: 4),
                  Text('Quantity: ${item.quantity?.toString() ?? '—'}'),
                ],
              )),
              Text(portfolioRupees(item.marketValue),
                style: Theme.of(context).textTheme.labelLarge),
              const Icon(Icons.chevron_right_rounded),
            ]),
          ),
        ),
      ],
    ]);
  }

  void _openInstrument(PortfolioInstrument item) {
    _open(item.name ?? item.symbol, [
      MintTickSurface(accent: true, child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
          MintTickEyebrow('${_name(item.broker)} · Broker reported'),
          const SizedBox(height: 10),
          Text('Market value: ${portfolioRupees(item.marketValue)}'),
          const SizedBox(height: 9),
          Text('Quantity: ${item.quantity?.toString() ?? '—'}'),
          const SizedBox(height: 7),
          Text('Last price: ${portfolioRupees(item.lastPrice)}'),
          const SizedBox(height: 7),
          Text('Average buy price: ${portfolioRupees(item.averagePrice)}'),
          const SizedBox(height: 7),
          Text('Broker P&L: ${portfolioRupees(item.pnl)}'),
        ],
      )),
      const SizedBox(height: 12),
      const MintTickInfoText('This is a broker-reported position. '
        'Its MintTick-managed portion is unknown until order and fill '
        'reconciliation is implemented. No trading action is authorized here.'),
    ]);
  }

  void _openCash() {
    _open('Cash & margin', [
      const MintTickInfoText('Available funds are reported per broker account. '
        'Do not add collateral or margin to holdings market value.'),
      const SizedBox(height: 10),
      for (final broker in _brokers) ...[
        MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(_name(broker.name), style: Theme.of(context).textTheme.titleSmall
              ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 7),
            Text(!broker.statusKnown ? 'Status unavailable'
              : !broker.connected ? 'Not connected'
              : portfolioRupees(broker.cash)),
          ],
        )),
        const SizedBox(height: 9),
      ],
    ]);
  }

  void _openPaperBalances() {
    _open('Virtual Paper ledger', [
      const MintTickInfoText('Paper balances are entirely simulated. '
        'Select a room to inspect its actual persisted cash events.'),
      for (final account in _paperAccounts)
        _row(account.room.name, _formatPaise(account.wallet.availableCashPaise),
          Icons.receipt_long_outlined, () => _openPaperRoom(account)),
      if (_paperAccounts.isEmpty) const MintTickInfoText('No funded rooms yet.'),
    ]);
  }

  void _openPaperRoom(_PaperAccount account) {
    _open(account.room.name, [
      MintTickSurface(accent: true, child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
          const MintTickEyebrow('Virtual wallet · Paper only'),
          const SizedBox(height: 9),
          Text('Available: ${_formatPaise(account.wallet.availableCashPaise)}'),
          const SizedBox(height: 7),
          Text('Initial: ${_formatPaise(account.wallet.initialCashPaise)}'),
          const SizedBox(height: 7),
          Text('Reserved: ${_formatPaise(account.wallet.reservedCashPaise)}'),
          const SizedBox(height: 7),
          Text('Realized P&L: ${_formatPaise(account.wallet.realizedPnlPaise)}'),
        ],
      )),
      const SizedBox(height: 12),
      const Text('Cash history', style: TextStyle(fontWeight: FontWeight.w800)),
      FutureBuilder<List<CustomerPaperCashEvent>>(
        future: widget.paperRepository.paperLedger(account.room.id),
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const LinearProgressIndicator(minHeight: 2);
          }
          if (snapshot.hasError) {
            return const MintTickInfoText('Cash ledger could not be verified.');
          }
          final events = snapshot.data ?? const <CustomerPaperCashEvent>[];
          if (events.isEmpty) return const Text('No recorded cash events.');
          return Column(children: [
            for (final event in events)
              _row(event.eventKind.replaceAll('_', ' '),
                  _formatPaise(event.cashDeltaPaise),
                Icons.receipt_long_outlined, () {}),
          ]);
        },
      ),
    ]);
  }
}
