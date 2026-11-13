import '../../../data/authenticated_api_client.dart';

/// Server-materialized Mintick Mind progress (contract `mind.progress.v1`).
///
/// The client never computes completion itself. It additionally rejects any
/// payload that claims DONE without runtime proof on the current, known
/// backend revision, so a mock, fixture or merged PR can never render green.
enum MindProgressStatus {
  notStarted('NOT_STARTED', 'NOT STARTED'),
  mock('MOCK', 'MOCK'),
  implementing('IMPLEMENTING', 'IMPLEMENTING'),
  codeComplete('CODE_COMPLETE', 'CODE COMPLETE'),
  tested('TESTED', 'TESTED'),
  deployed('DEPLOYED', 'DEPLOYED'),
  runtimeVerified('RUNTIME_VERIFIED', 'DONE · VERIFIED'),
  dataBlocked('DATA_BLOCKED', 'DATA BLOCKED'),
  rightsBlocked('RIGHTS_BLOCKED', 'RIGHTS BLOCKED'),
  failed('FAILED', 'FAILED');

  const MindProgressStatus(this.code, this.label);
  final String code;
  final String label;

  bool get isDone => this == MindProgressStatus.runtimeVerified;
  bool get isBlocked =>
      this == MindProgressStatus.dataBlocked || this == MindProgressStatus.rightsBlocked;

  static MindProgressStatus parse(Object? raw) {
    for (final value in values) {
      if (value.code == raw) return value;
    }
    throw const FormatException('Unknown Mind progress status');
  }
}

class MindEvidenceLink {
  const MindEvidenceLink({required this.kind, this.path, this.ref});
  final String kind;
  final String? path;
  final String? ref;
  String get display => path ?? ref ?? kind;
}

class MindProgressRuntime {
  const MindProgressRuntime({
    this.lastVerifiedAt,
    this.lastFailedAt,
    this.lastBlockedAt,
    this.verifiedRevision,
    this.datasetVersion,
    this.evidenceRef,
    this.runtimeNote,
    this.recheckRequestedAt,
    this.ownNotes = 0,
  });
  final DateTime? lastVerifiedAt;
  final DateTime? lastFailedAt;
  final DateTime? lastBlockedAt;
  final String? verifiedRevision;
  final String? datasetVersion;
  final String? evidenceRef;
  final String? runtimeNote;
  final DateTime? recheckRequestedAt;
  final int ownNotes;
}

class MindProgressItem {
  const MindProgressItem({
    required this.id,
    required this.kind,
    required this.name,
    required this.group,
    required this.owner,
    required this.status,
    required this.declaredStatus,
    required this.runtime,
    this.parentId,
    this.blockerCode,
    this.requiredInputs = const [],
    this.dependsOn = const [],
    this.implementationCode,
    this.implementationMatch,
    this.implementationRuntime,
    this.evidence = const [],
    this.missing = const [],
    this.note,
  });

  final String id;
  final String kind;
  final String name;
  final String group;
  final String? parentId;
  final String owner;
  final MindProgressStatus status;
  final MindProgressStatus declaredStatus;
  final String? blockerCode;
  final List<String> requiredInputs;
  final List<String> dependsOn;
  final String? implementationCode;
  final String? implementationMatch;
  final String? implementationRuntime;
  final List<MindEvidenceLink> evidence;
  final List<String> missing;
  final String? note;
  final MindProgressRuntime runtime;

  bool get done => status.isDone;

  bool matches(String query) {
    if (query.isEmpty) return true;
    final q = query.toLowerCase();
    return [id, name, group, owner, status.label, blockerCode ?? '', implementationCode ?? '']
        .any((v) => v.toLowerCase().contains(q));
  }

