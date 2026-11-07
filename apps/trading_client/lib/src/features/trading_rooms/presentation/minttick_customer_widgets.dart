import 'package:flutter/material.dart';

import '../../../brand/minttick_brand.dart';

const kMintTickMotion = Duration(milliseconds: 210);

String formatPaperRupees(int amount) {
  final digits = amount.abs().toString();
  if (digits.length <= 3) return '${amount < 0 ? '-' : ''}₹$digits';
  final tail = digits.substring(digits.length - 3);
  var head = digits.substring(0, digits.length - 3);
  final parts = <String>[];
  while (head.length > 2) {
    parts.insert(0, head.substring(head.length - 2));
    head = head.substring(0, head.length - 2);
  }
  if (head.isNotEmpty) parts.insert(0, head);
  return '${amount < 0 ? '-' : ''}₹${parts.join(',')},$tail';
}

class MintTickCustomerHeader extends StatelessWidget {
  const MintTickCustomerHeader({
    required this.onAccount,
    required this.onNotifications,
    super.key,
  });
  final VoidCallback onAccount;
  final VoidCallback onNotifications;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(
      height: 64,
      child: Row(children: [
        const Expanded(
          child: Align(
            alignment: Alignment.centerLeft,
            child: MintTickLockup(markWidth: 35, fontSize: 20),
          ),
        ),
        IconButton(
          key: const ValueKey('customer-notifications'),
          tooltip: 'Notifications',
          onPressed: onNotifications,
          icon: const Icon(Icons.notifications_none_rounded),
        ),
        const SizedBox(width: 4),
        IconButton(
          key: const ValueKey('customer-profile'),
          tooltip: 'Account',
          style: IconButton.styleFrom(
            backgroundColor: scheme.primary.withValues(alpha: 0.10),
            foregroundColor: scheme.primary,
          ),
          onPressed: onAccount,
          icon: const Icon(Icons.person_outline_rounded),
        ),
      ]),
    );
  }
}

class MintTickSurface extends StatelessWidget {
  const MintTickSurface({
    required this.child,
    this.accent = false,
    this.padding = const EdgeInsets.all(16),
    super.key,
  });

  final Widget child;
  final bool accent;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: padding,
      decoration: BoxDecoration(
        color: accent ? scheme.primaryContainer.withValues(alpha: 0.40)
            : scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: accent
              ? scheme.primary.withValues(alpha: 0.28)
              : scheme.outlineVariant,
        ),
      ),
      child: child,
    );
  }
}

class MintTickEyebrow extends StatelessWidget {
  const MintTickEyebrow(this.text, {super.key});
  final String text;

  @override
  Widget build(BuildContext context) => Text(
    text.toUpperCase(),
    style: Theme.of(context).textTheme.labelSmall?.copyWith(
      color: Theme.of(context).colorScheme.primary,
      fontWeight: FontWeight.w800,
      letterSpacing: 0.9,
    ),
  );
}

class MintTickSectionHeading extends StatelessWidget {
  const MintTickSectionHeading(this.title, {this.action, this.onAction, super.key});
  final String title;
  final String? action;
  final VoidCallback? onAction;
  @override
  Widget build(BuildContext context) => Row(children: [
    Expanded(child: Text(title, style: Theme.of(context).textTheme.titleMedium
        ?.copyWith(fontWeight: FontWeight.w800))),
    if (onAction != null && action != null)
      TextButton(onPressed: onAction, child: Text(action!)),
  ]);
}

class MintTickStatusLabel extends StatelessWidget {
  const MintTickStatusLabel(this.text, {this.warning = false, super.key});
  final String text;
  final bool warning;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final ink = warning ? scheme.error : scheme.primary;
    return Container(
      decoration: BoxDecoration(
        color: ink.withValues(alpha: 0.09),
        border: Border.all(color: ink.withValues(alpha: 0.25)),
        borderRadius: BorderRadius.circular(100),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      child: Text(text, style: Theme.of(context).textTheme.labelSmall
          ?.copyWith(color: ink, fontWeight: FontWeight.w800)),
    );
  }
}

class MintTickIconTile extends StatelessWidget {
  const MintTickIconTile(this.icon, {super.key});
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        color: scheme.primary.withValues(alpha: 0.10),
      ),
      child: Icon(icon, color: scheme.primary, size: 21),
    );
  }
}

class MintTickInfoText extends StatelessWidget {
  const MintTickInfoText(this.message, {super.key, this.icon = Icons.info_outline_rounded});
  final String message;
  final IconData icon;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return MintTickSurface(
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(icon, size: 19, color: scheme.onSurfaceVariant),
        const SizedBox(width: 10),
        Expanded(child: Text(message, style: Theme.of(context).textTheme.bodySmall
            ?.copyWith(color: scheme.onSurfaceVariant, height: 1.5))),
      ]),
    );
  }
}

class MintTickCustomerScaffold extends StatelessWidget {
  const MintTickCustomerScaffold({
    required this.children,
    this.onAccount,
    this.onNotifications,
    this.showHeader = true,
    super.key,
  });

  final List<Widget> children;
  final VoidCallback? onAccount;
  final VoidCallback? onNotifications;
  final bool showHeader;

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final inset = width < 350 ? 16.0 : 20.0;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: ListView(
              padding: EdgeInsets.fromLTRB(inset, 5, inset, 28),
              children: [
                if (showHeader) ...[
                  MintTickCustomerHeader(
                    onAccount: onAccount ?? () {},
                    onNotifications: onNotifications ?? () {},
                  ),
                  const SizedBox(height: 17),
                ],
                ...children,
              ],
            ),
          ),
        ),
      ),
    );
  }
}
