import '../../../data/authenticated_api_client.dart';
import '../../../data/instrument_repository.dart';

class CustomerMarketQuote {
  const CustomerMarketQuote({
    required this.instrumentId,
    required this.exchange,
    required this.tradingSymbol,
    required this.ltp,
    required this.fetchedAt,
    required this.source,
  });
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;
  final num ltp;
  final DateTime fetchedAt;
  final String source;

  factory CustomerMarketQuote.fromJson(Map<String, dynamic> value) {
    final rawPrice = value['ltp'];
    final price = rawPrice is num ? rawPrice : num.tryParse('$rawPrice');
    final stamp = DateTime.tryParse('${value['fetchedAt'] ?? ''}');
    if (value['instrumentId'] is! num || price == null || !price.isFinite ||
        price <= 0 || stamp == null ||
        value['exchange'] is! String || value['tradingSymbol'] is! String ||
        value['source'] is! String) {
      throw const FormatException('Invalid customer market quote');
    }
    return CustomerMarketQuote(
      instrumentId: (value['instrumentId'] as num).toInt(),
      exchange: value['exchange'] as String,
      tradingSymbol: value['tradingSymbol'] as String,
      ltp: price, fetchedAt: stamp, source: value['source'] as String,
    );
  }
}

/// Only a verified exchange-timestamped quote may be displayed as LIVE.
class CustomerLiveQuote {
  const CustomerLiveQuote({required this.instrumentId, required this.exchange,
    required this.tradingSymbol, required this.status,
    this.ltp, this.exchangeAt, this.source});
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;
  final String status;
  final num? ltp;
  final DateTime? exchangeAt;
  final String? source;

  factory CustomerLiveQuote.fromJson(Map<String, dynamic> value) {
    final id = value['instrumentId'];
    final status = value['status'];
    final exchange = value['exchange'];
    final tradingSymbol = value['tradingSymbol'];
    if (id is! num || id.toInt() < 1 ||
        (exchange != 'NSE' && exchange != 'BSE') ||
        tradingSymbol is! String || tradingSymbol.isEmpty ||
        status is! String ||
        !const {'LIVE','CONNECTING','RECONNECTING','STALE','UNAVAILABLE',
          'MARKET_CLOSED','BROKER_CONNECTION_REQUIRED'}.contains(status)) {
      throw const FormatException('Invalid live quote state');
    }
    if (status != 'LIVE') {
      // A disconnected or stale response must never carry a price to Flutter.
      if (value['ltp'] != null || value['exchangeAt'] != null) {
        throw const FormatException('Non-live price was supplied');
      }
      return CustomerLiveQuote(instrumentId: id.toInt(),
          exchange: exchange as String, tradingSymbol: tradingSymbol,
          status: status);
    }
    final price = value['ltp'] is num ? value['ltp'] as num :
        num.tryParse('${value['ltp'] ?? ''}');
    final tickAt = DateTime.tryParse('${value['exchangeAt'] ?? ''}');
    final source = value['source'];
    if (price == null || !price.isFinite || price <= 0 ||
        tickAt == null || source != 'ANGEL_ONE_SMARTAPI_WS_V2') {
      throw const FormatException('Unverified live quote');
    }
    return CustomerLiveQuote(instrumentId: id.toInt(),
        exchange: exchange as String, tradingSymbol: tradingSymbol,
        status: status, ltp: price, exchangeAt: tickAt,
        source: source as String);
  }
}

abstract interface class CustomerLiveMarketRepository {
  Future<CustomerLiveQuote> liveQuote(int instrumentId);
}

abstract interface class CustomerMarketRepository {
  Future<InstrumentCatalogueStatus> catalogueStatus();
  Future<InstrumentSearchPage> search({String? query, String? exchange});
  Future<CustomerMarketQuote> quote(int instrumentId);
}

class HttpCustomerMarketRepository implements CustomerMarketRepository, CustomerLiveMarketRepository {
  HttpCustomerMarketRepository(this.api);
  final AuthenticatedApiClient api;

  static const String _root = '/api/customer/market-data';

  @override
  Future<InstrumentCatalogueStatus> catalogueStatus() async {
    final data = await api.getJson('$_root/instruments/status');
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid instrument catalogue status');
    }
    return InstrumentCatalogueStatus.fromJson(data);
  }

  @override
  Future<InstrumentSearchPage> search({String? query, String? exchange}) async {
    final data = await api.getJson('$_root/instruments', query: {
      'query': query,
      'exchange': exchange,
      'page': '0',
      'size': '20',
    });
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid instrument catalogue');
    }
    return InstrumentSearchPage.fromJson(data);
  }

  @override
  Future<CustomerLiveQuote> liveQuote(int instrumentId) async {
    if (instrumentId < 1) throw ArgumentError.value(instrumentId);
    final data = await api.getJson('$_root/quotes', query: {
      'instrumentId': '$instrumentId',
      'live': 'true',
    }, timeout: const Duration(seconds: 8));
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid live quote response');
    }
    return CustomerLiveQuote.fromJson(data);
  }

  @override
  Future<CustomerMarketQuote> quote(int instrumentId) async {
    if (instrumentId < 1) throw ArgumentError.value(instrumentId);
    final data = await api.getJson('$_root/quotes', query: {
      'instrumentId': '$instrumentId',
    });
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid broker quote');
    }
    return CustomerMarketQuote.fromJson(data);
  }
}
