import 'package:flutter/material.dart';

import '../domain/trading_room_draft.dart';
import 'minttick_customer_widgets.dart';

/// The real post-Cognito destination, not a simulated trading dashboard.
/// A successful customer session can explore MintTick without any broker
/// authorization, executable Trading Room, or invented return figures.
class MintTickCustomerHomePage extends StatefulWidget {
  const MintTickCustomerHomePage({
    required this.loadProfile,
    required this.drafts,
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
      animation: widget.drafts,
      builder: (context, _) {
        final rooms = widget.drafts.drafts;
        return MintTickCustomerScaffold(
          onAccount: widget.onAccount,
          onNotifications: widget.onNotifications,
          children: [
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
            ] else if (rooms.isEmpty) ...[
              const MintTickEyebrow('Welcome to MintTick'),
              const SizedBox(height: 7),
              Text("Let's build your\ntrading space.",
                key: const ValueKey('customer-home-first-title'),
                style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                  fontWeight: FontWeight.w800, height: 1.13)),
              const SizedBox(height: 9),
              Text('Tell us what you want to achieve. Mantic Mind will '
                'help you explore a plan without technical complexity.',
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: scheme.onSurfaceVariant, height: 1.45)),
              const SizedBox(height: 22),
              MintTickSurface(
                accent: true,
                padding: const EdgeInsets.all(19),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      MintTickStatusLabel('YOUR NEXT STEP'),
                      MintTickStatusLabel('PAPER + LIVE'),
                    ],
                  ),
                  const SizedBox(height: 18),
                  const MintTickIconTile(Icons.layers_outlined),
                  const SizedBox(height: 14),
                  Text('Create your first Trading Room',
                    style: Theme.of(context).textTheme.titleLarge
                        ?.copyWith(fontWeight: FontWeight.w800, height: 1.2)),
                  const SizedBox(height: 8),
                  Text('One room, one purpose. Choose Paper or Live and review '
                      'the plan before any trading is activated.',
                    style: Theme.of(context).textTheme.bodyMedium
                        ?.copyWith(color: scheme.onSurfaceVariant)),
                  const SizedBox(height: 17),
                  SizedBox(width: double.infinity, child: FilledButton.icon(
                    key: const ValueKey('customer-home-create-first'),
                    onPressed: widget.onCreateRoom,
                    icon: const Icon(Icons.add_rounded),
                    label: const Text('Create my first room'),
                  )),
                  const SizedBox(height: 13),
                  Text('Choose a mode  →  Review risk  →  Save your plan',
                    style: Theme.of(context).textTheme.labelSmall
                        ?.copyWith(color: scheme.onSurfaceVariant)),
                ]),
              ),
              const SizedBox(height: 23),
              const MintTickSectionHeading('What Mantic Mind does'),
              const SizedBox(height: 10),
              MintTickSurface(child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const MintTickIconTile(Icons.psychology_outlined),
                  const SizedBox(width: 12),
                  Expanded(child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Intelligence without the complexity',
                        style: Theme.of(context).textTheme.titleSmall
                            ?.copyWith(fontWeight: FontWeight.w700)),
                      const SizedBox(height: 5),
                      Text('The planned system evaluates rules, explains '
                          'decisions, and respects authorized risk boundaries. '
                          'It is not operating in this UI release.',
                        style: Theme.of(context).textTheme.bodySmall
                            ?.copyWith(color: scheme.onSurfaceVariant, height: 1.4)),
                    ],
                  )),
                ],
              )),
              const SizedBox(height: 22),
              const MintTickSectionHeading('Explore MintTick'),
              const SizedBox(height: 8),
              _HomeQuickLinks(
                onMarkets: widget.onMarkets,
                onPortfolio: widget.onPortfolio,
              ),
              const SizedBox(height: 16),
              const MintTickInfoText(
                'Your MintTick login and broker account are separate. '
                'Connect Angel One to view live account data.',
              ),
            ] else ...[
              const MintTickEyebrow('Your workspace'),
              const SizedBox(height: 6),
              Text('Welcome back.',
                key: const ValueKey('customer-home-returning-title'),
                style: Theme.of(context).textTheme.headlineSmall
                    ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 5),
              Text('Your Trading Rooms and next steps at a glance.',
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: scheme.onSurfaceVariant)),
              const SizedBox(height: 18),
              MintTickSurface(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const MintTickEyebrow('Planned budgets · Not funded'),
                  const SizedBox(height: 8),
                  Text(formatPaperRupees(
                      rooms.fold<int>(0, (sum, room) => sum + room.paperBudget)),
                    key: const ValueKey('customer-home-draft-budget'),
                    style: Theme.of(context).textTheme.headlineMedium
                        ?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 6),
                  Text('${rooms.length} draft ${rooms.length == 1 ? 'room' : 'rooms'}'
                      '  ·  No automated trading enabled',
                    style: Theme.of(context).textTheme.bodySmall
                        ?.copyWith(color: scheme.onSurfaceVariant)),
                ]),
              ),
              const SizedBox(height: 20),
              MintTickSectionHeading('Your Trading Rooms',
                  action: 'View all', onAction: widget.onRooms),
              const SizedBox(height: 9),
              for (final room in rooms.take(3)) ...[
                _DraftHomeTile(room: room, onTap: widget.onRooms),
                const SizedBox(height: 8),
              ],
              const SizedBox(height: 4),
              SizedBox(width: double.infinity, child: OutlinedButton.icon(
                onPressed: widget.onCreateRoom,
                icon: const Icon(Icons.add_rounded),
                label: const Text('Plan another room'),
              )),
              const SizedBox(height: 21),
              const MintTickSectionHeading('Mantic Mind · Status'),
              const SizedBox(height: 9),
              const MintTickSurface(child: Row(children: [
                MintTickIconTile(Icons.psychology_outlined),
                SizedBox(width: 12),
                Expanded(child: Text(
                  'Paper wallets can hold virtual cash, but no customer '
                  'orders or Mantic Mind automation can run yet. '
                  'Live execution is separately gated.',
                )),
              ])),
              const SizedBox(height: 18),
              _HomeQuickLinks(
                onMarkets: widget.onMarkets,
                onPortfolio: widget.onPortfolio,
              ),
              if (widget.onBroker != null) ...[
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
