import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_paper_portfolio_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

const _room = '12345678-1234-1234-1234-123456789012';

Map<String, dynamic> portfolioJson({bool live = false, String mark = 'FRESH', bool paused = false}) => {
      'contractVersion': 'mind.paper-portfolio.v1', 'generatedAt': '2026-11-13T05:00:00Z', 'liveMoney': live,
      'fundedRooms': 1, 'initialCashPaise': 2000000, 'availableCashPaise': 1608159, 'realizedPnlPaise': -1250,
      'costBasisPaise': 391841, 'marketValuePaise': mark == 'MISSING' ? null : 391645,
      'unrealizedPnlPaise': mark == 'MISSING' ? null : -196, 'grossExposurePercent': 19.58,
      'staleOrMissingMarks': mark == 'FRESH' ? 0 : 1,
      'rooms': [
        {'roomId': _room, 'name': 'Swing room', 'initialCashPaise': 2000000, 'availableCashPaise': 1608159,
         'realizedPnlPaise': -1250, 'paused': paused, 'openPositions': 1},
      ],
      'positions': [
        {'roomId': _room, 'instrumentId': 7, 'tradingSymbol': 'RELIANCE-EQ', 'quantity': 37,
         'avgCostPaise': 10590, 'costBasisPaise': 391841, 'markPrice': mark == 'MISSING' ? null : 105.85,
         'markAt': mark == 'MISSING' ? null : '2026-11-13T04:59:58Z', 'markState': mark,
         'marketValuePaise': mark == 'MISSING' ? null : 391645, 'unrealizedPnlPaise': mark == 'MISSING' ? null : -196},
      ],
      'riskPolicy': {
        'perTradeRiskBudget': "1% of the room's starting virtual cash per entry",
        'maxSingleOrderExposure': '20% of starting virtual cash per order (no leverage)',
        'maxQuoteAge': '15 seconds, verified broker tick, inside cash session',
        'maxSignalQuoteDivergence': '3% between signal close and live quote',
        'sessionGate': 'NSE/BSE cash session only', 'sizing': 'Automatic',
        'exits': 'Trend break, entry stop or target',
        'liveTrading': 'Not available in Mind; LIVE is separately gated and not authorized',
      },
    };

class _Repo implements MindPaperPortfolioRepository {
  _Repo({this.mark = 'FRESH'});
  final String mark;
  bool paused = false;
  final calls = <(String, bool)>[];
  @override
  Future<MindPaperPortfolio> load() async => MindPaperPortfolio.fromJson(portfolioJson(mark: mark, paused: paused));
  @override
  Future<void> setPaused(String roomId, bool value) async {
    calls.add((roomId, value));
    paused = value;
  }
}

Future<void> open(WidgetTester tester, String route, MindPaperPortfolioRepository repo) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 1800);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(paperPortfolioRepository: repo)));
  await tester.pumpAndSettle();
  final nav = find.byKey(ValueKey('mind-route-$route'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  test('portfolio contract must be explicitly virtual', () {
    expect(MindPaperPortfolio.fromJson(portfolioJson()).equityPaise, 1608159 + 391645);
    expect(() => MindPaperPortfolio.fromJson(portfolioJson(live: true)), throwsFormatException);
    expect(() => MindPaperPortfolio.fromJson({...portfolioJson(), 'liveMoney': null}), throwsFormatException);
    expect(() => MindPaperPortfolio.fromJson(portfolioJson(mark: 'GUESSED')), throwsFormatException);
    expect(formatPaise(123456789), '₹1,234,567.89');
    expect(formatPaise(-1250), '-₹12.50');
  });

  testWidgets('Portfolio Digital Twin shows server PAPER values, never fixture money', (tester) async {
    await open(tester, 'portfolio', _Repo());
    expect(find.text('PAPER · VIRTUAL MONEY'), findsOneWidget);
    expect(find.text('₹28,46,730'), findsNothing);
    expect(find.text('-₹12.50'), findsOneWidget);
    expect(find.text('-₹1.96'), findsWidgets);
    expect(find.text('19.6%'), findsOneWidget);
    expect(find.textContaining('RELIANCE-EQ × 37 · FRESH'), findsOneWidget);
  });

  testWidgets('missing marks are reported, never priced', (tester) async {
    await open(tester, 'portfolio', _Repo(mark: 'MISSING'));
    expect(find.text('Mark unavailable'), findsWidgets);
    expect(find.textContaining('1 stale/missing mark(s)'), findsOneWidget);
  });

  testWidgets('Risk Center shows enforced policy and pauses a real PAPER room', (tester) async {
    final repo = _Repo();
    await open(tester, 'risk', repo);
    expect(find.byKey(const ValueKey('mind-risk-policy')), findsOneWidget);
    expect(find.textContaining('1% of the room'), findsOneWidget);
    expect(find.text('Max daily loss'), findsNothing); // fake slider gone
    expect(find.text('Demo emergency stop'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('mind-room-pause-$_room')));
    await tester.pumpAndSettle();
    expect(repo.calls.single, (_room, true));
    expect(find.textContaining('Paused · new virtual fills rejected'), findsOneWidget);
  });
}
