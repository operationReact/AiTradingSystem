import 'customer_angel_one_repository.dart';
import 'customer_upstox_repository.dart';

/// A best-effort, nonblocking session restoration after a verified MintTick
/// CUSTOMER login. The backend is the sole authority for the configured owner
/// identity and for reading Angel One SSM credentials.
///
/// Upstox never receives an automatic OAuth browser start. For an eligible
/// individual owner app, the backend may securely generate a new session
/// from exact-owner SSM PIN + TOTP. Developer keys alone never grant access.
class CustomerBrokerAutoRestore {
  const CustomerBrokerAutoRestore({
    required this.angelOne,
    required this.upstox,
  });

  final HttpCustomerAngelOneRepository angelOne;
  final HttpCustomerUpstoxRepository upstox;

  Future<BrokerRestoreResult> restore() async {
    bool angelConnected = false;
    bool upstoxConnected = false;
    bool upstoxNeedsAuthorization = false;

    // Reuse valid broker sessions to avoid revoking Upstox's previous token.
    // Only the explicitly bound Cognito owner can use an optional approved
    // individual-app PIN + TOTP flow; backend verifies the owner again.
    // Other customers stay on the normal Upstox-hosted OAuth login.
    try {
      var status = await upstox.status();
      upstoxConnected = status.connected;
      if (!upstoxConnected && status.available) {
        try {
          if (await upstox.configuredLoginAvailable()) {
            status = await upstox.connectConfigured();
            upstoxConnected = status.connected;
          }
        } catch (_) {
          // Unsupported app type, unavailable secrets, broker denial or 404
          // from an older backend: keep normal Upstox OAuth available.
        }
      }
      upstoxNeedsAuthorization = status.available && !upstoxConnected;
    } catch (_) {
      // A network failure is not evidence of an expired broker grant.
    }

    // Always verify the broker status first: reconnecting a live session
    // needlessly can invalidate streaming tokens and hit provider limits.
    try {
      var status = await angelOne.status();
      angelConnected = status.connected;
      if (!angelConnected) {
        final ownerAllowed = await angelOne.configuredLoginAvailable();
        if (ownerAllowed) {
          // Server verifies exact Cognito subject, then securely uses SSM.
          // This API does not accept client-supplied broker credentials.
          status = await angelOne.connectConfigured();
          angelConnected = status.connected;
        }
      }
    } catch (_) {
      // Leave the login and Home screen usable. Broker can retry on its page.
    }
    return BrokerRestoreResult(
      angelOneConnected: angelConnected,
      upstoxConnected: upstoxConnected,
      upstoxAuthorizationRequired: upstoxNeedsAuthorization,
    );
  }
}

class BrokerRestoreResult {
  const BrokerRestoreResult({
    required this.angelOneConnected,
    required this.upstoxConnected,
    required this.upstoxAuthorizationRequired,
  });

  final bool angelOneConnected;
  final bool upstoxConnected;
  final bool upstoxAuthorizationRequired;
}
