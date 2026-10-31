import 'package:flutter/material.dart';

import '../data/health_repository.dart';
import '../data/operator_session.dart';
import '../domain/health_snapshot.dart';
import '../theme/app_theme.dart';
import 'health_controller.dart';
import 'responsive_layout.dart';

class OperationsHealthPage extends StatefulWidget {
  const OperationsHealthPage({
    required this.repository,
    required this.session,
    required this.designStyle,
    required this.themeMode,
    required this.onDesignStyleChanged,
    required this.onThemeModeChanged,
    super.key,
  });

  final HealthRepository repository;
  final OperatorSession session;
  final DesignStyle designStyle;
  final ThemeMode themeMode;
  final ValueChanged<DesignStyle> onDesignStyleChanged;
  final ValueChanged<ThemeMode> onThemeModeChanged;

  @override
  State<OperationsHealthPage> createState() => _OperationsHealthPageState();
}

class _OperationsHealthPageState extends State<OperationsHealthPage> {
  late final HealthController _controller;

  @override
  void initState() {
    super.initState();
    _controller = HealthController(widget.repository)..addListener(_changed);
    if (widget.session.isAuthenticated) _controller.refresh();
  }

  @override
  void dispose() {
    _controller.removeListener(_changed);
    _controller.dispose();
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final body = LayoutBuilder(builder: (context, constraints) {
      final layout = ResponsiveLayout.classify(constraints.maxWidth);
      final maxWidth = switch (layout) {
        ResponsiveClass.mobile => constraints.maxWidth,
        ResponsiveClass.adaptive => 760.0,
        ResponsiveClass.desktop => 1120.0,
      };
      final padding = ResponsiveLayout.horizontalPadding(constraints.maxWidth);
      return Center(
        child: ConstrainedBox(
          constraints: BoxConstraints(maxWidth: maxWidth),
          child: ListView(
            key: const ValueKey('health-list'),
            padding: EdgeInsets.fromLTRB(padding, 16, padding, 112),
            children: [
              _productHeader(context),
              const SizedBox(height: 18),
              if (!widget.session.isAuthenticated) ...[
                _signInRequiredCard(context),
              ] else ...[
                _statusBody(context),
              ],
            ],
          ),
        ),
      );
    });

    return Scaffold(
      appBar: AppBar(title: const Text('System health')),
      body: SafeArea(child: body),
    );
  }

  Widget _productHeader(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: LinearGradient(
          colors: [
            colors.neonCyan.withValues(alpha: 0.10),
            colors.neonViolet.withValues(alpha: 0.08),
            colors.neonMagenta.withValues(alpha: 0.05),
          ],
        ),
        border: Border.all(color: theme.colorScheme.outlineVariant),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: colors.neonCyan.withValues(alpha: 0.12),
            ),
            child: Icon(Icons.monitor_heart_rounded, color: colors.neonCyan),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Platform status', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
                const SizedBox(height: 5),
                Text(
                  'Live service health, execution safety and market-data readiness.',
                  style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: 1.4),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _signInRequiredCard(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      key: const ValueKey('health-sign-in-required'),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.lock_outline_rounded, color: theme.colorScheme.primary, size: 28),
            const SizedBox(height: 14),
            Text('Sign in to view system health', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 6),
            Text(
              'Your account session is required before operational and trading-safety details can be displayed.',
              style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: 1.4),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusBody(BuildContext context) {
    if (_controller.loading && _controller.snapshot == null) {
      return const Center(
        key: ValueKey('health-loading'),
        child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()),
      );
    }

    final failure = _controller.failure;
    if (failure != null) {
      final needsAuth = failure.code == 'AUTHENTICATION_REQUIRED' || failure.code == 'UNAUTHORIZED';
      return Card(
        key: const ValueKey('health-error'),
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                needsAuth ? Icons.lock_outline_rounded : Icons.cloud_off_rounded,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(height: 12),
              Text(
                needsAuth ? 'Your session has expired' : 'System health is temporarily unavailable',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 6),
              Text(
                needsAuth
                    ? 'Sign in again to continue.'
                    : 'We could not refresh platform status. Your trading controls remain fail-closed until health is confirmed.',
              ),
              if (!needsAuth) ...[
                const SizedBox(height: 14),
                FilledButton.tonal(
                  key: const ValueKey('health-retry'),
                  onPressed: _controller.refresh,
                  child: const Text('Try again'),
                ),
              ],
              if (failure.correlationId != null) ...[
                const SizedBox(height: 10),
                Text(
                  'Reference ${failure.correlationId}',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
                ),
              ],
            ],
          ),
        ),
      );
    }

    final snapshot = _controller.snapshot;
    if (snapshot == null) {
      return Card(
        key: const ValueKey('health-empty'),
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Row(
            children: [
              const Icon(Icons.sync_rounded),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Platform status has not loaded yet.',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ),
              TextButton(onPressed: _controller.refresh, child: const Text('Refresh')),
            ],
          ),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _summaryCard(context, snapshot),
        const SizedBox(height: 12),
        ...snapshot.components.map(
          (component) => Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: _componentCard(context, component),
          ),
        ),
      ],
    );
  }

  Widget _summaryCard(BuildContext context, HealthSnapshot snapshot) {
    return Card(
      key: const ValueKey('health-summary'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Wrap(
          spacing: 20,
          runSpacing: 12,
          children: [
            _metric('Overall', snapshot.state.name.toUpperCase()),
            _metric('Execution', snapshot.executionMode),
            _metric('Live trading', snapshot.liveAvailable ? 'AVAILABLE' : 'LOCKED'),
            _metric(
              'Emergency stop',
              switch (snapshot.emergencyStopActive) {
                true => 'ACTIVE',
                false => 'CLEAR',
                null => 'UNKNOWN',
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _metric(String label, String value) {
    final slug = label.toLowerCase().replaceAll(' ', '-');
    return Semantics(
      key: ValueKey('health-metric-$slug'),
      container: true,
      excludeSemantics: true,
      liveRegion: true,
      identifier: 'health-metric-$slug',
      label: '$label $value',
      child: SizedBox(
        width: 150,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label),
            const SizedBox(height: 4),
            Text(value, style: const TextStyle(fontWeight: FontWeight.w700)),
          ],
        ),
      ),
    );
  }

  Widget _componentCard(BuildContext context, HealthComponent component) {
    final tradingColors = Theme.of(context).extension<TradingColors>()!;
    final color = switch (component.state) {
      HealthState.healthy || HealthState.idle => tradingColors.healthy,
      HealthState.degraded || HealthState.unavailable => tradingColors.degraded,
      HealthState.halted => tradingColors.halted,
      HealthState.unsafe => tradingColors.unsafe,
      HealthState.unknown => tradingColors.unknown,
    };
    return Card(
      key: ValueKey('health-component-${component.component}'),
      child: ListTile(
        leading: Icon(Icons.circle, size: 14, color: color, semanticLabel: component.state.name),
        title: Text(component.component),
        subtitle: Text('${component.reasonCode}${component.ageSeconds == null ? '' : ' • ${component.ageSeconds}s old'}'),
        trailing: Text(component.state.name.toUpperCase(), style: TextStyle(color: color, fontWeight: FontWeight.w700)),
      ),
    );
  }
}
