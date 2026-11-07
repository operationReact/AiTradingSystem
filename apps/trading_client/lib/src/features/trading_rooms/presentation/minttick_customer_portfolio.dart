import 'package:flutter/material.dart';

import '../data/customer_angel_one_repository.dart';
import '../data/customer_room_repository.dart';
import '../domain/trading_room_draft.dart';
import '../domain/trading_workspace_mode.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_mode_switcher.dart';

/// Authoritative mode-specific portfolio overview. Paper funds are from the
/// customer ledger. LIVE funds/holdings are from the user's own Angel One
/// session. Planned room budgets never appear as actual cash or P&L.
class MintTickCustomerPortfolioPage extends StatefulWidget {
  const MintTickCustomerPortfolioPage({
    required this.mode,
    required this.rooms,
    required this.paperRepository,
    required this.brokerRepository,
    required this.onRooms,
    required this.onBroker,
    super.key,
  });

  final TradingWorkspaceMode mode;
  final TradingRoomDraftStore rooms;
  final HttpCustomerRoomRepository paperRepository;
  final HttpCustomerAngelOneRepository brokerRepository;
  final VoidCallback onRooms;
  final VoidCallback onBroker;

  @override
  State<MintTickCustomerPortfolioPage> createState() =>
      _MintTickCustomerPortfolioPageState();
}

