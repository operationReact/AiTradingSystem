import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../data/customer_angel_one_repository.dart';
import '../data/customer_portfolio_models.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_broker_compact_widgets.dart';

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

class _MintTickAngelOneDashboardState
    extends State<MintTickAngelOneDashboard> {
  String _selected = 'holdings';
  final Map<String, Object?> _snapshots = {};
  final Map<String, String> _errors = {};
  final Set<String> _loading = {};
  int _epoch = 0;

  @override
  void initState() { super.initState(); _loadAll(); }

  @override
  void didUpdateWidget(covariant MintTickAngelOneDashboard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.repository != oldWidget.repository ||
        widget.connection.clientCodeMasked !=
            oldWidget.connection.clientCodeMasked ||
        widget.connection.connected != oldWidget.connection.connected) {
      ++_epoch;
      _snapshots.clear();
      _errors.clear();
      _loading.clear();
      _loadAll();
    }
  }

  @override
  void dispose() { ++_epoch; super.dispose(); }

  Future<Object?> _read(String section) => switch (section) {
    'holdings' => widget.repository.holdings(),
    'positions' => widget.repository.positions(),
    'funds' => widget.repository.funds(),
    'orders' => widget.repository.orders(),
    _ => Future<Object?>.value(null),
  };

  List<dynamic>? _rows(String section, Object? data) {
    final rows = portfolioRecords(data, section);
    if (rows != null) return rows;
    if (section == 'orders' && data is Map && data['orderBook'] is List) {
      return data['orderBook'] as List;
    }
    return null;
  }

  Future<void> _load(String section) async {
    if (!widget.connection.connected || _loading.contains(section)) return;
    final epoch = _epoch;
    setState(() { _loading.add(section); _errors.remove(section); });
    try {
      final data = await _read(section);
      if (!mounted || epoch != _epoch) return;
      final valid = switch (section) {
        'holdings' => PortfolioInstrument.decode(
            data, broker: 'ANGEL_ONE') != null,
        'positions' => PortfolioInstrument.decode(data,
            broker: 'ANGEL_ONE', position: true) != null,
        'funds' => portfolioAmount(data, broker: 'ANGEL_ONE') != null,
        'orders' => _rows('orders', data) != null,
        _ => false,
      };
      setState(() {
        if (valid) {
          _snapshots[section] = data;
        } else {
          _snapshots.remove(section);
          _errors[section] = 'Unverified broker response. '
              'Missing data is not a zero balance.';
        }
      });
    } catch (_) {
      if (mounted && epoch == _epoch) {
        setState(() {
          _snapshots.remove(section);
          _errors[section] = 'Angel One $section unavailable. '
              'Other broker data remains independent.';
        });
      }
    } finally {
      if (mounted && epoch == _epoch) {
        setState(() => _loading.remove(section));
      }
    }
  }

  void _loadAll() {
    for (final name in const ['holdings', 'positions', 'funds', 'orders']) {
      unawaited(_load(name));
    }
  }

  Future<void> _refresh() async {
    if (widget.busy) return;
    await widget.onRefresh();
    if (!mounted) return;
    ++_epoch;
    setState(() {
      _snapshots.clear();
      _errors.clear();
      _loading.clear();
    });
    _loadAll();
  }

  Future<void> _confirmDisconnect() async {
    if (widget.busy) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: const Text('Disconnect Angel One?'),
        content: const Text('This closes the read-only broker session. '
            'Protected AWS credentials remain on the server.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialog, false),
            child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(dialog, true),
            child: const Text('Disconnect')),
        ],
      ),
    );
    if (confirmed == true && mounted) await widget.onDisconnect();
  }

  void _showDetails() {
    showModalBottomSheet<void>(
      context: context, showDragHandle: true,
      builder: (sheet) {
        final t = Theme.of(sheet).textTheme;
        final muted = Theme.of(sheet).colorScheme.onSurfaceVariant;
        return SafeArea(child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 6, 22, 24),
          child: Column(mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Angel One connection', style: t.titleLarge),
            const SizedBox(height: 10),
            Text('Account ${widget.connection.clientCodeMasked ?? '—'}'),
            const SizedBox(height: 7),
            Text('Read-only link · Real-money order execution disabled',
              style: t.bodySmall?.copyWith(color: muted)),
            if (widget.connection.expiresAt != null) ...[
              const SizedBox(height: 7),
              Text('Session expires ${widget.connection.expiresAt!.toLocal()}',
                style: t.bodySmall?.copyWith(color: muted)),
            ],
            const SizedBox(height: 14),
            Text('Paper and Live portfolios are separate. Broker access '
                'does not automatically authorize orders.',
                style: t.bodySmall?.copyWith(color: muted)),
            const SizedBox(height: 12),
            TextButton.icon(
              key: const ValueKey('broker-disconnect'),
              onPressed: widget.busy ? null : () {
                Navigator.pop(sheet);
                _confirmDisconnect();
              },
              icon: const Icon(Icons.link_off_rounded),
              label: const Text('Disconnect Angel One'),
            ),
          ]),
        ));
      },
    );
  }

  num? _num(Object? raw) {
    if (raw is num && raw.toDouble().isFinite) return raw;
    if (raw is String) {
      final parsed = num.tryParse(raw.replaceAll(',', ''));
      if (parsed?.toDouble().isFinite == true) return parsed;
    }
    return null;
  }

  Widget _funds() {
    final data = _snapshots['funds'];
    if (data is! Map) return const Text('Funds unavailable.');
    final t = Theme.of(context).textTheme;
    const fields = [
      ('availablecash', 'Available cash'),
      ('net', 'Net balance'),
      ('availableintradaypayin', 'Intraday pay-in'),
      ('availablelimitmargin', 'Available limit margin'),
      ('collateral', 'Collateral'),
      ('utiliseddebits', 'Utilised debits'),
    ];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      for (final (key, label) in fields)
        if (_num(data[key]) != null)
          Padding(padding: const EdgeInsets.symmetric(vertical: 7),
            child: Row(children: [
              Expanded(child: Text(label, style: t.bodySmall)),
              Text(portfolioRupees(_num(data[key])),
                style: t.bodyMedium?.copyWith(fontWeight: FontWeight.w800)),
            ]),
          ),
      const SizedBox(height: 6),
      Text('Cash, collateral and margin are separate broker fields.',
        style: t.labelSmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant)),
    ]);
  }

  Widget _records(String section) {
    final rows = _rows(section, _snapshots[section]);
    if (rows == null) return const Text('Broker data unavailable.');
    if (rows.isEmpty) return Text('No $section reported by Angel One.');
    final t = Theme.of(context).textTheme;
    final muted = Theme.of(context).colorScheme.onSurfaceVariant;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      for (final row in rows.take(50))
        if (row is Map) Padding(
          padding: const EdgeInsets.symmetric(vertical: 7),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${row['tradingsymbol'] ?? row['trading_symbol'] ?? 'Broker record'}',
              style: t.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 3),
            Text('Qty ${row['quantity'] ?? row['netqty'] ?? '—'}'
                ' · Status ${row['status'] ?? '—'}',
              style: t.bodySmall?.copyWith(color: muted)),
            Divider(color: Theme.of(context).colorScheme.outlineVariant),
          ]),
        ),
      if (rows.length > 50)
        Text('Showing first 50 of ${rows.length} $section',
          style: t.bodySmall?.copyWith(color: muted)),
    ]);
  }

  List<Widget> _content(List<PortfolioInstrument>? holdings) {
    if (!_snapshots.containsKey(_selected)) return const [];
    return [
      if (_selected == 'holdings')
        MintTickBrokerHoldingsList(rows: holdings)
      else if (_selected == 'funds')
        _funds()
      else
        _records(_selected),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final t = Theme.of(context).textTheme;
    final holdings = PortfolioInstrument.decode(
        _snapshots['holdings'], broker: 'ANGEL_ONE');
    final value = portfolioHoldingValue(holdings);
    num? invested = holdings == null ? null : 0;
    num? pnl = holdings == null ? null : 0;
    for (final row in holdings ?? const <PortfolioInstrument>[]) {
      final qty = row.quantity;
      final avg = row.averagePrice;
      final cost = qty != null && qty >= 0 && avg != null && avg >= 0
          ? qty * avg : null;
      invested = invested != null && cost != null
          ? invested + cost : null;
      final linePnl = row.pnl ?? (cost != null && row.marketValue != null
          ? row.marketValue! - cost : null);
      pnl = pnl != null && linePnl != null ? pnl + linePnl : null;
    }
    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 8),
      MintTickBrokerHeader(
        logo: const AngelOneSymbol(size: 38),
        broker: 'Angel One',
        account: widget.connection.clientCodeMasked,
        connected: widget.connection.connected,
        onBack: widget.onBack,
        onRefresh: widget.busy ? null : _refresh,
        refreshKey: const ValueKey('angel-refresh-data'),
        menu: PopupMenuButton<String>(
          key: const ValueKey('angel-account-menu'),
          tooltip: 'Broker settings',
          icon: const Icon(Icons.more_vert_rounded, size: 21),
          onSelected: (_) => _showDetails(),
          itemBuilder: (context) => const [
            PopupMenuItem(value: 'details', child: Text('Account details')),
            PopupMenuItem(value: 'disconnect', child: Text('Disconnect…')),
          ],
        ),
      ),
      if (widget.busy) const LinearProgressIndicator(minHeight: 2),
      if (widget.error != null) ...[
        const SizedBox(height: 8),
        MintTickInfoText(widget.error!, icon: Icons.error_outline_rounded),
      ],
      const SizedBox(height: 12),
      MintTickBrokerPortfolioHero(
        broker: 'Angel One',
        value: value,
        invested: invested,
        pnl: pnl,
        holdingsCount: holdings?.length,
        isLoading: _loading.contains('holdings'),
        orderExecutionEnabled: widget.connection.orderExecutionEnabled,
        valueKey: const ValueKey('angel-portfolio-value'),
      ),
      const SizedBox(height: 12),
      MintTickBrokerTabs(
        selected: _selected,
        keyPrefix: 'angel-tab-',
        onSelected: (section) {
          setState(() => _selected = section);
          if (!_snapshots.containsKey(section) && !_loading.contains(section)) {
            unawaited(_load(section));
          }
        },
      ),
      if (_errors['funds'] != null && _selected != 'funds') ...[
        const SizedBox(height: 10),
        MintTickSurface(padding: const EdgeInsets.all(10),
          child: Row(children: [
            Icon(Icons.info_outline_rounded, size: 18,
              color: colors.onSurfaceVariant),
            const SizedBox(width: 9),
            Expanded(child: Text('Angel One funds unavailable. '
                'Holdings and positions are loaded independently.',
              style: t.bodySmall?.copyWith(
                color: colors.onSurfaceVariant))),
            IconButton(
              key: const ValueKey('angel-retry-funds'),
              tooltip: 'Retry funds',
              icon: const Icon(Icons.refresh_rounded, size: 18),
              onPressed: _loading.contains('funds')
                  ? null : () => _load('funds'),
            ),
          ]),
        ),
      ],
      const SizedBox(height: 9),
      MintTickBrokerSection(
        title: switch (_selected) {
          'holdings' => 'My holdings',
          'positions' => 'Positions',
          'funds' => 'Funds',
          _ => 'Orders',
        },
        loading: _loading.contains(_selected),
        error: _errors[_selected],
        refreshKey: const ValueKey('angel-retry-section'),
        onRefresh: () => _load(_selected),
        children: _content(holdings),
      ),
    ]);
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
