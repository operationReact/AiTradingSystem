import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'src/app.dart';
import 'src/core/session/operator_session.dart';
import 'src/features/operations_health/data/health_repository.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final session = OperatorSession();
  bool hasSeenWelcome = false;
  try {
    hasSeenWelcome = await SharedPreferencesAsync()
            .getBool('minttick.welcome.seen.v1') ??
        false;
  } catch (_) {
    // Cosmetic introduction state must never prevent launching the app.
  }
  runApp(TradingApp(
    repositoryFactory: () => HttpHealthRepository(session: session),
    session: session,
    initialRoute: session.isAuthenticated
        ? '/'
        : (hasSeenWelcome ? '/sign-in' : '/welcome'),
  ));
}
