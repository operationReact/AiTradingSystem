import 'package:flutter/material.dart';

import '../brand/minttick_brand.dart';

/// Premium customer sign-in shell. Authentication is deliberately fail-closed:
/// enabling provider configuration does not create a fake sign-in session.
class SignInPage extends StatelessWidget {
  const SignInPage({super.key});

  static const _providerName = String.fromEnvironment('AUTH_PROVIDER_NAME');
  static const _authConfigured = bool.fromEnvironment(
    'AUTH_PROVIDER_CONFIGURED',
    defaultValue: false,
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final providerLabel = _providerName.trim().isEmpty
        ? 'Secure account access'
        : _providerName.trim();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Sign in'),
      ),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, bounds) => SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 440),
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minHeight: bounds.maxHeight > 180 ? bounds.maxHeight - 56 : 0,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const MintTickLockup(markWidth: 52, fontSize: 25),
                      const SizedBox(height: 42),
                      Semantics(
                        header: true,
                        child: Text(
                          'Welcome to MintTick',
                          style: theme.textTheme.headlineLarge?.copyWith(
                            fontWeight: FontWeight.w800,
                            letterSpacing: -0.7,
                          ),
                        ),
                      ),
                      const SizedBox(height: 10),
                      Text(
                        'One place for markets, research, and your PAPER portfolio.',
                        style: theme.textTheme.bodyLarge?.copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
                      const SizedBox(height: 26),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: scheme.surfaceContainerLow,
                          border: Border.all(color: scheme.outlineVariant),
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Icon(
                              _authConfigured ? Icons.verified_user_outlined : Icons.lock_outline_rounded,
                              color: scheme.primary,
                              size: 23,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(providerLabel,
                                      style: theme.textTheme.titleMedium),
                                  const SizedBox(height: 6),
                                  Text(
                                    _authConfigured
                                        ? 'Identity provider configuration is present, but the secure sign-in handoff is not connected yet.'
                                        : 'Account sign-in is not available in this build. We are completing the secure identity integration.',
                                    style: theme.textTheme.bodyMedium?.copyWith(
                                      color: scheme.onSurfaceVariant,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 20),
                      SizedBox(
                        width: double.infinity,
                        child: FilledButton(
                          key: const ValueKey('secure-sign-in-continue'),
                          // A provider-name flag is not an authentication client.
                          onPressed: null,
                          child: const Text('Continue securely'),
                        ),
                      ),
                      const SizedBox(height: 12),
                      Center(
                        child: TextButton(
                          key: const ValueKey('sign-in-explore-first'),
                          onPressed: () => Navigator.of(context).pushNamedAndRemoveUntil('/', (route) => false),
                          child: const Text('Explore MintTick first'),
                        ),
                      ),
                      const SizedBox(height: 24),
                      Divider(color: scheme.outlineVariant),
                      const SizedBox(height: 14),
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(Icons.shield_outlined, color: scheme.onSurfaceVariant, size: 19),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              'PAPER trading only. Real-money orders are not enabled. '
                              'Never share a broker password with this app.',
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: scheme.onSurfaceVariant,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
