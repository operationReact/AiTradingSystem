import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/model_control_center_repository.dart';

/// Connected, read-only V4 Admin evidence; never exposes a LIVE or model-promotion action.
class ModelControlCenterPage extends StatefulWidget {
  const ModelControlCenterPage({required this.repository, super.key});
  final ModelControlCenterRepository repository;

  @override
  State<ModelControlCenterPage> createState() => _ModelControlCenterPageState();
}

class _ModelControlCenterPageState extends State<ModelControlCenterPage> {
  final TextEditingController _sessionController = TextEditingController();
  List<RegisteredModelEvidence>? _models;
  List<ModelVersionEvidence>? _versions;
  RegisteredModelEvidence? _selected;
  MatchedSessionEvidence? _session;
  List<MatchedCaseEvidence>? _cases;
  MatchedSessionAggregateEvidence? _aggregateEvidence;
  List<MatchedSessionEvidence>? _recentSessions;
  ApiFailure? _recentSessionsError;
  bool _loadingRecentSessions = false;
  ApiFailure? _modelsError;
  ApiFailure? _versionsError;
  ApiFailure? _sessionError;
  String? _sessionValidation;
  bool _loadingModels = false;
  bool _loadingVersions = false;
  bool _loadingSession = false;
  int _authGeneration = 0;

  bool _isAccessDenied(ApiFailure error) =>
      error is ApiAuthenticationRequired ||
      error.statusCode == 401 ||
      error.statusCode == 403;

  /// Invalidate every cached ADMIN view on any 401/403, including in-flight reads.
  void _clearRevokedEvidence(ApiFailure error) {
    _authGeneration++;
    _sessionController.clear();
    setState(() {
      _models = null;
      _versions = null;
      _selected = null;
      _session = null;
      _cases = null;
      _aggregateEvidence = null;
      _recentSessions = null;
      _modelsError = error;
      _versionsError = null;
      _sessionError = error;
      _recentSessionsError = error;
      _sessionValidation = null;
      _loadingModels = false;
      _loadingVersions = false;
      _loadingSession = false;
      _loadingRecentSessions = false;
    });
  }


  @override
  void initState() {
    super.initState();
    _loadModels();
    _loadRecentSessions();
  }

  @override
  void dispose() {
    _sessionController.dispose();
    super.dispose();
  }

  Future<void> _loadModels() async {
    if (_loadingModels) return;
    final generation = _authGeneration;
    setState(() { _loadingModels = true; _modelsError = null; });
    try {
      final models = await widget.repository.models();
      if (!mounted || generation != _authGeneration) return;
      setState(() => _models = models);
    } on ApiFailure catch (error) {
      if (!mounted || generation != _authGeneration) return;
      if (_isAccessDenied(error)) {
        _clearRevokedEvidence(error);
      } else {
        setState(() => _modelsError = error);
      }
    } finally {
      if (mounted && generation == _authGeneration) {
        setState(() => _loadingModels = false);
      }
    }
  }

  Future<void> _selectModel(RegisteredModelEvidence model) async {
    final generation = _authGeneration;
    setState(() {
      _selected = model;
      _versions = null;
      _versionsError = null;
      _loadingVersions = true;
    });
    try {
      final versions = await widget.repository.versions(model.key);
      if (!mounted || generation != _authGeneration ||
          _selected?.key != model.key) {
        return;
      }
      setState(() => _versions = versions);
    } on ApiFailure catch (error) {
      if (!mounted || generation != _authGeneration ||
          _selected?.key != model.key) {
        return;
      }
      if (_isAccessDenied(error)) {
        _clearRevokedEvidence(error);
      } else {
        setState(() => _versionsError = error);
      }
    } finally {
      if (mounted && generation == _authGeneration &&
          _selected?.key == model.key) {
        setState(() => _loadingVersions = false);
      }
    }
  }

  Future<void> _loadRecentSessions() async {
    if (_loadingRecentSessions) return;
    final generation = _authGeneration;
    setState(() { _loadingRecentSessions = true; _recentSessionsError = null; });
    try {
      final recent = await widget.repository.recentSessions();
      if (!mounted || generation != _authGeneration) return;
      setState(() => _recentSessions = recent);
    } on ApiFailure catch (error) {
      if (!mounted || generation != _authGeneration) return;
      if (_isAccessDenied(error)) {
        _clearRevokedEvidence(error);
      } else {
        setState(() => _recentSessionsError = error);
      }
    } finally {
      if (mounted && generation == _authGeneration) {
        setState(() => _loadingRecentSessions = false);
      }
    }
  }

