import 'customer_angel_one_repository.dart';
import 'customer_upstox_repository.dart';

/// A best-effort, nonblocking session restoration after a verified MintTick
/// CUSTOMER login. The backend is the sole authority for the configured owner
/// identity and for reading Angel One SSM credentials.
///
/// Upstox never receives an automatic OAuth start: its developer-app key and
/// secret cannot impersonate the trading-account owner after token expiry.
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

    // Upstox status reads only the currently bound, unexpired broker session.
    // Never silently open the login browser or trade on behalf of the user.
    try {
      final status = await upstox.status();
      upstoxConnected = status.connected;
      upstoxNeedsAuthorization = status.available && !status.connected;
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
