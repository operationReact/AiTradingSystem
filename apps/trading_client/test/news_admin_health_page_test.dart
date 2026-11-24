import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/features/market_intelligence/data/news_admin_health_repository.dart';
import 'package:trading_client/src/features/market_intelligence/presentation/news_admin_health_page.dart';

class _FixtureAdminHealth implements NewsAdminHealthRepository {
  _FixtureAdminHealth({this.forbidden = false});

  final bool forbidden;
  int calls = 0;

  @override
  Future<NewsAdminHealthSnapshot> readHealth() async {
    calls++;
    if (forbidden) {
      throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    }
    return NewsAdminHealthSnapshot(
      observedAt: DateTime.utc(2026, 11, 24, 6),
      ingestionEnabled: false,
      eventLedgerEnabled: false,
      analyzerBridgeEnabled: false,
      analysisSchedulerEnabled: false,
      mindContextEnabled: false,
      model: const NewsAdminModelStatus(
        selectedProvider: 'NONE',
        configuredGeminiModel: 'gemini-2.5-flash',
        geminiKeyConfigured: true,
        realProviderCallVerified: false,
      ),
      receiptCount: 7,
      eventCount: 3,
      eventVersionCount: 4,
      sources: [
        NewsAdminSourceStatus(
          sourceId: 'fictional-publisher',
          publisher: 'FICTIONAL PUBLISHER',
          rightsState: 'REVIEW_PENDING',
          canIngest: false,
          canRetain: false,
          canDisplay: false,
          canTransferToModel: false,
          latestStatus: 'NEVER_RUN',
          lastFetchedItems: 0,
          lastInsertedItems: 0,
        ),
      ],
    );
  }
}

void main() {
  testWidgets('renders pipeline counts and truthful model readiness without secrets', (tester) async {
    final repo = _FixtureAdminHealth();
    await tester.pumpWidget(MaterialApp(
      home: NewsAdminHealthPage(repository: repo),
    ));
    await tester.pumpAndSettle();

    expect(find.text('News pipeline health'), findsOneWidget);
    expect(find.byKey(const ValueKey('news-health-receipts')), findsOneWidget);
    expect(find.byKey(const ValueKey('news-health-events')), findsOneWidget);
    expect(find.text('NOT VERIFIED'), findsOneWidget);
    expect(find.text('GEMINI_API_KEY'), findsNothing);
    // The dense status page is intentionally scrollable on a mobile viewport.
    await tester.scrollUntilVisible(
      find.text('FICTIONAL PUBLISHER'), 180,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    expect(find.text('FICTIONAL PUBLISHER'), findsOneWidget);
    expect(find.textContaining('REVIEW_PENDING'), findsOneWidget);
    expect(repo.calls, 1);
  });

  testWidgets('permission denial is distinct from network unavailable', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: NewsAdminHealthPage(repository: _FixtureAdminHealth(forbidden: true)),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('ADMIN permission required'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
    expect(find.text('FICTIONAL PUBLISHER'), findsNothing);
  });

  testWidgets('refresh re-reads repository without enabling source or trading changes', (tester) async {
    final repo = _FixtureAdminHealth();
    await tester.pumpWidget(MaterialApp(
      home: NewsAdminHealthPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('news-health-refresh')));
    await tester.pumpAndSettle();
    expect(repo.calls, 2);
    expect(find.text('NOT VERIFIED'), findsOneWidget);
  });
}
