import 'package:flutter/material.dart';

import '../data/customer_home_summary_repository.dart';
import '../domain/trading_room_draft.dart';
import '../domain/trading_workspace_mode.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_mode_switcher.dart';

/// Approved compact Home layout backed only by authenticated customer evidence.
/// No market-price guesses, simulated broker balances or order submission.
class MintTickHomeCommandCentre extends StatefulWidget {
  const MintTickHomeCommandCentre({
    required this.loadProfile, required this.summaryRepository,
    required this.drafts, required this.mode,
    required this.onCreateRoom, required this.onOpenRoom,
    required this.onRooms, required this.onMarkets,
    required this.onMind, required this.onPortfolio,
    required this.onActivity, required this.onBroker,
    required this.onAccount, required this.onNotifications,
    super.key,
  });

  final Future<Map<String, dynamic>> Function() loadProfile;
  final CustomerHomeSummaryRepository summaryRepository;
  final TradingRoomDraftStore drafts;
  final TradingWorkspaceMode mode;
  final VoidCallback onCreateRoom;
  final ValueChanged<TradingRoomDraft> onOpenRoom;
  final VoidCallback onRooms;
  final VoidCallback onMarkets;
  final VoidCallback onMind;
  final VoidCallback onPortfolio;
  final VoidCallback onActivity;
  final VoidCallback onBroker;
  final VoidCallback onAccount;
  final VoidCallback onNotifications;

  @override
  State<MintTickHomeCommandCentre> createState() =>
      _MintTickHomeCommandCentreState();
}

