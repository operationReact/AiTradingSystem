import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/market_intelligence/data/market_event_repository.dart';
import 'package:trading_client/src/features/market_intelligence/presentation/market_event_timeline_page.dart';

class _FixtureEvents implements MarketEventRepository {
  _FixtureEvents({this.fail = false, this.empty = false});

  final bool fail;
  final bool empty;
  int calls = 0;

  @override
  Future<MarketEventPage> events({required int page, int size = 20}) async {
    calls++;
    if (fail) throw StateError('Fictional API unavailable');
    return MarketEventPage(
      page: page,
      size: size,
      totalItems: empty ? 0 : 1,
      items: empty ? const [] : [
        MarketEventCard(
          eventId: '00000000-0000-0000-0000-000000000001',
          version: 1,
          headline: 'Fictional corporate filing',
          eventClass: 'UNCLASSIFIED',
          category: 'UNCLASSIFIED',
          eventStatus: 'ACTIVE',
          sourceId: 'fixture',
          publisher: 'FICTIONAL PUBLISHER',
          sourceUrl: 'https://news.example.test/fictional-item',
          firstObservedAt: DateTime.utc(2026, 11, 24, 4, 5),
          effectiveKnownAt: DateTime.utc(2026, 11, 24, 4, 5),
          publishedAt: DateTime.utc(2026, 11, 24, 4),
          instrumentResolutionStatus: 'UNVERIFIED',
        ),
      ],
    );
  }
}

void main() {
  testWidgets('source-backed event opens evidence details, not an order ticket', (tester) async {
    final repo = _FixtureEvents();
    await tester.pumpWidget(MaterialApp(
      home: MarketEventTimelinePage(repository: repo),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Market events'), findsOneWidget);
    expect(find.text('Fictional corporate filing'), findsOneWidget);
    expect(find.textContaining('FICTIONAL PUBLISHER'), findsOneWidget);
    expect(find.textContaining('BUY'), findsNothing);

    await tester.tap(find.text('Fictional corporate filing'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Affected instruments are not yet verified'), findsOneWidget);
    expect(find.text('Open original publisher'), findsOneWidget);
    expect(find.textContaining('First observed:'), findsOneWidget);
    expect(repo.calls, 1);
  });

  testWidgets('empty and unavailable are distinct explicit states', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: MarketEventTimelinePage(repository: _FixtureEvents(empty: true)),
    ));
    await tester.pumpAndSettle();
    expect(find.text('No currently authorized source-backed events.'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);

    await tester.pumpWidget(MaterialApp(
      home: MarketEventTimelinePage(
        key: const ValueKey('unavailable'),
        repository: _FixtureEvents(fail: true),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('Verified event data is not available'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
  });

  testWidgets('refresh only reads repository and keeps existing timeline', (tester) async {
    final repo = _FixtureEvents();
    await tester.pumpWidget(MaterialApp(
      home: MarketEventTimelinePage(repository: repo),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('market-events-refresh')));
    await tester.pumpAndSettle();
    expect(repo.calls, 2);
    expect(find.text('Fictional corporate filing'), findsOneWidget);
  });
}
