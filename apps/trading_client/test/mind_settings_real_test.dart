import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_paper_portfolio_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_paper_portfolio_test.dart' show portfolioJson;

const _room = '12345678-1234-1234-1234-123456789012';

class _Portfolio implements MindPaperPortfolioRepository {
  final calls = <(String, bool)>[];
  bool paused = false;
  @override
  Future<MindPaperPortfolio> load() async => MindPaperPortfolio.fromJson(portfolioJson(paused: paused));
  @override
  Future<void> setPaused(String roomId, bool value) async {
    calls.add((roomId, value));
    paused = value;
  }
}

void main() {
  testWidgets('Preferences shows real PAPER room pause control and no fake monitoring switches', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1440, 1800);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = _Portfolio();
    await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(paperPortfolioRepository: repo)));
    await tester.pumpAndSettle();
    final nav = find.byKey(const ValueKey('mind-route-settings'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();

    expect(find.text('PAPER virtual only'), findsOneWidget);
    expect(find.text('Not authorized'), findsWidgets);
    expect(find.byKey(ValueKey('mind-settings-room-$_room')), findsOneWidget);
    expect(find.text('Paper mode'), findsNothing);
    expect(find.text('Shadow mode'), findsNothing);
    expect(find.text('Mock comparison mode'), findsNothing);

    await tester.tap(find.byKey(ValueKey('mind-settings-pause-$_room')));
    await tester.pumpAndSettle();
    expect(repo.calls.single, (_room, true));
    expect(find.textContaining('Paused · new virtual fills are rejected'), findsOneWidget);
  });
}
