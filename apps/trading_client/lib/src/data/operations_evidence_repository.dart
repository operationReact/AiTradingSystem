import 'authenticated_api_client.dart';

class NewsEvidence {
  const NewsEvidence({required this.id, required this.source, required this.publisher, required this.sourceUrl, required this.publishedAt, required this.fetchedAt, required this.title, required this.linkedSymbols, required this.ingestionStatus, required this.summary, required this.summaryProvider, required this.summaryModel, required this.summaryGeneratedAt});
  final String id;
  final String source;
  final String? publisher;
  final String? sourceUrl;
  final DateTime? publishedAt;
  final DateTime? fetchedAt;
  final String title;
  final List<String> linkedSymbols;
  final String? ingestionStatus;
  final String? summary;
  final String? summaryProvider;
  final String? summaryModel;
  final DateTime? summaryGeneratedAt;

  factory NewsEvidence.fromJson(Map<String,dynamic> json) => NewsEvidence(
    id: '${json['id'] ?? ''}', source: '${json['source'] ?? 'UNKNOWN'}', publisher: json['publisher']?.toString(),
    sourceUrl: json['sourceUrl']?.toString(), publishedAt: DateTime.tryParse('${json['publishedAt'] ?? ''}'),
    fetchedAt: DateTime.tryParse('${json['fetchedAt'] ?? ''}'), title: '${json['title'] ?? ''}',
    linkedSymbols: json['linkedSymbols'] is List ? (json['linkedSymbols'] as List).map((e)=>'$e').toList(growable:false) : const [],
    ingestionStatus: json['ingestionStatus']?.toString(), summary: json['summary']?.toString(),
    summaryProvider: json['summaryProvider']?.toString(), summaryModel: json['summaryModel']?.toString(),
    summaryGeneratedAt: DateTime.tryParse('${json['summaryGeneratedAt'] ?? ''}'),
  );
}

class NotificationEvidence {
  const NotificationEvidence({required this.id, required this.type, required this.severity, required this.title, required this.message, required this.entityType, required this.entityId, required this.createdAt, required this.acknowledgedAt, required this.acknowledgedBy});
  final String id;
  final String type;
  final String severity;
  final String title;
  final String message;
  final String? entityType;
  final String? entityId;
  final DateTime? createdAt;
  final DateTime? acknowledgedAt;
  final String? acknowledgedBy;
  bool get acknowledged => acknowledgedAt != null;

  factory NotificationEvidence.fromJson(Map<String,dynamic> json) => NotificationEvidence(
    id:'${json['id'] ?? ''}', type:'${json['type'] ?? 'UNKNOWN'}', severity:'${json['severity'] ?? 'UNKNOWN'}',
    title:'${json['title'] ?? ''}', message:'${json['message'] ?? ''}', entityType:json['entityType']?.toString(),
    entityId:json['entityId']?.toString(), createdAt:DateTime.tryParse('${json['createdAt'] ?? ''}'),
    acknowledgedAt:DateTime.tryParse('${json['acknowledgedAt'] ?? ''}'), acknowledgedBy:json['acknowledgedBy']?.toString(),
  );
}

class AuditEvidence {
  const AuditEvidence({required this.id, required this.eventType, required this.entityType, required this.entityId, required this.correlationId, required this.actor, required this.actorSource, required this.details, required this.createdAt});
  final int id;
  final String eventType;
  final String entityType;
  final String entityId;
  final String? correlationId;
  final String? actor;
  final String? actorSource;
  final Object? details;
  final DateTime? createdAt;

  factory AuditEvidence.fromJson(Map<String,dynamic> json) => AuditEvidence(
    id:(json['id'] as num?)?.toInt() ?? 0, eventType:'${json['eventType'] ?? 'UNKNOWN'}', entityType:'${json['entityType'] ?? 'UNKNOWN'}',
    entityId:'${json['entityId'] ?? ''}', correlationId:json['correlationId']?.toString(), actor:json['actor']?.toString(),
    actorSource:json['actorSource']?.toString(), details:json['details'], createdAt:DateTime.tryParse('${json['createdAt'] ?? ''}'),
  );
}

class ExecutionModeEvidence {
  const ExecutionModeEvidence({required this.activeMode, required this.liveAvailable, required this.liveBlockers, required this.checkedAt});
  final String activeMode;
  final bool liveAvailable;
  final List<String> liveBlockers;
  final DateTime? checkedAt;
  factory ExecutionModeEvidence.fromJson(Map<String,dynamic> json) => ExecutionModeEvidence(
    activeMode:'${json['activeMode'] ?? 'UNKNOWN'}', liveAvailable:json['liveAvailable']==true,
    liveBlockers:json['liveBlockers'] is List ? (json['liveBlockers'] as List).map((e)=>'$e').toList(growable:false) : const [],
    checkedAt:DateTime.tryParse('${json['checkedAt'] ?? ''}'),
  );
}

class OperationsEvidenceSnapshot {
  const OperationsEvidenceSnapshot({required this.news, required this.notifications, required this.audit, required this.executionMode});
  final List<NewsEvidence> news;
  final List<NotificationEvidence> notifications;
  final List<AuditEvidence> audit;
  final ExecutionModeEvidence executionMode;
}

abstract interface class OperationsEvidenceRepository {
  Future<OperationsEvidenceSnapshot> fetch();
  Future<NotificationEvidence> acknowledge(String id);
}

class HttpOperationsEvidenceRepository implements OperationsEvidenceRepository {
  HttpOperationsEvidenceRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<OperationsEvidenceSnapshot> fetch() async {
    final result = await Future.wait([
      api.getJson('/api/news', query: const {'limit':'20','offset':'0'}),
      api.getJson('/api/notifications', query: const {'activeOnly':'true','page':'0','size':'20'}),
      api.getJson('/api/audit', query: const {'page':'0','size':'20'}),
      api.getJson('/api/system/execution-mode'),
    ]);
    if (result[0] is! Map || result[1] is! Map || result[2] is! Map || result[3] is! Map) {
      throw const ApiTransportFailure('INVALID_OPERATIONS_EVIDENCE_RESPONSE', null);
    }
    return OperationsEvidenceSnapshot(
      news: _items(result[0] as Map).map(NewsEvidence.fromJson).toList(growable:false),
      notifications: _items(result[1] as Map).map(NotificationEvidence.fromJson).toList(growable:false),
      audit: _items(result[2] as Map).map(AuditEvidence.fromJson).toList(growable:false),
      executionMode: ExecutionModeEvidence.fromJson(Map<String,dynamic>.from(result[3] as Map)),
    );
  }

  @override
  Future<NotificationEvidence> acknowledge(String id) async {
    final decoded = await api.postJson('/api/notifications/$id/acknowledge');
    if (decoded is! Map) throw const ApiTransportFailure('INVALID_NOTIFICATION_RESPONSE', null);
    return NotificationEvidence.fromJson(Map<String,dynamic>.from(decoded));
  }

  static Iterable<Map<String,dynamic>> _items(Map<dynamic,dynamic> page) {
    final raw=page['items'];
    if (raw is! List) return const [];
    return raw.whereType<Map>().map((item)=>Map<String,dynamic>.from(item));
  }
}
