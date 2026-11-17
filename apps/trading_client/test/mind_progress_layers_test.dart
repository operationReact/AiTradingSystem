import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_progress_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_progress_panel.dart';

Map<String, dynamic> _layer(int count, int total, num percent) =>
    {'count': count, 'total': total, 'percent': percent};

Map<String, dynamic> _overall({Map<String, dynamic>? layers}) => {
      'total': 313, 'done': 0, 'pending': 313, 'blocked': 269, 'percentDone': 0.0,
      'layers': layers ?? {
        'implemented': _layer(89, 313, 28.4),
        'ciTested': _layer(43, 313, 13.7),
        'deployed': _layer(0, 313, 0.0),
        'runtimeVerified': _layer(0, 313, 0.0),
      },
    };

void main() {
  group('MindProgressLayers.fromOverall', () {
    test('parses the four server layers with their own counts', () {
      final layers = MindProgressLayers.fromOverall(_overall());
      expect(layers, isNotNull);
      expect(layers!.implemented.count, 89);
      expect(layers.ciTested.count, 43);
      expect(layers.deployed.count, 0);
      expect(layers.runtimeVerified.count, 0);
      expect(layers.implemented.total, 313);
      expect(layers.implemented.percent, 28.4);
    });

    test('missing layers return null, never zeros', () {
      expect(MindProgressLayers.fromOverall(<String, dynamic>{'total': 313}), isNull);
      expect(MindProgressLayers.fromOverall(_overall(layers: {'implemented': _layer(1, 2, 50.0)})), isNull);
    });

    test('inconsistent or non-finite values fail closed', () {
      expect(MindProgressLayers.fromOverall(_overall(layers: {
        'implemented': _layer(400, 313, 100.0), // count above total
        'ciTested': _layer(0, 313, 0.0),
        'deployed': _layer(0, 313, 0.0),
        'runtimeVerified': _layer(0, 313, 0.0),
      })), isNull);
      expect(MindProgressLayers.fromOverall(_overall(layers: {
        'implemented': _layer(1, 10, 150.0), // percent above 100
        'ciTested': _layer(0, 10, 0.0),
        'deployed': _layer(0, 10, 0.0),
        'runtimeVerified': _layer(0, 10, 0.0),
      })), isNull);
    });
  });

  testWidgets('shows each layer separately with count, total and percent', (tester) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: MindProgressLayerRow(layers: MindProgressLayers.fromOverall(_overall())),
    )));
    expect(find.byKey(const ValueKey('mind-progress-layers')), findsOneWidget);
    expect(find.text('89/313'), findsOneWidget);
    expect(find.text('43/313'), findsOneWidget);
    expect(find.text('Runtime-verified'), findsOneWidget);
    expect(find.text('0/313'), findsNWidgets(2));
    expect(find.text('28.4% · code evidence'), findsOneWidget);
  });

  testWidgets('unavailable layers are stated, not shown as verified', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: MindProgressLayerRow(layers: null))));
    expect(find.byKey(const ValueKey('mind-progress-layers-unavailable')), findsOneWidget);
    expect(find.textContaining('nothing is shown as verified'), findsOneWidget);
  });
}
