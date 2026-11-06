import 'authenticated_api_client.dart';
import 'market_data_repository.dart';

/// A verified read-only quote; fetchedAt marks backend retrieval, NOT exchange tick time.
class HomeEquityQuote {
  const HomeEquityQuote({
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
  final double ltp;
  final DateTime fetchedAt;
  final String source;

  factory HomeEquityQuote.fromJson(Map<String, dynamic> json) {
    final id = (json['instrumentId'] as num?)?.toInt();
    final price = double.tryParse('${json['ltp'] ?? ''}');
    final exchange = '${json['exchange'] ?? ''}';
    final symbol = '${json['tradingSymbol'] ?? ''}';
    final source = '${json['source'] ?? ''}';
    final fetchedAt = DateTime.tryParse('${json['fetchedAt'] ?? ''}');
    if (id == null || id < 1 || price == null || !price.isFinite ||
        price <= 0 || exchange.isEmpty || symbol.isEmpty ||
        source.isEmpty || fetchedAt == null) {
      throw const ApiTransportFailure('INVALID_HOME_QUOTE_RESPONSE', null);
    }
    return HomeEquityQuote(
      instrumentId: id,
      exchange: exchange,
      tradingSymbol: symbol,
      ltp: price,
      fetchedAt: fetchedAt,
      source: source,
    );
  }
}

class HomeQuoteSnapshot {
  const HomeQuoteSnapshot({required this.quotes, required this.unavailable});

  final List<HomeEquityQuote> quotes;
  final int unavailable;
}

abstract interface class HomeQuoteRepository {
  Future<HomeQuoteSnapshot> fetch();
}

/// Fetches only an authorized user's NSE equity quotes. No hard-coded broker tokens.
/// The sequential requests respect the pilot Lambda's single-concurrency limit.
class HttpHomeQuoteRepository implements HomeQuoteRepository {
  HttpHomeQuoteRepository(this.api);

  final AuthenticatedApiClient api;
  static const _symbols = ['RELIANCE', 'TCS', 'INFY'];

  @override
  Future<HomeQuoteSnapshot> fetch() async {
    final catalogue = HttpMarketDataRepository(api);
    final quotes = <HomeEquityQuote>[];
    var unavailable = 0;

    for (final symbol in _symbols) {
      try {
        final matches = await catalogue.searchInstruments(
          query: symbol,
          exchange: 'NSE',
          type: 'EQ',
          size: 10,
        );
        final exact = matches.content.where((instrument) {
          final tradingSymbol = instrument.tradingSymbol.toUpperCase();
          return instrument.exchange == 'NSE' &&
              instrument.instrumentType.toUpperCase() == 'EQ' &&
              (tradingSymbol == symbol || tradingSymbol == '$symbol-EQ');
        });
        if (exact.isEmpty) {
          unavailable++;
          continue;
        }
        final instrument = exact.first;
        final response = await api.getJson('/api/market-data/quotes', query: {
          'instrumentId': '${instrument.id}',
        });
        if (response is! Map<String, dynamic>) {
          throw const ApiTransportFailure('INVALID_HOME_QUOTE_RESPONSE', null);
        }
        final quote = HomeEquityQuote.fromJson(response);
        if (quote.instrumentId != instrument.id ||
            quote.tradingSymbol != instrument.tradingSymbol ||
            quote.exchange != instrument.exchange) {
          throw const ApiTransportFailure('HOME_QUOTE_IDENTITY_MISMATCH', null);
        }
        quotes.add(quote);
      } on ApiAuthenticationRequired {
        rethrow;
      } on ApiHttpFailure catch (error) {
        if (error.statusCode == 401 || error.statusCode == 403) rethrow;
        unavailable++;
      } catch (_) {
        // Partial market-data outages must never become invented price values.
        unavailable++;
      }
    }
    return HomeQuoteSnapshot(quotes: quotes, unavailable: unavailable);
  }
}
