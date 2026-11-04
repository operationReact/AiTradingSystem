import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/research_experiment_job_repository.dart';

/// ADMIN-only research job launcher. Queuing is not model execution or PAPER acceptance.
class ResearchExperimentJobsPage extends StatefulWidget {
  const ResearchExperimentJobsPage({required this.repository, super.key});

  final ResearchExperimentJobRepository repository;

  @override
  State<ResearchExperimentJobsPage> createState() => _ResearchExperimentJobsPageState();
}

class _ResearchExperimentJobsPageState extends State<ResearchExperimentJobsPage> {
  final TextEditingController _runnerVersion = TextEditingController();
  List<ResearchExperimentSpec>? _specs;
  List<ResearchExperimentJob>? _jobs;
  String? _selectedSpec;
  String _runnerType = 'PYTHON_INTELLIGENCE';
  int _attempts = 1;
  bool _loading = false;
  bool _submitting = false;
  ApiFailure? _failure;
  String? _localError;
  ResearchExperimentJob? _lastEnqueued;
  ResearchExperimentResult? _selectedResult;
  String? _resultError;
  String? _loadingResultId;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _runnerVersion.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_loading || _submitting) return;
    setState(() {
      _loading = true;
      _failure = null;
      _localError = null;
    });
    try {
      final results = await Future.wait<Object>([
        widget.repository.listSpecs(),
        widget.repository.listJobs(),
      ]);
      if (!mounted) return;
      final specs = results[0] as List<ResearchExperimentSpec>;
      final jobs = results[1] as List<ResearchExperimentJob>;
      setState(() {
        _specs = specs;
        _jobs = jobs;
        if (!specs.any((item) => item.id == _selectedSpec)) {
          _selectedSpec = null;
        }
      });
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() {
        _failure = failure;
        if (_denied(failure)) _purge();
      });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  bool _denied(ApiFailure failure) =>
      failure is ApiAuthenticationRequired ||
      (failure is ApiHttpFailure &&
          (failure.statusCode == 401 || failure.statusCode == 403));

  void _purge() {
    _specs = null;
    _jobs = null;
    _selectedSpec = null;
    _lastEnqueued = null;
    _selectedResult = null;
    _loadingResultId = null;
  }

  Future<void> _enqueue() async {
    if (_loading || _submitting) return;
    final experimentId = _selectedSpec;
    final runnerVersion = _runnerVersion.text.trim();
    final pattern = RegExp(r'^[A-Za-z0-9][A-Za-z0-9._-]{2,79}$');
    if (experimentId == null || !pattern.hasMatch(runnerVersion)) {
      setState(() => _localError =
          'Select a registered experiment and enter a runner version (3–80 letters, digits, dot, dash or underscore).');
      return;
    }
    final approved = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Queue research experiment?'),
        content: Text(
          'Experiment: $experimentId\n'
          'Runner: $_runnerType / $runnerVersion\n'
          'Maximum attempts: $_attempts\n\n'
          'This queues a Java-governed research job only. It cannot place PAPER/LIVE '
          'orders and does not prove that any worker has run.',
        ),
        actions: [
          TextButton(
            key: const ValueKey('research-job-cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('research-job-confirm'),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Confirm queue'),
          ),
        ],
      ),
    );
    if (!mounted || approved != true) return;

    setState(() {
      _submitting = true;
      _failure = null;
      _localError = null;
      _lastEnqueued = null;
      _selectedResult = null;
    });
    try {
      final job = await widget.repository.enqueue(
        experimentId: experimentId,
        runnerType: _runnerType,
        runnerVersion: runnerVersion,
        maxAttempts: _attempts,
      );
      if (!mounted) return;
      // The reply is merely queued/claimable evidence, not a completed model run.
      setState(() => _lastEnqueued = job);
      final actual = await widget.repository.listJobs();
      if (!mounted) return;
      setState(() => _jobs = actual);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() {
        _failure = failure;
        if (_denied(failure)) _purge();
      });
    } catch (_) {
      if (mounted) {
        setState(() => _localError = 'Unable to confirm research job enqueue. Refresh server evidence.');
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _showResult(ResearchExperimentJob job) async {
    if (job.status != 'SUCCEEDED' || _loadingResultId != null) return;
    setState(() {
      _loadingResultId = job.id;
      _resultError = null;
      _selectedResult = null;
    });
    try {
      final result = await widget.repository.result(job.id);
      if (!mounted) return;
      setState(() => _selectedResult = result);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() {
        _resultError = _denied(failure)
            ? 'ADMIN access was revoked; clear cached results and sign in again.'
            : 'Immutable result unavailable: ${failure.code}';
        if (_denied(failure)) _purge();
      });
    } catch (_) {
      if (mounted) {
        setState(() => _resultError = 'Result could not be independently read back.');
      }
    } finally {
      if (mounted) setState(() => _loadingResultId = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final specs = _specs;
    final jobs = _jobs;
    final theme = Theme.of(context);
    final denied = _failure != null && _denied(_failure!);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Research experiment jobs'),
        actions: [
          IconButton(
            key: const ValueKey('research-job-refresh'),
            tooltip: 'Refresh Java experiment evidence',
            onPressed: _loading || _submitting ? null : _refresh,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          key: const ValueKey('research-job-page'),
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 100),
          children: [
            Text('Experiment launcher', style: theme.textTheme.headlineSmall),
            const SizedBox(height: 8),
            const Card(
              key: ValueKey('research-job-safety'),
              child: Padding(
                padding: EdgeInsets.all(14),
                child: Text(
                  'ADMIN research queue only. Java governance checks model eligibility. '
                  'QUEUED is not RUNNING or SUCCEEDED; even SUCCEEDED research results '
                  'never authorize trading capital or LIVE orders.',
                ),
              ),
            ),
            if (_loading && specs == null)
              const LinearProgressIndicator(key: ValueKey('research-job-loading')),
            if (_failure != null)
              Card(
                key: ValueKey(denied
                    ? 'research-job-access-denied'
                    : specs == null ? 'research-job-error' : 'research-job-stale'),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(denied
                      ? 'ADMIN access required. Cached research and jobs have been cleared.'
                      : specs == null
                          ? 'Research records unavailable: ${_failure!.code}'
                          : 'Refresh failed (${_failure!.code}); showing last-known records.'),
                ),
              ),
            if (_localError != null)
              Card(
                key: const ValueKey('research-job-validation'),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(_localError!),
                ),
              ),
            if (!denied && specs != null) ...[
              const SizedBox(height: 12),
              Text('Registered experiments', style: theme.textTheme.titleLarge),
              const SizedBox(height: 8),
              if (specs.isEmpty)
                const Card(
                  key: ValueKey('research-job-no-specs'),
                  child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Text('No immutable experiments have been registered in Java.'),
                  ),
                )
              else ...[
                DropdownButtonFormField<String>(
                  key: const ValueKey('research-job-spec-picker'),
                  isExpanded: true,
                  initialValue: _selectedSpec,
                  decoration: const InputDecoration(
                    labelText: 'Select immutable experiment specification',
                    border: OutlineInputBorder(),
                  ),
                  items: specs.take(100).map((item) => DropdownMenuItem<String>(
                    value: item.id,
                    child: Text('${_short(item.id)} · model ${_short(item.modelVersionId)}',
                        overflow: TextOverflow.ellipsis),
                  )).toList(growable: false),
                  onChanged: _submitting
                      ? null : (id) => setState(() => _selectedSpec = id),
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  key: const ValueKey('research-job-runner-picker'),
                  initialValue: _runnerType,
                  decoration: const InputDecoration(
                    labelText: 'Registered runner',
                    border: OutlineInputBorder(),
                  ),
                  items: const [
                    DropdownMenuItem(
                      value: 'PYTHON_INTELLIGENCE',
                      child: Text('Python intelligence research'),
                    ),
                    DropdownMenuItem(
                      value: 'JAVA_BACKTEST',
                      child: Text('Java research backtest'),
                    ),
                  ],
                  onChanged: _submitting
                      ? null : (value) {
                          if (value != null) setState(() => _runnerType = value);
                        },
                ),
                const SizedBox(height: 12),
                TextField(
                  key: const ValueKey('research-job-version'),
                  controller: _runnerVersion,
                  enabled: !_submitting,
                  maxLength: 80,
                  decoration: const InputDecoration(
                    labelText: 'Exact runner version',
                    hintText: 'python-research-v4',
                    border: OutlineInputBorder(),
                  ),
                ),
                DropdownButtonFormField<int>(
                  key: const ValueKey('research-job-attempts'),
                  initialValue: _attempts,
                  decoration: const InputDecoration(
                    labelText: 'Maximum attempts',
                    border: OutlineInputBorder(),
                  ),
                  items: const [
                    DropdownMenuItem(value: 1, child: Text('1 attempt')),
                    DropdownMenuItem(value: 2, child: Text('2 attempts')),
                    DropdownMenuItem(value: 3, child: Text('3 attempts')),
                  ],
                  onChanged: _submitting
                      ? null : (value) {
                          if (value != null) setState(() => _attempts = value);
                        },
                ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  key: const ValueKey('research-job-enqueue'),
                  onPressed: _loading || _submitting ? null : _enqueue,
                  icon: const Icon(Icons.science_outlined),
                  label: const Text('Review and queue research job'),
                ),
              ],
              if (_lastEnqueued != null)
                Card(
                  key: const ValueKey('research-job-enqueued'),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text('Java job ${_lastEnqueued!.id} · ${_lastEnqueued!.status}. '
                        'Runner progress requires an authorized worker; no model execution is asserted.'),
                  ),
                ),
              const SizedBox(height: 24),
              Text('Java job queue', style: theme.textTheme.titleLarge),
              const SizedBox(height: 8),
              if (jobs == null)
                const Text('Job list not loaded.')
              else if (jobs.isEmpty)
                const Card(
                  key: ValueKey('research-job-empty'),
                  child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Text('No research jobs recorded by Java.'),
                  ),
                )
              else
                ...jobs.take(50).map((job) => Card(
                  key: ValueKey('research-job-${job.id}'),
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('${job.status} · ${job.runnerType}',
                            style: theme.textTheme.titleMedium),
                        Text('Experiment ${_short(job.experimentId)}'),
                        Text('Runner version ${job.runnerVersion}'),
                        Text('Attempts ${job.attempt}/${job.maxAttempts}'),
                        Text('Spec ${_short(job.specFingerprint)}'),
                        if (job.failureCode != null)
                          Text('Failure code: ${job.failureCode}'),
                        if (job.status == 'SUCCEEDED')
                          TextButton(
                            key: ValueKey('research-job-result-${job.id}'),
                            onPressed: _loadingResultId == null
                                ? () => _showResult(job) : null,
                            child: const Text('Read immutable result'),
                          ),
                      ],
                    ),
                  ),
                )),
              if (_loadingResultId != null)
                const LinearProgressIndicator(
                  key: ValueKey('research-job-result-loading'),
                ),
              if (_resultError != null)
                Card(child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text(_resultError!),
                )),
              if (_selectedResult != null)
                Card(
                  key: const ValueKey('research-job-result-evidence'),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Research assessment: ${_selectedResult!.assessment}'),
                        Text('Samples: ${_selectedResult!.sampleCount}'),
                        Text('Result fingerprint: ${_selectedResult!.resultFingerprint}'),
                        Text('Artifact fingerprint: ${_selectedResult!.artifactFingerprint}'),
                        const Text('Not a real-world profitability or LIVE acceptance certificate.'),
                      ],
                    ),
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }

  static String _short(String value) =>
      value.length <= 12 ? value : '${value.substring(0, 12)}…';
}
