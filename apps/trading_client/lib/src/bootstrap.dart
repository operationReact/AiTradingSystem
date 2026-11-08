import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'auth/customer_identity.dart';
import 'brand/minttick_brand.dart';
import 'core/session/operator_session.dart';
import 'features/operations_health/data/health_repository.dart';
import 'presentation/welcome_page.dart';
import 'theme/app_theme.dart';

/// Draws the welcome screen immediately. Network-backed Cognito startup and
/// restored-session verification must NEVER block Flutter's first frame.
/// During bootstrap, the form is visible but cannot submit credentials.
class MintTickStartupApp extends StatefulWidget {
  const MintTickStartupApp({
    super.key,
    this.restoreSession,
    this.readyBuilder,
  });

  /// A test seam; production uses [restoreMintTickSession].
  final Future<String> Function(OperatorSession session)? restoreSession;
  final Widget Function(OperatorSession session, String route)? readyBuilder;

  @override
  State<MintTickStartupApp> createState() => _MintTickStartupAppState();
}

class _MintTickStartupAppState extends State<MintTickStartupApp> {
  final OperatorSession _session = OperatorSession();
  String? _readyRoute;
  bool _failed = false;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _start();
  }

  void _start() {
    final generation = ++_generation;
    _session.clear();
    _readyRoute = null;
    _failed = false;
    unawaited(_finishStartup(generation));
  }

  Future<void> _finishStartup(int generation) async {
    try {
      final route =
          await (widget.restoreSession ?? restoreMintTickSession)(_session);
      if (!mounted || generation != _generation) return;
      setState(() => _readyRoute = route);
    } catch (_) {
      // Fail closed: never show a protected route if session restoration fails.
      if (!mounted || generation != _generation) return;
      setState(() => _failed = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final route = _readyRoute;
    if (route != null) {
      return (widget.readyBuilder ?? _buildTradingApp)(_session, route);
    }
    return MaterialApp(
      title: MintTickBrand.appName,
      debugShowCheckedModeBanner: false,
      theme: buildTheme(Brightness.dark),
      home: Stack(
        key: const ValueKey('minttick-startup-welcome'),
        children: [
          // The same welcome page is available at the first frame, not after
          // Cognito, preferences, or an AWS backend round trip.
          const Positioned.fill(
            child: IgnorePointer(
              ignoring: true,
              child: MintTickWelcomePage(),
            ),
          ),
          Positioned(
            left: 20,
            right: 20,
            bottom: 16,
            child: SafeArea(
              child: Material(
                color: MintTickPalette.nearBlack,
                borderRadius: BorderRadius.circular(12),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 14, vertical: 12),
                  child: _failed
                      ? Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Text(
                              'Secure sign-in initialization did not finish. '
                              'Check your Cognito connection and retry.',
                              textAlign: TextAlign.center,
                            ),
                            TextButton(
                              key: const ValueKey('minttick-startup-retry'),
                              onPressed: () => setState(_start),
                              child: const Text('Retry secure sign-in'),
                            ),
                          ],
                        )
                      : const Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            SizedBox(
                              width: 17,
                              height: 17,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                            SizedBox(width: 12),
                            Flexible(child: Text('Preparing secure sign-in…')),
                          ],
                        ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTradingApp(OperatorSession session, String route) =>
      TradingApp(
        repositoryFactory: () => HttpHealthRepository(session: session),
        session: session,
        initialRoute: route,
      );
}

/// Restores a cached Cognito session only after verified JWT-backed MintTick
/// provisioning. All network operations are bounded and fail closed.
/// No remembered preference grants access to a protected screen by itself.
Future<String> restoreMintTickSession(OperatorSession session) async {
  await MintTickIdentity.initialize().timeout(const Duration(seconds: 12));
  if (!MintTickIdentity.current.isConfigured) {
    throw StateError('Cognito is not configured');
  }

  var rememberMe = false;
  try {
    rememberMe = await SharedPreferencesAsync()
            .getBool('minttick.auth.remember_me.v1')
            .timeout(const Duration(seconds: 3)) ??
        false;
  } catch (_) {
    // An unreadable preference is not permission to restore a session.
  }

  final hasSession = await MintTickIdentity.current
      .hasSession()
      .timeout(const Duration(seconds: 7));
  if (!rememberMe) {
    if (hasSession) {
      // Never retain a cached session when Remember Me is off.
      await MintTickIdentity.current
          .signOut()
          .timeout(const Duration(seconds: 7));
    }
    return '/welcome';
  }

  if (!hasSession) return '/welcome';
  if (const bool.fromEnvironment(
      'MINTTICK_AUTH_ONLY_TEST', defaultValue: false)) {
    return '/auth-test';
  }

  try {
    await MintTickCustomerAccountApi()
        .provision()
        .timeout(const Duration(seconds: 16));
    session.markCustomerAuthenticated();
    return '/';
  } catch (_) {
    // A cached Cognito session alone is not sufficient for product access.
    return '/welcome';
  }
}
