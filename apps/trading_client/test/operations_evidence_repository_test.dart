import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/data/operations_evidence_repository.dart';

void main(){
  test('fetches news notifications audit and execution mode from exact endpoints',() async{
    final session=OperatorSession()..setOperatorKey('operator-key');
    final seen=<String>[];
    final repo=HttpOperationsEvidenceRepository(AuthenticatedApiClient(
      session:session,
      baseUri:Uri.parse('http://example.test'),
      client:MockClient((request) async{
        seen.add(request.url.path);
        expect(request.headers['X-API-Key'],'operator-key');
        switch(request.url.path){
          case '/api/news':
            expect(request.url.queryParameters,{'limit':'20','offset':'0'});
            return http.Response(jsonEncode({'items':[{'id':'n1','source':'RSS','publisher':'P','sourceUrl':'https://example.test/n1','publishedAt':'2026-10-31T00:00:00Z','fetchedAt':'2026-10-31T00:01:00Z','title':'Headline','linkedSymbols':['INFY-EQ'],'ingestionStatus':'READY','summary':'Summary','summaryProvider':'provider','summaryModel':'model','summaryGeneratedAt':'2026-10-31T00:02:00Z'}],'limit':20,'offset':0,'hasMore':false}),200);
          case '/api/notifications':
            expect(request.url.queryParameters,{'activeOnly':'true','page':'0','size':'20'});
            return http.Response(jsonEncode({'items':[{'id':'00000000-0000-0000-0000-000000000001','type':'INCIDENT','severity':'WARNING','title':'Provider degraded','message':'Feed delayed','entityType':'PROVIDER','entityId':'angel','createdAt':'2026-10-31T00:00:00Z','acknowledgedAt':null,'acknowledgedBy':null}],'page':0,'size':20,'totalItems':1,'totalPages':1,'projection':{}}),200);
          case '/api/audit':
            expect(request.url.queryParameters,{'page':'0','size':'20'});
            return http.Response(jsonEncode({'items':[{'id':1,'eventType':'ORDER_CREATED','entityType':'ORDER','entityId':'o1','correlationId':'c1','actor':'operator','actorSource':'API_KEY','details':{'mode':'PAPER'},'createdAt':'2026-10-31T00:00:00Z'}],'page':0,'size':20,'totalItems':1,'totalPages':1}),200);
          case '/api/system/execution-mode':
            return http.Response(jsonEncode({'activeMode':'PAPER','liveAvailable':false,'liveBlockers':['LIVE_ORDER_PATH_NOT_IMPLEMENTED'],'checkedAt':'2026-10-31T00:00:00Z'}),200);
        }
        throw StateError('unexpected ${request.url}');
      }),
    ));

    final snapshot=await repo.fetch();
    expect(seen.toSet(),{'/api/news','/api/notifications','/api/audit','/api/system/execution-mode'});
    expect(snapshot.news.single.summaryProvider,'provider');
    expect(snapshot.notifications.single.acknowledged,isFalse);
    expect(snapshot.audit.single.correlationId,'c1');
    expect(snapshot.executionMode.activeMode,'PAPER');
    expect(snapshot.executionMode.liveAvailable,isFalse);
  });

  test('acknowledges notification using authenticated POST endpoint',() async{
    final session=OperatorSession()..setOperatorKey('operator-key');
    late http.Request captured;
    final repo=HttpOperationsEvidenceRepository(AuthenticatedApiClient(
      session:session,
      baseUri:Uri.parse('http://example.test'),
      client:MockClient((request) async{
        captured=request;
        return http.Response(jsonEncode({'id':'00000000-0000-0000-0000-000000000001','type':'INCIDENT','severity':'WARNING','title':'Provider degraded','message':'Feed delayed','entityType':'PROVIDER','entityId':'angel','createdAt':'2026-10-31T00:00:00Z','acknowledgedAt':'2026-10-31T00:05:00Z','acknowledgedBy':'operator'}),200);
      }),
    ));

    final notification=await repo.acknowledge('00000000-0000-0000-0000-000000000001');
    expect(captured.method,'POST');
    expect(captured.url.path,'/api/notifications/00000000-0000-0000-0000-000000000001/acknowledge');
    expect(notification.acknowledged,isTrue);
    expect(notification.acknowledgedBy,'operator');
  });
}
