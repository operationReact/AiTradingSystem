import 'package:flutter/material.dart';

import '../domain/trading_room_draft.dart';
import 'minttick_customer_widgets.dart';

/// Clear customer-only states for backend resources that are not authorized
/// by the Cognito account API. Never call the operator/PAPER APIs with a
/// customer identity or display invented quotes, holdings, or trade activity.
class MintTickCustomerUnavailablePage extends StatelessWidget {
  const MintTickCustomerUnavailablePage({
    required this.section,
    required this.drafts,
    required this.onRooms,
    this.onBroker,
    super.key,
  });

  final String section;
  final TradingRoomDraftStore drafts;
  final VoidCallback onRooms;
  final VoidCallback? onBroker;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final details = switch (section) {
      'Markets' => (
        'Market quotes not connected',
        'The customer market-data API is not available in this release. '
        'No prices or simulated live signals are shown.',
        Icons.show_chart_rounded,
      ),
      'Portfolio' => (
        'No broker portfolio connected',
        'Choose Paper or Live trading for each room. Your MintTick login '
        'is independent from your Angel One account. '
        'Connect Angel One to inspect real holdings, funds and positions.',
        Icons.account_balance_wallet_outlined,
      ),
      'Activity' => (
        'No Mantic Mind activity yet',
        'Your local Trading Room drafts are not running strategies. '
        'No trade decisions or execution events have occurred.',
        Icons.history_rounded,
      ),
      _ => (
        'No notifications to display',
        'Account and trading notifications will appear here when '
        'their authenticated backend integration is available.',
        Icons.notifications_none_rounded,
      ),
    };
    return AnimatedBuilder(
      animation: drafts,
      builder: (context, _) => MintTickCustomerScaffold(
        showHeader: false,
        children: [
          const SizedBox(height: 20),
          const MintTickEyebrow('MintTick workspace'),
          const SizedBox(height: 7),
          Text(section, style: Theme.of(context).textTheme.headlineSmall
              ?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 19),
          MintTickSurface(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              MintTickIconTile(details.$3),
              const SizedBox(height: 15),
              Text(details.$1, style: Theme.of(context).textTheme.titleLarge
                  ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 8),
              Text(details.$2, style: Theme.of(context).textTheme.bodyMedium
                  ?.copyWith(color: scheme.onSurfaceVariant, height: 1.5)),
            ]),
          ),
          const SizedBox(height: 20),
          if (section == 'Portfolio') ...[
            const MintTickSectionHeading('Paper & Live'),
            const SizedBox(height: 9),
            const MintTickInfoText(
              'Paper: a separate simulated portfolio will become available '
              'after customer-specific paper accounting is connected. '
              'Live: actual broker funds and holdings are available through '
              'the authenticated Angel One read-only connection.',
              icon: Icons.layers_outlined,
            ),
            if (onBroker != null) ...[
              const SizedBox(height: 10),
              FilledButton.icon(
                key: const ValueKey('customer-portfolio-connect-broker'),
                onPressed: onBroker,
                icon: const Icon(Icons.account_balance_outlined),
                label: const Text('Angel One · Live account'),
              ),
            ],
            const SizedBox(height: 18),
            const MintTickSectionHeading('Your room planning'),
            const SizedBox(height: 8),
            MintTickSurface(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const MintTickEyebrow('Planned allocations · not funded'),
                const SizedBox(height: 7),
                Text(formatPaperRupees(drafts.drafts.fold<int>(
                      0, (sum, room) => sum + room.paperBudget)),
                  style: Theme.of(context).textTheme.headlineSmall
                      ?.copyWith(fontWeight: FontWeight.w800)),
                const SizedBox(height: 7),
                Text('${drafts.drafts.length} local room drafts · No funding',
                  style: Theme.of(context).textTheme.bodySmall
                      ?.copyWith(color: scheme.onSurfaceVariant)),
              ],
            )),
            const SizedBox(height: 16),
          ],
          if (section == 'Activity') ...[
            const MintTickSectionHeading('What Mantic Mind will explain'),
            const SizedBox(height: 9),
            const MintTickInfoText(
              'When supported, each decision should disclose the rule, '
              'risk check, order status, and reason to avoid a trade. '
              'No events are fabricated here.',
            ),
            const SizedBox(height: 16),
          ],
          OutlinedButton.icon(
            onPressed: onRooms,
            icon: const Icon(Icons.layers_outlined),
            label: const Text('View Trading Rooms'),
          ),
        ],
      ),
    );
  }
}
