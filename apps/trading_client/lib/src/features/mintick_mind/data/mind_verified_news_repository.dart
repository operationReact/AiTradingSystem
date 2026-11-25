import '../../../data/authenticated_api_client.dart';

/// Research-only, source-verified issuer proof. This is never a trading signal.
class MindVerifiedNewsReceipt {
  const MindVerifiedNewsReceipt({
    required this.eventId,
    required this.eventVersion,
    required this.verifiedListingId,
    required this.receiptId,
    required this.sourceId,
    required this.publisher,
    required this.headline,
    required this.originalUrl,
    required this.firstObservedAt,
    required this.fetchedAt,
    required this.mappingFirstKnownAt,
    required this.eventCategory,
    required this.retracted,
    this.publisherPublishedAt,
  });

  final String eventId;
  final int eventVersion;
  final String verifiedListingId;
  final String receiptId;
  final String sourceId;
  final String publisher;
  final String headline;
  final Uri originalUrl;
  final DateTime? publisherPublishedAt;
  final DateTime firstObservedAt;
  final DateTime fetchedAt;
  final DateTime mappingFirstKnownAt;
  final String eventCategory;
  final bool retracted;

  factory MindVerifiedNewsReceipt.fromJson(Object? raw, {
    required String listingId,
    required DateTime cutoff,
  }) {
    if (raw is! Map<String, dynamic>) throw const FormatException('Invalid issuer news receipt');
    final eventId = raw['eventId'];
    final version = raw['eventVersion'];
    final linkedListing = raw['verifiedListingId'];
    final receiptId = raw['receiptId'];
    final sourceId = raw['sourceId'];
    final publisher = raw['publisher'];
    final headline = raw['headline'];
    final originalUrl = raw['originalUrl'];
    final uri = originalUrl is String ? Uri.tryParse(originalUrl) : null;
    final publishedRaw = raw['publisherPublishedAt'];
    final published = publishedRaw == null ? null : DateTime.tryParse('$publishedRaw');
    final observed = DateTime.tryParse('${raw['firstObservedAt'] ?? ''}')?.toUtc();
    final fetched = DateTime.tryParse('${raw['fetchedAt'] ?? ''}')?.toUtc();
    final mapped = DateTime.tryParse('${raw['mappingFirstKnownAt'] ?? ''}')?.toUtc();
    final category = raw['eventCategory'];
    final retracted = raw['retracted'];
    if (!_uuid(eventId) || version is! int || version <= 0 ||
        !_uuid(linkedListing) || linkedListing != listingId ||
        !_uuid(receiptId) ||
        sourceId is! String || sourceId.trim().isEmpty ||
        publisher is! String || publisher.trim().isEmpty ||
        headline is! String || headline.trim().isEmpty ||
        uri == null || uri.scheme != 'https' || uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        category is! String || category.trim().isEmpty ||
        retracted is! bool || observed == null || fetched == null || mapped == null ||
        observed.isAfter(fetched) || fetched.isAfter(mapped) || mapped.isAfter(cutoff) ||
        (publishedRaw != null && published == null) ||
        (published != null && published.toUtc().isAfter(cutoff))) {
      throw const FormatException('Untrustworthy as-of issuer news receipt');
    }
    return MindVerifiedNewsReceipt(
      eventId: eventId as String,
      eventVersion: version,
      verifiedListingId: linkedListing as String,
      receiptId: receiptId as String,
      sourceId: sourceId.trim(),
      publisher: publisher.trim(),
      headline: headline.trim(),
      originalUrl: uri,
      publisherPublishedAt: published?.toUtc(),
      firstObservedAt: observed,
      fetchedAt: fetched,
      mappingFirstKnownAt: mapped,
      eventCategory: category.trim(),
      retracted: retracted,
    );
  }
}

class MindVerifiedNewsEvidence {
  const MindVerifiedNewsEvidence({
    required this.instrumentId,
    required this.researchCutoff,
    required this.catalogueVersion,
    required this.verificationStatus,
    required this.verifiedListingId,
    required this.evidence,
  });