class _MintTickCustomerPortfolioPageState extends State<MintTickCustomerPortfolioPage> {
  bool _loading = true;
  String? _error;
  int _paperCashPaise = 0;
  int _fundedCount = 0;
  CustomerAngelOneConnection? _broker;
  String? _liveAvailableCash;
  int? _holdingsCount;
  int? _positionsCount;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    widget.mode.addListener(_onModeChanged);
    _refresh();
  }

  @override
  void dispose() {
    ++_generation;
    widget.mode.removeListener(_onModeChanged);
    super.dispose();
  }

  void _onModeChanged() => _refresh();

  Future<void> _refresh() async {
    final generation = ++_generation;
    if (mounted) setState(() {
      _loading = true;
      _error = null;
      _broker = null;
      _liveAvailableCash = null;
      _holdingsCount = null;
      _positionsCount = null;
      _paperCashPaise = 0;
      _fundedCount = 0;
    });
    try {
      if (widget.mode.isPaper) {
        if (!widget.rooms.loaded) await widget.rooms.load();
        if (widget.rooms.lastError != null) throw StateError('Room list unavailable');
        final funded = widget.rooms.drafts
            .where((r) => r.mode == 'PAPER' && r.status == 'PAPER_FUNDED')
            .toList();
        // Only successful signed, tenant-scoped backend responses are summed.
        final wallets = await Future.wait(
          funded.map((room) => widget.paperRepository.paperAccount(room.id)),
        );
        if (!mounted || generation != _generation) return;
        setState(() {
          _paperCashPaise = wallets.fold(0, (sum, w) => sum + w.availableCashPaise);
          _fundedCount = funded.length;
        });
      } else {
        final status = await widget.brokerRepository.status();
        if (!mounted || generation != _generation) return;
        if (status.connected) {
          final payload = await Future.wait<Object?>([
            widget.brokerRepository.funds(),
            widget.brokerRepository.holdings(),
            widget.brokerRepository.positions(),
          ]);
          if (!mounted || generation != _generation) return;
          final funds = payload[0];
          final account = funds is Map ? funds : null;
          final rawCash = account?['availablecash'] ?? account?['availableCash'];
          final holdings = payload[1], positions = payload[2];
          setState(() {
            _broker = status;
            _liveAvailableCash = rawCash is num || rawCash is String
                ? rawCash.toString() : null;
            _holdingsCount = _countRows(holdings, 'holdings');
            _positionsCount = _countRows(positions, 'positions');
          });
        } else {
          setState(() => _broker = status);
        }
      }
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error = 'Portfolio data could not be verified. Retry or reconnect your broker.');
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  int? _countRows(Object? value, String name) {
    if (value is List) return value.length;
    if (value is Map && value[name] is List) {
      return (value[name] as List).length;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final mode = widget.mode.selected;
    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 18),
      const MintTickEyebrow('Your portfolio'),
      const SizedBox(height: 7),
      Text('Portfolio', style: Theme.of(context).textTheme.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 14),
      MintTickModeSwitcher(mode: widget.mode),
      const SizedBox(height: 18),
      if (_loading) const Center(child: LinearProgressIndicator()),
      if (_error != null) ...[
        MintTickInfoText(_error!, icon: Icons.error_outline_rounded),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: _refresh,
          icon: const Icon(Icons.refresh_rounded),
          label: const Text('Retry portfolio'),
        ),
      ] else if (!_loading && mode == 'PAPER') ...[
        MintTickSurface(accent: true, child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            const MintTickEyebrow('Virtual Paper cash · Not real money'),
            const SizedBox(height: 9),
            Text(_formatPaise(_paperCashPaise),
              key: const ValueKey('paper-portfolio-available'),
              style: Theme.of(context).textTheme.headlineMedium
                  ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 8),
            Text('Available across $_fundedCount funded Paper ${_fundedCount == 1 ? 'room' : 'rooms'}',
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: scheme.onSurfaceVariant)),
            const SizedBox(height: 11),
            const Text('No customer Paper orders or strategy execution are enabled.'),
          ],
        )),
        const SizedBox(height: 13),
        OutlinedButton.icon(
          onPressed: widget.onRooms,
          icon: const Icon(Icons.layers_outlined),
          label: const Text('Manage Paper Trading Rooms'),
        ),
      ] else if (!_loading && mode == 'LIVE') ...[
        MintTickSurface(accent: true, child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            const MintTickEyebrow('Live · Angel One'),
            const SizedBox(height: 11),
            MintTickStatusLabel(_broker?.connected == true
                ? 'BROKER CONNECTED' : 'NOT CONNECTED'),
            const SizedBox(height: 10),
            Text(_broker?.connected == true
                ? 'Verified account ${_broker?.clientCodeMasked ?? ''}'
                : 'Connect your Angel One account to view actual broker funds.',
              style: Theme.of(context).textTheme.bodyMedium),
            if (_broker?.connected == true) ...[
              const SizedBox(height: 15),
              Text(_liveAvailableCash == null
                  ? 'Available cash not provided by broker'
                  : 'Broker-reported available cash: ₹$_liveAvailableCash',
                key: const ValueKey('live-broker-cash'),
                style: Theme.of(context).textTheme.titleSmall
                    ?.copyWith(fontWeight: FontWeight.w700)),
              const SizedBox(height: 9),
              Text('Holdings: ${_holdingsCount?.toString() ?? 'not available'}'
                  ' · Positions: ${_positionsCount?.toString() ?? 'not available'}',
                style: Theme.of(context).textTheme.bodySmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
            ],
            const SizedBox(height: 12),
            const MintTickInfoText(
              'Live order execution is not authorized. Broker connectivity '
              'is read-only until compliance, account-wide risk limits, '
              'order audits, and reconciliation are verified.',
              icon: Icons.shield_outlined,
            ),
          ],
        )),
        const SizedBox(height: 13),
        FilledButton.icon(
          onPressed: widget.onBroker,
          icon: const Icon(Icons.account_balance_outlined),
          label: Text(_broker?.connected == true
              ? 'View Angel One account' : 'Connect Angel One'),
        ),
      ],
      const SizedBox(height: 13),
      OutlinedButton.icon(
        onPressed: _loading ? null : _refresh,
        icon: const Icon(Icons.refresh_rounded),
        label: const Text('Refresh verified balances'),
      ),
    ]);
  }

  String _formatPaise(int paise) {
    final value = paise.abs();
    return '${paise < 0 ? '-' : ''}${formatPaperRupees(value ~/ 100)}.'
        '${(value % 100).toString().padLeft(2, '0')}';
  }
}
