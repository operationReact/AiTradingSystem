import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_assistant_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

Map<String, dynamic> answerJson(String intent, String text) => {
      'contractVersion': 'mind.assistant.v1', 'intent': intent, 'answer': text, 'readOnly': true,
      'liveMoney': false, 'generatedAt': '2026-11-14T04:00:00Z',
      'sources': [
        {'name': 'PAPER portfolio', 'endpoint': '/api/customer/mind/v1/paper-portfolio', 'asOf': '2026-11-14T04:00:00Z'},
      ],
    };

class _Assistant implements MindAssistantRepository {
  final asked = <String>[];
  @override
  Future<MindAssistantAnswer> ask(String question) async {
    asked.add(question);
    if (question.toLowerCase().contains('buy')) {
      return MindAssistantAnswer.fromJson(answerJson('TRADE_REQUEST_REFUSED',
          'Mind cannot place, change or cancel orders.'));
    }
    return MindAssistantAnswer.fromJson(answerJson('PAPER_PNL', 'Realized P&L: Rs -12.50'));
  }
}

void main() {
  test('assistant contract refuses any answer that is not explicitly read-only and non-LIVE', () {
    expect(MindAssistantAnswer.fromJson(answerJson('PAPER_PNL', 'x')).sources, hasLength(1));
    expect(() => MindAssistantAnswer.fromJson({...answerJson('PAPER_PNL', 'x'), 'readOnly': false}),
        throwsFormatException);
    expect(() => MindAssistantAnswer.fromJson({...answerJson('PAPER_PNL', 'x'), 'liveMoney': true}),
        throwsFormatException);
  });

  testWidgets('Ask MintTick Mind answers from the server with sources and refuses trade requests',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1440, 1800);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = _Assistant();
    await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(assistantRepository: repo)));
    await tester.pumpAndSettle();
    final nav = find.byKey(const ValueKey('mind-route-assistant'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-assistant-panel')), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('mind-assistant-input')), 'What is my PAPER P&L?');
    await tester.tap(find.byKey(const ValueKey('mind-assistant-send')));
    await tester.pumpAndSettle();
    expect(repo.asked.single, 'What is my PAPER P&L?');
    expect(find.textContaining('Realized P&L: Rs -12.50'), findsOneWidget);
    expect(find.textContaining('Sources: PAPER portfolio'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('mind-assistant-input')), 'buy RELIANCE');
    await tester.tap(find.byKey(const ValueKey('mind-assistant-send')));
    await tester.pumpAndSettle();
    expect(find.textContaining('cannot place, change or cancel orders'), findsOneWidget);
    expect(find.text('Try a Question'), findsNothing); // scripted mock UI is gone
    expect(find.textContaining('scripted'), findsNothing);
  });
}
