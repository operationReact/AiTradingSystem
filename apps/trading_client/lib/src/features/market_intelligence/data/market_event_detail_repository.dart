import '../../../data/authenticated_api_client.dart';

/// A publisher receipt visible only while its stored DISPLAY rights are valid.
class MarketEventReceipt {
  const MarketEventReceipt({
    required this.receiptId,
    required this.sourceId,
    required this.publisher,
    required this.title,
    required this.sourceUrl,
    required this.firstObservedAt,
    required this.observedAt,
    required this.evidenceKind,
    this.publishedAt,
  });

  final String receiptId;
  final String sourceId;
  final String publisher;
  final String title;
  final Uri sourceUrl;
  final DateTime? publishedAt;
  final DateTime firstObservedAt;
  final DateTime observedAt;
  final String evidenceKind;

  factory MarketEventReceipt.fromJson(Object? input) {
    if (input is! Map<String, dynamic>) {
      throw const FormatException('Invalid source receipt');
    }
    final id = input['receiptId'];
    final source = input['sourceId'];
    final publisher = input['publisher'];
    final title = input['title'];
    final url = input['sourceUrl'];
    final kind = input['evidenceKind'];
    final uri = url is String ? Uri.tryParse(url) : null;
    final publishedRaw = input['publishedAt'];
    final published = publishedRaw == null
        ? null
        : DateTime.tryParse('$publishedRaw');
    final first = DateTime.tryParse('${input['firstObservedAt'] ?? ''}');
    final observed = DateTime.tryParse('${input['observedAt'] ?? ''}');
    if (id is! String || id.isEmpty ||
        source is! String || source.trim().isEmpty ||
        publisher is! String || publisher.trim().isEmpty ||
        title is! String || title.trim().isEmpty ||
        uri == null || !{'https', 'http'}.contains(uri.scheme) || uri.host.isEmpty ||
        kind is! String || kind.trim().isEmpty ||
        first == null || observed == null || observed.isBefore(first) ||
        (publishedRaw != null && published == null)) {
      throw const FormatException('Invalid publisher evidence');
    }
    return MarketEventReceipt(
      receiptId: id,
      sourceId: source,
      publisher: publisher.trim(),
      title: title.trim(),
      sourceUrl: uri,
      firstObservedAt: first.toUtc(),
      observedAt: observed.toUtc(),
      publishedAt: published?.toUtc(),
      evidenceKind: kind,
    );
  }
}

class MarketEventRevision {
  const MarketEventRevision({
    required this.version,
    required this.category,
    required this.effectiveKnownAt,
    required this.retraction,
    required this.evidenceTruncated,
    required this.evidence,
    this.correctionOfVersion,
  });

  final int version;
  final String category;
  final DateTime effectiveKnownAt;
  final bool retraction;
  final int? correctionOfVersion;
  final bool evidenceTruncated;
  final List<MarketEventReceipt> evidence;

  factory MarketEventRevision.fromJson(Object? input) {
    if (input is! Map<String, dynamic>) {
      throw const FormatException('Invalid revision');
    }
    final version = input['version'];
    final category = input['category'];
    final known = DateTime.tryParse('${input['effectiveKnownAt'] ?? ''}');
    final retraction = input['retraction'];
    final correction = input['correctionOfVersion'];
    final truncated = input['evidenceTruncated'];
    final receipts = input['evidence'];
    if (version is! int || version < 1 || category is! String || category.isEmpty ||
        known == null || retraction is! bool || truncated is! bool ||
        (correction != null && (correction is! int || correction < 1 || correction >= version)) ||
        receipts is! List || receipts.isEmpty || receipts.length > 25) {
      throw const FormatException('Invalid source-backed revision');
    }
    final evidence = receipts.map(MarketEventReceipt.fromJson).toList(growable: false);
    if (evidence.map((x) => x.receiptId).toSet().length != evidence.length) {
      throw const FormatException('Repeated receipt in event revision');
    }
    return MarketEventRevision(
      version: version,
      category: category,
      effectiveKnownAt: known.toUtc(),
      retraction: retraction,
      correctionOfVersion: correction as int?,
      evidenceTruncated: truncated,
      evidence: List.unmodifiable(evidence),
    );
  }
}

class MarketEventDetail {
  const MarketEventDetail({
    required this.eventId,
    required this.eventClass,
    required this.eventStatus,
    required this.currentVersion,
    required this.firstSeenAt,
    required this.currentKnownAt,
    required this.instrumentResolutionStatus,
    required this.versionsTruncated,
    required this.versions,
  });

  final String eventId;
  final String eventClass;
  final String eventStatus;
  final int currentVersion;
  final DateTime firstSeenAt;
  final DateTime currentKnownAt;
  final String instrumentResolutionStatus;
  final bool versionsTruncated;
  final List<MarketEventRevision> versions;

  factory MarketEventDetail.fromJson(Object? input) {
    if (input is! Map<String, dynamic>) {
      throw const FormatException('Invalid event evidence');
    }
    final id = input['eventId'];
    final eventClass = input['eventClass'];
    final eventStatus = input['eventStatus'];
    final version = input['currentVersion'];
    final firstSeen = DateTime.tryParse('${input['firstSeenAt'] ?? ''}');
    final currentKnown = DateTime.tryParse('${input['currentKnownAt'] ?? ''}');
    final resolution = input['instrumentResolutionStatus'];
    final truncated = input['versionsTruncated'];
    final revisionRows = input['versions'];
    if (id is! String || !RegExp(
          r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
        ).hasMatch(id) ||
        eventClass is! String || eventClass.isEmpty ||
        eventStatus is! String || !{'ACTIVE', 'RETRACTED'}.contains(eventStatus) ||
        version is! int || version < 1 ||
        firstSeen == null || currentKnown == null || currentKnown.isBefore(firstSeen) ||
        resolution is! String || resolution.isEmpty ||
        truncated is! bool ||
        revisionRows is! List || revisionRows.isEmpty || revisionRows.length > 20) {
      throw const FormatException('Invalid canonical event detail');
    }
    final parsed = revisionRows.map(MarketEventRevision.fromJson).toList(growable: false);
    if (parsed.first.version != version ||
        parsed.map((r) => r.version).toSet().length != parsed.length ||
        parsed.first.effectiveKnownAt != currentKnown.toUtc()) {
      throw const FormatException('Revision history does not match current event');
    }
    for (var i = 1; i < parsed.length; i++) {
      if (parsed[i].version >= parsed[i - 1].version) {
        throw const FormatException('Revision history must be newest first');
      }
    }
    return MarketEventDetail(
      eventId: id,
      eventClass: eventClass,
      eventStatus: eventStatus,
      currentVersion: version,
      firstSeenAt: firstSeen.toUtc(),
      currentKnownAt: currentKnown.toUtc(),
      instrumentResolutionStatus: resolution,
      versionsTruncated: truncated,
      versions: List.unmodifiable(parsed),
    );
  }
}

abstract interface class MarketEventDetailRepository {
  Future<MarketEventDetail> detail(String eventId);
}

class HttpMarketEventDetailRepository implements MarketEventDetailRepository {
  const HttpMarketEventDetailRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MarketEventDetail> detail(String eventId) async {
    if (!RegExp(
      r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
    ).hasMatch(eventId)) {
      throw ArgumentError('Invalid event identifier');
    }
    final response = await api.getJson(
      '/api/customer/market-intelligence/events/$eventId',
    );
    final parsed = MarketEventDetail.fromJson(response);
    if (parsed.eventId.toLowerCase() != eventId.toLowerCase()) {
      throw const FormatException('Evidence belongs to a different event');
    }
    return parsed;
  }
}
