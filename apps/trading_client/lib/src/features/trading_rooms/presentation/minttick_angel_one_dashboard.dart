import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../brand/minttick_brand.dart';
import '../data/customer_angel_one_repository.dart';
import 'minttick_customer_widgets.dart';

/// Real API-backed rendering of the approved Angel One broker mockups.
///
/// All figures are parsed from the authenticated account API; an unavailable
/// figure is displayed as a dash, never filled with fictional demo data.
/// Broker credentials, tokens, and order execution do not cross this view.
enum MintTickAngelPanel { overview, funds, holdings, positions, orders }

class MintTickAngelOneDashboard extends StatefulWidget {
  const MintTickAngelOneDashboard({
    required this.repository,
    required this.connection,
    required this.busy,
    required this.onRefresh,
    required this.onDisconnect,
    this.onBack,
    this.error,
    super.key,
  });

  final HttpCustomerAngelOneRepository repository;
  final CustomerAngelOneConnection connection;
  final bool busy;
  final String? error;
  final Future<void> Function() onRefresh;
  final Future<void> Function() onDisconnect;
  final VoidCallback? onBack;

  @override
  State<MintTickAngelOneDashboard> createState() =>
      _MintTickAngelOneDashboardState();
}

class _MintTickAngelOneDashboardState extends State<MintTickAngelOneDashboard> {
  MintTickAngelPanel _panel = MintTickAngelPanel.overview;
  int _orderTab = 0;
  final Map<MintTickAngelPanel, Object?> _payloads = {};
  final Map<MintTickAngelPanel, String> _failures = {};
  final Set<MintTickAngelPanel> _loading = {};

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  Future<void> _loadAll() async {
    await Future.wait([
      _load(MintTickAngelPanel.funds),
      _load(MintTickAngelPanel.holdings),
      _load(MintTickAngelPanel.positions),
      _load(MintTickAngelPanel.orders),
    ]);
  }

  Future<void> _load(MintTickAngelPanel panel) async {
    if (_loading.contains(panel)) return;
    if (mounted) setState(() { _loading.add(panel); _failures.remove(panel); });
    try {
      final Object? result = switch (panel) {
        MintTickAngelPanel.funds => await widget.repository.funds(),
        MintTickAngelPanel.holdings => await widget.repository.holdings(),
        MintTickAngelPanel.positions => await widget.repository.positions(),
        MintTickAngelPanel.orders => await widget.repository.orders(),
        MintTickAngelPanel.overview => null,
      };
      if (mounted) setState(() { _payloads[panel] = result; });
    } catch (_) {
      if (mounted) {
        setState(() {
          _payloads.remove(panel);
          _failures[panel] = 'Unable to read this information from Angel One. Retry to refresh.';
        });
      }
    } finally {
      if (mounted) setState(() { _loading.remove(panel); });
    }
  }

  Future<void> _refresh() async {
    if (widget.busy) return;
    await widget.onRefresh();
    if (mounted) await _loadAll();
  }

  void _open(MintTickAngelPanel panel) {
    setState(() { _panel = panel; });
    if (panel != MintTickAngelPanel.overview &&
        !_payloads.containsKey(panel) && !_loading.contains(panel)) {
      _load(panel);
    }
  }

