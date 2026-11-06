import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'src/app.dart';
import 'src/auth/customer_identity.dart';
import 'src/core/session/operator_session.dart';
import 'src/features/operations_health/data/health_repository.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await MintTickIdentity.initialize();
  final session = OperatorSession();
  // The Remember me preference contains no credentials. When it is not set
  // we deliberately terminate a cached Cognito session rather than silently
  // signing into somebody else's account on a shared device.
  var rememberMe = false;
  try {
    rememberMe = await SharedPreferencesAsync()
        .getBool('minttick.auth.remember_me.v1') ?? false;
  } catch (_) {
    // A missing preference must fail closed.
  }
  if (!rememberMe && await MintTickIdentity.current.hasSession()) {
    try {
      await MintTickIdentity.current.signOut();
    } catch (_) {
      // Still fail closed; do not expose the protected dashboard.
    }
  }
  // A device's cached Cognito session is not itself proof that MintTick's
  // backend recognizes and authorizes the customer. Validate it every launch.
  if (rememberMe && await MintTickIdentity.current.hasSession()) {
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
