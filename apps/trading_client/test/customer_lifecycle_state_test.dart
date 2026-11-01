import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/customer_lifecycle_state.dart';

void main() {
  group('customer PAPER lifecycle language', () {
    test('maps backend order states without overstating execution', () {
      expect(
        CustomerLifecycleState.forOrder(status: 'NEW', side: 'BUY').label,
        'Watching',
      );
      expect(
        CustomerLifecycleState.forOrder(status: 'RISK_APPROVED', side: 'BUY').label,
        'Risk approved',
      );
      expect(
        CustomerLifecycleState.forOrder(status: 'REJECTED', side: 'BUY').label,
        'Rejected by risk',
      );
      expect(
        CustomerLifecycleState.forOrder(status: 'FILLED', side: 'BUY').label,
        'Position opened',
      );
      expect(
        CustomerLifecycleState.forOrder(status: 'FILLED', side: 'SELL').label,
        'Position closed',
      );
    });

    test('unknown backend states fail safely to review', () {
      expect(
        CustomerLifecycleState.forOrder(status: 'SOMETHING_NEW', side: 'BUY'),
        same(CustomerLifecycleState.needsReview),
      );
    });

    test('research evidence is not presented as execution approval', () {
      final state = CustomerLifecycleState.forEvaluation('RESEARCH_SUPPORTED');

      expect(state.label, 'Research supported');
      expect(state.description, contains('not execution approval'));
    });
  });
}
