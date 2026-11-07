// Review-only app entry point. NEVER use this file as a release build target.
// It exercises the real production Markets widgets with deterministic fixtures
// and labels every screenshot so example symbols/prices cannot be mistaken for
// live data. Production main.dart and backend integrations are unchanged.
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/data/market_data_repository.dart';
import 'package:trading_client/src/presentation/market_data_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

const _stocks = <(String, String)>[
  ('RELIANCE', 'Reliance Industries'),
  ('TCS', 'Tata Consultancy Services'),
  ('HDFCBANK', 'HDFC Bank'),
  ('INFY', 'Infosys'),
  ('ICICIBANK', 'ICICI Bank'),
  ('ITC', 'ITC Limited'),
  ('BHARTIARTL', 'Bharti Airtel'),
  ('SBIN', 'State Bank of India'),
  ('LT', 'Larsen & Toubro'),
  ('AXISBANK', 'Axis Bank'),
  ('HCLTECH', 'HCL Technologies'),
  ('WIPRO', 'Wipro'),
  ('BAJFINANCE', 'Bajaj Finance'),
  ('HINDUNILVR', 'Hindustan Unilever'),
  ('KOTAKBANK', 'Kotak Mahindra Bank'),
  ('TITAN', 'Titan Company'),
  ('MARUTI', 'Maruti Suzuki'),
  ('SUNPHARMA', 'Sun Pharma'),
  ('ULTRACEMCO', 'UltraTech Cement'),
  ('TATAMOTORS', 'Tata Motors'),
  ('TATASTEEL', 'Tata Steel'),
  ('TECHM', 'Tech Mahindra'),
  ('NTPC', 'NTPC'),
  ('POWERGRID', 'Power Grid Corp.'),
  ('NESTLEIND', 'Nestlé India'),
  ('ONGC', 'ONGC'),
  ('COALINDIA', 'Coal India'),
  ('ASIANPAINT', 'Asian Paints'),
  ('EICHERMOT', 'Eicher Motors'),
  ('ADANIENT', 'Adani Enterprises'),
  ('INDUSINDBK', 'IndusInd Bank'),
  ('HDFCLIFE', 'HDFC Life'),
  ('SBILIFE', 'SBI Life'),
  ('BEL', 'Bharat Electronics'),
  ('CIPLA', 'Cipla'),
  ('BAJAJFINSV', 'Bajaj Finserv'),
  ('GRASIM', 'Grasim Industries'),
  ('APOLLOHOSP', 'Apollo Hospitals'),
];

class ReviewCatalogueRepository implements MarketDataRepository {
  ReviewCatalogueRepository();
  late final List<InstrumentRecord> records = [
    for (var i = 0; i < _stocks.length; i++)
      InstrumentRecord(
        id: i + 1,
        exchange: i.isEven ? 'NSE' : 'BSE',
        exchangeSegment: 'cm',
        symbolToken: '${10001 + i}',
        tradingSymbol: '${_stocks[i].$1}-EQ',
        name: _stocks[i].$2,
        instrumentType: 'EQ',
        expiry: null,
        strike: null,
        lotSize: 1,
        tickSize: 0.05,
      ),
  ];

  @override
  Future<InstrumentCatalogueStatus> instrumentStatus() async =>
      InstrumentCatalogueStatus(
        version: 'REVIEW_FIXTURE',
        activatedAt: DateTime.utc(2026, 11, 6),
        activeCount: records.length,
      );

  @override
  Future<InstrumentSearchPage> searchInstruments({
    String? query,
    String? exchange,
    String? type,
    int page = 0,
    int size = 20,
  }) async {
    final q = query?.trim().toLowerCase();
    final matches = records.where((record) =>
        (exchange == null || record.exchange == exchange) &&
        (type == null || record.instrumentType == type) &&
        (q == null || q.isEmpty ||
          record.tradingSymbol.toLowerCase().contains(q) ||
          record.name.toLowerCase().contains(q))).toList();
    final offset = page * size;
    final result = offset >= matches.length
        ? <InstrumentRecord>[]
        : matches.skip(offset).take(size).toList();
    return InstrumentSearchPage(
      content: result,
      page: page,
      size: size,
      totalElements: matches.length,
      totalPages: (matches.length / size).ceil(),
      hasNext: offset + result.length < matches.length,
      catalogueVersion: 'REVIEW_FIXTURE',
      activatedAt: DateTime.utc(2026, 11, 6),
    );
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
    final instrument = records.firstWhere((record) => record.id == instrumentId);
    // Purely synthetic candles demonstrate the production chart component.
    // They must NEVER be presented or consumed as real exchange market data.
    final items = <MarketCandleRecord>[
      for (var i = 0; i < 30; i++)
        MarketCandleRecord(
          id: i + 1,
          instrumentId: instrumentId,
          exchange: instrument.exchange,
          tradingSymbol: instrument.tradingSymbol,
          interval: interval,
          candleTime: DateTime.utc(2026, 10, 1).add(Duration(days: i)),
          open: 100 + i * 1.3,
          high: 105 + i * 1.3 + math.sin(i) * 2,
          low: 95 + i * 1.3 + math.sin(i) * 2,
          close: 101 + i * 1.3 + math.sin(i * 0.8) * 5,
          volume: 10000 + i * 100,
          source: 'SYNTHETIC_REVIEW_ONLY',
        ),
    ];
    return MarketCandlePage(
      items: items,
      page: 0,
      size: size,
      totalItems: items.length,
      totalPages: 1,
      hasNext: false,
    );
  }

  @override
  Future<void> syncInstruments() async =>
      throw UnsupportedError('Import is forbidden in the review fixture');
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final brightness = Uri.base.queryParameters['theme'] == 'light'
      ? Brightness.light
      : Brightness.dark;
  runApp(MaterialApp(
    title: 'MintTick REVIEW FIXTURE — NOT LIVE',
    debugShowCheckedModeBanner: false,
    theme: buildTheme(brightness),
    home: Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Builder(builder: (context) {
              final scheme = Theme.of(context).colorScheme;
              return Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
                color: scheme.errorContainer,
                child: Text(
                  'TEST FIXTURE · NOT LIVE QUOTES OR PRICES',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                    color: scheme.onErrorContainer,
                  ),
                ),
              );
            }),
            Expanded(
              child: MarketDataPage(
                repository: ReviewCatalogueRepository(),
                isAuthenticated: true,
              ),
            ),
          ],
        ),
      ),
    ),
  ));
}
