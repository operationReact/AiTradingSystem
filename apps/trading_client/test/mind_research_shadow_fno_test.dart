import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_ma_walkforward_repository.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_progress_repository.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_prospective_cohort_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_progress_repository_test.dart' show item, payload;

/// Failing repositories prove the real server panels are mounted (and fail closed).
class _WalkForward implements MindMaWalkForwardRepository {
  @override
  Future<MindMaWalkForwardReport> load() async => throw StateError('offline');
}

class _Cohort implements MindProspectiveCohortRepository {
  @override
  Future<MindProspectiveCohort> load() async => throw StateError('offline');
}

class _Progress implements MindProgressRepository {
  @override
  Future<MindProgressSnapshot> load() async => MindProgressSnapshot.fromJson(payload([
        item('data.fno', kind: 'DATA', status: 'DATA_BLOCKED', declared: 'DATA_BLOCKED')
          ..['blockerCode'] = 'NO_PERMITTED_SOURCE',
      ]));
  @override
  Future<MindProgressDetail> detail(String id) => throw UnimplementedError();
  @override
  Future<void> annotate(String id, {required String type, String? text}) async {}
}

Future<void> open(WidgetTester tester, String route, Widget page) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 2200);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: page));
  await tester.pumpAndSettle();
  final nav = find.byKey(ValueKey('mind-route-$route'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Research & Backtesting mounts the real walk-forward panel above labelled mocks', (tester) async {
    await open(tester, 'research', MintickMindWorkspacePage(walkForwardRepository: _WalkForward()));
    expect(find.byKey(const ValueKey('mind-research-walkforward')), findsOneWidget);
    expect(find.text('EXPERIMENT CARDS BELOW: MOCK FIXTURES'), findsOneWidget);
  });

  testWidgets('Shadow Arena mounts forward-only prospective evidence', (tester) async {
    await open(tester, 'shadow-arena', MintickMindWorkspacePage(prospectiveRepository: _Cohort()));
    expect(find.byKey(const ValueKey('mind-shadow-cohort')), findsOneWidget);
    expect(find.text('FORWARD-ONLY SHADOW EVIDENCE · NO ORDERS'), findsOneWidget);
  });

  testWidgets('F&O screens state the real data blocker', (tester) async {
    await open(tester, 'derivatives', MintickMindWorkspacePage(progressRepository: _Progress()));
    expect(find.byKey(const ValueKey('mind-fno-data-blocked')), findsOneWidget);
    expect(find.textContaining('DATA BLOCKED (NO_PERMITTED_SOURCE)'), findsOneWidget);
  });

  testWidgets('without the registry the F&O banner says Not verified', (tester) async {
    await open(tester, 'options-chain', const MintickMindWorkspacePage());
    expect(find.textContaining('F&O data status not verified'), findsOneWidget);
    expect(find.text('Call LTP'), findsOneWidget); // labelled mock still present
  });
}