  static const contract = 'mind.verified-news-evidence.v1';
  static const statuses = {
    'UNVERIFIED_CATALOGUE_SOURCE',
    'INSTRUMENT_NOT_IN_ASOF_CATALOGUE',
    'NOT_CASH_EQUITY',
    'LISTING_IDENTITY_UNVERIFIED',
    'VERIFIED_LISTING_NO_EVENT_EVIDENCE',
    'VERIFIED_LISTING_EVENT_EVIDENCE',
  };

  final int instrumentId;
  final DateTime researchCutoff;
  final String catalogueVersion;
  final String verificationStatus;
  final String? verifiedListingId;
  final List<MindVerifiedNewsReceipt> evidence;
  bool get hasVerifiedListing => verifiedListingId != null;

  factory MindVerifiedNewsEvidence.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['contractVersion'] != contract) {
      throw const FormatException('Invalid verified-news research contract');
    }
    final id = raw['instrumentId'];
    final cutoff = DateTime.tryParse('${raw['researchCutoff'] ?? ''}')?.toUtc();
    final catalogueVersion = raw['catalogueVersion'];
    final status = raw['verificationStatus'];
    final listing = raw['verifiedListingId'];
    final count = raw['evidenceCount'];
    final rows = raw['evidence'];

    if (id is! int || id < 1 || cutoff == null ||
        cutoff.isAfter(DateTime.now().toUtc()) ||
        !_uuid(catalogueVersion) ||
        status is! String || !statuses.contains(status) ||
        (listing != null && !_uuid(listing)) ||
        count is! int || count < 0 || count > 100 ||
        rows is! List || rows.length != count ||
        raw['includedInDecision'] != false ||
        raw['canAuthorizeCapital'] != false) {
      throw const FormatException('Untrustworthy verified-news evidence');
    }
    final isVerified = status.startsWith('VERIFIED_LISTING_');
    if (isVerified != (listing is String)) {
      throw const FormatException('Listing verification status mismatch');
    }
    if (status == 'VERIFIED_LISTING_EVENT_EVIDENCE' && count == 0 ||
        status != 'VERIFIED_LISTING_EVENT_EVIDENCE' && count != 0) {
      throw const FormatException('Evidence count contradicts verified listing status');
    }
    final sourceRows = rows.map((r) => MindVerifiedNewsReceipt.fromJson(
      r, listingId: listing as String, cutoff: cutoff,
    )).toList(growable: false);
    if (sourceRows.map((r) => r.receiptId).toSet().length != sourceRows.length) {
      throw const FormatException('Repeated original publisher receipt');
    }
    return MindVerifiedNewsEvidence(
      instrumentId: id,
      researchCutoff: cutoff,
      catalogueVersion: catalogueVersion as String,
      verificationStatus: status,
      verifiedListingId: listing as String?,
      evidence: List.unmodifiable(sourceRows),
    );
  }
}

bool _uuid(Object? value) => value is String &&
    RegExp(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
        .hasMatch(value);

abstract interface class MindVerifiedNewsRepository {
  Future<MindVerifiedNewsEvidence> verifiedAt({
    required int instrumentId,
    required DateTime asOf,
  });
}

class HttpMindVerifiedNewsRepository implements MindVerifiedNewsRepository {
  const HttpMindVerifiedNewsRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindVerifiedNewsEvidence> verifiedAt({
    required int instrumentId,
    required DateTime asOf,
  }) async {
    if (instrumentId < 1 || asOf.toUtc().isAfter(DateTime.now().toUtc())) {
      throw ArgumentError('Invalid decision cutoff or instrument ID');
    }
    final result = MindVerifiedNewsEvidence.fromJson(
      await api.getJson('/api/customer/mind/v1/verified-news-evidence',
        query: {'instrumentId': '$instrumentId', 'asOf': asOf.toUtc().toIso8601String()}),
    );
    if (result.instrumentId != instrumentId ||
        result.researchCutoff != asOf.toUtc()) {
      throw const FormatException('Issuer evidence does not match requested decision');
    }
    return result;
  }
}
