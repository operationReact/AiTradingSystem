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
  late final TextEditingController _keyController;

  @override
  void initState() {
    super.initState();
    _controller = HealthController(widget.repository)..addListener(_changed);
    _keyController = TextEditingController(text: widget.session.operatorKey ?? '');
    if (widget.session.isAuthenticated) _controller.refresh();
  }

  @override
  void dispose() {
    _controller.removeListener(_changed);
    _controller.dispose();
    _keyController.dispose();
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
            padding: EdgeInsets.fromLTRB(padding, 16, padding, 32),
            children: [
              _header(context),
              const SizedBox(height: 16),
              _authCard(context),
              const SizedBox(height: 16),
              _statusBody(context),
            ],
          ),
        ),
      );
    });

    return Scaffold(
      appBar: AppBar(title: const Text('Operations health')),
      body: SafeArea(child: body),
    );
  }

  Widget _header(BuildContext context) {
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        SegmentedButton<DesignStyle>(
          key: const ValueKey('design-style-control'),
          segments: const [
            ButtonSegment(value: DesignStyle.neonFuturisticAi, label: Text('Neon AI')),
            ButtonSegment(value: DesignStyle.cleanModern, label: Text('Clean')),
          ],
          selected: {widget.designStyle},
          onSelectionChanged: (value) => widget.onDesignStyleChanged(value.first),
        ),
        DropdownButton<ThemeMode>(
          key: const ValueKey('theme-mode-control'),
          value: widget.themeMode,
          items: const [
            DropdownMenuItem(value: ThemeMode.system, child: Text('System theme')),
            DropdownMenuItem(value: ThemeMode.light, child: Text('Light')),
            DropdownMenuItem(value: ThemeMode.dark, child: Text('Dark')),
          ],
          onChanged: (value) {
            if (value != null) widget.onThemeModeChanged(value);
          },
        ),
      ],
    );
  }

  Widget _authCard(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Development operator session', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            const Text('Credential is held in memory only and is never persisted by this Flutter slice.'),
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey('operator-key-field'),
              controller: _keyController,
              obscureText: true,
              enableSuggestions: false,
              autocorrect: false,
              decoration: const InputDecoration(labelText: 'Operator key'),
              onSubmitted: (_) => _connect(),
            ),
            const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 8, children: [
              FilledButton(
                key: const ValueKey('connect-health'),
                onPressed: _controller.loading ? null : _connect,
                child: const Text('Connect and refresh'),
              ),
              OutlinedButton(
                key: const ValueKey('clear-session'),
                onPressed: () {
                  widget.session.clear();
                  _keyController.clear();
                  _controller.clearEvidence();
                },
                child: const Text('Clear session'),
              ),
            ]),
          ],
        ),
      ),
    );
  }

  void _connect() {
    widget.session.setOperatorKey(_keyController.text);
    _controller.refresh();
  }

  Widget _statusBody(BuildContext context) {
    if (_controller.loading && _controller.snapshot == null) {
      return const Center(key: ValueKey('health-loading'), child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()));
    }
    final failure = _controller.failure;
    if (failure != null) {
      return Card(
        key: const ValueKey('health-error'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Health evidence unavailable', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(failure.code),
            if (failure.correlationId != null) Text('Correlation: ${failure.correlationId}'),
            const SizedBox(height: 12),
            FilledButton.tonal(key: const ValueKey('health-retry'), onPressed: _controller.refresh, child: const Text('Retry')),
          ]),
        ),
      );
    }
    final snapshot = _controller.snapshot;
    if (snapshot == null) {
      return const Card(
        key: ValueKey('health-empty'),
        child: Padding(padding: EdgeInsets.all(20), child: Text('Connect to load authoritative operational health evidence.')),
      );
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _summaryCard(context, snapshot),
      const SizedBox(height: 12),
      ...snapshot.components.map((component) => Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: _componentCard(context, component),
          )),
    ]);
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
            _metric('LIVE', snapshot.liveAvailable ? 'AVAILABLE' : 'UNAVAILABLE'),
            _metric('Emergency stop', switch (snapshot.emergencyStopActive) { true => 'ACTIVE', false => 'CLEAR', null => 'UNKNOWN' }),
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
