import 'package:flutter/material.dart';

import '../data/customer_home_activity_repository.dart';
import '../data/customer_home_summary_repository.dart';
import '../domain/trading_workspace_mode.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_mode_switcher.dart';

/// Verified customer timeline. PAPER cash events are simulations; no
/// brokerage orders or Mintick Mind research are mislabeled as trade fills.
class MintTickHomeActivityPage extends StatefulWidget {
  const MintTickHomeActivityPage({
    required this.repository,
    required this.mode,
    required this.onRoom,
    required this.onRooms,
    super.key,
  });

  final CustomerHomeActivityRepository repository;
  final TradingWorkspaceMode mode;
  final ValueChanged<String> onRoom;
  final VoidCallback onRooms;

  @override
  State<MintTickHomeActivityPage> createState() =>
      _MintTickHomeActivityPageState();
}

class _MintTickHomeActivityPageState extends State<MintTickHomeActivityPage> {
  int _generation = 0;
  bool _loading = true;
  String? _error;
  List<HomeActivity>? _events;

  @override
  void initState() {
    super.initState();
    widget.mode.addListener(_load);
    _load();
  }

  @override
  void dispose() {
    ++_generation;
    widget.mode.removeListener(_load);
    super.dispose();
  }

  Future<void> _load() async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _events = null;
      _error = null;
    });
    try {
      final mode = widget.mode.selected;
      final events = await widget.repository.load(mode);
      if (!mounted || generation != _generation ||
          mode != widget.mode.selected) {
        return;
      }
      setState(() => _events = events);
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error =
          'Recent activity could not be verified. Retry to load your history.');
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  String _label(HomeActivity activity) {
    if (activity.kind == 'ROOM_PLAN_SAVED') return 'Trading Room plan saved';
    if (activity.description == 'INITIAL_PAPER_ALLOCATION') {
      return 'Virtual Paper wallet funded';
    }
    return 'Paper wallet cash event';
  }

  String _date(DateTime time) {
    final local = time.toLocal();
    return '${local.day}/${local.month}/${local.year} · '
        '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return MintTickCustomerScaffold(showHeader: false, children: [
      Row(children: [
        IconButton(
          tooltip: 'Back to previous screen',
          onPressed: () => Navigator.of(context).maybePop(),
          icon: const Icon(Icons.arrow_back_rounded),
        ),
        Expanded(child: Text('Recent activity',
          style: theme.textTheme.titleLarge?.copyWith(
            fontWeight: FontWeight.w800))),
        IconButton(
          key: const ValueKey('home-activity-refresh'),
          tooltip: 'Refresh verified activity',
          onPressed: _loading ? null : _load,
          icon: const Icon(Icons.refresh_rounded),
        ),
      ]),
      const SizedBox(height: 3),
      Text('Room plans and virtual wallet events from your account.',
        style: theme.textTheme.bodySmall?.copyWith(
          color: colors.onSurfaceVariant)),
      const SizedBox(height: 11),
      MintTickModeSwitcher(mode: widget.mode),
      const SizedBox(height: 11),
      if (_loading)
        const LinearProgressIndicator(minHeight: 2)
      else if (_error != null) ...[
        MintTickInfoText(_error!, icon: Icons.error_outline_rounded),
        const SizedBox(height: 7),
        OutlinedButton.icon(
          key: const ValueKey('home-activity-retry'),
          onPressed: _load,
          icon: const Icon(Icons.refresh_rounded),
          label: const Text('Retry activity')),
      ] else if (_events?.isEmpty ?? true) ...[
        const MintTickInfoText(
          'No persisted Trading Room or virtual wallet events in '
          'this workspace. No broker trades are inferred.'),
        const SizedBox(height: 9),
        OutlinedButton.icon(
          onPressed: widget.onRooms,
          icon: const Icon(Icons.layers_outlined),
          label: const Text('Explore Trading Rooms')),
      ] else ...[
        for (final event in _events!) ...[
          Material(
            key: ValueKey('home-activity-${event.roomId}-${event.occurredAt.microsecondsSinceEpoch}'),
            color: colors.surfaceContainerLow,
            borderRadius: BorderRadius.circular(12),
            child: InkWell(
              onTap: event.roomId == null ? null
                  : () => widget.onRoom(event.roomId!),
              borderRadius: BorderRadius.circular(12),
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  border: Border.all(color: colors.outlineVariant),
                  borderRadius: BorderRadius.circular(12)),
                child: Row(children: [
                  Icon(event.kind == 'PAPER_CASH_EVENT'
                      ? Icons.account_balance_wallet_outlined
                      : Icons.layers_outlined,
                    color: colors.primary, size: 20),
                  const SizedBox(width: 11),
                  Expanded(child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(_label(event),
                        style: theme.textTheme.bodyMedium?.copyWith(
                          fontWeight: FontWeight.w700)),
                      const SizedBox(height: 4),
                      Text(_date(event.occurredAt),
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: colors.onSurfaceVariant)),
                    ],
                  )),
                  if (event.roomId != null)
                    const Icon(Icons.chevron_right_rounded, size: 18),
                ]),
              ),
            ),
          ),
          const SizedBox(height: 7),
        ],
      ],
      const SizedBox(height: 8),
      Text('Only persisted account events are shown. '
          'Virtual Paper credits are not real money; '
          'broker order execution remains disabled.',
        style: theme.textTheme.bodySmall?.copyWith(
          color: colors.onSurfaceVariant, height: 1.3)),
    ]);
  }
}
