import 'authenticated_api_client.dart';
import 'instrument_repository.dart';

class MarketCandleRecord {
  const MarketCandleRecord({
    required this.id,
    required this.instrumentId,
    required this.exchange,
    required this.tradingSymbol,
    required this.interval,
    required this.candleTime,
    required this.open,
    required this.high,
    required this.low,
    required this.close,
    required this.volume,
    required this.source,
  });

  final int id;
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;
  final String interval;
  final DateTime? candleTime;
  final num open;
  final num high;
  final num low;
  final num close;
  final int volume;
  final String source;

  factory MarketCandleRecord.fromJson(Map<String, dynamic> json) => MarketCandleRecord(
        id: (json['id'] as num?)?.toInt() ?? 0,
        instrumentId: (json['instrumentId'] as num?)?.toInt() ?? 0,
        exchange: '${json['exchange'] ?? ''}',
        tradingSymbol: '${json['tradingSymbol'] ?? ''}',
        interval: '${json['interval'] ?? ''}',
        candleTime: DateTime.tryParse('${json['candleTime'] ?? ''}'),
        open: json['open'] as num? ?? 0,
        high: json['high'] as num? ?? 0,
        low: json['low'] as num? ?? 0,
        close: json['close'] as num? ?? 0,
        volume: (json['volume'] as num?)?.toInt() ?? 0,
        source: '${json['source'] ?? ''}',
      );
}

class MarketCandlePage {
  const MarketCandlePage({
    required this.items,
    required this.page,
    required this.size,
    required this.totalItems,
    required this.totalPages,
    required this.hasNext,
  });

  final List<MarketCandleRecord> items;
  final int page;
  final int size;
  final int totalItems;
  final int totalPages;
  final bool hasNext;

  factory MarketCandlePage.fromJson(Map<String, dynamic> json) => MarketCandlePage(
        items: (json['items'] as List<dynamic>? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(MarketCandleRecord.fromJson)
            .toList(growable: false),
        page: (json['page'] as num?)?.toInt() ?? 0,
        size: (json['size'] as num?)?.toInt() ?? 0,
        totalItems: (json['totalItems'] as num?)?.toInt() ?? 0,
        totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
        hasNext: json['hasNext'] == true,
      );
}

abstract interface class MarketDataRepository {
  Future<InstrumentCatalogueStatus> instrumentStatus();
  Future<InstrumentSearchPage> searchInstruments({
    String? query,
    String? exchange,
    String? type,
    int page = 0,
    int size = 20,
  });
  Future<MarketCandlePage> candles({
    required int instrumentId,
    required String interval,
    required DateTime fromDate,
    required DateTime toDate,
    int page = 0,
    int size = 100,
  });
  Future<void> syncInstruments();
}

class HttpMarketDataRepository implements MarketDataRepository {
  HttpMarketDataRepository(this.api);

  final AuthenticatedApiClient api;

  String _date(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';

  @override
  Future<InstrumentCatalogueStatus> instrumentStatus() async {
    final decoded = await api.getJson('/api/market-data/instruments/status');
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_MARKET_INSTRUMENT_STATUS_RESPONSE', null);
    }
    return InstrumentCatalogueStatus.fromJson(decoded);
  }

  @override
  Future<InstrumentSearchPage> searchInstruments({
    String? query,
    String? exchange,
    String? type,
    int page = 0,
    int size = 20,
  }) async {
    final decoded = await api.getJson(
      '/api/market-data/instruments',
      query: {
        'query': query,
        'exchange': exchange,
        'type': type,
        'page': '$page',
        'size': '$size',
      },
    );
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_MARKET_INSTRUMENT_SEARCH_RESPONSE', null);
    }
    return InstrumentSearchPage.fromJson(decoded);
  }

  @override
  Future<MarketCandlePage> candles({
    required int instrumentId,
    required String interval,
    required DateTime fromDate,
    required DateTime toDate,
    int page = 0,
    int size = 100,
  }) async {
    final decoded = await api.getJson(
      '/api/market-data/candles',
      query: {
        'instrumentId': '$instrumentId',
        'interval': interval,
        'fromDate': _date(fromDate),
        'toDate': _date(toDate),
        'page': '$page',
        'size': '$size',
      },
    );
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_CANDLE_RESPONSE', null);
    }
    return MarketCandlePage.fromJson(decoded);
  }

  @override
  Future<void> syncInstruments() async {
    await api.postJson('/api/market-data/instruments/sync');
  }
}
