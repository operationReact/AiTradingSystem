import 'package:flutter/material.dart';

import '../data/customer_market_repository.dart';
import '../domain/trading_room_draft.dart';
import '../domain/trading_workspace_mode.dart';
import 'minttick_mode_switcher.dart';
import 'minttick_market_preview.dart';
import 'minttick_customer_widgets.dart';

/// The real post-Cognito destination, not a simulated trading dashboard.
/// A successful customer session can explore MintTick without any broker
/// authorization, executable Trading Room, or invented return figures.
class MintTickCustomerHomePage extends StatefulWidget {
  const MintTickCustomerHomePage({
    required this.loadProfile,
    required this.drafts,
    required this.mode,
    this.marketRepository,
    required this.onCreateRoom,
    required this.onRooms,
    required this.onMarkets,
    required this.onPortfolio,
    required this.onAccount,
    required this.onNotifications,
    this.onBroker,
    super.key,
  });

  final Future<Map<String, dynamic>> Function() loadProfile;
  final TradingRoomDraftStore drafts;
  final TradingWorkspaceMode mode;
  final CustomerMarketRepository? marketRepository;
  final VoidCallback onCreateRoom;
  final VoidCallback onRooms;
  final VoidCallback onMarkets;
  final VoidCallback onPortfolio;
  final VoidCallback onAccount;
  final VoidCallback onNotifications;
  final VoidCallback? onBroker;

  @override
  State<MintTickCustomerHomePage> createState() =>
      _MintTickCustomerHomePageState();
}

class _MintTickCustomerHomePageState extends State<MintTickCustomerHomePage> {
  bool _loading = true;
  Map<String, dynamic>? _profile;

  @override
  void initState() {
    super.initState();
    _loadProfile();
  }

