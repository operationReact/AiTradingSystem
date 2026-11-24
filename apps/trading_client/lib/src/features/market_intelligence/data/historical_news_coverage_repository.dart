import '../../../data/authenticated_api_client.dart';

class HistoricalNewsCoveragePage {
  const HistoricalNewsCoveragePage({
    required this.version, required this.fingerprint, required this.asOf,
    required this.total, required this.offset, required this.measured,
    required this.nextOffset, required this.withEvidence, required this.withoutEvidence,
    required this.unverified, required this.unavailable,
  });

  final String version, fingerprint;
  final DateTime asOf;
  final int total, offset, measured, withEvidence, withoutEvidence, unverified, unavailable;
  final int? nextOffset;

  factory HistoricalNewsCoveragePage.decode(Object? raw, {
    required DateTime asOf, required int offset, required int limit,
    String? version, String? fingerprint,
  }) {
    if (raw is! Map<String, dynamic>) throw const FormatException('Invalid coverage');
    int count(String name) {
      final value = raw[name];
      if (value is! int || value < 0) throw const FormatException('Invalid coverage count');
      return value;
    }
    final v = raw['catalogueVersion'];
    final f = raw['catalogueFingerprint'];
    final cutoff = DateTime.tryParse('${raw['asOf']}');
    final total = count('totalCatalogueInstruments');
    final measured = count('measuredInstruments');
    final next = raw['nextOffset'];
    final yes = count('verifiedWithEvidence');
    final no = count('verifiedWithoutEvidence');
    final unknown = count('unverifiedIdentity');
    final unavailable = count('unavailable');
    final end = offset + measured;
    if (v is! String || !RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(v) ||
        f is! String || !RegExp(r'^[a-f0-9]{64}$').hasMatch(f) ||
        (version != null && v != version) || (fingerprint != null && f != fingerprint) ||
        cutoff != asOf || count('offset') != offset || total > 50000 ||
        measured > limit || end > total || (measured == 0 && end < total) ||
        yes + no + unknown + unavailable != measured ||
        raw['canAuthorizeCapital'] != false ||
        raw['completeUniverseInThisPage'] != (offset == 0 && end == total) ||
        next != (end < total ? end : null)) {
      throw const FormatException('Inconsistent coverage page');
    }
    final rows = raw['rows'];
    if (rows is! List || rows.length != measured) {
      throw const FormatException('Invalid coverage rows');
    }
    final ids = <int>{};
    final buckets = [0, 0, 0, 0];
    for (final row in rows) {
      if (row is! Map<String, dynamic> || row['instrumentId'] is! int ||
          (row['instrumentId'] as int) <= 0 ||
          !ids.add(row['instrumentId'] as int) ||
          !['NSE', 'BSE'].contains(row['exchange']) ||
          row['verifiedEventReceipts'] is! int) {
        throw const FormatException('Invalid coverage row');
      }
      final receipts = row['verifiedEventReceipts'] as int;
      final bucket = switch (row['verificationStatus']) {
        'VERIFIED_LISTING_EVENT_EVIDENCE' when receipts > 0 => 0,
        'VERIFIED_LISTING_NO_EVENT_EVIDENCE' when receipts == 0 => 1,
        'LISTING_IDENTITY_UNVERIFIED' when receipts == 0 => 2,
        'UNVERIFIED_CATALOGUE_SOURCE' || 'INSTRUMENT_NOT_IN_ASOF_CATALOGUE' ||
          'NOT_CASH_EQUITY' when receipts == 0 => 3,
        _ => throw const FormatException('Invalid coverage status'),
      };
      buckets[bucket]++;
    }
    if (buckets[0] != yes || buckets[1] != no ||
        buckets[2] != unknown || buckets[3] != unavailable) {
      throw const FormatException('Coverage totals do not match rows');
    }
    return HistoricalNewsCoveragePage(version: v, fingerprint: f, asOf: cutoff!,
      total: total, offset: offset, measured: measured, nextOffset: next as int?,
      withEvidence: yes, withoutEvidence: no, unverified: unknown, unavailable: unavailable);
  }
}

abstract interface class HistoricalNewsCoverageRepository {
  Future<HistoricalNewsCoveragePage> read({
    required DateTime asOf, required int offset, String? version, String? fingerprint,
  });
}

class HttpHistoricalNewsCoverageRepository implements HistoricalNewsCoverageRepository {
  const HttpHistoricalNewsCoverageRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<HistoricalNewsCoveragePage> read({
    required DateTime asOf, required int offset, String? version, String? fingerprint,
  }) async {
    const limit = 25;
    final raw = await api.getJson('/api/admin/market-intelligence/historical-coverage',
      query: {'asOf': asOf.toUtc().toIso8601String(), 'offset': '$offset', 'limit': '$limit',
        'expectedVersion': ?version,
        'expectedFingerprint': ?fingerprint});
    return HistoricalNewsCoveragePage.decode(raw, asOf: asOf, offset: offset,
      limit: limit, version: version, fingerprint: fingerprint);
  }
}
