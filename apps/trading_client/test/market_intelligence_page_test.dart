import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/market_intelligence/data/market_news_repository.dart';
import 'package:trading_client/src/features/market_intelligence/presentation/market_intelligence_page.dart';

class _FixtureNews implements MarketNewsRepository {
  _FixtureNews({this.fail = false, this.empty = false});

  final bool fail;
  final bool empty;
  int calls = 0;
  String? lastSymbol;

  @override
  Future<List<MarketNewsSource>> sources() async => const [];

  @override
  Future<NewsDrivenMindScan> runNewsDrivenScan({String interval = 'FIVE_MINUTE', int limit = 100}) async =>
      const NewsDrivenMindScan(id: '00000000-0000-4000-8000-000000000001',
          status: 'SUCCEEDED', scannedCount: 1, candidateCount: 0);

  @override
  Future<List<NewsDrivenMindCandidate>> newsDrivenCandidates(String runId) async => const [];

  @override
  Future<MarketNewsPage> articles({required int page, int size = 20, String? symbol}) async {
    calls++;
    lastSymbol = symbol;
    if (fail) throw StateError('Fixture API unavailable');
    return MarketNewsPage(
      page: page, size: size, totalItems: empty ? 0 : 1,
      items: empty ? const [] : [
        MarketNewsArticle(
          id: 'fixture-1',
          title: 'Synthetic corporate filing headline',
          source: 'TEST PUBLISHER',
          url: 'https://example.test/fictional-announcement',
          excerpt: 'This is a labelled fixture excerpt.',
          publishedAt: DateTime.utc(2026, 11, 24, 6),
          ingestedAt: DateTime.utc(2026, 11, 24, 6, 5),
          analysisStatus: 'ANALYZED',
          stockImpact: const MarketNewsImpact(
            sentiment: 'NEGATIVE', category: 'EARNINGS', impactedSymbols: ['TCS'],
            evidence: 'Synthetic earnings evidence.', risks: ['Synthetic uncertainty.'],
            directionScore: -0.35, confidence: 0.8, materiality: 0.6,
            horizon: 'MULTI_DAY', ambiguity: true,
          ),
        ),
      ],
    );
  }
}

void main() {
  testWidgets('compact sourced article opens evidence sheet without trading calls', (tester) async {
    final repo = _FixtureNews();
    await tester.pumpWidget(MaterialApp(home: MarketIntelligencePage(repository: repo)));
    await tester.pumpAndSettle();

    expect(find.text('Market intelligence'), findsOneWidget);
    expect(find.text('Latest publisher-attributed headlines'), findsOneWidget);
    expect(find.text('Synthetic corporate filing headline'), findsOneWidget);
    expect(find.textContaining('TEST PUBLISHER'), findsOneWidget);
    expect(find.textContaining('BUY'), findsNothing);

    await tester.tap(find.text('Synthetic corporate filing headline'));
    await tester.pumpAndSettle();

    expect(find.text('This is a labelled fixture excerpt.'), findsOneWidget);
    expect(find.text('Open publisher source'), findsOneWidget);
    expect(find.textContaining('Observed by MintTick'), findsOneWidget);
    expect(repo.calls, 1);
  });

  testWidgets('stock filter requests matching impact and article sheet explains model evidence', (tester) async {
    final repo = _FixtureNews();
    await tester.pumpWidget(MaterialApp(home: MarketIntelligencePage(repository: repo)));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('market-news-symbol-filter')), 'tcs');
    await tester.tap(find.byKey(const ValueKey('market-news-symbol-submit')));
    await tester.pumpAndSettle();

    expect(repo.lastSymbol, 'TCS');
    expect(find.textContaining('affecting TCS'), findsOneWidget);

    await tester.tap(find.text('Synthetic corporate filing headline'));
    await tester.pumpAndSettle();
    expect(find.text('MintTick Mind · stock impact assessment'), findsOneWidget);
    expect(find.textContaining('Affected symbols: TCS'), findsOneWidget);
    expect(find.textContaining('not a price or return forecast'), findsOneWidget);
  });

  testWidgets('empty collection is distinct from unavailable source', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: MarketIntelligencePage(repository: _FixtureNews(empty: true)),
    ));
    await tester.pumpAndSettle();
    expect(find.text('No approved publisher articles are available.'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);

    await tester.pumpWidget(MaterialApp(
      home: MarketIntelligencePage(
        key: const ValueKey('unavailable-news'), repository: _FixtureNews(fail: true),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('not available from the connected source'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
  });

  testWidgets('refresh is read-only and calls only the repository', (tester) async {
    final repo = _FixtureNews();
    await tester.pumpWidget(MaterialApp(home: MarketIntelligencePage(repository: repo)));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('market-news-refresh')));
    await tester.pumpAndSettle();

    expect(repo.calls, 2);
    expect(find.text('Synthetic corporate filing headline'), findsOneWidget);
  });
}
