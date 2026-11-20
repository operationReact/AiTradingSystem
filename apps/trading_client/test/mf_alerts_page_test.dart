import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mutual_funds/data/mf_alerts_repository.dart';
import 'package:trading_client/src/features/mutual_funds/presentation/mf_alerts_page.dart';

class _ResearchFixture implements MfAlertsRepository {
  @override
  Future<MfResearchBoard> load() async => MfResearchBoard.fromJson({
    'source': 'AMFI_NAVALL',
    'disclaimer': 'Research only; invest manually.',
    'feed': {'status': 'VERIFIED', 'latestNavDate': '2026-11-17'},
    'watchlist': [
      for (var index = 0; index < 9; index++)
        {'schemeCode': 120716 + index, 'isin': 'INF789F01XA0',
         'name': 'Watch Fund $index', 'category': 'Index',
         'risk': 'HIGH', 'navDate': '2026-11-17',
         'nav': 123.45, 'observations': 22, 'state': 'MONITORING'}
    ],
    'alerts': [{
      'id': '9d332c7f-1ce4-4092-89cb-754de26d2e99',
      'schemeCode': 120716, 'name': 'Watch Fund 0',
      'category': 'Index', 'risk': 'HIGH',
      'navDate': '2026-11-17', 'nav': 95.0,
      'kind': 'PULLBACK_REVIEW', 'drawdownPercent': 5.0,
      'peakNav': 100.0, 'illustrativeAmountInr': 500,
      'explanation': 'Price change only. Verify suitability.'
    }],
  });
}

void main() {
  testWidgets('renders manual-investment alerts and separate watchlist without execution controls',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: ThemeData.dark(),
      home: MfAlertsPage(repository: _ResearchFixture()),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('minttick-mutual-fund-alerts-page')), findsOneWidget);
    expect(find.text('Opportunity monitor'), findsOneWidget);
    expect(find.text('Review alerts'), findsNothing);
    expect(find.text('Pullback · Review'), findsOneWidget);
    expect(find.text('Illustrative amount: ₹500'), findsOneWidget);
    expect(find.text('Invest now'), findsNothing);
    expect(find.text('Pay now'), findsNothing);
    await tester.tap(find.text('Watchlist'));
    await tester.pumpAndSettle();
    expect(find.text('Watch Fund 0'), findsOneWidget);
    expect(find.text('NAV ₹123.450'), findsWidgets);
  });
}
