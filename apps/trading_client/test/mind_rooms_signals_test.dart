import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_paper_journal_repository.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_paper_portfolio_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_paper_cycle_test.dart' show cycleJson;
import 'mind_paper_journal_test.dart' show journalJson;
import 'mind_paper_portfolio_test.dart' show portfolioJson;

class _Portfolio implements MindPaperPortfolioRepository {
  @override
  Future<MindPaperPortfolio> load() async => MindPaperPortfolio.fromJson(portfolioJson(paused: true));
  @override
  Future<void> setPaused(String roomId, bool paused) async {}
}

class _Journal implements MindPaperJournalRepository {
  @override
  Future<MindPaperJournal> load() async {
    final raw = journalJson();
    (raw['cycles'] as List).add({...cycleJson(outcome: 'QUOTE_UNAVAILABLE'), 'id': 'c3',
      'reasons': ['FAST_SMA_ABOVE_SLOW_SMA', 'FRESH_QUOTE_UNAVAILABLE']});
    (raw['cycles'] as List).add({...cycleJson(outcome: 'FAILED_UNKNOWN'), 'id': 'c4',
      'reasons': ['ORDER_OUTCOME_UNKNOWN']});
    return MindPaperJournal.fromJson(raw);
  }
}

Future<void> open(WidgetTester tester, String route) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 2000);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(
      paperPortfolioRepository: _Portfolio(), paperJournalRepository: _Journal())));
  await tester.pumpAndSettle();
  final nav = find.byKey(ValueKey('mind-route-$route'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Trading Rooms lists real funded PAPER rooms, not fixture rooms', (tester) async {
    await open(tester, 'trading-rooms');
    expect(find.text('Swing room'), findsOneWidget);
    expect(find.text('PAUSED'), findsOneWidget);
    expect(find.text('Intraday Room'), findsNothing);
    expect(find.text('Mock configuration'), findsNothing);
  });

  testWidgets('Culprit Catcher groups real causes with unknown outcomes first', (tester) async {
    await open(tester, 'culprit-catcher');
    final unknown = tester.getTopLeft(find.text('ORDER_OUTCOME_UNKNOWN'));
    final quote = tester.getTopLeft(find.text('FRESH_QUOTE_UNAVAILABLE'));
    expect(unknown.dy, lessThan(quote.dy));
    expect(find.text('POSITION_ALREADY_OPEN'), findsOneWidget);
    expect(find.text('INC-102'), findsNothing);
  });

  testWidgets('Signal Scorecard shows the real conversion funnel with sample warning', (tester) async {
    await open(tester, 'signal-scorecard');
    expect(find.byKey(const ValueKey('mind-scorecard-funnel')), findsOneWidget);
    expect(find.textContaining('no score is statistically meaningful'), findsOneWidget);
    expect(find.text('Momentum Breakout'), findsNothing);
  });
}