  Future<void> _loadSession() async {
    if (_loadingSession) return;
    final id = _sessionController.text.trim();
    final uuid = RegExp(r'^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$');
    if (!uuid.hasMatch(id)) {
      setState(() => _sessionValidation = 'Enter a valid session UUID.');
      return;
    }
    final generation = _authGeneration;
    setState(() {
      _loadingSession = true;
      _session = null;
      _cases = null;
      _aggregateEvidence = null;
      _sessionError = null;
      _sessionValidation = null;
    });
    try {
      final session = await widget.repository.session(id);
      if (!mounted || generation != _authGeneration) return;
      final cases = await widget.repository.recentCases(id);
      if (!mounted || generation != _authGeneration) return;
      final aggregate = await widget.repository.sessionEvidence(id);
      if (!mounted || generation != _authGeneration) return;
      if (aggregate.sessionId != session.id ||
          aggregate.enrollmentFingerprint != session.fingerprint) {
        throw const ApiTransportFailure('MATCHED_SESSION_IDENTITY_MISMATCH', null);
      }
      setState(() {
        _session = session;
        _cases = cases;
        _aggregateEvidence = aggregate;
      });
    } on ApiFailure catch (error) {
      if (!mounted || generation != _authGeneration) return;
      if (_isAccessDenied(error)) {
        _clearRevokedEvidence(error);
      } else {
        setState(() => _sessionError = error);
      }
    } finally {
      if (mounted && generation == _authGeneration) {
        setState(() => _loadingSession = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Model Control Center'),
          bottom: const TabBar(tabs: [
            Tab(icon: Icon(Icons.memory_rounded), text: 'Model inventory'),
            Tab(icon: Icon(Icons.fact_check_outlined), text: 'PAPER / SHADOW'),
          ]),
        ),
        body: SafeArea(
          child: TabBarView(children: [
            _inventory(context),
            _paperShadow(context),
          ]),
        ),
      ),
    );
  }