  Future<void> _confirmDisconnect() async {
    if (widget.busy) return;
    final consent = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Disconnect Angel One?'),
        content: const Text(
          'This closes the current read-only broker session. '
          'Your saved AWS credentials remain protected on the server.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Disconnect'),
          ),
        ],
      ),
    );
    if (consent == true && mounted) await widget.onDisconnect();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ink = theme.colorScheme.onSurface;
    final muted = theme.colorScheme.onSurfaceVariant;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        if (_panel != MintTickAngelPanel.overview) {
          _open(MintTickAngelPanel.overview);
        } else {
          widget.onBack?.call();
        }
      },
      child: MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 10),
      const Align(
        alignment: Alignment.centerLeft,
        child: MintTickLockup(markWidth: 32, fontSize: 20),
      ),
      const SizedBox(height: 16),
      Row(children: [
        IconButton(
          key: const ValueKey('angel-back-overview'),
          tooltip: _panel == MintTickAngelPanel.overview
              ? 'Back to connected brokers'
              : 'Back to Angel One overview',
          onPressed: _panel == MintTickAngelPanel.overview
              ? widget.onBack
              : () => _open(MintTickAngelPanel.overview),
          icon: const Icon(Icons.arrow_back_rounded),
        ),
        Expanded(
          child: Text(
            'Angel One',
            style: theme.textTheme.headlineSmall?.copyWith(
              color: ink, fontWeight: FontWeight.w800),
          ),
        ),
        const MintTickStatusLabel('CONNECTED'),
      ]),
      const SizedBox(height: 17),
      _brokerHero(),
      if (widget.error != null) ...[
        const SizedBox(height: 12),
        MintTickInfoText(widget.error!, icon: Icons.error_outline_rounded),
      ],
      if (widget.busy) ...[
        const SizedBox(height: 10),
        const LinearProgressIndicator(minHeight: 2),
      ],
      const SizedBox(height: 26),
      ...switch (_panel) {
        MintTickAngelPanel.overview => _overview(),
        MintTickAngelPanel.funds => _funds(),
        MintTickAngelPanel.holdings => _holdings(),
        MintTickAngelPanel.positions => _positions(),
        MintTickAngelPanel.orders => _orders(),
      },
      const SizedBox(height: 18),
      Row(children: [
        Expanded(child: OutlinedButton.icon(
          key: const ValueKey('angel-refresh-data'),
          onPressed: widget.busy ? null : _refresh,
          icon: const Icon(Icons.refresh_rounded),
          label: const Text('Refresh data'),
        )),
      ]),
      const SizedBox(height: 20),
      Text('Read-only access · MintTick never enables order execution from this screen.',
        style: theme.textTheme.bodySmall?.copyWith(color: muted)),
      ]),
    );
  }

  Widget _brokerHero() {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final connection = widget.connection;
    return MintTickSurface(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const AngelOneSymbol(size: 55),
          const SizedBox(width: 13),
          Expanded(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Angel One SmartAPI',
                style: theme.textTheme.titleMedium
                    ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 5),
              Text('Account: ${connection.clientCodeMasked ?? 'Verified'}',
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: scheme.onSurfaceVariant)),
              const SizedBox(height: 9),
              _pill('Read-only connection', Icons.lock_outline_rounded),
              const SizedBox(height: 8),
              Text('Live order placement not yet available',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
            ],
          )),
        ]),
        const SizedBox(height: 13),
        Divider(color: scheme.outlineVariant, height: 1),
        const SizedBox(height: 12),
        Row(children: [
          Icon(Icons.schedule_rounded, size: 20, color: scheme.onSurfaceVariant),
          const SizedBox(width: 10),
          Expanded(child: Text('Session expiry',
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: scheme.onSurfaceVariant))),
          Flexible(child: Text(
            _expiry(connection.expiresAt),
            textAlign: TextAlign.end,
            style: theme.textTheme.bodyMedium
                ?.copyWith(fontWeight: FontWeight.w700),
          )),
        ]),
      ]),
    );
  }

  List<Widget> _overview() {
    final theme = Theme.of(context);
    final funds = _map(_payloads[MintTickAngelPanel.funds]);
    final holdingCount = _count(MintTickAngelPanel.holdings);
    final positionCount = _count(MintTickAngelPanel.positions);
    final orderCount = _count(MintTickAngelPanel.orders);
    return [
      _heading('Live account summary',
          'Your connected Angel One account at a glance.'),
      const SizedBox(height: 16),
      LayoutBuilder(builder: (context, constraints) {
        final tileWidth = (constraints.maxWidth - 10) / 2;
        return Wrap(spacing: 10, runSpacing: 10, children: [
          _summaryTile(tileWidth, 'Funds',
              _amount(funds, ['availablecash', 'cash', 'net']),
              Icons.account_balance_wallet_outlined,
              MintTickAngelPanel.funds),
          _summaryTile(tileWidth, 'Holdings', holdingCount,
              Icons.layers_outlined, MintTickAngelPanel.holdings),
          _summaryTile(tileWidth, 'Positions', positionCount,
              Icons.trending_up_rounded, MintTickAngelPanel.positions),
          _summaryTile(tileWidth, 'Orders', orderCount,
              Icons.receipt_long_outlined, MintTickAngelPanel.orders),
        ]);
      }),
      const SizedBox(height: 25),
      _heading('Connection details', 'Secure, owner-scoped broker access.'),
      const SizedBox(height: 13),
      MintTickSurface(child: Column(children: [
        _detailRow(Icons.account_balance_outlined, 'Broker', 'Angel One'),
        _detailRow(Icons.person_outline_rounded, 'Account',
            widget.connection.clientCodeMasked ?? 'Verified'),
        _detailRow(Icons.shield_outlined, 'Access', 'Read-only'),
        _detailRow(Icons.schedule_outlined, 'Session',
            _expiry(widget.connection.expiresAt)),
        _detailRow(Icons.verified_user_outlined, 'Security',
            'Verified Cognito owner', last: true),
      ])),
      const SizedBox(height: 21),
      Row(children: [
        Expanded(child: FilledButton.icon(
          key: const ValueKey('angel-view-live-data'),
          onPressed: () => _open(MintTickAngelPanel.funds),
          icon: const Icon(Icons.arrow_outward_rounded),
          label: const Text('View live data'),
        )),
        const SizedBox(width: 10),
        Expanded(child: OutlinedButton.icon(
          key: const ValueKey('broker-disconnect'),
          onPressed: widget.busy ? null : _confirmDisconnect,
          icon: const Icon(Icons.link_off_rounded),
          label: const Text('Disconnect'),
        )),
      ]),
      const SizedBox(height: 11),
      Text('Account balances and activity are shown only when returned by the broker.',
        style: theme.textTheme.bodySmall
            ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
    ];
  }

  List<Widget> _funds() {
    final data = _map(_payloads[MintTickAngelPanel.funds]);
    return [
      _heading('Funds & Margin',
          'Cash and available margin reported by Angel One.'),
      const SizedBox(height: 14),
      _sectionState(MintTickAngelPanel.funds),
      LayoutBuilder(builder: (context, constraints) {
        final tileWidth = (constraints.maxWidth - 10) / 2;
        return Wrap(spacing: 10, runSpacing: 10, children: [
          _figureTile(tileWidth, 'Net',
              _amount(data, ['net']), Icons.account_balance_outlined),
          _figureTile(tileWidth, 'Available Cash',
              _amount(data, ['availablecash', 'cash']), Icons.wallet_outlined),
          _figureTile(tileWidth, 'Intraday Payin',
              _amount(data, ['availableintradaypayin']),
              Icons.swap_vert_rounded),
          _figureTile(tileWidth, 'Limit Margin',
              _amount(data, ['availablelimitmargin']),
              Icons.layers_outlined),
          _figureTile(tileWidth, 'Collateral',
              _amount(data, ['collateral']), Icons.shield_outlined),
          _figureTile(tileWidth, 'Utilised Debits',
              _amount(data, ['utiliseddebits']), Icons.receipt_long_outlined),
        ]);
      }),
      const SizedBox(height: 23),
      _heading('Cash overview', 'Latest broker-reported snapshot.'),
      const SizedBox(height: 12),
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_amount(data, ['availablecash', 'cash']),
              style: Theme.of(context).textTheme.headlineMedium
                  ?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 12),
          Row(children: [
            Icon(Icons.timeline_rounded,
                color: Theme.of(context).colorScheme.onSurfaceVariant),
            const SizedBox(width: 10),
            Expanded(child: Text(
              'A historical cash time-series is not supplied by this broker endpoint. '
              'No simulated performance chart is displayed.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant),
            )),
          ]),
        ],
      )),
      const SizedBox(height: 24),
      _heading('Margin insights', 'Current balances, not trading recommendations.'),
      const SizedBox(height: 13),
      MintTickSurface(child: Column(children: [
        _detailRow(Icons.bolt_outlined, 'Margin headroom',
            _amount(data, ['availablelimitmargin'])),
        _detailRow(Icons.percent_rounded, 'Intraday payin',
            _amount(data, ['availableintradaypayin']), last: true),
      ])),
    ];
  }

  List<Widget> _holdings() {
    final rows = _rows(MintTickAngelPanel.holdings);
    final valuation = <double>[];
    for (final row in rows) {
      final qty = _num(_field(row, ['quantity', 'qty']));
      final ltp = _num(_field(row, ['ltp', 'lasttradedprice', 'close']));
      final existing = _num(_field(row, ['currentvalue', 'holdingvalue']));
      final value = existing ?? ((qty != null && ltp != null) ? qty * ltp : null);
      if (value != null && value >= 0) valuation.add(value);
    }
    final canValue = rows.isNotEmpty && valuation.length == rows.length;
    final sum = valuation.fold<double>(0, (a, b) => a + b);
    final pnl = rows.map((r) => _num(_field(r, ['pnl', 'profitandloss']))).toList();
    final canPnl = rows.isNotEmpty && pnl.every((value) => value != null);
    final totalPnl = pnl.fold<double>(0, (a, b) => a + (b ?? 0));
    return [
      _heading('Portfolio value', 'Holdings returned by your connected broker.'),
      const SizedBox(height: 14),
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(canValue ? _money(sum) : '—',
            style: Theme.of(context).textTheme.headlineMedium
                ?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          Text(canPnl ? 'Total reported P&L: ${_money(totalPnl)}'
                  : 'Total P&L not available for every holding',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant)),
          const SizedBox(height: 15),
          if (canValue && sum > 0) ...[
            Text('Allocation by market value',
              style: Theme.of(context).textTheme.titleSmall
                  ?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            ...rows.asMap().entries.take(8).map((entry) {
              final value = valuation[entry.key];
              final ratio = (value / sum).clamp(0.0, 1.0).toDouble();
              return Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Column(children: [
                  Row(children: [
                    Expanded(child: Text(_symbol(entry.value),
                        maxLines: 1, overflow: TextOverflow.ellipsis)),
                    Text('${(ratio * 100).toStringAsFixed(1)}%'),
                  ]),
                  const SizedBox(height: 5),
                  LinearProgressIndicator(
                    value: ratio, minHeight: 5,
                    borderRadius: BorderRadius.circular(5),
                    backgroundColor: Theme.of(context).colorScheme.surfaceContainerHigh,
                  ),
                ]),
              );
            }),
            if (rows.length > 8)
              Text('Showing 8 of ${rows.length} allocation rows'),
          ] else
            Text('Allocation requires verified quantities and prices.',
                style: Theme.of(context).textTheme.bodySmall),
        ],
      )),
      const SizedBox(height: 14),
      LayoutBuilder(builder: (context, constraints) {
        final tileWidth = (constraints.maxWidth - 10) / 2;
        return Wrap(spacing: 10, runSpacing: 10, children: [
          _figureTile(tileWidth, 'Total Holdings',
              _count(MintTickAngelPanel.holdings), Icons.layers_outlined),
          _figureTile(tileWidth, 'Portfolio Value',
              canValue ? _money(sum) : '—', Icons.pie_chart_outline_rounded),
          _figureTile(tileWidth, 'Reported P&L',
              canPnl ? _money(totalPnl) : '—', Icons.trending_up_rounded),
          _figureTile(tileWidth, 'Allocation Count',
              canValue ? rows.length.toString() : '—',
              Icons.donut_small_outlined),
        ]);
      }),
      const SizedBox(height: 25),
      _heading("Holdings (${_loading.contains(MintTickAngelPanel.holdings) ? '…' : rows.length})",
          'Positions owned in your Angel One account.'),
      const SizedBox(height: 12),
      _sectionState(MintTickAngelPanel.holdings),
      if (rows.isEmpty && !_loading.contains(MintTickAngelPanel.holdings) &&
          !_failures.containsKey(MintTickAngelPanel.holdings))
        _empty('No holdings', 'Your broker returned no holdings for this account.'),
      ...rows.map((row) => _securityRow(row, isOrder: false)),
    ];
  }

  List<Widget> _positions() {
    final rows = _rows(MintTickAngelPanel.positions);
    return [
      _heading('Positions',
          'Your open and carried-forward broker positions.'),
      const SizedBox(height: 13),
      _sectionState(MintTickAngelPanel.positions),
      if (rows.isEmpty && !_loading.contains(MintTickAngelPanel.positions) &&
          !_failures.containsKey(MintTickAngelPanel.positions))
        _empty('No positions', 'Angel One has not returned any positions.'),
      ...rows.map((row) => _securityRow(row, isOrder: false)),
    ];
  }

  List<Widget> _orders() {
    final all = _rows(MintTickAngelPanel.orders);
    final open = all.where((row) {
      final state = _field(row, ['status', 'orderstatus'])?.toString()
          .toLowerCase() ?? '';
      return state.contains('open') || state.contains('pending') ||
          state.contains('trigger') || state.contains('placed');
    }).toList();
    final historic = all.where((row) => !open.contains(row)).toList();
    final display = _orderTab == 0 ? open : historic;
    return [
      _heading('Orders & Activity',
          'Broker order book, history and session details.'),
      const SizedBox(height: 14),
      Wrap(spacing: 8, runSpacing: 8, children: [
        _tab('Open Orders', 0), _tab('History', 1), _tab('Activity', 2),
      ]),
      const SizedBox(height: 15),
      _sectionState(MintTickAngelPanel.orders),
      if (_orderTab == 2)
        _empty('Account activity', 'This broker API does not currently provide '
          'a separate sync or session-event history. '
          'No fictional events will be shown.')
      else if (display.isEmpty && !_loading.contains(MintTickAngelPanel.orders) &&
          !_failures.containsKey(MintTickAngelPanel.orders))
        _empty(_orderTab == 0 ? 'No open orders' : 'No order history',
          'No matching orders were returned by the broker.')
      else
        ...display.map((row) => _securityRow(row, isOrder: true)),
      const SizedBox(height: 24),
      _heading('Session & security', 'Protected account access.'),
      const SizedBox(height: 12),
      MintTickSurface(child: Column(children: [
        _detailRow(Icons.lock_outline_rounded, 'Access', 'Read-only'),
        _detailRow(Icons.schedule_rounded, 'Next re-authentication',
            _expiry(widget.connection.expiresAt)),
        _detailRow(Icons.shield_outlined, 'Order execution', 'Disabled',
            last: true),
      ])),
    ];
  }

  Widget _tab(String label, int index) {
    final selected = _orderTab == index;
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => setState(() { _orderTab = index; }),
      showCheckmark: false,
    );
  }

  Widget _heading(String title, String subtitle) {
    final theme = Theme.of(context);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: theme.textTheme.titleLarge
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 6),
      Text(subtitle, style: theme.textTheme.bodyMedium
          ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
    ]);
  }

  Widget _pill(String text, IconData icon) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: scheme.primary.withValues(alpha: 0.11),
        border: Border.all(color: scheme.primary.withValues(alpha: 0.25)),
        borderRadius: BorderRadius.circular(50),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 16, color: scheme.onSurface),
        const SizedBox(width: 6),
        Flexible(child: Text(text, style: Theme.of(context).textTheme.labelSmall)),
      ]),
    );
  }

  Widget _summaryTile(double width, String label, String value,
      IconData icon, MintTickAngelPanel panel) {
    final theme = Theme.of(context);
    return SizedBox(width: width, child: Material(
      color: theme.colorScheme.surfaceContainerLow,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: theme.colorScheme.outlineVariant),
      ),
      child: InkWell(
        key: ValueKey('angel-open-${panel.name}'),
        borderRadius: BorderRadius.circular(16),
        onTap: () => _open(panel),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 14),
          child: Row(children: [
            _smallIcon(icon),
            const SizedBox(width: 8),
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: theme.textTheme.labelMedium,
                  maxLines: 1, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 5),
                Text(value, style: theme.textTheme.titleSmall
                    ?.copyWith(fontWeight: FontWeight.w800),
                  maxLines: 1, overflow: TextOverflow.ellipsis),
              ],
            )),
            Icon(Icons.chevron_right_rounded, size: 17,
                color: theme.colorScheme.onSurfaceVariant),
          ]),
        ),
      ),
    ));
  }

  Widget _figureTile(double width, String label, String value, IconData icon) {
    final theme = Theme.of(context);
    return SizedBox(width: width, child: MintTickSurface(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 14),
      child: Row(children: [
        _smallIcon(icon),
        const SizedBox(width: 8),
        Expanded(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: theme.textTheme.labelMedium,
              maxLines: 2, overflow: TextOverflow.ellipsis),
            const SizedBox(height: 5),
            Text(value, style: theme.textTheme.titleSmall
                ?.copyWith(fontWeight: FontWeight.w800),
              maxLines: 1, overflow: TextOverflow.ellipsis),
          ],
        )),
      ]),
    ));
  }

  Widget _smallIcon(IconData icon) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      height: 36, width: 36,
      decoration: BoxDecoration(
        color: scheme.primary.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(10)),
      child: Icon(icon, size: 20, color: scheme.primary),
    );
  }

  Widget _detailRow(IconData icon, String label, String value,
      {bool last = false}) {
    final theme = Theme.of(context);
    return Column(children: [
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 11),
        child: Row(children: [
          Icon(icon, size: 19, color: theme.colorScheme.onSurfaceVariant),
          const SizedBox(width: 9),
          Expanded(flex: 4, child: Text(label,
            style: theme.textTheme.bodySmall
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant))),
          const SizedBox(width: 5),
          Expanded(flex: 5, child: Text(value, textAlign: TextAlign.end,
            style: theme.textTheme.bodySmall
              ?.copyWith(fontWeight: FontWeight.w700))),
        ]),
      ),
      if (!last) Divider(height: 1, color: theme.colorScheme.outlineVariant),
    ]);
  }

  Widget _sectionState(MintTickAngelPanel panel) {
    if (_loading.contains(panel)) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 12),
        child: LinearProgressIndicator(minHeight: 2),
      );
    }
    final failure = _failures[panel];
    if (failure == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: MintTickSurface(child: Row(children: [
        const Icon(Icons.error_outline_rounded),
        const SizedBox(width: 9),
        Expanded(child: Text(failure)),
        TextButton(onPressed: () => _load(panel), child: const Text('Retry')),
      ])),
    );
  }

  Widget _empty(String title, String detail) {
    final theme = Theme.of(context);
    return MintTickSurface(child: Column(children: [
      Icon(Icons.inbox_outlined, size: 35, color: theme.colorScheme.onSurfaceVariant),
      const SizedBox(height: 10),
      Text(title, style: theme.textTheme.titleMedium),
      const SizedBox(height: 6),
      Text(detail, textAlign: TextAlign.center,
          style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant)),
    ]));
  }

  Widget _securityRow(Map<String, dynamic> row, {required bool isOrder}) {
    final theme = Theme.of(context);
    final side = _field(row, ['transactiontype', 'side'])?.toString();
    final status = _field(row, ['status', 'orderstatus'])?.toString();
    final quantity = _field(row, ['quantity', 'qty', 'netqty']);
    final average = _num(_field(row, ['averageprice', 'avgnetprice', 'avgprice']));
    final last = _num(_field(row, ['ltp', 'lasttradedprice']));
    final pnl = _num(_field(row, ['pnl', 'profitandloss']));
    final details = <String>[
      if (side != null && side.isNotEmpty) side.toUpperCase(),
      if (quantity != null) 'Qty $quantity',
      if (average != null) 'Avg ${_money(average)}',
      if (last != null) 'LTP ${_money(last)}',
    ];
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: MintTickSurface(child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          _smallIcon(isOrder ? Icons.receipt_long_outlined
              : Icons.candlestick_chart_outlined),
          const SizedBox(width: 11),
          Expanded(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(_symbol(row), maxLines: 1, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleSmall
                    ?.copyWith(fontWeight: FontWeight.w800)),
              if (details.isNotEmpty) ...[
                const SizedBox(height: 5),
                Text(details.join(' · '), maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant)),
              ],
            ],
          )),
          const SizedBox(width: 6),
          Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            if (isOrder && status != null)
              Text(status, style: theme.textTheme.labelSmall
                  ?.copyWith(color: theme.colorScheme.primary)),
            if (!isOrder && pnl != null)
              Text(_money(pnl), style: theme.textTheme.labelMedium?.copyWith(
                  color: pnl < 0 ? theme.colorScheme.error
                      : theme.colorScheme.primary,
                  fontWeight: FontWeight.w800)),
          ]),
        ],
      )),
    );
  }

  String _count(MintTickAngelPanel panel) {
    if (_loading.contains(panel) || _failures.containsKey(panel) ||
        !_payloads.containsKey(panel)) { return '—'; }
    return _rows(panel).length.toString();
  }

  List<Map<String, dynamic>> _rows(MintTickAngelPanel panel) {
    final data = _payloads[panel];
    final key = switch (panel) {
      MintTickAngelPanel.holdings => 'holdings',
      MintTickAngelPanel.positions => 'positions',
      MintTickAngelPanel.orders => 'orders',
      _ => 'data',
    };
    Object? value = data;
    if (value is Map) {
      value = value[key] ?? value['orderBook'] ?? value['data'];
    }
    if (value is! List) return const [];
    return value.whereType<Map>().map((x) => Map<String, dynamic>.from(x)).toList();
  }

  Map<String, dynamic> _map(Object? value) {
    if (value is Map<String, dynamic>) return value;
    if (value is Map) return Map<String, dynamic>.from(value);
    return const {};
  }

  String _symbol(Map<String, dynamic> row) {
    return _field(row, [
      'tradingsymbol', 'tradingsymbolname', 'symbol', 'symbolname',
      'trading_symbol', 'instrumentname', 'name',
    ])?.toString() ?? 'Broker record';
  }

  Object? _field(Map<String, dynamic> map, List<String> possibilities) {
    final normalized = <String, Object?>{};
    for (final entry in map.entries) {
      normalized[entry.key.toLowerCase().replaceAll(RegExp('[^a-z0-9]'), '')] =
          entry.value;
    }
    for (final key in possibilities) {
      final value = normalized[key.toLowerCase()
          .replaceAll(RegExp('[^a-z0-9]'), '')];
      if (value != null) return value;
    }
    return null;
  }

  double? _num(Object? value) {
    if (value is num) return value.toDouble();
    if (value is String) {
      return double.tryParse(value.replaceAll(',', '').trim());
    }
    return null;
  }

  String _amount(Map<String, dynamic> value, List<String> keys) =>
      _money(_num(_field(value, keys)));

  String _money(num? value) {
    if (value == null || !value.isFinite) return '—';
    final negative = value < 0;
    final parts = value.abs().toStringAsFixed(2).split('.');
    final digits = parts.first;
    var head = digits;
    var grouped = '';
    if (digits.length > 3) {
      final tail = digits.substring(digits.length - 3);
      head = digits.substring(0, digits.length - 3);
      while (head.length > 2) {
        grouped = ',${head.substring(head.length - 2)}$grouped';
        head = head.substring(0, head.length - 2);
      }
      grouped = '$head$grouped,$tail';
    } else {
      grouped = digits;
    }
    return "${negative ? '−' : ''}₹$grouped.${parts.last}";
  }

  String _expiry(DateTime? at) {
    if (at == null) return 'Not provided';
    final local = at.toLocal();
    final date = local.toIso8601String().replaceFirst('T', ' ');
    return date.split('.').first;
  }
}

