import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_research_engine_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

Map<String, dynamic> registryJson({bool connected = false}) => {
      'contractVersion': 'mind.research-engines.v1', 'generatedAt': '2026-11-14T06:00:00Z',
      'total': 2, 'connected': connected ? 1 : 0,
      'engines': [
        {'id': 'VECTORBT', 'name': 'VectorBT', 'category': 'Vectorised backtests', 'connected': connected,
         'enabled': false, 'status': connected ? 'CONNECTED' : 'NOT_CONNECTED', 'capabilities': ['sweep'],
         'dependencies': ['numpy'], 'missing': 'No adapter.'},
        {'id': 'FREQTRADE', 'name': 'Freqtrade', 'category': 'Bot', 'connected': false, 'enabled': false,
         'status': 'NOT_APPLICABLE', 'capabilities': <String>[], 'dependencies': <String>[], 'missing': 'Not applicable.'},
      ],
    };

class _Repo implements MindResearchEngineRepository {
  _Repo(this.json);
  final Map<String, dynamic> json;
  @override
  Future<MindEngineRegistry> load() async => MindEngineRegistry.fromJson(json);
}

void main() {
  test('registry refuses an engine marked connected or enabled without CONNECTED status', () {
    expect(MindEngineRegistry.fromJson(registryJson()).connected, 0);
    final lying = registryJson();
    (lying['engines'] as List)[0]['enabled'] = true;
    expect(() => MindEngineRegistry.fromJson(lying), throwsFormatException);
    expect(() => MindEngineRegistry.fromJson({...registryJson(), 'contractVersion': 'v0'}), throwsFormatException);
  });

  testWidgets('Research Engines Lab shows real engine state and never presents a named engine as available',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1440, 1800);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(
        researchEngineRepository: _Repo(registryJson()))));
    await tester.pumpAndSettle();
    final nav = find.byKey(const ValueKey('mind-route-research-engines'));
    await tester.ensureVisible(nav);
    await tester.tap(nav);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-engines-summary')), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-engine-VECTORBT')), findsOneWidget);
    expect(find.text('NOT CONNECTED'), findsOneWidget);
    expect(find.text('Connected'), findsOneWidget);
    expect(find.text('NOT_CONNECTED'), findsNothing);
  });
}