  static MindProgressItem parse(Object? raw, {required String backendRevision,
      required bool revisionKnown}) {
    if (raw is! Map<String, dynamic>) throw const FormatException('Invalid progress item');
    final id = _string(raw['id']);
    final status = MindProgressStatus.parse(raw['status']);
    final declared = MindProgressStatus.parse(raw['declaredStatus']);
    final runtime = _runtime(raw['runtime']);
    final serverDone = raw['done'];
    if (serverDone is! bool || serverDone != status.isDone) {
      throw const FormatException('Inconsistent DONE flag');
    }
    if (status.isDone) {
      // Green requires runtime proof on this exact, known backend revision.
      if (!revisionKnown || runtime.lastVerifiedAt == null ||
          runtime.verifiedRevision != backendRevision ||
          declared == MindProgressStatus.mock ||
          declared == MindProgressStatus.notStarted) {
        throw const FormatException('DONE without runtime proof');
      }
    }
    final impl = raw['implementation'];
    return MindProgressItem(
      id: id,
      kind: _string(raw['kind']),
      name: _string(raw['name']),
      group: raw['group'] is String ? raw['group'] as String : '',
      parentId: raw['parentId'] as String?,
      owner: _string(raw['owner']),
      status: status,
      declaredStatus: declared,
      blockerCode: raw['blockerCode'] as String?,
      requiredInputs: _strings(raw['requiredInputs']),
      dependsOn: _strings(raw['dependsOn']),
      implementationCode: impl is Map<String, dynamic> ? impl['code'] as String? : null,
      implementationMatch: impl is Map<String, dynamic> ? impl['match'] as String? : null,
      implementationRuntime: impl is Map<String, dynamic> ? impl['runtime'] as String? : null,
      evidence: [
        for (final e in (raw['evidence'] is List ? raw['evidence'] as List : const []))
          if (e is Map<String, dynamic> && e['kind'] is String)
            MindEvidenceLink(kind: e['kind'] as String,
                path: e['path'] as String?, ref: e['ref'] as String?),
      ],
      missing: _strings(raw['missing']),
      note: raw['note'] as String?,
      runtime: runtime,
    );
  }
}

class MindProgressSummary {
  const MindProgressSummary({
    required this.kind,
    required this.denominator,
    required this.done,
    required this.tested,
    required this.inProgress,
    required this.blocked,
    required this.mock,
    required this.notStarted,
    required this.failed,
  });
  final String kind;
  final int denominator;
  final int done;
  final int tested;
  final int inProgress;
  final int blocked;
  final int mock;
  final int notStarted;
  final int failed;
}

class MindPaperStage {
  const MindPaperStage({required this.capabilityId, required this.name, required this.status});
  final String capabilityId;
  final String name;
  final MindProgressStatus status;
}

class MindProgressSnapshot {
  const MindProgressSnapshot({
    required this.manifestVersion,
    required this.auditedAt,
    required this.generatedAt,
    required this.backendRevision,
    required this.revisionKnown,
    required this.paperStage,
    required this.summaries,
    required this.items,
  });

  final String manifestVersion;
  final String auditedAt;
  final DateTime generatedAt;
  final String backendRevision;
  final bool revisionKnown;
  final MindPaperStage paperStage;
  final Map<String, MindProgressSummary> summaries;
  final List<MindProgressItem> items;

  MindProgressSummary? summary(String kind) => summaries[kind];

  factory MindProgressSnapshot.fromJson(Object? decoded) {
    if (decoded is! Map<String, dynamic> || decoded['contractVersion'] != 'mind.progress.v1') {
      throw const FormatException('Unsupported Mind progress contract');
    }
    final generatedAt = DateTime.tryParse(_string(decoded['generatedAt']));
    if (generatedAt == null) throw const FormatException('Missing progress timestamp');
    final revision = _string(decoded['backendRevision']);
    final known = decoded['revisionKnown'] == true;
    final rawItems = decoded['items'];
    final rawSummaries = decoded['summaries'];
    if (rawItems is! List || rawSummaries is! List) {
      throw const FormatException('Invalid progress lists');
    }
    final items = [
      for (final raw in rawItems)
        MindProgressItem.parse(raw, backendRevision: revision, revisionKnown: known),
    ];
    final summaries = <String, MindProgressSummary>{};
    for (final raw in rawSummaries) {
      if (raw is! Map<String, dynamic>) throw const FormatException('Invalid summary');
      final kind = _string(raw['kind']);
      final s = MindProgressSummary(
        kind: kind,
        denominator: _int(raw['denominator']),
        done: _int(raw['done']),
        tested: _int(raw['tested']),
        inProgress: _int(raw['inProgress']),
        blocked: _int(raw['blocked']),
        mock: _int(raw['mock']),
        notStarted: _int(raw['notStarted']),
        failed: _int(raw['failed']),
      );
      // Counts must reconcile with the items actually delivered.
      final ofKind = items.where((i) => i.kind == kind).toList();
      if (s.denominator != ofKind.length || s.done != ofKind.where((i) => i.done).length) {
        throw const FormatException('Progress summary does not reconcile');
      }
      summaries[kind] = s;
    }
    final stage = decoded['paperStage'];
    if (stage is! Map<String, dynamic>) throw const FormatException('Missing PAPER stage');
    return MindProgressSnapshot(
      manifestVersion: _string(decoded['manifestVersion']),
      auditedAt: _string(decoded['auditedAt']),
      generatedAt: generatedAt,
      backendRevision: revision,
      revisionKnown: known,
      paperStage: MindPaperStage(
        capabilityId: _string(stage['capabilityId']),
        name: _string(stage['name']),
        status: MindProgressStatus.parse(stage['status']),
      ),
      summaries: summaries,
      items: List.unmodifiable(items),
    );
  }
}

