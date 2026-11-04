import 'package:flutter/material.dart';

import '../core/widgets/data_state_panel.dart';
import '../data/authenticated_api_client.dart';
import '../data/model_control_repository.dart';

/// V4-11 read-only Admin model inventory. Java enforces the ADMIN role.
class ModelControlCenterPage extends StatefulWidget {
  const ModelControlCenterPage({required this.repository, super.key});
  final ModelControlRepository repository;

  @override
  State<ModelControlCenterPage> createState() => _ModelControlCenterPageState();
}

class _ModelControlCenterPageState extends State<ModelControlCenterPage> {
  List<RegisteredModel> _models = const [];
  List<RegisteredModelVersion> _versions = const [];
  List<EmergencyModelControl> _controls = const [];
  RegisteredModel? _selected;
  bool _loading = false;
  bool _loadingVersions = false;
  bool _permissionDenied = false;
  String? _error;
  String? _versionError;
  DateTime? _lastRefresh;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _error = null;
      _permissionDenied = false;
    });
    try {
      final models = await widget.repository.models();
      final controls = await widget.repository.emergencyControls();
      if (!mounted) return;
      setState(() {
        _models = models;
        _controls = controls;
        _selected = null;
        _versions = const [];
        _versionError = null;
        _lastRefresh = DateTime.now().toUtc();
      });
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() {
        _permissionDenied = failure.statusCode == 401 || failure.statusCode == 403;
        _error = _permissionDenied
            ? 'Administrator authentication is required.'
            : 'Refresh failed: ' + failure.code;
        if (_permissionDenied) {
          _models = const [];
          _versions = const [];
          _controls = const [];
          _selected = null;
        }
      });
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Model registry evidence was unavailable or invalid.');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _select(RegisteredModel model) async {
    setState(() {
      _selected = model;
      _versions = const [];
      _loadingVersions = true;
      _versionError = null;
    });
    try {
      final versions = await widget.repository.versions(model.modelKey);
      if (!mounted || _selected?.modelKey != model.modelKey) return;
      setState(() => _versions = versions);
    } on ApiFailure catch (failure) {
      if (!mounted || _selected?.modelKey != model.modelKey) return;
      setState(() {
        if (failure.statusCode == 401 || failure.statusCode == 403) {
          _permissionDenied = true;
          _error = 'Admin permissions were revoked.';
          _models = const [];
          _versions = const [];
          _controls = const [];
          _selected = null;
        } else {
          _versionError = failure.code;
        }
      });
    } catch (_) {
      if (mounted && _selected?.modelKey == model.modelKey) {
        setState(() => _versionError = 'The model-version evidence was invalid.');
      }
    } finally {
      if (mounted && _selected?.modelKey == model.modelKey) {
        setState(() => _loadingVersions = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final theme = Theme.of(context).textTheme;
    final active = _controls.where((c) => c.active).length;
    return Scaffold(
      appBar: AppBar(title: const Text('Model Control Center'), actions: [
        IconButton(
          key: const ValueKey('model-control-refresh'),
          tooltip: 'Refresh model evidence',
          onPressed: _loading ? null : _refresh,
          icon: const Icon(Icons.refresh_rounded),
        ),
      ]),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, size) => RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              key: const ValueKey('model-control-inventory'),
              padding: EdgeInsets.fromLTRB(size.maxWidth >= 900 ? 42 : 16,
                16, size.maxWidth >= 900 ? 42 : 16, 96),
              children: [
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: [
                      scheme.primaryContainer,
                      scheme.surfaceContainerHigh,
                    ]),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('AI model governance',
                      style: theme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
                    const SizedBox(height: 7),
                    const Text('Exact model identities, immutable versions and emergency state. '
                      'Research evidence never authorizes LIVE trading.'),
                    const SizedBox(height: 10),
                    Wrap(spacing: 8, runSpacing: 8, children: [
                      Chip(label: Text(_models.length.toString() + ' models')),
                      Chip(label: Text(active.toString() + ' active controls')),
                      const Chip(label: Text('LIVE unavailable')),
                    ]),
                    if (_lastRefresh != null)
                      Text('Last updated: ' + _lastRefresh!.toIso8601String(),
                        style: theme.bodySmall),
                  ]),
                ),
                const SizedBox(height: 14),
                if (_loading && _models.isEmpty && !_permissionDenied)
                  const DataStatePanel(
                    kind: DataStateKind.loading, title: 'Loading model registry',
                    message: 'Reading authoritative Java model evidence.',
                  ),
                if (_permissionDenied)
                  DataStatePanel(kind: DataStateKind.error,
                    title: 'Administrator access required',
                    message: _error ?? 'You do not have access to this evidence.'),
                if (_error != null && !_permissionDenied)
                  DataStatePanel(
                    kind: _models.isEmpty ? DataStateKind.error : DataStateKind.stale,
                    title: _models.isEmpty ? 'Registry unavailable' : 'Last-known evidence',
                    message: _error!, actionLabel: 'Retry', onAction: _refresh,
                  ),
                if (!_permissionDenied && !_loading && _error == null && _models.isEmpty)
                  const DataStatePanel(kind: DataStateKind.empty,
                    title: 'No registered models',
                    message: 'Register models through the Java Admin API to see them here.'),
                if (!_permissionDenied && _models.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text('Registered models',
                    style: theme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 7),
                  ..._models.map((model) => Card(
                    key: ValueKey('model-row-' + model.modelKey),
                    child: ListTile(
                      selected: _selected?.modelKey == model.modelKey,
                      leading: const Icon(Icons.psychology_alt_outlined),
                      title: Text(model.displayName,
                        style: const TextStyle(fontWeight: FontWeight.w800)),
                      subtitle: Text(model.family + ' · ' + model.role + ' · ' + model.provider
                        + '\nLicense: ' + model.licenseStatus),
                      isThreeLine: true,
                      trailing: const Icon(Icons.chevron_right_rounded),
                      onTap: () => _select(model),
                    ),
                  )),
                ],
                if (!_permissionDenied && _selected != null) ...[
                  const SizedBox(height: 24),
                  Text('Versions: ' + _selected!.displayName,
                    style: theme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 6),
                  SelectableText('Definition SHA-256: ' + _selected!.definitionFingerprint,
                    style: theme.bodySmall),
                  if (_loadingVersions)
                    const DataStatePanel(kind: DataStateKind.loading,
                      title: 'Loading immutable versions',
                      message: 'Reading model artifact and feature lineage.')
                  else if (_versionError != null)
                    DataStatePanel(kind: DataStateKind.error,
                      title: 'Versions unavailable', message: _versionError!)
                  else if (_versions.isEmpty)
                    const DataStatePanel(kind: DataStateKind.empty,
                      title: 'No versions', message: 'This model has no registered artifact versions.')
                  else
                    ..._versions.map((v) => Card(
                      key: ValueKey('model-version-' + v.versionId),
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text(v.label, style: theme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
                          const SizedBox(height: 6),
                          SelectableText('Version: ' + v.versionId
                            + '\nArtifact: ' + v.artifactFingerprint
                            + '\nFeature schema: ' + v.featureSchemaFingerprint
                            + '\nDataset: ' + v.trainingDatasetFingerprint
                            + '\nCode SHA: ' + v.codeSha,
                            style: theme.bodySmall),
                          if (v.trainingCutoff != null)
                            Text('Training cutoff: ' + v.trainingCutoff!.toIso8601String()),
                        ]),
                      ),
                    )),
                ],
                if (!_permissionDenied) ...[
                  const SizedBox(height: 20),
                  Text('Emergency controls',
                    style: theme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 6),
                  if (_controls.isEmpty)
                    const DataStatePanel(kind: DataStateKind.empty,
                      title: 'No emergency overrides',
                      message: 'This does not imply any model is eligible for LIVE.')
                  else
                    ..._controls.map((control) => Card(
                      key: ValueKey('model-control-' + control.type + '-' + control.scope),
                      child: ListTile(
                        leading: Icon(control.active
                          ? Icons.gpp_bad_rounded : Icons.gpp_good_outlined,
                          color: control.active ? scheme.error : scheme.primary),
                        title: Text(control.type + ' · ' + control.scope),
                        subtitle: Text(control.reason.isEmpty
                          ? 'No operator reason reported' : control.reason),
                        trailing: Text(control.active ? 'ACTIVE' : 'INACTIVE',
                          style: TextStyle(fontWeight: FontWeight.bold,
                            color: control.active ? scheme.error : scheme.onSurfaceVariant)),
                      ),
                    )),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
