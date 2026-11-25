import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_fno_auto_paper_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_fno_auto_paper_panel.dart';

class _FakeRepo implements MindFnoAutoPaperRepository {
  bool enabled=false;
  @override
  Future<List<FnoPaperRoom>> rooms() async => [
    const FnoPaperRoom('12345678-1234-1234-1234-123456789012','F&O Swing PAPER'),
  ];
  @override
  Future<FnoPaperAutomationState> state(String roomId) async =>
    FnoPaperAutomationState(enabled,'NO_TRADE','NO_NEW_DIRECTIONAL_CROSSOVER',
      DateTime.utc(2026,11,20),DateTime.utc(2026,11,20,5));
  @override
  Future<FnoPaperAutomationState> setEnabled(String roomId,bool value) async {
    enabled=value;
    return state(roomId);
  }
  @override
  Future<List<FnoPaperCycleEvent>> history(String roomId) async => [
    FnoPaperCycleEvent(DateTime.utc(2026,11,20,5),'NO_TRADE',
      'NO_NEW_DIRECTIONAL_CROSSOVER','NONE',null,null,null,null),
  ];
}

void main() {
  test('status parser refuses LIVE authority',(){
    expect(()=>FnoPaperAutomationState.parse({
      'enabled':true,'liveMoney':true,
    }),throwsFormatException);
    expect(FnoPaperAutomationState.parse({
      'enabled':false,'liveMoney':false,
    }).enabled,isFalse);
  });

  test('cycle parser distinguishes logged abstention and filled evidence',(){
    final at='2026-11-20T05:00:00Z';
    final event=FnoPaperCycleEvent.parse({
      'evaluatedAt':at,'liveMoney':false,'status':'NO_TRADE',
      'reasonCode':'NO_NEW_DIRECTIONAL_CROSSOVER','action':'NONE',
    });
    expect(event.action,'NONE');
    expect(()=>FnoPaperCycleEvent.parse({
      'evaluatedAt':at,'liveMoney':false,'status':'FILLED_RECONCILED',
      'reasonCode':'PAPER_FILL_RECONCILED','action':'ENTER',
    }),throwsFormatException);
  });

  test('all endpoints stay under strict room-scoped PAPER path',(){
    expect(HttpMindFnoAutoPaperRepository.path(
      '12345678-1234-1234-1234-123456789012'),contains('/mind/fno/paper-automation'));
    expect(()=>HttpMindFnoAutoPaperRepository.path('not-a-room'),throwsFormatException);
  });

  testWidgets('opt-in, stop, and audit history do not expose LIVE orders',(tester) async {
    final repo=_FakeRepo();
    await tester.pumpWidget(MaterialApp(home:Scaffold(body:SingleChildScrollView(
      child:MindFnoAutoPaperPanel(repository:repo),
    ))));
    await tester.pumpAndSettle();
    expect(find.textContaining('VIRTUAL ONLY'),findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('mind-fno-auto-room')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('F&O Swing PAPER').last);
    await tester.pumpAndSettle();
    expect(find.text('AUTOMATION STOPPED'),findsOneWidget);
    expect(find.textContaining('NO_NEW_DIRECTIONAL_CROSSOVER'),findsWidgets);
    await tester.tap(find.byKey(const ValueKey('mind-fno-auto-toggle')));
    await tester.pumpAndSettle();
    expect(find.text('AUTOMATION ENABLED'),findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('mind-fno-auto-toggle')));
    await tester.pumpAndSettle();
    expect(find.text('AUTOMATION STOPPED'),findsOneWidget);
    expect(find.text('Place LIVE Order'),findsNothing);
  });
}
