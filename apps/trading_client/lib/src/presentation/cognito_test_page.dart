import 'package:flutter/material.dart';

import '../auth/customer_identity.dart';
import '../brand/minttick_brand.dart';
import '../theme/app_theme.dart';

/// A restricted Android smoke-test destination. Cognito sign-in is real,
/// but the private Spring Boot customer account API is not publicly live yet.
/// Never mark the user as a MintTick CUSTOMER or show trading routes here.
class MintTickCognitoTestPage extends StatefulWidget {
  const MintTickCognitoTestPage({super.key});

  @override
  State<MintTickCognitoTestPage> createState() =>
      _MintTickCognitoTestPageState();
}

class _MintTickCognitoTestPageState extends State<MintTickCognitoTestPage> {
  late final Future<bool> _session = MintTickIdentity.current.hasSession();
  bool _busy = false;

  Future<void> _signOut() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await MintTickIdentity.current.signOut();
    } catch (_) {
      // Don't treat an unknown sign-out state as verified.
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Unable to confirm sign-out. Please try again.'),
        ));
      }
      return;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (!mounted) return;
    Navigator.of(context).pushNamedAndRemoveUntil('/welcome', (_) => false);
  }

  @override
  Widget build(BuildContext context) {
    const restrictedTestBuild =
        bool.fromEnvironment('MINTTICK_AUTH_ONLY_TEST', defaultValue: false);
    return Theme(
      data: buildTheme(Brightness.dark),
      child: Scaffold(
        key: const ValueKey('minttick-cognito-test-status'),
        backgroundColor: MintTickPalette.navy,
        appBar: AppBar(
          title: const Text('Account verification'),
          backgroundColor: MintTickPalette.navy,
          automaticallyImplyLeading: false,
        ),
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: Padding(
                padding: const EdgeInsets.all(26),
                child: FutureBuilder<bool>(
                  future: _session,
                  builder: (context, snapshot) {
                    final verified = restrictedTestBuild &&
                        snapshot.connectionState == ConnectionState.done &&
                        snapshot.data == true;
                    return Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Icon(
                          verified
                              ? Icons.verified_user_outlined
                              : Icons.lock_outline_rounded,
                          size: 64,
                          color: MintTickPalette.teal,
                        ),
                        const SizedBox(height: 22),
                        Text(
                          verified
                              ? 'Cognito sign-in verified'
                              : snapshot.connectionState != ConnectionState.done
                                  ? 'Checking secure session'
                                  : 'Sign-in is not verified',
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            fontSize: 24,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        const SizedBox(height: 15),
                        Text(
                          verified
                              ? 'Your email-and-password sign-in has a valid '
                                'AWS Cognito session. MintTick backend account '
                                'activation is not available in this test build.'
                              : 'Please sign in again to verify your Cognito '
                                'account. Trading access remains locked.',
                          style: const TextStyle(
                            color: Color(0xFFB0C0CA),
                            fontSize: 15,
                            height: 1.5,
                          ),
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: 16),
                        const Text(
                          'Test scope: registration, email verification, '
                          'sign-in, password reset, and sign-out. '
                          'Trading rooms and brokerage actions are unavailable.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Color(0xFF8EA5AF), fontSize: 13, height: 1.4,
                          ),
                        ),
                        const SizedBox(height: 28),
                        FilledButton(
                          key: const ValueKey('minttick-auth-test-sign-out'),
                          onPressed: _busy ? null : _signOut,
                          style: FilledButton.styleFrom(
                            backgroundColor: MintTickPalette.teal,
                            foregroundColor: MintTickPalette.navy,
                            minimumSize: const Size.fromHeight(52),
                          ),
                          child: Text(_busy ? 'Signing out…' : 'Sign out'),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