  Future<void> _loadProfile() async {
    if (mounted) setState(() => _loading = true);
    try {
      final result = await widget.loadProfile();
      if (result['role'] != 'CUSTOMER' ||
          result['userId'] is! String ||
          (result['userId'] as String).isEmpty ||
          result['email'] is! String ||
          (result['email'] as String).isEmpty) {
        throw const FormatException('Unverified customer');
      }
      if (mounted) setState(() => _profile = result);
      await widget.mode.loadForCustomer(result['userId'] as String);
      await widget.drafts.load();
    } catch (_) {
      if (mounted) setState(() => _profile = null);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: Listenable.merge([widget.drafts, widget.mode]),
      builder: (context, _) {
        final rooms = widget.drafts.drafts
            .where((room) => room.mode == widget.mode.selected)
            .toList(growable: false);
        return MintTickCustomerScaffold(
          onAccount: widget.onAccount,
          onNotifications: widget.onNotifications,
          children: [
            if (!_loading && _profile != null) ...[
              MintTickModeSwitcher(mode: widget.mode),
              const SizedBox(height: 19),
            ],
            if (_loading)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 65),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_profile == null) ...[
              const MintTickEyebrow('Account verification'),
              const SizedBox(height: 8),
              Text('Your workspace is unavailable.',
                style: Theme.of(context).textTheme.headlineSmall
                    ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 12),
              const MintTickInfoText(
                'MintTick could not verify your customer profile with the '
                'backend. Trading information is hidden until verification succeeds.',
              ),
              const SizedBox(height: 14),
              OutlinedButton.icon(
                key: const ValueKey('customer-home-retry'),
                onPressed: _loadProfile,
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('Retry account verification'),
              ),
            ] else if (widget.drafts.isLoading) ...[
              const Center(child: CircularProgressIndicator()),
            ] else if (widget.drafts.lastError != null) ...[
              const MintTickEyebrow('Trading Rooms'),
              const SizedBox(height: 9),
              MintTickInfoText(widget.drafts.lastError!,
                  icon: Icons.error_outline_rounded),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                onPressed: widget.drafts.load,
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('Retry loading your rooms'),
              ),
            ] else if (widget.drafts.drafts.isEmpty) ...[
              MintTickEyebrow(widget.mode.isPaper
                  ? 'Paper · Your workspace' : 'Live · Your workspace'),
              const SizedBox(height: 7),
              Text(widget.mode.isPaper ? 'Welcome to MintTick'
                  : 'Live workspace',
                key: const ValueKey('customer-home-first-title'),
                style: Theme.of(context).textTheme.headlineSmall
                    ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 6),
              Text(widget.mode.isPaper
                  ? 'Start with your first Paper Room and test a strategy with simulated funds.'
                  : 'Manage broker connectivity and prepare separate Live Trading Rooms.',
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: scheme.onSurfaceVariant)),
              const SizedBox(height: 19),
              if (widget.mode.selected == TradingWorkspaceMode.live &&
                  widget.onBroker != null) ...[
                MintTickSurface(child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Broker account',
                      style: Theme.of(context).textTheme.titleMedium
                          ?.copyWith(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 5),
                    const Text('Check your Angel One or Upstox account status before trading. '
                      'Connection alone does not authorize live orders.'),
                    const SizedBox(height: 12),
                    SizedBox(width: double.infinity,
                      child: FilledButton.icon(
                        key: const ValueKey('home-connect-broker'),
                        onPressed: widget.onBroker,
                        icon: const Icon(Icons.link_rounded),
                        label: const Text('Manage broker connection'))),
                  ],
                )),
                const SizedBox(height: 20),
              ],
              if (widget.marketRepository != null) ...[
                MintTickMarketPreview(
                  repository: widget.marketRepository!,
                  onMarkets: widget.onMarkets,
                  onConnectBroker: widget.onBroker),
                const SizedBox(height: 15),
              ],
              if (widget.mode.isPaper) ...[
                MintTickSurface(child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('PAPER · PLANNED ALLOCATION',
                      style: Theme.of(context).textTheme.labelSmall
                          ?.copyWith(color: scheme.onSurfaceVariant)),
                    const SizedBox(height: 8),
                    Text('₹0', style: Theme.of(context).textTheme.headlineMedium
                        ?.copyWith(fontWeight: FontWeight.w800)),
                    const SizedBox(height: 2),
                    Text('0 rooms · Simulated only',
                      style: Theme.of(context).textTheme.bodySmall),
                  ],
                )),
                const SizedBox(height: 18),
              ],
              MintTickSectionHeading('Your Trading Rooms',
                action: 'View all', onAction: widget.onRooms),
              const SizedBox(height: 8),
              MintTickSurface(child: Row(children: [
                const MintTickIconTile(Icons.layers_outlined),
                const SizedBox(width: 12),
                Expanded(child: Text(widget.mode.isPaper
                    ? 'No Paper Rooms yet. Your first room starts as a draft.'
                    : 'No Live Rooms yet. Broker verification and risk checks apply.',
                  style: Theme.of(context).textTheme.bodySmall)),
              ])),
              const SizedBox(height: 10),
              if (widget.mode.isPaper)
                SizedBox(width: double.infinity, child: FilledButton.icon(
                  key: const ValueKey('customer-home-create-first'),
                  onPressed: widget.onCreateRoom,
                  icon: const Icon(Icons.add_rounded),
                  label: const Text('Create your first Paper Room'),
                ))
              else
                SizedBox(width: double.infinity, child: OutlinedButton.icon(
                  key: const ValueKey('customer-home-create-first'),
                  onPressed: widget.onCreateRoom,
                  icon: const Icon(Icons.add_rounded),
                  label: const Text('Plan a Live Room'),
                )),
              const SizedBox(height: 17),
              MintTickSurface(child: Row(children: [
                const MintTickIconTile(Icons.psychology_outlined),
                const SizedBox(width: 12),
                Expanded(child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('MintTick Mind',
                      style: TextStyle(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 4),
                    Text('Not configured · Strategies and risk controls are independent.',
                      style: Theme.of(context).textTheme.bodySmall
                          ?.copyWith(color: scheme.onSurfaceVariant)),
                  ],
                )),
              ])),
              const SizedBox(height: 18),
              const MintTickSectionHeading('Explore'),
              const SizedBox(height: 8),
              _HomeQuickLinks(
                onMarkets: widget.onMarkets, onPortfolio: widget.onPortfolio),
            ] else ...[
              MintTickEyebrow('${widget.mode.selected} · Your workspace'),
              const SizedBox(height: 6),
              Text('Welcome back.',
                key: const ValueKey('customer-home-returning-title'),
                style: Theme.of(context).textTheme.headlineSmall
                    ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 5),
              Text('Your ${widget.mode.selected == 'PAPER' ? 'Paper' : 'Live'} Trading Rooms and next steps at a glance.',
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: scheme.onSurfaceVariant)),
              const SizedBox(height: 15),
              if (widget.marketRepository != null) ...[
                MintTickMarketPreview(
                  repository: widget.marketRepository!,
                  onMarkets: widget.onMarkets,
                  onConnectBroker: widget.onBroker),
                const SizedBox(height: 10),
              ],
              MintTickSurface(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const MintTickEyebrow('Planned room allocations · Not actual balances'),
                  const SizedBox(height: 8),
                  Text(formatPaperRupees(
                      rooms.fold<int>(0, (sum, room) => sum + room.paperBudget)),
                    key: const ValueKey('customer-home-draft-budget'),
                    style: Theme.of(context).textTheme.headlineMedium
                        ?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 6),
                  Text('${rooms.length} ${widget.mode.selected} ${rooms.length == 1 ? 'room' : 'rooms'}'
                      ' · Mode selection does not enable execution',
                    style: Theme.of(context).textTheme.bodySmall
                        ?.copyWith(color: scheme.onSurfaceVariant)),
                ]),
              ),
              const SizedBox(height: 20),
              MintTickSectionHeading('Your Trading Rooms',
                  action: 'View all', onAction: widget.onRooms),
              const SizedBox(height: 9),
              if (rooms.isEmpty) ...[
                MintTickInfoText(
                  'No ${widget.mode.selected == 'PAPER' ? 'Paper' : 'Live'} Trading Rooms yet. '
                  'Your other-mode rooms remain untouched.',
                  icon: Icons.layers_outlined,
                ),
                const SizedBox(height: 8),
              ],
              for (final room in rooms.take(3)) ...[
                _DraftHomeTile(room: room, onTap: widget.onRooms),
                const SizedBox(height: 8),
              ],
              const SizedBox(height: 4),
              SizedBox(width: double.infinity, child: OutlinedButton.icon(
                onPressed: widget.onCreateRoom,
                icon: const Icon(Icons.add_rounded),
                label: Text('Create ${widget.mode.selected == 'PAPER' ? 'Paper' : 'Live'} room'),
              )),
              const SizedBox(height: 21),
              const MintTickSectionHeading('MintTick Mind · Status'),
              const SizedBox(height: 9),
              const MintTickSurface(child: Row(children: [
                MintTickIconTile(Icons.psychology_outlined),
                SizedBox(width: 12),
                Expanded(child: Text(
                  'Paper wallets use simulated money. Live uses your linked '
                  'brokerage account. MintTick Mind and order execution '
                  'remain disabled until their own safety gates pass.',
                )),
              ])),
              const SizedBox(height: 18),
              _HomeQuickLinks(
                onMarkets: widget.onMarkets,
                onPortfolio: widget.onPortfolio,
              ),
              if (widget.mode.selected == TradingWorkspaceMode.live &&
                  widget.onBroker != null) ...[
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  key: const ValueKey('home-connect-broker'),
                  onPressed: widget.onBroker,
                  icon: const Icon(Icons.account_balance_outlined),
                  label: const Text('Connect Angel One'),
                ),
              ],
            ],
          ],
        );
      },
    );
  }
}

