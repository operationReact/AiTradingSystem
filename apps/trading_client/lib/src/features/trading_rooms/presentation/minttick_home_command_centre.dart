import 'package:flutter/material.dart';

import '../data/customer_home_summary_repository.dart';
import '../../mintick_mind/data/mind_paper_portfolio_repository.dart';
import '../../mintick_mind/data/mind_decision_history_repository.dart';
import '../../mintick_mind/data/mind_progress_repository.dart';
import '../domain/trading_room_draft.dart';
import '../domain/trading_workspace_mode.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_mode_switcher.dart';

/// Approved compact Home layout backed only by authenticated customer evidence.
/// No market-price guesses, simulated broker balances or order submission.
class MintTickHomeCommandCentre extends StatefulWidget {
  const MintTickHomeCommandCentre({
    required this.loadProfile, required this.summaryRepository,
    this.paperPortfolioRepository, this.mindHistoryRepository,
    this.mindProgressRepository,
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
  final MindPaperPortfolioRepository? paperPortfolioRepository;
  final MindDecisionHistoryRepository? mindHistoryRepository;
  final MindProgressRepository? mindProgressRepository;
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
  MindPaperPortfolio? _paperPortfolio;
  MindDecisionHistory? _mindHistory;
  MindProgressDetail? _multibaggerProgress;
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
      MindPaperPortfolio? portfolio;
      MindDecisionHistory? history;
      MindProgressDetail? multibagger;
      if (mode == 'PAPER' && widget.paperPortfolioRepository != null) {
        try { portfolio = await widget.paperPortfolioRepository!.load(); } catch (_) {}
      }
      if (widget.mindHistoryRepository != null) {
        try { history = await widget.mindHistoryRepository!.load(); } catch (_) {}
      }
      if (widget.mindProgressRepository != null) {
        try { multibagger = await widget.mindProgressRepository!.detail('screen.multibagger'); } catch (_) {}
      }
      if (!mounted || request != _generation || mode != widget.mode.selected) return;
      setState(() { _summary = result; _paperPortfolio = portfolio; _mindHistory = history; _multibaggerProgress = multibagger; });
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
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minHeight: 48),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(10),
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

  List<Widget> _verifiedContent(CustomerHomeSummary s) {
    final paper = s.mode == 'PAPER';
    final featured = s.rooms.featured;
    final p = _paperPortfolio;
    final pnl = p != null && p.unrealizedPnlPaise != null
        ? p.realizedPnlPaise + p.unrealizedPnlPaise! : null;
    final pnlColor = pnl == null ? null : pnl >= 0 ? const Color(0xFF42A879) : const Color(0xFFE16B6B);
    final candidates = _mindHistory?.decisions.take(2).toList() ?? const [];
    return [
      // One compact performance module replaces the repeated metric/status cards.
      Material(
        key: const ValueKey('home-portfolio-performance'),
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(onTap: widget.onPortfolio, borderRadius: BorderRadius.circular(14),
          child: Container(padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
            decoration: BoxDecoration(borderRadius: BorderRadius.circular(14),
              border: Border.all(color: pnlColor?.withValues(alpha: .45) ?? Theme.of(context).colorScheme.outlineVariant)),
            child: Row(children: [
              Icon(pnl == null ? Icons.account_balance_wallet_outlined : pnl >= 0 ? Icons.trending_up_rounded : Icons.trending_down_rounded,
                color: pnlColor ?? Theme.of(context).colorScheme.primary, size: 22),
              const SizedBox(width: 10),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(paper ? 'Paper portfolio' : 'Portfolio', style: Theme.of(context).textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w700)),
                Text(pnl == null ? (paper ? 'P&L unavailable · marks not verified' : 'Open portfolio for broker values')
                    : '${pnl >= 0 ? '+' : ''}${_cash(pnl)} total P&L · ${p!.staleOrMissingMarks == 0 ? 'marks current' : '${p.staleOrMissingMarks} stale / missing marks'}',
                  maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(color: pnlColor ?? Theme.of(context).colorScheme.onSurfaceVariant)),
              ])),
              Text(p?.equityPaise == null ? '—' : _cash(p!.equityPaise!),
                style: Theme.of(context).textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w800)),
              const Icon(Icons.chevron_right_rounded, size: 18),
            ])),
        ),
      ),
      const SizedBox(height: 8),
      _heading('MintTick Mind', 'Explore', widget.onMind),
      if (candidates.isEmpty)
        _row('Mind findings', s.mind.research.status == 'UNAVAILABLE'
            ? 'No verified research candidates available' : 'Research scan history is unavailable',
          Icons.psychology_outlined, widget.onMind, key: const ValueKey('home-mind-status'))
      else ...[
        for (final candidate in candidates)
          _row('${candidate.tradingSymbol} · ${candidate.exchange}',
            '${candidate.strategy} · ${candidate.interval} · research candidate only',
            Icons.psychology_outlined, widget.onMind,
            key: ValueKey('home-mind-${candidate.candidateFingerprint}')),
        Padding(padding: const EdgeInsets.only(top: 2), child: Text('Recorded candidates are research evidence, not multibagger predictions or buy calls.',
          style: Theme.of(context).textTheme.labelSmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant))),
      ],
      _row('Potential multibaggers', _multibaggerProgress?.item.status == MindProgressStatus.dataBlocked
          ? 'No validated stock list · data source blocked'
          : _multibaggerProgress?.item.status.label ?? 'Mind status unavailable',
        Icons.auto_awesome_outlined, widget.onMind,
        key: const ValueKey('home-multibagger-status')),
      const SizedBox(height: 5),
      _heading('Trading Rooms', '${s.rooms.count} · View all', widget.onRooms),
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
      const SizedBox(height: 5),
      InkWell(onTap: widget.onMarkets, child: Padding(padding: const EdgeInsets.symmetric(vertical: 5), child: Row(children: [
        Icon(Icons.show_chart_rounded, color: Theme.of(context).colorScheme.primary, size: 17),
        const SizedBox(width: 7), Expanded(child: Text('Market data · ${_recorded(s.mind.market, market: true)}', maxLines: 1, overflow: TextOverflow.ellipsis,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant))),
        Text('Markets', style: Theme.of(context).textTheme.labelSmall?.copyWith(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w700)),
      ]))),
      const SizedBox(height: 5),
      Text('Virtual Paper values only. Mind candidates are research-only. Selecting Live does not enable order execution.',
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
