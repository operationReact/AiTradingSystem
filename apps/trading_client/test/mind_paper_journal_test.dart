import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_paper_journal_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_paper_cycle_test.dart' show cycleJson;

Map<String, dynamic> journalJson({bool live = false, int wins = 0, int losses = 1}) => {
      'contractVersion': 'mind.paper-journal.v1', 'generatedAt': '2026-11-13T05:00:00Z', 'liveMoney': live,
      'cycles': [
        cycleJson(),
        {...cycleJson(outcome: 'NO_ENTRY'), 'id': 'c2', 'reasons': ['POSITION_ALREADY_OPEN']},
      ],
      'performance': {
        'cycles': 2, 'fills': 1, 'noEntry': 1, 'blocked': 0, 'closedTrades': 1, 'wins': wins, 'losses': losses,
        'realizedPnlPaise': -1250, 'averagePnlPaise': -1250, 'maxDrawdownPaise': 1250, 'winRatePercent': 0.0,
        'sampleTooSmall': true, 'minimumMeaningfulSample': 30,
      },
    };

class _Repo implements MindPaperJournalRepository {
  @override
  Future<MindPaperJournal> load() async => MindPaperJournal.fromJson(journalJson());
}

Future<void> open(WidgetTester tester, String route) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 1800);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(paperJournalRepository: _Repo())));
  await tester.pumpAndSettle();
  final nav = find.byKey(ValueKey('mind-route-$route'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  test('journal contract is virtual-only and must reconcile', () {
    expect(MindPaperJournal.fromJson(journalJson()).cycles, hasLength(2));
    expect(() => MindPaperJournal.fromJson(journalJson(live: true)), throwsFormatException);
    expect(() => MindPaperJournal.fromJson(journalJson(wins: 1, losses: 1)), throwsFormatException);
  });

  testWidgets('Decision Explorer lists recorded cycles and opens the gate-by-gate trail', (tester) async {
    await open(tester, 'decisions');
    expect(find.text('TITAN · WAIT'), findsNothing); // fixture timeline gone
    expect(find.text('MINDA-EQ · BUY'), findsNWidgets(2)); // fill + held position (signal BUY)
    expect(find.text('PAPER FILL'), findsNWidgets(2)); // filter chip + row
    expect(find.text('NO ENTRY'), findsWidgets);
    await tester.tap(find.text('MINDA-EQ · BUY').first);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-decision-anatomy')), findsOneWidget);
    expect(find.text('DATA_GATE · PASSED'), findsOneWidget);
    expect(find.textContaining('mock'), findsNothing);
  });

  testWidgets('Performance shows closed-trade figures and the small-sample warning, no invented returns',
      (tester) async {
    await open(tester, 'performance');
    expect(find.text('+12.4%'), findsNothing);
    expect(find.text('61.4%'), findsNothing);
    expect(find.text('-₹12.50'), findsWidgets);
    expect(find.byKey(const ValueKey('mind-performance-sample-warning')), findsOneWidget);
    expect(find.textContaining('not meaningful'), findsOneWidget);
  });

  testWidgets('Decision Journal audit reflects server data', (tester) async {
    await open(tester, 'journal');
    await tester.scrollUntilVisible(find.text('None · PAPER virtual orders only'), 300,
        scrollable: find.descendant(of: find.byKey(const ValueKey('mind-screen-journal')),
            matching: find.byType(Scrollable)).first);
    expect(find.text('None · PAPER virtual orders only'), findsOneWidget);
    expect(find.text('Export journal preview'), findsNothing);
    expect(find.textContaining('order o1'), findsOneWidget);
  });
}
