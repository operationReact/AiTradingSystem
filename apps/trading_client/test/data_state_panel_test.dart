import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/core/widgets/data_state_panel.dart';

Widget host(DataStatePanel panel) => MaterialApp(
      home: Scaffold(
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: panel,
        ),
      ),
    );

void main() {
  testWidgets('loading state is explicit and accessible', (tester) async {
    await tester.pumpWidget(
      host(
        const DataStatePanel(
          kind: DataStateKind.loading,
          title: 'Loading authoritative state',
          message: 'Waiting for the trading service to respond.',
        ),
      ),
    );

    expect(find.byKey(const ValueKey('data-state-loading')), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is Semantics &&
            widget.properties.label ==
                'LOADING: Loading authoritative state. Waiting for the trading service to respond.',
      ),
      findsOneWidget,
    );
  });

  testWidgets('stale state keeps last-known evidence visibly non-current', (tester) async {
    await tester.pumpWidget(
      host(
        const DataStatePanel(
          kind: DataStateKind.stale,
          title: 'Showing last-known evidence',
          message: 'Refresh failed. Treat these values as stale until a new authoritative response succeeds.',
        ),
      ),
    );

    expect(find.byKey(const ValueKey('data-state-stale')), findsOneWidget);
    expect(find.text('Showing last-known evidence'), findsOneWidget);
    expect(find.textContaining('Treat these values as stale'), findsOneWidget);
  });

  testWidgets('unknown state never presents missing evidence as healthy', (tester) async {
    await tester.pumpWidget(
      host(
        const DataStatePanel(
          kind: DataStateKind.unknown,
          title: 'System state unknown',
          message: 'Required evidence is unavailable. Safety-sensitive actions must remain blocked.',
        ),
      ),
    );

    expect(find.byKey(const ValueKey('data-state-unknown')), findsOneWidget);
    expect(find.text('System state unknown'), findsOneWidget);
    expect(find.textContaining('must remain blocked'), findsOneWidget);
    expect(find.text('Healthy'), findsNothing);
  });
}