class _MintTickHomeCommandCentreState extends State<MintTickHomeCommandCentre> {
  bool _checkingIdentity = true;
  bool _verifiedIdentity = false;
  bool _loadingSummary = false;
  String? _identityError;
  String? _summaryError;
  CustomerHomeSummary? _summary;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    widget.mode.addListener(_modeChanged);
    _initialize();
  }

  @override
  void dispose() {
    ++_generation;
    widget.mode.removeListener(_modeChanged);
    super.dispose();
  }

  void _modeChanged() {
    if (_verifiedIdentity && mounted) _refresh();
  }

  Future<void> _initialize() async {
    ++_generation;
    setState(() {
      _checkingIdentity = true;
      _verifiedIdentity = false;
      _identityError = null;
      _summary = null;
    });
    try {
      final profile = await widget.loadProfile();
      if (profile['role'] != 'CUSTOMER' ||
          profile['userId'] is! String ||
          (profile['userId'] as String).isEmpty ||
          profile['email'] is! String ||
          (profile['email'] as String).isEmpty) {
        throw const FormatException('Customer identity unverified');
      }
      if (!mounted) return;
      await widget.mode.loadForCustomer(profile['userId'] as String);
      if (!mounted) return;
      await widget.drafts.load();
      if (!mounted) return;
      setState(() {
        _checkingIdentity = false;
        _verifiedIdentity = true;
      });
      await _refresh();
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _checkingIdentity = false;
        _verifiedIdentity = false;
        _identityError = 'Your account could not be verified. '
            'Sign in again or retry the secure connection.';
      });
    }
  }

  Future<void> _refresh() async {
    if (!mounted || !_verifiedIdentity) return;
    final request = ++_generation;
    final mode = widget.mode.selected;
    setState(() {
      _loadingSummary = true;
      _summaryError = null;
      if (_summary?.mode != mode) _summary = null;
    });
    try {
      final result = await widget.summaryRepository.load(mode);
      if (!mounted || request != _generation ||
          result.mode != widget.mode.selected) {
        return;
      }
      setState(() => _summary = result);
    } catch (_) {
      if (!mounted || request != _generation) return;
      setState(() => _summaryError =
          'Summary unavailable. No unverified totals will be displayed.');
    } finally {
      if (mounted && request == _generation) {
        setState(() => _loadingSummary = false);
      }
    }
  }

  void _openRoom(String? id) {
    for (final room in widget.drafts.drafts) {
      if (room.id == id) {
        widget.onOpenRoom(room);
        return;
      }
    }
    // The authoritative room list may have updated since this Home snapshot.
    widget.onRooms();
  }

  void _next(HomeAction action) {
    switch (action.destination) {
      case 'CREATE_ROOM':
        widget.onCreateRoom();
        return;
      case 'ROOM':
        _openRoom(action.roomId);
        return;
      case 'BROKERS':
        widget.onBroker();
        return;
    }
  }

  String _nextTitle(String action) => switch (action) {
    'CREATE_PAPER_ROOM' => 'Create your first Paper Room',
    'COMPLETE_PAPER_ROOM' => 'Finish your Paper Room setup',
    'REVIEW_PAPER_ROOM' => 'Review your funded Paper Room',
    'VERIFY_BROKER' => 'Verify broker connectivity',
    'CONNECT_BROKER' => 'Connect your brokerage account',
    'PLAN_LIVE_ROOM' => 'Plan your first Live Room',
    'REVIEW_LIVE_ROOM' => 'Review your Live Room',
    _ => 'Review your workspace',
  };

  String _nextDetail(String action) => switch (action) {
    'CREATE_PAPER_ROOM' => 'Start a plan using simulated funds',
    'COMPLETE_PAPER_ROOM' => 'Draft saved · funds not allocated',
    'REVIEW_PAPER_ROOM' => 'Virtual wallet funded · execution disabled',
    'VERIFY_BROKER' => 'Connection status could not be verified',
    'CONNECT_BROKER' => 'Angel One and Upstox · account read access',
    'PLAN_LIVE_ROOM' => 'Broker linked · no live order authorization',
    'REVIEW_LIVE_ROOM' => 'Plan saved · execution still disabled',
    _ => 'Check your workspace setup',
  };

  String _brokerLabel(HomeBrokers broker) {
    if (!broker.complete) return 'Broker status unavailable';
    if (broker.connected == 0) return 'No connected brokers';
    if (broker.connected == 2) return 'Angel One + Upstox linked';
    return broker.angelOne == 'CONNECTED'
        ? 'Angel One linked' : 'Upstox linked';
  }

  String _recorded(HomeEvidence evidence, {bool market = false}) {
    if (evidence.status == 'UNAVAILABLE') {
      return market ? 'No verified market snapshot'
          : 'Research evidence unavailable';
    }
    final value = evidence.asOf?.toLocal();
    final time = value == null ? 'time unavailable'
        : '${value.day}/${value.month} '
          '${value.hour.toString().padLeft(2, '0')}:'
          '${value.minute.toString().padLeft(2, '0')}';
    if (evidence.status == 'STALE') return 'Recorded $time · stale';
    return market ? 'Last recorded $time · not live'
        : 'Research ${evidence.status.toLowerCase()} · $time';
  }

  String _cash(int paise) {
    final rupees = formatPaperRupees(paise ~/ 100);
    final fractional = paise % 100;
    return fractional == 0 ? rupees :
        '$rupees.${fractional.toString().padLeft(2, '0')}';
  }

  Widget _heading(String title, String action, VoidCallback onTap) {
    final theme = Theme.of(context);
    return Row(children: [
      Expanded(child: Text(title,
        style: theme.textTheme.titleSmall?.copyWith(
          fontWeight: FontWeight.w800))),
      TextButton(
        onPressed: onTap,
        style: TextButton.styleFrom(
          visualDensity: VisualDensity.compact,
          padding: const EdgeInsets.symmetric(horizontal: 5)),
        child: Text(action, style: theme.textTheme.labelMedium?.copyWith(
          color: theme.colorScheme.primary,
          fontWeight: FontWeight.w700)),
      ),
    ]);
  }

  Widget _row(String label, String subtitle, IconData icon,
      VoidCallback onTap, {String? tag, Key? key}) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Material(
      key: key,
      color: colors.surfaceContainerLow,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minHeight: 55),
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: colors.outlineVariant),
          ),
          child: Row(children: [
            Icon(icon, size: 18, color: colors.primary),
            const SizedBox(width: 10),
            Expanded(child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text(subtitle, maxLines: 2, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: colors.onSurfaceVariant, fontSize: 11.5,
                    height: 1.25)),
              ],
            )),
            if (tag != null) ...[
              const SizedBox(width: 5),
              Text(tag, style: theme.textTheme.labelSmall?.copyWith(
                color: colors.primary, fontWeight: FontWeight.w800)),
            ],
            Icon(Icons.chevron_right_rounded, size: 17,
                color: colors.onSurfaceVariant),
          ]),
        ),
      ),
    );
  }

  Widget _metric(String amount, String label) {
    final theme = Theme.of(context);
    final color = theme.colorScheme;
    return Expanded(child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
      decoration: BoxDecoration(
        color: color.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.outlineVariant)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(amount, style: theme.textTheme.titleLarge?.copyWith(
          fontSize: 19, fontWeight: FontWeight.w800)),
        const SizedBox(height: 2),
        Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: theme.textTheme.labelSmall?.copyWith(
            fontSize: 10, color: color.onSurfaceVariant)),
      ]),
    ));
  }

  Widget _nextCard(CustomerHomeSummary summary) {
    final theme = Theme.of(context);
    final color = theme.colorScheme;
    final action = summary.nextAction;
    return Material(
      key: const ValueKey('home-next-action'),
      borderRadius: BorderRadius.circular(14),
      color: color.primaryContainer.withValues(alpha: .34),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () => _next(action),
        child: Container(
          padding: const EdgeInsets.all(13),
          decoration: BoxDecoration(
            border: Border.all(color: color.primary.withValues(alpha: .3)),
            borderRadius: BorderRadius.circular(14)),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('YOUR NEXT ACTION',
                style: theme.textTheme.labelSmall?.copyWith(
                  fontWeight: FontWeight.w800, color: color.primary,
                  letterSpacing: .6))),
              const Icon(Icons.arrow_outward_rounded, size: 17),
            ]),
            const SizedBox(height: 6),
            Text(_nextTitle(action.code), style:
              theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w800)),
            const SizedBox(height: 3),
            Text(_nextDetail(action.code),
              style: theme.textTheme.bodySmall?.copyWith(
                color: color.onSurfaceVariant)),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: Text(
                summary.mode == 'PAPER'
                  ? '${formatPaperRupees(summary.rooms.plannedBudgetInr)} planned · not funded cash'
                  : _brokerLabel(summary.brokers),
                maxLines: 2, overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: color.onSurfaceVariant))),
              Text('Continue', style: theme.textTheme.labelMedium?.copyWith(
                fontWeight: FontWeight.w700, color: color.primary)),
              const Icon(Icons.chevron_right_rounded, size: 17),
            ]),
          ]),
        ),
      ),
    );
  }

  List<Widget> _verifiedContent(CustomerHomeSummary s) {
    final paper = s.mode == 'PAPER';
    final featured = s.rooms.featured;
    final wallet = s.rooms.availablePaperCashPaise;
    return [
      _nextCard(s),
      const SizedBox(height: 9),
      Row(children: [
        _metric('${s.rooms.count}', 'Rooms'),
        const SizedBox(width: 7),
        _metric(paper ? '${s.rooms.funded}' :
          s.brokers.complete ? '${s.brokers.connected}' : '—',
          paper ? 'Funded' : 'Brokers'),
        const SizedBox(width: 7),
        _metric('Off', 'Execution'),
      ]),
      const SizedBox(height: 10),
      _heading('Your Trading Rooms', 'View all', widget.onRooms),
      if (featured.isEmpty)
        _row('No ${paper ? 'Paper' : 'Live'} Rooms',
          'Create a plan · no automated orders enabled',
          Icons.layers_outlined, widget.onCreateRoom)
      else
        for (final room in featured) ...[
          _row(room.name,
            '${room.mode} · ${room.focus.replaceAll('_', ' ')} · '
              '${formatPaperRupees(room.plannedBudgetInr)} planned',
            Icons.layers_outlined, () => _openRoom(room.id),
            tag: room.status == 'PAPER_FUNDED' ? 'FUNDED' : 'DRAFT',
            key: ValueKey('home-room-${room.id}')),
          const SizedBox(height: 5),
        ],
      const SizedBox(height: 2),
      _heading('Market pulse', 'Markets', widget.onMarkets),
      _row('NSE / BSE · Market evidence',
        _recorded(s.mind.market, market: true),
        Icons.show_chart_rounded, widget.onMarkets,
        key: const ValueKey('home-market-pulse')),
      const SizedBox(height: 3),
      _heading('Mintick Mind', 'Explore', widget.onMind),
      _row('Research & readiness',
        '${_recorded(s.mind.research)} · execution off',
        Icons.psychology_outlined, widget.onMind,
        key: const ValueKey('home-mind-status')),
      const SizedBox(height: 3),
      _heading('Portfolio & accounts', 'View', widget.onPortfolio),
      _row(paper ? 'Paper portfolio' : 'Broker investments',
        paper ? (wallet == null
          ? 'Virtual wallet balance unverified'
          : '${_cash(wallet)} virtual available · not real money')
          : '${_brokerLabel(s.brokers)} · read-only account view',
        Icons.account_balance_wallet_outlined, widget.onPortfolio,
        key: const ValueKey('home-portfolio-status')),
      const SizedBox(height: 3),
      _heading('Recent activity', 'View all', widget.onActivity),
      _row(s.activity == null ? 'No verified room activity'
          : s.activity!.kind == 'ROOM_PLAN_SAVED'
            ? 'Trading Room plan saved' : 'Paper wallet ledger event',
        s.activity == null
          ? 'No order activity inferred from plans'
          : 'Persisted in your workspace',
        Icons.history_rounded,
        widget.onActivity,
        key: const ValueKey('home-recent-activity')),
      const SizedBox(height: 8),
      Text('Planned room allocations are not actual balances. '
        'Paper cash is virtual. Mode selection does not enable execution.',
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          height: 1.3)),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return MintTickCustomerScaffold(
      onAccount: widget.onAccount,
      onNotifications: widget.onNotifications,
      children: [
        if (_checkingIdentity)
          const Padding(padding: EdgeInsets.symmetric(vertical: 50),
            child: Center(child: CircularProgressIndicator()))
        else if (!_verifiedIdentity) ...[
          const MintTickEyebrow('Account verification'),
          const SizedBox(height: 8),
          Text('Your workspace is unavailable.',
            style: theme.textTheme.titleLarge),
          const SizedBox(height: 9),
          MintTickInfoText(_identityError ?? 'Sign-in required.'),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            key: const ValueKey('customer-home-retry'),
            onPressed: _initialize,
            icon: const Icon(Icons.refresh_rounded),
            label: const Text('Retry account verification')),
        ] else ...[
          MintTickEyebrow('${widget.mode.selected} · Trading workspace'),
          const SizedBox(height: 4),
          Row(children: [
            Expanded(child: Text('Your trading overview',
              key: const ValueKey('home-compact-title'),
              style: theme.textTheme.titleLarge?.copyWith(
                fontSize: 23, fontWeight: FontWeight.w800))),
            IconButton(
              key: const ValueKey('home-refresh-summary'),
              tooltip: 'Refresh verified Home summary',
              visualDensity: VisualDensity.compact,
              onPressed: _loadingSummary ? null : _refresh,
              icon: const Icon(Icons.refresh_rounded, size: 21),
            ),
          ]),
          const SizedBox(height: 8),
          MintTickModeSwitcher(mode: widget.mode),
          const SizedBox(height: 11),
          if (_loadingSummary && _summary == null)
            const LinearProgressIndicator(minHeight: 2),
          if (_summaryError != null) ...[
            MintTickInfoText(_summaryError!,
              icon: Icons.error_outline_rounded),
            const SizedBox(height: 4),
            TextButton.icon(
              key: const ValueKey('home-summary-retry'),
              onPressed: _refresh,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Retry Home summary')),
          ],
          if (_summary != null) ..._verifiedContent(_summary!),
        ],
      ],
    );
  }
}
