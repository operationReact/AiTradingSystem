import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/operations_evidence_repository.dart';
import 'package:trading_client/src/presentation/operations_evidence_page.dart';

class _FakeOperationsEvidenceRepository implements OperationsEvidenceRepository {
  _FakeOperationsEvidenceRepository(this.snapshot);
  OperationsEvidenceSnapshot snapshot;
  String? acknowledgedId;

  @override
  Future<OperationsEvidenceSnapshot> fetch() async => snapshot;

  @override
  Future<NotificationEvidence> acknowledge(String id) async {
    acknowledgedId=id;
    final current=snapshot.notifications.single;
    final updated=NotificationEvidence(
      id:current.id,type:current.type,severity:current.severity,title:current.title,message:current.message,
      entityType:current.entityType,entityId:current.entityId,createdAt:current.createdAt,
      acknowledgedAt:DateTime.parse('2026-10-31T00:05:00Z'),acknowledgedBy:'operator',
    );
    snapshot=OperationsEvidenceSnapshot(news:snapshot.news,notifications:[updated],audit:snapshot.audit,executionMode:snapshot.executionMode);
    return updated;
  }
}

OperationsEvidenceSnapshot _snapshot()=>OperationsEvidenceSnapshot(
  news:[NewsEvidence(id:'n1',source:'RSS',publisher:'Publisher',sourceUrl:'https://example.test/n1',publishedAt:DateTime.parse('2026-10-31T00:00:00Z'),fetchedAt:DateTime.parse('2026-10-31T00:01:00Z'),title:'Headline',linkedSymbols:const ['INFY-EQ'],ingestionStatus:'READY',summary:'Grounded summary',summaryProvider:'provider',summaryModel:'model',summaryGeneratedAt:DateTime.parse('2026-10-31T00:02:00Z'))],
  notifications:[NotificationEvidence(id:'notification-1',type:'INCIDENT',severity:'WARNING',title:'Provider degraded',message:'Feed delayed',entityType:'PROVIDER',entityId:'angel',createdAt:DateTime.parse('2026-10-31T00:00:00Z'),acknowledgedAt:null,acknowledgedBy:null)],
  audit:[AuditEvidence(id:1,eventType:'ORDER_CREATED',entityType:'ORDER',entityId:'o1',correlationId:'c1',actor:'operator',actorSource:'API_KEY',details:const {'mode':'PAPER'},createdAt:DateTime.parse('2026-10-31T00:00:00Z'))],
  executionMode:ExecutionModeEvidence(activeMode:'PAPER',liveAvailable:false,liveBlockers:const ['LIVE_ORDER_PATH_NOT_IMPLEMENTED','LIVE_SAFETY_CONDITIONS_NOT_VERIFIED'],checkedAt:DateTime.parse('2026-10-31T00:00:00Z')),
);

void main(){
  testWidgets('news screen renders source and summary provenance',(tester) async{
    final repo=_FakeOperationsEvidenceRepository(_snapshot());
    await tester.pumpWidget(MaterialApp(home:OperationsEvidencePage(repository:repo,section:OperationsEvidenceSection.news)));
    await tester.pumpAndSettle();
    expect(find.text('Headline'),findsOneWidget);
    expect(find.textContaining('RSS • Publisher'),findsOneWidget);
    expect(find.textContaining('Summary provenance: provider / model'),findsOneWidget);
    expect(find.textContaining('Source: https://example.test/n1'),findsOneWidget);
  });

  testWidgets('notification acknowledgement refreshes authoritative acknowledgement state',(tester) async{
    final repo=_FakeOperationsEvidenceRepository(_snapshot());
    await tester.pumpWidget(MaterialApp(home:OperationsEvidencePage(repository:repo,section:OperationsEvidenceSection.notifications)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('acknowledge-notification-1')));
    await tester.pumpAndSettle();
    expect(repo.acknowledgedId,'notification-1');
    expect(find.textContaining('Acknowledged 2026-10-31T00:05:00.000Z by operator'),findsOneWidget);
  });

  testWidgets('settings shows backend LIVE blockers and no LIVE activation control',(tester) async{
    final repo=_FakeOperationsEvidenceRepository(_snapshot());
    await tester.pumpWidget(MaterialApp(home:OperationsEvidencePage(repository:repo,section:OperationsEvidenceSection.settings)));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('execution-mode-evidence')),findsOneWidget);
    expect(find.text('Execution mode: PAPER'),findsOneWidget);
    expect(find.textContaining('LIVE is NOT available'),findsOneWidget);
    expect(find.textContaining('LIVE_ORDER_PATH_NOT_IMPLEMENTED'),findsOneWidget);
    expect(find.widgetWithText(FilledButton,'Switch to LIVE'),findsNothing);
    expect(find.widgetWithText(OutlinedButton,'Switch to LIVE'),findsNothing);
  });

  testWidgets('audit screen renders server-redacted event evidence without enrichment',(tester) async{
    final repo=_FakeOperationsEvidenceRepository(_snapshot());
    await tester.pumpWidget(MaterialApp(home:OperationsEvidencePage(repository:repo,section:OperationsEvidenceSection.audit)));
    await tester.pumpAndSettle();
    expect(find.text('ORDER_CREATED'),findsOneWidget);
    expect(find.textContaining('correlation c1'),findsOneWidget);
    expect(find.textContaining('Details {mode: PAPER}'),findsOneWidget);
  });
}
