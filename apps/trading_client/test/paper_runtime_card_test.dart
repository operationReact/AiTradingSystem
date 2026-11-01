import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/paper_runtime/data/paper_runtime_repository.dart';
import 'package:trading_client/src/features/paper_runtime/presentation/paper_runtime_card.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _FakePaperRuntimeRepository implements PaperRuntimeRepository {
  _FakePaperRuntimeRepository(this.state);
  PaperRuntimeState state;
  int mutations = 0;

  @override
  Future<PaperRuntimeState> fetch() async => state;

  @override
  Future<PaperRuntimeState> setEmergencyStop({required bool halted}) async {
    mutations += 1;
    state = PaperRuntimeState(
      tradingMode: state.tradingMode,
      executionMode: state.executionMode,
      riskEngineActive: state.riskEngineActive,
      emergencyStopped: halted,
      liveAvailable: state.liveAvailable,
    );
    return state;
  }
}

void main() {
  testWidgets('PAPER automated Home state is premium, phone-safe and emergency stoppable', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakePaperRuntimeRepository(
      const PaperRuntimeState(
        tradingMode: 'PAPER',
        executionMode: PaperExecutionMode.automated,
        riskEngineActive: true,
        emergencyStopped: false,
        liveAvailable: false,
      ),
    );

    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(DesignStyle.neonFuturisticAi, Brightness.dark),
      home: Scaffold(body: SingleChildScrollView(padding: const EdgeInsets.all(20), child: PaperRuntimeCard(repository: repository))),
    ));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('PAPER · AUTOMATED'), findsOneWidget);
    expect(find.text('Risk Engine: Active'), findsOneWidget);
    expect(find.text('Auto Trading: Running'), findsOneWidget);
    expect(find.text('LIVE locked'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-runtime-emergency-stop')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('paper-runtime-emergency-stop')));
    await tester.pumpAndSettle();
    expect(find.text('Stop automated PAPER trading?'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('paper-runtime-confirm-action')));
    await tester.pumpAndSettle();

    expect(repository.mutations, 1);
    expect(repository.state.emergencyStopped, isTrue);
    expect(find.text('Automation paused'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-runtime-resume')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
