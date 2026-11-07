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

abstract interface class CustomerMarketRepository {
  Future<InstrumentCatalogueStatus> catalogueStatus();
  Future<InstrumentSearchPage> search({String? query, String? exchange});
  Future<CustomerMarketQuote> quote(int instrumentId);
}

class HttpCustomerMarketRepository implements CustomerMarketRepository {
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
