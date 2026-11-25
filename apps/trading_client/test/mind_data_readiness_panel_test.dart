import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_progress_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_data_readiness_panel.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

import 'mind_progress_repository_test.dart' show item, payload;

class _ProgressRepo implements MindProgressRepository {
  _ProgressRepo(this.snapshot);
  final MindProgressSnapshot snapshot;

  @override
  Future<MindProgressSnapshot> load() async => snapshot;

  @override
  Future<MindProgressDetail> detail(String id) async =>
      MindProgressDetail(
        item: snapshot.items.firstWhere((item) => item.id == id),
        history: const [],
      );

  @override
  Future<void> annotate(String id, {required String type, String? text}) async {}
}

class _FailedRepo implements MindProgressRepository {
  @override
  Future<MindProgressSnapshot> load() async => throw StateError('offline');

  @override
  Future<MindProgressDetail> detail(String id) async => throw StateError('offline');

  @override
  Future<void> annotate(String id, {required String type, String? text}) async =>
      throw StateError('offline');
}

MindProgressSnapshot _snapshot() {
  final candle = item('data.historical-candles', kind: 'DATA');
  final fno = item('data.fno', kind: 'DATA',
      status: 'DATA_BLOCKED', declared: 'DATA_BLOCKED')
    ..['blockerCode'] = 'NO_PERMITTED_SOURCE'
    ..['missing'] = ['Historical option chain with expiry and OI'];
  return MindProgressSnapshot.fromJson(payload([candle, fno]));
}

void _mobile(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(430, 1100);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  testWidgets('24 data families render without a false readiness percentage',
      (tester) async {
    _mobile(tester);
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: SingleChildScrollView(child:
        MindDataReadinessPanel(repository: _ProgressRepo(_snapshot()))),
    )));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-data-readiness-console')),
        findsOneWidget);
    expect(find.text('24'), findsOneWidget);
    expect(find.text('DATA BLOCKED'), findsOneWidget);
    expect(find.text('CODE TESTED'), findsWidgets);
    expect(find.byKey(const ValueKey('mind-data-family-derivatives_chain')),
        findsOneWidget);
    expect(find.byKey(const ValueKey('mind-data-family-model_features')),
        findsOneWidget);
    expect(find.textContaining('100%'), findsNothing);
    expect(find.text('READY'), findsNothing);

    await tester.ensureVisible(
      find.byKey(const ValueKey('mind-data-family-derivatives_chain')));
    await tester.tap(
      find.byKey(const ValueKey('mind-data-family-derivatives_chain')));
    await tester.pumpAndSettle();
    expect(find.text('NO_PERMITTED_SOURCE'), findsWidgets);
    expect(find.textContaining('Historical option chain'), findsWidgets);
  });

  testWidgets('unavailable registry never becomes a verified data source',
      (tester) async {
    _mobile(tester);
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: SingleChildScrollView(child:
        MindDataReadinessPanel(repository: _FailedRepo())),
    )));
    await tester.pumpAndSettle();
    expect(find.textContaining('Registry unavailable'), findsOneWidget);
    expect(find.text('Not verified'), findsWidgets);
    expect(find.text('RUNTIME EVENT'), findsNothing);
  });

  testWidgets('data readiness opens within the Mind workspace without adding a catalogue route',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1440, 1600);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      home: MintickMindWorkspacePage(
        progressRepository: _ProgressRepo(_snapshot()),
      ),
    ));
    await tester.pumpAndSettle();
    expect(MintickMindScreens.all.length, 44);
    // The standard Overview intentionally does not render the workspace
    // topbar. Navigate to the existing Coverage route through its sidebar
    // before exercising the new header shortcut.
    final coverage = find.byKey(const ValueKey('mind-route-coverage'));
    await tester.ensureVisible(coverage);
    await tester.tap(coverage);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-progress-coverage')),
        findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('mind-open-data-readiness')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-data-readiness-panel')),
        findsOneWidget);
    expect(find.text('Data Readiness'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('mind-data-back-to-coverage')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-progress-coverage')),
        findsOneWidget);
  });
}