  Widget _notice() => const Card(
    key: ValueKey('model-control-research-only'),
    child: Padding(
      padding: EdgeInsets.all(14),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(Icons.shield_outlined, size: 20),
        SizedBox(width: 10),
        Expanded(child: Text(
          'Research and PAPER/SHADOW evidence only. Models, decisions and '
          'synthetic results cannot authorize live orders or capital.',
        )),
      ]),
    ),
  );

  Widget _problem(ApiFailure error, {required bool stale}) {
    final denied = _isAccessDenied(error);
    final detail = denied
        ? 'ADMIN access is required. No model evidence was changed.'
        : stale
            ? 'Refresh failed. Existing information is stale.'
            : 'Model evidence unavailable: ${error.code}';
    return Card(
      key: ValueKey(denied
          ? 'model-control-access-denied'
          : (stale ? 'model-control-stale' : 'model-control-error')),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Text(detail),
      ),
    );
  }

  Widget _inventory(BuildContext context) {
    final theme = Theme.of(context);
    final models = _models;
    return ListView(
      key: const ValueKey('model-control-inventory'),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 96),
      children: [
        _notice(),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(child: Text('Registered models',
              style: theme.textTheme.titleLarge)),
          IconButton(
            key: const ValueKey('model-inventory-refresh'),
            tooltip: 'Refresh inventory',
            onPressed: _loadingModels ? null : _loadModels,
            icon: const Icon(Icons.refresh),
          ),
        ]),
        if (_modelsError != null) _problem(_modelsError!, stale: models != null),
        if (_loadingModels && models == null)
          const Center(
            key: ValueKey('model-inventory-loading'),
            child: Padding(
              padding: EdgeInsets.all(24),
              child: CircularProgressIndicator(),
            ),
          ),
        if (!_loadingModels && models != null && models.isEmpty)
          const Card(
            key: ValueKey('model-inventory-empty'),
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text('No registered model versions. Register them in the ADMIN backend.'),
            ),
          ),
        if (models != null)
          ...models.map((model) => Card(
            key: ValueKey('model-inventory-${model.key}'),
            child: ListTile(
              title: Text(model.displayName),
              subtitle: Text('${model.family} · ${model.role}\n${model.provider} · License: ${model.licenseStatus}'),
              isThreeLine: true,
              trailing: const Icon(Icons.chevron_right),
              selected: _selected?.key == model.key,
              onTap: () => _selectModel(model),
            ),
          )),
        if (_selected != null) ...[
          const SizedBox(height: 16),
          Text('Versions · ${_selected!.displayName}',
              style: theme.textTheme.titleMedium),
          Text('Definition: ${_short(_selected!.fingerprint)}'),
          if (_loadingVersions)
            const LinearProgressIndicator(key: ValueKey('model-versions-loading')),
          if (_versionsError != null)
            _problem(_versionsError!, stale: _versions != null),
          if (_versions != null && _versions!.isEmpty)
            const Card(child: ListTile(
              title: Text('No versions registered for this model.'),
            )),
          if (_versions != null)
            ..._versions!.map((version) => Card(
              key: ValueKey('model-version-${version.id}'),
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(version.label, style: theme.textTheme.titleSmall),
                    const SizedBox(height: 6),
                    Text('Version: ${_short(version.fingerprint)}'),
                    Text('Artifact: ${_short(version.artifactFingerprint)}'),
                    Text('Feature schema: ${_short(version.featureSchemaFingerprint)}'),
                    Text('Training dataset: ${_short(version.trainingDatasetFingerprint)}'),
                    Text('Training config: ${_short(version.trainingConfigFingerprint)}'),
                    Text('Code: ${_short(version.codeSha)}'),
                    if (version.researchRegistryFingerprint != null)
                      Text('Research: ${_short(version.researchRegistryFingerprint!)}'),
                    if (version.trainingCutoff != null)
                      Text('Training cutoff: ${version.trainingCutoff!.toIso8601String()}'),
                  ],
                ),
              ),
            )),
        ],
      ],
    );
  }

  Widget _paperShadow(BuildContext context) {
    final theme = Theme.of(context);
    final session = _session;
    final cases = _cases;
    return ListView(
      key: const ValueKey('model-control-paper-shadow'),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 96),
      children: [
        _notice(),
        const SizedBox(height: 10),
        Text('Matched decision evidence', style: theme.textTheme.titleLarge),
        const SizedBox(height: 6),
        const Text(
          'Inspect an existing Java PAPER/SHADOW session. This read-only '
          'view does not enroll models or submit simulated or live orders.',
        ),
        const SizedBox(height: 14),
        Row(children: [
          Expanded(child: Text('Recent registered sessions',
              style: theme.textTheme.titleMedium)),
          IconButton(
            key: const ValueKey('model-session-list-refresh'),
            tooltip: 'Refresh session list',
            onPressed: _loadingRecentSessions ? null : _loadRecentSessions,
            icon: const Icon(Icons.refresh),
          ),
        ]),
        if (_loadingRecentSessions && _recentSessions == null)
          const LinearProgressIndicator(key: ValueKey('model-session-list-loading')),
        if (_recentSessionsError != null)
          _problem(_recentSessionsError!, stale: _recentSessions != null),
        if (_recentSessions != null && _recentSessions!.isEmpty)
          const Text('No registered PAPER/SHADOW sessions yet.'),
        if (_recentSessions != null && _recentSessions!.isNotEmpty)
          DropdownButtonFormField<String>(
            key: const ValueKey('model-session-picker'),
            isExpanded: true,
            decoration: const InputDecoration(
              labelText: 'Browse session',
              border: OutlineInputBorder(),
            ),
            items: _recentSessions!.map((item) => DropdownMenuItem<String>(
              value: item.id,
              child: Text(
                'Session ${_short(item.id)} · ${item.participants.length} models',
                overflow: TextOverflow.ellipsis,
              ),
            )).toList(growable: false),
            onChanged: (id) {
              if (id == null) return;
              _sessionController.text = id;
              _loadSession();
            },
          ),
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('model-session-input'),
          controller: _sessionController,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(
            labelText: 'Session UUID',
            prefixIcon: Icon(Icons.tag),
            border: OutlineInputBorder(),
          ),
        ),
        if (_sessionValidation != null)
          Text(_sessionValidation!,
              key: const ValueKey('model-session-validation')),
        const SizedBox(height: 10),
        FilledButton.icon(
          key: const ValueKey('model-session-fetch'),
          onPressed: _loadingSession ? null : _loadSession,
          icon: const Icon(Icons.search),
          label: const Text('Load session evidence'),
        ),
        if (_loadingSession)
          const LinearProgressIndicator(key: ValueKey('model-session-loading')),
        if (_sessionError != null) _problem(_sessionError!, stale: session != null),
        if (session != null) ...[
          const SizedBox(height: 14),
          Card(
            key: const ValueKey('model-session-summary'),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Session ${_short(session.id)}',
                      style: theme.textTheme.titleMedium),
                  Text("Captured by ${session.createdBy} · ${session.createdAt?.toIso8601String() ?? 'Unknown time'}"),
                  Text('Enrollment ${_short(session.fingerprint)}'),
                  Text('Participants: ${session.participants.length}'),
                  const SizedBox(height: 6),
                  ...session.participants.map((item) => Text(
                    '${item.role} · ${_short(item.versionId)}',
                  )),
                  const SizedBox(height: 6),
                  const Text(
                    'Source and runtime independently verified: NO. '
                    'LIVE / capital authority: DISABLED.',
                    key: ValueKey('model-session-no-live'),
                  ),
                ],
              ),
            ),
          ),
        ],
        if (_aggregateEvidence != null) ...[
          const SizedBox(height: 12),
          Card(
            key: const ValueKey('model-session-evidence'),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Recorded PAPER/SHADOW evidence',
                      style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  Text('Safety state: ${_aggregateEvidence!.sessionSafetyState} '
                      '(revision ${_aggregateEvidence!.sessionStateRevision})'),
                  Text('Matched cases: ${_aggregateEvidence!.matchedCases} · '
                      'Champion ENTER: ${_aggregateEvidence!.championEnterCases}'),
                  Text('Challenger disagreements: '
                      '${_aggregateEvidence!.shadowDisagreementCases}'),
                  Text('Linked PAPER orders: ${_aggregateEvidence!.linkedPaperOrders} '
                      '· Unlinked ENTER: ${_aggregateEvidence!.unlinkedChampionEnters}'),
                  Text('Fill-quantity mismatches: '
                      '${_aggregateEvidence!.paperFillQuantityMismatchOrders}'),
                  Text('Recorded PAPER fills: '
                      '${_aggregateEvidence!.recordedFillQuantity}'),
                  Text('Recorded PAPER fees: '
                      '${_aggregateEvidence!.recordedPaperFees} · '
                      'Slippage: ${_aggregateEvidence!.recordedPaperSlippage}'),
                  const SizedBox(height: 8),
                  const Text(
                    'Independent data, inference, broker reconciliation, soak '
                    'and LIVE acceptance: NOT VERIFIED.',
                    key: ValueKey('model-evidence-unverified'),
                  ),
                  const SizedBox(height: 8),
                  Text('Pending/failed checks: '
                      '${_aggregateEvidence!.reasonCodes.join(', ')}'),
                ],
              ),
            ),
          ),
        ],
        if (cases != null && cases.isEmpty)
          const Card(
            key: ValueKey('model-session-empty'),
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text('No matched PAPER/SHADOW decisions recorded in this session.'),
            ),
          ),
        if (cases != null && cases.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text('Recorded decisions: ${cases.length}',
              style: theme.textTheme.titleMedium),
          ...cases.map((event) => Card(
            key: ValueKey('model-case-${event.id}'),
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${event.symbol} · ${event.id}',
                    style: theme.textTheme.titleMedium),
                Text(event.decisionAt?.toIso8601String() ?? 'Unknown decision time'),
                const SizedBox(height: 6),
                Text(event.hasDisagreement
                    ? 'Champion/challenger disagreement'
                    : 'No reported candidate disagreement',
                    key: ValueKey('model-case-disagreement-${event.id}')),
                ...event.candidates.map((decision) => Text(
                  '${decision.role} · ${decision.action} · ${_short(decision.versionId)}',
                )),
                const SizedBox(height: 6),
                Text('Case hash: ${_short(event.caseFingerprint)}'),
                const Text('Independent source validation: NOT VERIFIED'),
              ]),
            ),
          )),
        ],
      ],
    );
  }

  static String _short(String id) =>
      id.length <= 16 ? id : '${id.substring(0, 12)}…';
}
