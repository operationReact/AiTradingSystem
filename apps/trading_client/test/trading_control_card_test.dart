import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_control/data/trading_control_repository.dart';
import 'package:trading_client/src/features/trading_control/presentation/trading_control_card.dart';

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
  testWidgets('shows PAPER as locked-safe trading mode and lets user select automation', (tester) async {
    final repository = _FakeTradingControlRepository(
      const TradingControlState(
        tradingMode: TradingMode.paper,
        executionMode: OrderExecutionMode.manual,
        liveAvailable: false,
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: Scaffold(body: TradingControlCard(repository: repository)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('trading-control-card')), findsOneWidget);
    expect(find.text('PAPER'), findsOneWidget);
    expect(find.text('Manual'), findsOneWidget);
    expect(find.text('Automated'), findsOneWidget);
    expect(find.textContaining('Live trading stays locked'), findsOneWidget);

    await tester.tap(find.text('Automated'));
    await tester.pumpAndSettle();

    expect(repository.updates, 1);
    expect(repository.state.executionMode, OrderExecutionMode.automated);
    expect(find.textContaining('Every order still passes risk checks'), findsOneWidget);
  });
}
