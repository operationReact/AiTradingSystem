import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_fixtures.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_progress_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

Map<String, dynamic> item(String id, {String kind = 'ENGINE', String status = 'TESTED',
    String declared = 'TESTED', bool? done, Map<String, dynamic>? runtime}) => {
      'id': id, 'kind': kind, 'name': id, 'group': 'G', 'parentId': null, 'owner': 'Claude',
      'status': status, 'declaredStatus': declared, 'done': done ?? (status == 'RUNTIME_VERIFIED'),
      'blockerCode': null, 'requiredInputs': <String>[], 'dependsOn': <String>[], 'implementation': null,
      'evidence': <Object>[], 'missing': <String>[], 'note': null, 'runtime': runtime ?? <String, dynamic>{},
    };

Map<String, dynamic> payload(List<Map<String, dynamic>> items, {String revision = 'abc1234', bool known = true}) {
  final kinds = <String, List<Map<String, dynamic>>>{};
  for (final i in items) {
    kinds.putIfAbsent(i['kind'] as String, () => []).add(i);
  }
  return {
    'contractVersion': 'mind.progress.v1', 'manifestVersion': 'm1', 'auditedAt': '2026-11-13',
    'generatedAt': '2026-11-13T10:00:00Z', 'backendRevision': revision, 'revisionKnown': known,
    'sourceIssues': ['#998'],
    'paperStage': {'capabilityId': 'engine.x', 'name': 'X', 'status': 'TESTED'},
    'summaries': [
      for (final e in kinds.entries)
        {'kind': e.key, 'denominator': e.value.length,
         'done': e.value.where((i) => i['done'] == true).length,
         'tested': 0, 'inProgress': 0, 'blocked': 0, 'mock': 0, 'notStarted': 0, 'failed': 0},
    ],
    'items': items,
  };
}

void main() {
  test('verified item parses only with runtime proof on the current known revision', () {
    final ok = MindProgressSnapshot.fromJson(payload([
      item('engine.a', status: 'RUNTIME_VERIFIED',
          runtime: {'lastVerifiedAt': '2026-11-13T09:00:00Z', 'verifiedRevision': 'abc1234'}),
      item('screen.x', kind: 'SCREEN', status: 'MOCK', declared: 'MOCK'),
    ]));
    expect(ok.items.first.done, isTrue);
    expect(ok.summary('ENGINE')!.done, 1);
    expect(ok.summary('SCREEN')!.done, 0);
  });

  test('mock, unknown revision, stale revision or inconsistent flags can never render DONE', () {
    final proof = {'lastVerifiedAt': '2026-11-13T09:00:00Z', 'verifiedRevision': 'abc1234'};
    for (final bad in <Map<String, dynamic>>[
      payload([item('screen.x', kind: 'SCREEN', status: 'RUNTIME_VERIFIED', declared: 'MOCK', runtime: proof)]),
      payload([item('engine.a', status: 'RUNTIME_VERIFIED', runtime: proof)], revision: 'unknown', known: false),
      payload([item('engine.a', status: 'RUNTIME_VERIFIED',
          runtime: {'lastVerifiedAt': '2026-11-13T09:00:00Z', 'verifiedRevision': 'fffffff'})]),
      payload([item('engine.a', status: 'RUNTIME_VERIFIED')]),
      payload([item('engine.a', status: 'TESTED', done: true)]),
      payload([item('engine.a', status: 'DONE')]),
    ]) {
      expect(() => MindProgressSnapshot.fromJson(bad), throwsFormatException);
    }
    final mismatch = payload([item('engine.a')]);
    (mismatch['summaries'] as List).first['denominator'] = 2;
    expect(() => MindProgressSnapshot.fromJson(mismatch), throwsFormatException);
    expect(() => MindProgressSnapshot.fromJson({...payload([item('engine.a')]), 'contractVersion': 'v0'}),
        throwsFormatException);
  });

  test('backend audited manifest matches the approved Flutter screens, catalog and models exactly', () {
    final file = File('../../backend/src/main/resources/mind/capability-manifest.v1.json');
    final manifest = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
    final items = (manifest['items'] as List).cast<Map<String, dynamic>>();
    final screens = items.where((i) => i['kind'] == 'SCREEN').toList();
    final strategies = items.where((i) => i['kind'] == 'STRATEGY').toList();
    final models = items.where((i) => i['kind'] == 'MODEL').toList();

    expect(screens.map((i) => i['id']).toList(),
        MintickMindScreens.all.map((s) => 'screen.${s.id}').toList());
    expect(screens.map((i) => i['name']).toList(), MintickMindScreens.all.map((s) => s.label).toList());
    expect(strategies.map((i) => i['id']).toList(),
        MindFixtures.strategies.map((s) => 'strategy.${s.id}').toList());
    expect(strategies.map((i) => i['name']).toList(), MindFixtures.strategies.map((s) => s.name).toList());
    expect(strategies.map((i) => i['group']).toList(), MindFixtures.strategies.map((s) => s.family).toList());
    expect(models.map((i) => i['name']).toList(), MindFixtures.models.map((m) => m.name).toList());
    expect(manifest['denominators'], {'SCREEN': 43, 'STRATEGY': 232, 'MODEL': 18});
    for (final i in items) {
      expect(const {'RUNTIME_VERIFIED', 'DEPLOYED', 'FAILED'}.contains(i['status']), isFalse, reason: '${i['id']}');
    }
  });
}
