import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_control/data/trading_control_repository.dart';
import 'package:trading_client/src/features/trading_control/presentation/trading_control_card.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _FakeTradingControlRepository implements TradingControlRepository {
  _FakeTradingControlRepository(this.state);

  TradingControlState state;
  int updates = 0;

  @override
  Future<TradingControlState> load() async => state;

  @override
  Future<TradingControlState> update({TradingMode? tradingMode, OrderExecutionMode? executionMode}) async {
    updates += 1;
    state = TradingControlState(
      tradingMode: tradingMode ?? state.tradingMode,
      executionMode: executionMode ?? state.executionMode,
      liveAvailable: state.liveAvailable,
    );
    return state;
  }
}

void main() {
  testWidgets('shows PAPER risk state and enables automated PAPER execution at phone width', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakeTradingControlRepository(
      const TradingControlState(
        tradingMode: TradingMode.paper,
        executionMode: OrderExecutionMode.manual,
        liveAvailable: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: buildTheme(DesignStyle.neonFuturisticAi, Brightness.dark),
        home: Scaffold(
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: TradingControlCard(repository: repository),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byKey(const ValueKey('trading-control-card')), findsOneWidget);
    expect(find.text('PAPER · MANUAL'), findsOneWidget);
    expect(find.text('Risk Engine: Active'), findsOneWidget);
    expect(find.text('Manual'), findsOneWidget);
    expect(find.text('Automated'), findsOneWidget);
    expect(find.textContaining('Live trading is locked'), findsOneWidget);

    await tester.tap(find.text('Automated'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(repository.updates, 1);
    expect(repository.state.executionMode, OrderExecutionMode.automated);
    expect(find.text('PAPER · AUTOMATED'), findsOneWidget);
    expect(find.text('Auto Trading: Running'), findsOneWidget);
    expect(find.textContaining('Risk checks remain mandatory'), findsOneWidget);
  });
}
