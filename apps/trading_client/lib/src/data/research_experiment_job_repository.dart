import 'authenticated_api_client.dart';

/// Java-authored research records; neither a queue entry nor a result grants capital authority.
class ResearchExperimentSpec {
  const ResearchExperimentSpec({
    required this.id,
    required this.modelVersionId,
    required this.specFingerprint,
    required this.fromAt,
    required this.toAt,
  });

  final String id;
  final String modelVersionId;
  final String specFingerprint;
  final DateTime? fromAt;
  final DateTime? toAt;

  factory ResearchExperimentSpec.fromJson(Map<String, dynamic> json) =>
      ResearchExperimentSpec(
        id: _text(json, 'experimentId'),
        modelVersionId: _text(json, 'modelVersionId'),
        specFingerprint: _text(json, 'specFingerprint'),
        fromAt: DateTime.tryParse(_text(json, 'fromAt')),
        toAt: DateTime.tryParse(_text(json, 'toAt')),
      );
}

class ResearchExperimentJob {
  const ResearchExperimentJob({
    required this.id,
    required this.experimentId,
    required this.runnerType,
    required this.runnerVersion,
    required this.status,
    required this.attempt,
    required this.maxAttempts,
    required this.specFingerprint,
    required this.createdAt,
    required this.failureCode,
    required this.canAuthorizeCapital,
  });

  final String id;
  final String experimentId;
  final String runnerType;
  final String runnerVersion;
  final String status;
  final int attempt;
  final int maxAttempts;
  final String specFingerprint;
  final DateTime? createdAt;
  final String? failureCode;
  final bool canAuthorizeCapital;

  factory ResearchExperimentJob.fromJson(Map<String, dynamic> json) =>
      ResearchExperimentJob(
        id: _text(json, 'jobId'),
        experimentId: _text(json, 'experimentId'),
        runnerType: _text(json, 'runnerType'),
        runnerVersion: _text(json, 'runnerVersion'),
        status: _text(json, 'status'),
        attempt: (json['attempt'] as num?)?.toInt() ?? 0,
        maxAttempts: (json['maxAttempts'] as num?)?.toInt() ?? 0,
        specFingerprint: _text(json, 'specFingerprint'),
        createdAt: DateTime.tryParse(_text(json, 'createdAt')),
        failureCode: json['failureCode']?.toString(),
        canAuthorizeCapital: json['canAuthorizeCapital'] == true,
      );
}

class ResearchExperimentResult {
  const ResearchExperimentResult({
    required this.jobId,
    required this.assessment,
    required this.sampleCount,
    required this.resultFingerprint,
    required this.artifactFingerprint,
    required this.canAuthorizeCapital,
  });

  final String jobId;
  final String assessment;
  final int sampleCount;
  final String resultFingerprint;
  final String artifactFingerprint;
  final bool canAuthorizeCapital;

  factory ResearchExperimentResult.fromJson(Map<String, dynamic> json) =>
      ResearchExperimentResult(
        jobId: _text(json, 'jobId'),
        assessment: _text(json, 'assessment'),
        sampleCount: (json['sampleCount'] as num?)?.toInt() ?? 0,
        resultFingerprint: _text(json, 'resultFingerprint'),
        artifactFingerprint: _text(json, 'resultArtifactFingerprint'),
        canAuthorizeCapital: json['canAuthorizeCapital'] == true,
      );
}

String _text(Map<String, dynamic> json, String key) =>
    json[key]?.toString() ?? '';

Map<String, dynamic> _record(Object? raw) {
  if (raw is! Map) {
    throw const ApiTransportFailure('INVALID_RESEARCH_JOB_RESPONSE', null);
  }
  return Map<String, dynamic>.from(raw);
}

List<Map<String, dynamic>> _records(Object? raw) {
  if (raw is! List) {
    throw const ApiTransportFailure('INVALID_RESEARCH_JOB_RESPONSE', null);
  }
  return raw.map(_record).toList(growable: false);
}

abstract interface class ResearchExperimentJobRepository {
  Future<List<ResearchExperimentSpec>> listSpecs();
  Future<List<ResearchExperimentJob>> listJobs();
  Future<ResearchExperimentJob> enqueue({
    required String experimentId,
    required String runnerType,
    required String runnerVersion,
    required int maxAttempts,
  });
  Future<ResearchExperimentResult> result(String jobId);
}

class HttpResearchExperimentJobRepository implements ResearchExperimentJobRepository {
  const HttpResearchExperimentJobRepository(this.api);

  final AuthenticatedApiClient api;
  static const String _root = '/api/admin/model-governance';
  static const Set<String> allowedRunners = {
    'JAVA_BACKTEST',
    'PYTHON_INTELLIGENCE',
  };
  static final RegExp _uuidPattern = RegExp(
    r'^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$',
  );
  static final RegExp _runnerVersionPattern = RegExp(
    r'^[A-Za-z0-9][A-Za-z0-9._-]{2,79}$',
  );

  @override
  Future<List<ResearchExperimentSpec>> listSpecs() async {
    final result = await api.getJson('$_root/experiments');
    return _records(result).map(ResearchExperimentSpec.fromJson)
        .toList(growable: false);
  }

  @override
  Future<List<ResearchExperimentJob>> listJobs() async {
    final result = await api.getJson('$_root/experiment-jobs');
    return _records(result).map(ResearchExperimentJob.fromJson)
        .toList(growable: false);
  }

  @override
  Future<ResearchExperimentJob> enqueue({
    required String experimentId,
    required String runnerType,
    required String runnerVersion,
    required int maxAttempts,
  }) async {
    final version = runnerVersion.trim();
    if (!_uuidPattern.hasMatch(experimentId) ||
        !allowedRunners.contains(runnerType) ||
        !_runnerVersionPattern.hasMatch(version) ||
        maxAttempts < 1 || maxAttempts > 10) {
      throw ArgumentError('Valid experiment, runner, version and attempt limit are required');
    }
    final result = await api.postJson('$_root/experiment-jobs', body: {
      'experimentId': experimentId,
      'runnerType': runnerType,
      'runnerVersion': version,
      'maxAttempts': maxAttempts,
    });
    final job = ResearchExperimentJob.fromJson(_record(result));
    if (job.experimentId != experimentId || job.runnerType != runnerType ||
        job.runnerVersion != version || job.canAuthorizeCapital ||
        job.id.isEmpty) {
      throw const ApiTransportFailure('INVALID_ENQUEUED_RESEARCH_JOB', null);
    }
    return job;
  }

  @override
  Future<ResearchExperimentResult> result(String jobId) async {
    if (!_uuidPattern.hasMatch(jobId)) {
      throw ArgumentError.value(jobId, 'jobId', 'Invalid job identifier');
    }
    final result = ResearchExperimentResult.fromJson(
      _record(await api.getJson('$_root/experiment-jobs/$jobId/result')),
    );
    if (result.jobId != jobId || result.canAuthorizeCapital) {
      throw const ApiTransportFailure('INVALID_RESEARCH_RESULT_EVIDENCE', null);
    }
    return result;
  }
}
