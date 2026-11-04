import 'package:flutter/material.dart';

import '../core/widgets/data_state_panel.dart';
import '../data/authenticated_api_client.dart';
import '../data/model_emergency_control_repository.dart';

enum _EmergencyAction { fallback, family, version }

/// Admin-controlled de-risk actions only; deliberate confirmation and audited reason required.
class ModelEmergencyControlsPage extends StatefulWidget {
  const ModelEmergencyControlsPage({required this.repository, super.key});
  final ModelEmergencyControlRepository repository;

  @override
  State<ModelEmergencyControlsPage> createState() => _ModelEmergencyControlsPageState();
}

class _ModelEmergencyControlsPageState extends State<ModelEmergencyControlsPage> {
  final TextEditingController _reason = TextEditingController();
  final TextEditingController _scope = TextEditingController();
  _EmergencyAction _action = _EmergencyAction.fallback;
  List<ModelEmergencyControl>? _controls;
  String? _error;
  bool _denied = false;
  bool _loading = false;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _reason.dispose();
    _scope.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_loading || _submitting) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await widget.repository.list();
      if (!mounted) return;
      setState(() {
        _controls = result;
        _denied = false;
      });
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() {
        _denied = failure.statusCode == 401 || failure.statusCode == 403;
        _error = _denied ? 'Admin access is required.' : 'Control read failed: ${failure.code}';
        if (_denied) _controls = null;
      });
    } catch (_) {
      if (mounted) setState(() => _error = 'Emergency-control evidence was invalid.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _submit() async {
    if (_submitting || _loading || _denied) return;
    final reason = _reason.text.trim();
    final scope = _scope.text.trim();
    if (reason.length < 10 || reason.length > 512) {
      setState(() => _error = 'Provide an audit reason between 10 and 512 characters.');
      return;
    }
    if (_action == _EmergencyAction.version &&
        !RegExp(r'^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$').hasMatch(scope)) {
      setState(() => _error = 'Provide the registered model-version UUID.');
      return;
    }
    if (_action == _EmergencyAction.family &&
        !RegExp(r'^[A-Z0-9][A-Z0-9_]{1,39}$').hasMatch(scope)) {
      setState(() => _error = 'Provide the exact registered model family.');
      return;
    }
    final name = switch (_action) {
      _EmergencyAction.fallback => 'Activate global static fallback',
      _EmergencyAction.family => 'Disable family $scope',
      _EmergencyAction.version => 'Disable model version $scope',
    };
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Confirm emergency reduction'),
        content: Text('$name?\n\nAudit reason: $reason\n\n'
          'This action may remove model influence. It cannot enable LIVE or submit orders.'),
        actions: [
          TextButton(
            key: const ValueKey('emergency-cancel'),
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('emergency-confirm'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirm reduction'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      switch (_action) {
        case _EmergencyAction.fallback:
          await widget.repository.enableStaticFallback(reason);
        case _EmergencyAction.family:
          await widget.repository.disableFamily(scope, reason);
        case _EmergencyAction.version:
          await widget.repository.disableVersion(scope, reason);
      }
      if (!mounted) return;
      _reason.clear();
      await _refreshAfterMutation();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() {
        _denied = failure.statusCode == 401 || failure.statusCode == 403;
        _error = _denied ? 'Admin access was revoked.' : 'Reduction failed: ${failure.code}';
        if (_denied) _controls = null;
      });
    } catch (_) {
      if (mounted) setState(() => _error = 'Emergency reduction was not confirmed by the server.');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _refreshAfterMutation() async {
    try {
      final rows = await widget.repository.list();
      if (!mounted) return;
      setState(() => _controls = rows);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() {
        _denied = failure.statusCode == 401 || failure.statusCode == 403;
        _error = _denied ? 'Admin access was revoked.' : 'Reload failed: ${failure.code}';
        if (_denied) _controls = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final active = _controls?.where((item) => item.active).length ?? 0;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Emergency model controls'),
        actions: [
          IconButton(
            key: const ValueKey('emergency-refresh'),
            onPressed: _loading || _submitting ? null : _refresh,
            tooltip: 'Refresh emergency controls',
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          key: const ValueKey('emergency-controls-page'),
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 110),
          children: [
            Text('Model safety controls',
              style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
            const SizedBox(height: 8),
            const Text('Only reduce model participation. Java retains risk, PAPER and LIVE authority. '
              'Restoring models requires a separately reviewed workflow.'),
            const SizedBox(height: 12),
            Chip(label: Text('$active active safety overrides')),
            if (_loading && _controls == null && !_denied)
              const DataStatePanel(kind: DataStateKind.loading,
                title: 'Loading safety state', message: 'Reading Java emergency-control evidence.'),
            if (_error != null)
              DataStatePanel(kind: _denied || _controls == null
                  ? DataStateKind.error : DataStateKind.stale,
                title: _denied ? 'Permission denied' : 'Safety state needs review',
                message: _error!,
                actionLabel: _denied ? null : 'Retry',
                onAction: _denied ? null : _refresh),
            if (!_denied && _controls != null) ...[
              const SizedBox(height: 14),
              Text('Current overrides', style: theme.textTheme.titleLarge),
              if (_controls!.isEmpty)
                const DataStatePanel(kind: DataStateKind.empty,
                  title: 'No recorded overrides',
                  message: 'This does not grant LIVE eligibility.')
              else
                ..._controls!.map((item) => Card(
                  key: ValueKey('emergency-control-${item.type}-${item.scopeKey}'),
                  child: ListTile(
                    leading: Icon(item.active
                      ? Icons.shield_rounded : Icons.shield_outlined),
                    title: Text('${item.type} · ${item.scopeKey}'),
                    subtitle: Text(item.reason),
                    trailing: Text(item.active ? 'ACTIVE' : 'INACTIVE'),
                  ),
                )),
              const SizedBox(height: 24),
              Text('Emergency reduction', style: theme.textTheme.titleLarge),
              const SizedBox(height: 8),
              DropdownButtonFormField<_EmergencyAction>(
                key: const ValueKey('emergency-action'),
                initialValue: _action,
                decoration: const InputDecoration(labelText: 'Control type'),
                items: const [
                  DropdownMenuItem(value: _EmergencyAction.fallback,
                    child: Text('Activate global static fallback')),
                  DropdownMenuItem(value: _EmergencyAction.family,
                    child: Text('Disable model family')),
                  DropdownMenuItem(value: _EmergencyAction.version,
                    child: Text('Disable model version')),
                ],
                onChanged: _submitting ? null : (next) {
                  if (next != null) {
                    setState(() {
                      _action = next;
                      _scope.clear();
                    });
                  }
                },
              ),
              if (_action != _EmergencyAction.fallback) ...[
                const SizedBox(height: 12),
                TextField(
                  key: const ValueKey('emergency-scope'),
                  controller: _scope,
                  enabled: !_submitting,
                  decoration: InputDecoration(labelText:
                    _action == _EmergencyAction.family ? 'Registered family' : 'Model version UUID'),
                ),
              ],
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('emergency-reason'),
                controller: _reason,
                enabled: !_submitting,
                maxLength: 512,
                maxLines: 3,
                decoration: const InputDecoration(
                  labelText: 'Required audit reason (minimum 10 characters)'),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                key: const ValueKey('emergency-review'),
                onPressed: _submitting || _loading ? null : _submit,
                icon: const Icon(Icons.shield_rounded),
                label: Text(_submitting ? 'Submitting...' : 'Review safety reduction'),
              ),
              const SizedBox(height: 8),
              const Text('No enable-LIVE, restore-model, broker or order-submission '
                'action exists on this page.'),
            ],
          ],
        ),
      ),
    );
  }
}
