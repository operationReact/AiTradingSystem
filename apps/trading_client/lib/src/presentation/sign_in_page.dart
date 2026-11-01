import 'package:flutter/material.dart';

class SignInPage extends StatelessWidget {
  const SignInPage({super.key});

  static const _providerName = String.fromEnvironment('AUTH_PROVIDER_NAME');
  static const _authConfigured = bool.fromEnvironment('AUTH_PROVIDER_CONFIGURED', defaultValue: false);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final providerLabel = _providerName.trim().isEmpty ? 'Secure identity provider' : _providerName.trim();

    return Scaffold(
      appBar: AppBar(title: const Text('Secure Sign In')),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: Container(
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(28),
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      scheme.primaryContainer,
                      scheme.secondaryContainer.withValues(alpha: 0.72),
                      scheme.surfaceContainerHighest,
                    ],
                  ),
                  border: Border.all(color: scheme.primary.withValues(alpha: 0.35)),
                  boxShadow: [
                    BoxShadow(color: scheme.primary.withValues(alpha: 0.12), blurRadius: 36, spreadRadius: 2),
                  ],
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 58,
                      height: 58,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: scheme.primary.withValues(alpha: 0.14),
                      ),
                      child: Icon(Icons.lock_person_rounded, color: scheme.primary, size: 30),
                    ),
                    const SizedBox(height: 20),
                    Semantics(
                      header: true,
                      child: Text(
                        'Welcome back',
                        style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w900),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Sign in to access your PAPER portfolio, AI research, risk controls, and connected broker account.',
                      style: theme.textTheme.bodyLarge?.copyWith(color: scheme.onSurfaceVariant, height: 1.5),
                    ),
                    const SizedBox(height: 24),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(18),
                        color: scheme.surface.withValues(alpha: 0.55),
                      ),
                      child: Row(
                        children: [
                          Icon(_authConfigured ? Icons.verified_user_rounded : Icons.shield_outlined, color: scheme.primary),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(providerLabel, style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w800)),
                                const SizedBox(height: 3),
                                Text(
                                  _authConfigured
                                      ? 'Identity configuration is available for this build.'
                                      : 'Production identity configuration has not been enabled for this build yet.',
                                  style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 18),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        key: const ValueKey('secure-sign-in-continue'),
                        onPressed: _authConfigured ? () => _showConfigurationBoundary(context) : null,
                        icon: const Icon(Icons.arrow_forward_rounded),
                        label: const Text('Continue securely'),
                      ),
                    ),
                    if (!_authConfigured) ...[
                      const SizedBox(height: 12),
                      Text(
                        'Sign-in stays disabled until the approved production identity provider is configured. No development key or broker password can be entered here.',
                        style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.45),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _showConfigurationBoundary(BuildContext context) {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Secure identity handoff is the next authentication integration step.')),
    );
  }
}