class _DraftHomeTile extends StatelessWidget {
  const _DraftHomeTile({required this.room, required this.onTap});
  final TradingRoomDraft room;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: Theme.of(context).colorScheme.surfaceContainerLow,
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(15),
      side: BorderSide(color: Theme.of(context).colorScheme.outlineVariant),
    ),
    child: InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(15),
      child: Padding(
        padding: const EdgeInsets.all(13),
        child: Row(children: [
          const MintTickIconTile(Icons.layers_outlined),
          const SizedBox(width: 11),
          Expanded(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(room.name, maxLines: 1, overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 3),
              Text('${room.mode} · ${room.focus} · ${formatPaperRupees(room.paperBudget)} planned',
                style: Theme.of(context).textTheme.bodySmall),
            ],
          )),
          MintTickStatusLabel(room.status == 'PAPER_FUNDED'
              ? 'PAPER FUNDED' : 'DRAFT'),
        ]),
      ),
    ),
  );
}

class _HomeQuickLinks extends StatelessWidget {
  const _HomeQuickLinks({required this.onMarkets, required this.onPortfolio});
  final VoidCallback onMarkets;
  final VoidCallback onPortfolio;

  @override
  Widget build(BuildContext context) => Row(children: [
    Expanded(child: OutlinedButton.icon(
      onPressed: onMarkets,
      icon: const Icon(Icons.show_chart_rounded),
      label: const Text('Markets'),
    )),
    const SizedBox(width: 9),
    Expanded(child: OutlinedButton.icon(
      onPressed: onPortfolio,
      icon: const Icon(Icons.account_balance_wallet_outlined),
      label: const Text('Portfolio'),
    )),
  ]);
}