/// Offline-safe vector broker symbol. An approved official asset may replace
/// the mark without changing layout, semantics, or the MintTick theme.
class AngelOneSymbol extends StatelessWidget {
  const AngelOneSymbol({this.size = 46, super.key});
  final double size;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      image: true,
      label: 'Angel One broker mark',
      child: Container(
        width: size, height: size,
        padding: EdgeInsets.all(size * .15),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.primary.withValues(alpha: .11),
          borderRadius: BorderRadius.circular(size * .22),
        ),
        child: CustomPaint(painter: _AngelOneMarkPainter()),
      ),
    );
  }
}

class _AngelOneMarkPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final green = Paint()
      ..color = const Color(0xFF3D9567)
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..strokeWidth = math.max(2.0, size.width * .14);
    final orange = Paint()
      ..color = const Color(0xFFF38D39)
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..strokeWidth = math.max(2.0, size.width * .13);
    final shape = Path()
      ..moveTo(size.width * .1, size.height * .8)
      ..lineTo(size.width * .45, size.height * .12)
      ..lineTo(size.width * .85, size.height * .8);
    canvas.drawPath(shape, green);
    canvas.drawLine(
      Offset(size.width * .52, size.height * .54),
      Offset(size.width * .84, size.height * .82), orange);
  }

  @override
  bool shouldRepaint(covariant _AngelOneMarkPainter oldDelegate) => false;
}
