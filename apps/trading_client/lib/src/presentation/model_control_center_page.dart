import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/model_control_center_repository.dart';

/// Connected ADMIN V4 evidence and bounded session de-risk controls; no LIVE or model promotion.
class ModelControlCenterPage extends StatefulWidget {
  const ModelControlCenterPage({required this.repository, super.key});
  final ModelControlCenterRepository repository;

  @override
  State<ModelControlCenterPage> createState() => _ModelControlCenterPageState();
}

class _ModelControlCenterPageState extends State<ModelControlCenterPage> {
  final TextEditingController _sessionController = TextEditingController();
  final TextEditingController _researchCaseController = TextEditingController();
  List<RegisteredModelEvidence>? _models;
  List<ModelVersionEvidence>? _versions;
  RegisteredModelEvidence? _selected;
  MatchedSessionEvidence? _session;
  List<MatchedCaseEvidence>? _cases;
  MatchedSessionAggregateEvidence? _aggregateEvidence;
  ResearchReceiptReport? _researchReport;
  ApiFailure? _researchError;
  bool _loadingResearch = false;
  String? _researchValidation;
  MatchedSessionSafetyState? _safety;
  List<MatchedSessionSafetyChange>? _safetyHistory;
  ApiFailure? _safetyError;
  bool _loadingSafety = false;
  bool _updatingSafety = false;
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
    _researchCaseController.clear();
    setState(() {
      _models = null;
      _versions = null;
      _selected = null;
      _session = null;
      _cases = null;
      _aggregateEvidence = null;
      _researchReport = null;
      _researchError = null;
      _researchValidation = null;
      _loadingResearch = false;
      _safety = null;
      _safetyHistory = null;
      _safetyError = null;
      _loadingSafety = false;
      _updatingSafety = false;
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
    _researchCaseController.dispose();
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
      _researchReport = null;
      _researchError = null;
      _researchValidation = null;
      _researchCaseController.clear();
      _safety = null;
      _safetyHistory = null;
      _safetyError = null;
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
      await _loadSafety(id);
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

  Future<void> _loadSafety(String id) async {
    if (_loadingSafety || _updatingSafety) return;
    final generation = _authGeneration;
    setState(() {
      _loadingSafety = true;
      _safetyError = null;
    });
    try {
      final state = await widget.repository.safetyState(id);
      if (!mounted || generation != _authGeneration || _session?.id != id) return;
      final history = await widget.repository.safetyHistory(id);
      if (!mounted || generation != _authGeneration || _session?.id != id) return;
      // The independently read Java safety state must agree with the
      // session aggregate. A concurrent pause invalidates stale evidence.
      setState(() {
        if (_aggregateEvidence != null &&
            (_aggregateEvidence!.sessionSafetyState != state.state ||
             _aggregateEvidence!.sessionStateRevision != state.revision)) {
          _aggregateEvidence = null;
          _researchReport = null;
        }
        if (_researchReport != null &&
            (_researchReport!.sessionSafetyState != state.state ||
             _researchReport!.sessionSafetyRevision != state.revision)) {
          _researchReport = null;
        }
        _safety = state;
        _safetyHistory = history;
      });
    } on ApiFailure catch (error) {
      if (!mounted || generation != _authGeneration) return;
      if (_isAccessDenied(error)) {
        _clearRevokedEvidence(error);
      } else {
        setState(() {
          _safety = null;
          _safetyHistory = null;
          _safetyError = error;
        });
      }
    } finally {
      if (mounted && generation == _authGeneration) {
        setState(() => _loadingSafety = false);
      }
    }
  }

  Future<void> _loadResearchReceipts() async {
    final session = _session;
    if (session == null || _loadingResearch || _loadingSession) return;
    final key = _researchCaseController.text.trim();
    if (!RegExp(r'^[A-Za-z0-9][A-Za-z0-9._-]{2,95}
    final session = _session;
    final old = _safety;
    if (session == null || old == null || _updatingSafety ||
        _safetyError != null) {
      return;
    }
    final expected = switch (action) {
      'PAUSE' => 'PAUSED',
      'RESUME' => 'ACTIVE',
      'QUARANTINE' => 'QUARANTINED',
      _ => '',
    };
    if (expected.isEmpty || old.state == 'QUARANTINED' ||
        (action == 'PAUSE' && old.state != 'ACTIVE') ||
        (action == 'RESUME' && old.state != 'PAUSED')) {
      return;
    }

    String reasonDraft = '';
    final reason = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Confirm $action session control'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(action == 'QUARANTINE'
              ? 'Quarantine is permanent. This session cannot resume or accept new decisions.'
              : 'This changes Java PAPER/SHADOW evidence intake only. It does not control broker orders.'),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('matched-safety-reason'),
            onChanged: (value) => reasonDraft = value,
            maxLength: 512, maxLines: 2,
            decoration: const InputDecoration(
              labelText: 'Audit reason (at least 10 characters)',
              border: OutlineInputBorder(),
            ),
          ),
        ]),
        actions: [
          TextButton(
            key: const ValueKey('matched-safety-cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('matched-safety-confirm'),
            onPressed: () {
              final cleaned = reasonDraft.trim();
              if (cleaned.length < 10 || cleaned.length > 512) return;
              Navigator.of(dialogContext).pop(cleaned);
            },
            child: Text('Confirm $action'),
          ),
        ],
      ),
    );
    if (!mounted || reason == null || _session?.id != session.id) return;
    final generation = _authGeneration;
    setState(() { _updatingSafety = true; _safetyError = null; });
    try {
      final changed = await widget.repository.transitionSafety(session.id, action, reason);
      if (!mounted || generation != _authGeneration) return;
      if (changed.state != expected || changed.revision <= old.revision) {
        throw const ApiTransportFailure('MATCHED_SAFETY_NOT_CONFIRMED', null);
      }
      final confirmed = await widget.repository.safetyState(session.id);
      if (!mounted || generation != _authGeneration) return;
      final history = await widget.repository.safetyHistory(session.id);
      if (!mounted || generation != _authGeneration) return;
      if (confirmed.state != expected ||
          confirmed.fingerprint != changed.fingerprint ||
          confirmed.revision != changed.revision ||
          history.isEmpty || history.first.revision != confirmed.revision) {
        throw const ApiTransportFailure('MATCHED_SAFETY_READBACK_MISMATCH', null);
      }
      setState(() {
        _safety = confirmed;
        _safetyHistory = history;
        // Any control mutation invalidates the previously loaded aggregate.
        _aggregateEvidence = null;
        _researchReport = null;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Server confirmed $expected session control.')),
      );
    } on ApiFailure catch (error) {
      if (!mounted || generation != _authGeneration) return;
      if (_isAccessDenied(error)) {
        _clearRevokedEvidence(error);
      } else {
        setState(() { _safety = null; _safetyError = error; });
      }
    } finally {
      if (mounted && generation == _authGeneration) {
        setState(() => _updatingSafety = false);
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

  Widget _sessionSafetyPanel() {
    final session = _session;
    if (session == null) return const SizedBox.shrink();
    final safety = _safety;
    return Card(
      key: const ValueKey('matched-safety-panel'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('Session intake safety',
                style: Theme.of(context).textTheme.titleMedium)),
            IconButton(
              key: const ValueKey('matched-safety-refresh'),
              tooltip: 'Refresh Java safety state',
              onPressed: _loadingSafety || _updatingSafety
                  ? null : () => _loadSafety(session.id),
              icon: const Icon(Icons.refresh),
            ),
          ]),
          const Text(
            'Java safety state controls matched evidence intake only. '
            'It does not halt separate PAPER automation or authorize LIVE orders.',
          ),
          if (_loadingSafety || _updatingSafety)
            const LinearProgressIndicator(key: ValueKey('matched-safety-loading')),
          if (_safetyError != null)
            _problem(_safetyError!, stale: false),
          if (safety != null && _safetyError == null) ...[
            const SizedBox(height: 8),
            Text('Intake: ${safety.state}',
                key: const ValueKey('matched-safety-state')),
            Text('Revision ${safety.revision} · ${safety.updatedBy}'),
            Text('Reason: ${safety.reason}'),
            Text('Safety hash: ${_short(safety.fingerprint)}'),
            const Text('Capital authority: NONE'),
            const SizedBox(height: 10),
            Wrap(spacing: 8, runSpacing: 8, children: [
              if (safety.state == 'ACTIVE')
                OutlinedButton.icon(
                  key: const ValueKey('matched-safety-pause'),
                  onPressed: _updatingSafety
                      ? null : () => _transitionSafety('PAUSE'),
                  icon: const Icon(Icons.pause_circle_outline),
                  label: const Text('Pause intake'),
                ),
              if (safety.state == 'PAUSED')
                OutlinedButton.icon(
                  key: const ValueKey('matched-safety-resume'),
                  onPressed: _updatingSafety
                      ? null : () => _transitionSafety('RESUME'),
                  icon: const Icon(Icons.play_circle_outline),
                  label: const Text('Resume intake'),
                ),
              if (safety.state != 'QUARANTINED')
                FilledButton.tonalIcon(
                  key: const ValueKey('matched-safety-quarantine'),
                  onPressed: _updatingSafety
                      ? null : () => _transitionSafety('QUARANTINE'),
                  icon: const Icon(Icons.report_gmailerrorred_outlined),
                  label: const Text('Quarantine permanently'),
                ),
            ]),
            if (safety.state == 'QUARANTINED')
              const Text('Terminal quarantine: enroll a new session to recover.',
                  key: ValueKey('matched-safety-terminal')),
          ],
          if (_safetyHistory != null && _safetyHistory!.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('Immutable transition history',
                style: Theme.of(context).textTheme.titleSmall),
            ..._safetyHistory!.take(5).map((change) => Text(
              'Revision ${change.revision}: '
              '${change.previousState ?? 'START'} → ${change.newState} · '
              '${change.changedBy} · ${change.reason}',
              key: ValueKey('matched-safety-history-${change.revision}'),
            )),
          ],
        ]),
      ),
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
        if (session != null) ...[
          const SizedBox(height: 10),
          _sessionSafetyPanel(),
        ],
        if (session != null) ...[
          const SizedBox(height: 12),
          Card(
            key: const ValueKey('model-research-receipt-inspector'),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Research model receipts',
                      style: theme.textTheme.titleMedium),
                  const Text('Inspect one frozen case. Missing or uncertain '
                      'model outcomes cannot authorize PAPER decisions or LIVE trades.'),
                  const SizedBox(height: 10),
                  TextField(
                    key: const ValueKey('model-research-case-input'),
                    controller: _researchCaseController,
                    autocorrect: false,
                    enableSuggestions: false,
                    decoration: const InputDecoration(
                      labelText: 'Research case key',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  if (_researchValidation != null)
                    Text(_researchValidation!,
                        key: const ValueKey('model-research-case-validation')),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    key: const ValueKey('model-research-case-fetch'),
                    onPressed: _loadingResearch ? null : _loadResearchReceipts,
                    icon: const Icon(Icons.fact_check_outlined),
                    label: const Text('Inspect model receipts'),
                  ),
                  if (_loadingResearch)
                    const LinearProgressIndicator(
                        key: ValueKey('model-research-case-loading')),
                  if (_researchError != null)
                    _problem(_researchError!, stale: false),
                  if (_researchReport != null) ...[
                    const SizedBox(height: 12),
                    Text('Case: ${_researchReport!.caseKey}'),
                    Text('Session: ${_researchReport!.sessionSafetyState} '
                        '(revision ${_researchReport!.sessionSafetyRevision})'),
                    Text('Source captured: ${_researchReport!.sourceCaptured ? 'YES' : 'NO'} '
                        '· Features frozen: ${_researchReport!.featuresFrozen ? 'YES' : 'NO'}'),
                    Text('Validated current receipts: '
                        '${_researchReport!.successfulReceipts}/${_researchReport!.expectedModels}'),
                    Text('Missing/uncertain/expired: '
                        '${_researchReport!.unresolvedModels}'),
                    Text('V62 matched evidence finalized: '
                        '${_researchReport!.matchedCaseFinalized ? 'YES' : 'NO'}'),
                    const SizedBox(height: 8),
                    ..._researchReport!.models.map((model) => Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Text('${model.role} · ${model.family} '
                          '· ${_short(model.versionId)} '
                          '\n${model.status} · ${model.reasonCode} '
                          '· identity ${model.identityBound ? 'BOUND' : 'UNVERIFIED'}'),
                    )),
                    const Text(
                      'Independent licensed source, worker runtime, broker '
                      'reconciliation, PAPER soak and LIVE: NOT VERIFIED.',
                      key: ValueKey('model-research-receipts-unverified'),
                    ),
                  ],
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
).hasMatch(key)) {
      setState(() => _researchValidation = 'Enter a valid research case key.');
      return;
    }
    final generation = _authGeneration;
    setState(() {
      _loadingResearch = true;
      _researchReport = null;
      _researchError = null;
      _researchValidation = null;
    });
    try {
      final report = await widget.repository.researchReceipts(session.id, key);
      if (!mounted || generation != _authGeneration ||
          _session?.id != session.id ||
          _researchCaseController.text.trim() != key) return;
      if (report.sessionId != session.id ||
          report.caseKey != key ||
          report.enrollmentFingerprint != session.fingerprint ||
          (_safety != null &&
           (report.sessionSafetyState != _safety!.state ||
            report.sessionSafetyRevision != _safety!.revision))) {
        throw const ApiTransportFailure('RESEARCH_RECEIPT_SESSION_MISMATCH', null);
      }
      setState(() => _researchReport = report);
    } on ApiFailure catch (error) {
      if (!mounted || generation != _authGeneration ||
          _session?.id != session.id) return;
      if (_isAccessDenied(error)) {
        _clearRevokedEvidence(error);
      } else {
        setState(() { _researchReport = null; _researchError = error; });
      }
    } finally {
      if (mounted && generation == _authGeneration) {
        setState(() => _loadingResearch = false);
      }
    }
  }

  Future<void> _transitionSafety(String action) async {
    final session = _session;
    final old = _safety;
    if (session == null || old == null || _updatingSafety ||
        _safetyError != null) {
      return;
    }
    final expected = switch (action) {
      'PAUSE' => 'PAUSED',
      'RESUME' => 'ACTIVE',
      'QUARANTINE' => 'QUARANTINED',
      _ => '',
    };
    if (expected.isEmpty || old.state == 'QUARANTINED' ||
        (action == 'PAUSE' && old.state != 'ACTIVE') ||
        (action == 'RESUME' && old.state != 'PAUSED')) {
      return;
    }

    String reasonDraft = '';
    final reason = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Confirm $action session control'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(action == 'QUARANTINE'
              ? 'Quarantine is permanent. This session cannot resume or accept new decisions.'
              : 'This changes Java PAPER/SHADOW evidence intake only. It does not control broker orders.'),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('matched-safety-reason'),
            onChanged: (value) => reasonDraft = value,
            maxLength: 512, maxLines: 2,
            decoration: const InputDecoration(
              labelText: 'Audit reason (at least 10 characters)',
              border: OutlineInputBorder(),
            ),
          ),
        ]),
        actions: [
          TextButton(
            key: const ValueKey('matched-safety-cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('matched-safety-confirm'),
            onPressed: () {
              final cleaned = reasonDraft.trim();
              if (cleaned.length < 10 || cleaned.length > 512) return;
              Navigator.of(dialogContext).pop(cleaned);
            },
            child: Text('Confirm $action'),
          ),
        ],
      ),
    );
    if (!mounted || reason == null || _session?.id != session.id) return;
    final generation = _authGeneration;
    setState(() { _updatingSafety = true; _safetyError = null; });
    try {
      final changed = await widget.repository.transitionSafety(session.id, action, reason);
      if (!mounted || generation != _authGeneration) return;
      if (changed.state != expected || changed.revision <= old.revision) {
        throw const ApiTransportFailure('MATCHED_SAFETY_NOT_CONFIRMED', null);
      }
      final confirmed = await widget.repository.safetyState(session.id);
      if (!mounted || generation != _authGeneration) return;
      final history = await widget.repository.safetyHistory(session.id);
      if (!mounted || generation != _authGeneration) return;
      if (confirmed.state != expected ||
          confirmed.fingerprint != changed.fingerprint ||
          confirmed.revision != changed.revision ||
          history.isEmpty || history.first.revision != confirmed.revision) {
        throw const ApiTransportFailure('MATCHED_SAFETY_READBACK_MISMATCH', null);
      }
      setState(() {
        _safety = confirmed;
        _safetyHistory = history;
        // Any control mutation invalidates the previously loaded aggregate.
        _aggregateEvidence = null;
        _researchReport = null;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Server confirmed $expected session control.')),
      );
    } on ApiFailure catch (error) {
      if (!mounted || generation != _authGeneration) return;
      if (_isAccessDenied(error)) {
        _clearRevokedEvidence(error);
      } else {
        setState(() { _safety = null; _safetyError = error; });
      }
    } finally {
      if (mounted && generation == _authGeneration) {
        setState(() => _updatingSafety = false);
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

  Widget _sessionSafetyPanel() {
    final session = _session;
    if (session == null) return const SizedBox.shrink();
    final safety = _safety;
    return Card(
      key: const ValueKey('matched-safety-panel'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('Session intake safety',
                style: Theme.of(context).textTheme.titleMedium)),
            IconButton(
              key: const ValueKey('matched-safety-refresh'),
              tooltip: 'Refresh Java safety state',
              onPressed: _loadingSafety || _updatingSafety
                  ? null : () => _loadSafety(session.id),
              icon: const Icon(Icons.refresh),
            ),
          ]),
          const Text(
            'Java safety state controls matched evidence intake only. '
            'It does not halt separate PAPER automation or authorize LIVE orders.',
          ),
          if (_loadingSafety || _updatingSafety)
            const LinearProgressIndicator(key: ValueKey('matched-safety-loading')),
          if (_safetyError != null)
            _problem(_safetyError!, stale: false),
          if (safety != null && _safetyError == null) ...[
            const SizedBox(height: 8),
            Text('Intake: ${safety.state}',
                key: const ValueKey('matched-safety-state')),
            Text('Revision ${safety.revision} · ${safety.updatedBy}'),
            Text('Reason: ${safety.reason}'),
            Text('Safety hash: ${_short(safety.fingerprint)}'),
            const Text('Capital authority: NONE'),
            const SizedBox(height: 10),
            Wrap(spacing: 8, runSpacing: 8, children: [
              if (safety.state == 'ACTIVE')
                OutlinedButton.icon(
                  key: const ValueKey('matched-safety-pause'),
                  onPressed: _updatingSafety
                      ? null : () => _transitionSafety('PAUSE'),
                  icon: const Icon(Icons.pause_circle_outline),
                  label: const Text('Pause intake'),
                ),
              if (safety.state == 'PAUSED')
                OutlinedButton.icon(
                  key: const ValueKey('matched-safety-resume'),
                  onPressed: _updatingSafety
                      ? null : () => _transitionSafety('RESUME'),
                  icon: const Icon(Icons.play_circle_outline),
                  label: const Text('Resume intake'),
                ),
              if (safety.state != 'QUARANTINED')
                FilledButton.tonalIcon(
                  key: const ValueKey('matched-safety-quarantine'),
                  onPressed: _updatingSafety
                      ? null : () => _transitionSafety('QUARANTINE'),
                  icon: const Icon(Icons.report_gmailerrorred_outlined),
                  label: const Text('Quarantine permanently'),
                ),
            ]),
            if (safety.state == 'QUARANTINED')
              const Text('Terminal quarantine: enroll a new session to recover.',
                  key: ValueKey('matched-safety-terminal')),
          ],
          if (_safetyHistory != null && _safetyHistory!.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('Immutable transition history',
                style: Theme.of(context).textTheme.titleSmall),
            ..._safetyHistory!.take(5).map((change) => Text(
              'Revision ${change.revision}: '
              '${change.previousState ?? 'START'} → ${change.newState} · '
              '${change.changedBy} · ${change.reason}',
              key: ValueKey('matched-safety-history-${change.revision}'),
            )),
          ],
        ]),
      ),
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
        if (session != null) ...[
          const SizedBox(height: 10),
          _sessionSafetyPanel(),
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
