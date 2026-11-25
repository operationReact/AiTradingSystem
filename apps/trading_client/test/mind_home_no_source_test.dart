import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_home_summary_repository.dart';

Map<String, dynamic> homeJson() => {
      'contractVersion': 'customer.home.v1', 'mode': 'PAPER', 'liveExecutionEnabled': false,
      'generatedAt': '2026-11-13T05:00:00Z',
      'rooms': {'count': 1, 'drafts': 0, 'funded': 1, 'plannedBudgetInr': 20000,
        'availablePaperCashPaise': 1608159, 'featured': <Object>[]},
      'brokers': {'angelOne': 'NOT_CONNECTED', 'upstox': 'NOT_CONNECTED', 'connected': 0, 'complete': true},
      'mind': {'executionEnabled': false,
        'market': {'status': 'STORED', 'asOf': '2026-11-12T10:00:00Z', 'source': 'STORED_LAST_OVERVIEW'},
        'research': {'status': 'RECORDED', 'asOf': '2026-11-12T10:00:00Z', 'source': 'PERSISTED_RESEARCH_SCAN'}},
      'nextAction': {'code': 'REVIEW_PAPER_ROOM', 'destination': 'ROOM', 'roomId': '12345678-1234-1234-1234-123456789012'},
      'activity': null,
    };

class _Home implements CustomerHomeSummaryRepository {
  @override
  Future<CustomerHomeSummary> load(String mode) async => CustomerHomeSummary.decode(homeJson());
}

Future<void> open(WidgetTester tester, String route, {CustomerHomeSummaryRepository? home}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 1800);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(homeSummaryRepository: home)));
  await tester.pumpAndSettle();
  final nav = find.byKey(ValueKey('mind-route-$route'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  test('home contract refuses live execution and unknown next actions', () {
    expect(CustomerHomeSummary.decode(homeJson()).rooms.funded, 1);
    expect(() => CustomerHomeSummary.decode({...homeJson(), 'liveExecutionEnabled': true}), throwsFormatException);
  });

  testWidgets('app-home shows the real server summary, not fixture text', (tester) async {
    await open(tester, 'app-home', home: _Home());
    expect(find.byKey(const ValueKey('mind-home-summary')), findsOneWidget);
    expect(find.text('review paper room'), findsOneWidget);
    expect(find.text('1 funded · 0 draft'), findsOneWidget);
    expect(find.text('₹20000'), findsOneWidget);
    expect(find.text('Not authorized'), findsOneWidget);
    expect(find.text('Mind Preview'), findsNothing);
  });

  testWidgets('screens without a backend source say so and are not presented as working', (tester) async {
    await open(tester, 'intelligence-hub');
    expect(find.byKey(const ValueKey('mind-no-backend-source')), findsOneWidget);
    await open(tester, 'events');
    expect(find.byKey(const ValueKey('mind-no-backend-source')), findsOneWidget);
    await open(tester, 'settings');
    expect(find.byKey(const ValueKey('mind-no-backend-source')), findsNothing);
  });
}