class MindProgressEvent {
  const MindProgressEvent({required this.type, required this.observedAt,
      required this.backendRevision, this.detail, this.evidenceRef, this.datasetVersion});
  final String type;
  final DateTime observedAt;
  final String backendRevision;
  final String? detail;
  final String? evidenceRef;
  final String? datasetVersion;
}

class MindProgressDetail {
  const MindProgressDetail({required this.item, required this.history});
  final MindProgressItem item;
  final List<MindProgressEvent> history;

  factory MindProgressDetail.fromJson(Object? decoded) {
    if (decoded is! Map<String, dynamic> || decoded['contractVersion'] != 'mind.progress.v1') {
      throw const FormatException('Unsupported Mind progress detail');
    }
    final revision = _string(decoded['backendRevision']);
    final known = RegExp(r'^[0-9a-f]{7,40}$').hasMatch(revision);
    final history = <MindProgressEvent>[];
    for (final raw in (decoded['history'] is List ? decoded['history'] as List : const [])) {
      if (raw is! Map<String, dynamic>) continue;
      final at = DateTime.tryParse('${raw['observedAt']}');
      if (at == null) continue;
      history.add(MindProgressEvent(
        type: _string(raw['type']), observedAt: at,
        backendRevision: _string(raw['backendRevision']),
        detail: raw['detail'] as String?, evidenceRef: raw['evidenceRef'] as String?,
        datasetVersion: raw['datasetVersion'] as String?));
    }
    return MindProgressDetail(
      item: MindProgressItem.parse(decoded['item'], backendRevision: revision, revisionKnown: known),
      history: List.unmodifiable(history),
    );
  }
}

abstract interface class MindProgressRepository {
  Future<MindProgressSnapshot> load();
  Future<MindProgressDetail> detail(String capabilityId);
  /// `type` is NOTE or RECHECK_REQUESTED. There is no status-changing call.
  Future<void> annotate(String capabilityId, {required String type, String? text});
}

class HttpMindProgressRepository implements MindProgressRepository {
  HttpMindProgressRepository(this.api);
  final AuthenticatedApiClient api;
  static const _base = '/api/customer/mind/v1/progress';

  @override
  Future<MindProgressSnapshot> load() async =>
      MindProgressSnapshot.fromJson(await api.getJson(_base));

  @override
  Future<MindProgressDetail> detail(String capabilityId) async {
    _requireId(capabilityId);
    return MindProgressDetail.fromJson(await api.getJson('$_base/items/$capabilityId'));
  }

  @override
  Future<void> annotate(String capabilityId, {required String type, String? text}) async {
    _requireId(capabilityId);
    if (type != 'NOTE' && type != 'RECHECK_REQUESTED') {
      throw ArgumentError.value(type, 'type');
    }
    final key = 'mind-note-${DateTime.now().toUtc().microsecondsSinceEpoch}';
    await api.postJson('$_base/items/$capabilityId/notes',
        headers: {'Idempotency-Key': key},
        body: {'type': type, if (text != null && text.trim().isNotEmpty) 'text': text.trim()});
  }

  static void _requireId(String id) {
    if (!RegExp(r'^[a-z]+\.[A-Za-z0-9._-]{1,90}$').hasMatch(id) || id.contains('..')) {
      throw ArgumentError.value(id, 'capabilityId');
    }
  }
}

String _string(Object? raw) {
  if (raw is! String || raw.isEmpty) throw const FormatException('Missing progress field');
  return raw;
}

int _int(Object? raw) {
  if (raw is! int || raw < 0) throw const FormatException('Invalid progress count');
  return raw;
}

List<String> _strings(Object? raw) =>
    raw is List ? List.unmodifiable(raw.whereType<String>()) : const [];

MindProgressRuntime _runtime(Object? raw) {
  if (raw is! Map<String, dynamic>) return const MindProgressRuntime();
  DateTime? at(String key) => raw[key] is String ? DateTime.tryParse(raw[key] as String) : null;
  return MindProgressRuntime(
    lastVerifiedAt: at('lastVerifiedAt'),
    lastFailedAt: at('lastFailedAt'),
    lastBlockedAt: at('lastBlockedAt'),
    verifiedRevision: raw['verifiedRevision'] as String?,
    datasetVersion: raw['datasetVersion'] as String?,
    evidenceRef: raw['evidenceRef'] as String?,
    runtimeNote: raw['runtimeNote'] as String?,
    recheckRequestedAt: at('recheckRequestedAt'),
    ownNotes: raw['ownNotes'] is int ? raw['ownNotes'] as int : 0,
  );
}
