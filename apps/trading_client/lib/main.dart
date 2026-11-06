import 'package:flutter/material.dart';

import 'src/app.dart';
import 'src/core/session/operator_session.dart';
import 'src/features/operations_health/data/health_repository.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final session = OperatorSession();
  runApp(TradingApp(
    repositoryFactory: () => HttpHealthRepository(session: session),
    session: session,
    // Every signed-out launch displays the inline sign-in form and original
    // MintTick logo reveal directly, never an additional sign-in page.
    initialRoute: session.isAuthenticated ? '/' : '/welcome',
  ));
}
