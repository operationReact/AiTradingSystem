import 'package:flutter/material.dart';

import '../data/customer_angel_one_repository.dart';
import '../data/customer_upstox_repository.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_angel_one_dashboard.dart';

/// Broker selection is a separate concept from PAPER/LIVE workspace mode.
/// Connecting one broker never authorizes orders through another.
class MintTickBrokerHubPage extends StatefulWidget {
  const MintTickBrokerHubPage({
    required this.angelOne,
    required this.upstox,
    required this.onAngelOne,
    required this.onUpstox,
    super.key,
  });

  final HttpCustomerAngelOneRepository angelOne;
  final HttpCustomerUpstoxRepository upstox;
  final VoidCallback onAngelOne;
  final VoidCallback onUpstox;

  @override
  State<MintTickBrokerHubPage> createState() => _MintTickBrokerHubPageState();
}

class _MintTickBrokerHubPageState extends State<MintTickBrokerHubPage> {
  bool _busy = false;
  CustomerAngelOneConnection? _angelOne;
  CustomerUpstoxStatus? _upstox;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<Object?> _angelStatus() async {
    try { return await widget.angelOne.status(); } catch (_) { return null; }
  }

  Future<Object?> _upstoxStatus() async {
    try { return await widget.upstox.status(); } catch (_) { return null; }
  }

  Future<void> _refresh() async {
    if (_busy) return;
    setState(() => _busy = true);
    final results = await Future.wait<Object?>([
      _angelStatus(), _upstoxStatus(),
    ]);
    if (mounted) {
      setState(() {
        _angelOne = results[0] is CustomerAngelOneConnection
            ? results[0] as CustomerAngelOneConnection : null;
        _upstox = results[1] is CustomerUpstoxStatus
            ? results[1] as CustomerUpstoxStatus : null;
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 18),
      const MintTickEyebrow('MintTick / Accounts'),
      const SizedBox(height: 7),
      Text('Connect a broker',
        style: Theme.of(context).textTheme.headlineSmall
            ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 7),
      Text('Connect your existing trading accounts. '
          'Each broker keeps its own balances and order history.',
        style: Theme.of(context).textTheme.bodyMedium
            ?.copyWith(color: scheme.onSurfaceVariant, height: 1.5)),
      const SizedBox(height: 18),
      _BrokerOption(
        name: 'Angel One',
        description: 'View your Angel One funds and holdings',
        connected: _angelOne?.connected == true,
        detail: 'Secure SmartAPI connection · Read-only',
        onTap: widget.onAngelOne,
        key: const ValueKey('broker-hub-angel-one'),
      ),
      const SizedBox(height: 11),
      _BrokerOption(
        name: 'Upstox',
        description: 'Sign in securely on the Upstox website',
        connected: _upstox?.connected == true,
        detail: _upstox?.available == false
            ? 'Upstox setup required'
            : 'Official Upstox authorization · Read-only',
        onTap: widget.onUpstox,
        key: const ValueKey('broker-hub-upstox'),
      ),
      AnimatedSwitcher(
        duration: kMintTickMotion,
        child: _busy
            ? const Padding(
                key: ValueKey('broker-refreshing'),
                padding: EdgeInsets.only(top: 12),
                child: LinearProgressIndicator(minHeight: 2),
              )
            : const SizedBox(key: ValueKey('broker-ready')),
      ),
      const SizedBox(height: 19),
      const MintTickInfoText(
        'Your MintTick login is independent from your brokerage accounts. '
        'A connected broker can provide account information. '
        'Live order placement remains disabled until broker permissions '
        'and the centralized risk controls are verified.',
        icon: Icons.shield_outlined,
      ),
      const SizedBox(height: 15),
      OutlinedButton.icon(
        onPressed: _busy ? null : _refresh,
        icon: const Icon(Icons.refresh_rounded),
        label: const Text('Refresh connections'),
      ),
    ]);
  }
}

class _BrokerOption extends StatelessWidget {
  const _BrokerOption({
    required this.name,
    required this.description,
    required this.detail,
    required this.connected,
    required this.onTap,
    super.key,
  });
  final String name;
  final String description;
  final String detail;
  final bool connected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerLow.withValues(alpha: 0.88),
            border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.67)),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Row(children: [
            if (name == 'Angel One')
              const AngelOneSymbol(size: 44)
            else
              Container(
                width: 44, height: 44,
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerHigh.withValues(alpha: 0.80),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(Icons.account_balance_outlined,
                  color: scheme.onSurfaceVariant, size: 21),
              ),
            const SizedBox(width: 13),
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start, children: [
                Wrap(
                  spacing: 7,
                  runSpacing: 6,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(name, style: Theme.of(context).textTheme.titleMedium
                        ?.copyWith(fontWeight: FontWeight.w800)),
                    if (connected) const MintTickStatusLabel('CONNECTED'),
                  ],
                ),
                const SizedBox(height: 5),
                Text(description, style: Theme.of(context).textTheme.bodySmall),
                const SizedBox(height: 5),
                Text(detail, style: Theme.of(context).textTheme.labelSmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
              ],
            )),
            const SizedBox(width: 6),
            const Icon(Icons.chevron_right_rounded, size: 19),
          ]),
        ),
      ),
    );
  }
}
