import 'package:flutter/material.dart';

import 'src/app.dart';
import 'src/data/health_repository.dart';
import 'src/data/operator_session.dart';

void main() {
  final session = OperatorSession();
  runApp(TradingApp(
    repositoryFactory: () => HttpHealthRepository(session: session),
    session: session,
  ));
}
