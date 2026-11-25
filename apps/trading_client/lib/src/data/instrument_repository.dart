import 'authenticated_api_client.dart';

class InstrumentRecord {
  const InstrumentRecord({
    required this.id,
    required this.exchange,
    required this.exchangeSegment,
    required this.symbolToken,
    required this.tradingSymbol,
    required this.name,
    required this.instrumentType,
    required this.expiry,
    required this.strike,
    required this.lotSize,
    required this.tickSize,
  });

  final int id;
  final String exchange;
  final String exchangeSegment;
  final String symbolToken;
  final String tradingSymbol;
  final String name;
  final String instrumentType;
  final DateTime? expiry;
  final num? strike;
  final int lotSize;
  final num? tickSize;

  factory InstrumentRecord.fromJson(Map<String, dynamic> json) => InstrumentRecord(
        id: (json['id'] as num?)?.toInt() ?? 0,
        exchange: '${json['exchange'] ?? ''}',
        exchangeSegment: '${json['exchangeSegment'] ?? ''}',
        symbolToken: '${json['symbolToken'] ?? ''}',
        tradingSymbol: '${json['tradingSymbol'] ?? ''}',
        name: '${json['name'] ?? ''}',
        instrumentType: '${json['instrumentType'] ?? ''}',
        expiry: DateTime.tryParse('${json['expiry'] ?? ''}'),
        strike: json['strike'] as num?,
        lotSize: (json['lotSize'] as num?)?.toInt() ?? 0,
        tickSize: json['tickSize'] as num?,
      );
}

class InstrumentSearchPage {
  const InstrumentSearchPage({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
    required this.hasNext,
    required this.catalogueVersion,
    required this.activatedAt,
  });

  final List<InstrumentRecord> content;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;
  final bool hasNext;
  final String? catalogueVersion;
  final DateTime? activatedAt;

  factory InstrumentSearchPage.fromJson(Map<String, dynamic> json) => InstrumentSearchPage(
        content: (json['content'] as List<dynamic>? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(InstrumentRecord.fromJson)
            .toList(growable: false),
        page: (json['page'] as num?)?.toInt() ?? 0,
        size: (json['size'] as num?)?.toInt() ?? 0,
        totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
        totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
        hasNext: json['hasNext'] == true,
        catalogueVersion: json['catalogueVersion']?.toString(),
        activatedAt: DateTime.tryParse('${json['activatedAt'] ?? ''}'),
      );
}

class InstrumentCatalogueStatus {
  const InstrumentCatalogueStatus({
    required this.version,
    required this.activatedAt,
    required this.activeCount,
  });

  final String? version;
  final DateTime? activatedAt;
  final int activeCount;

  factory InstrumentCatalogueStatus.fromJson(Map<String, dynamic> json) => InstrumentCatalogueStatus(
        version: json['version']?.toString(),
        activatedAt: DateTime.tryParse('${json['activatedAt'] ?? ''}'),
        activeCount: (json['activeCount'] as num?)?.toInt() ?? 0,
      );
}

abstract interface class InstrumentRepository {
  Future<InstrumentCatalogueStatus> status();
  Future<InstrumentSearchPage> search({
    String? query,
    String? exchange,
    String? type,
    int page = 0,
    int size = 20,
  });
  Future<void> importLatest();
}

class HttpInstrumentRepository implements InstrumentRepository {
  HttpInstrumentRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<InstrumentCatalogueStatus> status() async {
    final decoded = await api.getJson('/api/instruments/status');
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_INSTRUMENT_STATUS_RESPONSE', null);
    }
    return InstrumentCatalogueStatus.fromJson(decoded);
  }

  @override
  Future<InstrumentSearchPage> search({
    String? query,
    String? exchange,
    String? type,
    int page = 0,
    int size = 20,
  }) async {
    final decoded = await api.getJson(
      '/api/instruments/search',
      query: {
        'q': query,
        'exchange': exchange,
        'type': type,
        'page': '$page',
        'size': '$size',
      },
    );
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_INSTRUMENT_SEARCH_RESPONSE', null);
    }
    return InstrumentSearchPage.fromJson(decoded);
  }

  @override
  Future<void> importLatest() async {
    await api.postJson('/api/instruments/import');
  }
}
