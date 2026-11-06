import 'package:flutter/material.dart';

import 'src/app.dart';
import 'src/auth/customer_identity.dart';
import 'src/core/session/operator_session.dart';
import 'src/features/operations_health/data/health_repository.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await MintTickIdentity.initialize();
  final session = OperatorSession();
  // A device's cached Cognito session is not itself proof that MintTick's
  // backend recognizes and authorizes the customer. Validate it every launch.
  if (await MintTickIdentity.current.hasSession()) {
    try {
      await MintTickCustomerAccountApi().provision();
      session.markCustomerAuthenticated();
    } catch (_) {
      // Fail closed. Do not open a protected dashboard on a stale identity.
    }
  }
  runApp(TradingApp(
    repositoryFactory: () => HttpHealthRepository(session: session),
    session: session,
    initialRoute: session.isAuthenticated ? '/' : '/welcome',
  ));
}
