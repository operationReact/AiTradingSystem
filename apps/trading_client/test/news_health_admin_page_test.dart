import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/market_intelligence/data/news_health_repository.dart';
import 'package:trading_client/src/features/market_intelligence/presentation/news_health_admin_page.dart';

class _FixtureNewsHealth implements NewsHealthRepository {
  _FixtureNewsHealth({this.fail = false});

  final bool fail;
  int calls = 0;

  @override
  Future<NewsHealthSnapshot> fetch() async {
    calls++;
    if (fail) throw StateError('ADMIN only: insufficient permissions');
    return NewsHealthSnapshot(
      observedAt: DateTime.utc(2026, 11, 24, 6),
      ingestionEnabled: false,
      eventLedgerEnabled: false,
      analyzerBridgeEnabled: false,
      analysisSchedulerEnabled: false,
      mindContextEnabled: false,
      selectedProvider: 'NONE',
      configuredGeminiModel: 'gemini-2.5-flash',
      geminiKeyConfigured: false,
      realProviderCallVerified: false,
      receiptCount: 0,
      eventCount: 0,
      eventVersionCount: 0,
      sources: [
        const NewsHealthSource(
          sourceId: 'fictional-feed',
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
  testWidgets('compact source health explicitly distinguishes disabled from verified', (tester) async {
    final repository = _FixtureNewsHealth();
    await tester.pumpWidget(MaterialApp(
      home: NewsHealthAdminPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    expect(find.text('News pipeline'), findsOneWidget);
    expect(find.text('RSS ingestion'), findsOneWidget);
    expect(find.text('Canonical ledger'), findsOneWidget);
    expect(find.text('Real provider call verified'), findsOneWidget);
    expect(find.text('FICTIONAL PUBLISHER'), findsOneWidget);
    expect(find.textContaining('Rights: REVIEW_PENDING'), findsOneWidget);
    expect(find.byKey(const ValueKey('news-source-fictional-feed')), findsOneWidget);
    expect(find.textContaining('test-secret'), findsNothing);
    expect(repository.calls, 1);
  });

  testWidgets('unauthorized user sees no privileged health metrics', (tester) async {
    final repository = _FixtureNewsHealth(fail: true);
    await tester.pumpWidget(MaterialApp(
      home: NewsHealthAdminPage(repository: repository),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('ADMIN authorization'), findsOneWidget);
    expect(find.text('FICTIONAL PUBLISHER'), findsNothing);
    expect(find.text('Retry'), findsOneWidget);
    await tester.tap(find.text('Retry'));
    await tester.pumpAndSettle();
    expect(repository.calls, 2);
  });
}
