import 'package:flutter/material.dart';

import 'src/app.dart';
import 'src/core/session/operator_session.dart';
import 'src/features/operations_health/data/health_repository.dart';

void main() {
  final session = OperatorSession();
  runApp(TradingApp(
    repositoryFactory: () => HttpHealthRepository(session: session),
    session: session,
    initialRoute: '/sign-in',
  ));
}
