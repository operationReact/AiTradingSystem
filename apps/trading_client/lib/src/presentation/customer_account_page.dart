import 'package:flutter/material.dart';

import '../brand/minttick_brand.dart';
import '../theme/app_theme.dart';

/// First customer-only destination. No operator, paper, or broker privileges
/// are implied by successful Cognito sign-in.
class CustomerAccountPage extends StatefulWidget {
  const CustomerAccountPage({
    required this.loadAccount,
    required this.onSignOut,
    super.key,
  });

  final Future<Map<String, dynamic>> Function() loadAccount;
  final Future<void> Function() onSignOut;

  @override
  State<CustomerAccountPage> createState() => _CustomerAccountPageState();
}

class _CustomerAccountPageState extends State<CustomerAccountPage> {
  Map<String, dynamic>? _profile;
  bool _busy = true;
  bool _signingOut = false;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    setState(() => _busy = true);
    try {
      final profile = await widget.loadAccount();
      if (!mounted) return;
      final id = profile['userId'];
      final email = profile['email'];
      if (id is! String || id.isEmpty || email is! String || email.isEmpty ||
          profile['role'] != 'CUSTOMER') {
        throw const FormatException('Unverified account response');
      }
      setState(() => _profile = profile);
    } catch (_) {
      if (mounted) setState(() => _profile = null);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _signOut() async {
    if (_signingOut) return;
    setState(() => _signingOut = true);
    try {
      await widget.onSignOut();
    } finally {
      if (mounted) setState(() => _signingOut = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: buildTheme(Brightness.dark),
      child: Scaffold(
        key: const ValueKey('minttick-customer-account-screen'),
        backgroundColor: MintTickPalette.navy,
        body: SafeArea(
          child: RefreshIndicator(
            onRefresh: _reload,
            child: ListView(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 26),
              children: [
                const Center(child: MintTickLockup(markWidth: 64, fontSize: 26)),
                const SizedBox(height: 42),
                const Icon(Icons.verified_user_outlined,
                    size: 62, color: MintTickPalette.teal),
                const SizedBox(height: 16),
                const Text(
                  'Your MintTick account',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 12),
                const Text(
                  'Your application identity is stored separately from your sign-in credentials.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Color(0xFFB5C4CE), fontSize: 14),
                ),
                const SizedBox(height: 28),
                if (_busy)
                  const Center(child: CircularProgressIndicator())
                else if (_profile == null) ...[
                  const Text(
                    'We could not verify your MintTick account with the backend. '
                    'Your session has not been granted trading access.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Color(0xFFFFB4AB)),
                  ),
                  const SizedBox(height: 14),
                  OutlinedButton(
                    key: const ValueKey('minttick-retry-account'),
                    onPressed: _reload,
                    child: const Text('Retry verification'),
                  ),
                ] else
                  Container(
                    key: const ValueKey('minttick-account-verified-card'),
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: const Color(0xFF152630),
                      border: Border.all(color: const Color(0xFF3A645F)),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Row(children: [
                          Icon(Icons.check_circle_outline,
                              color: MintTickPalette.teal, size: 20),
                          SizedBox(width: 9),
                          Text('Securely signed in',
                              style: TextStyle(fontWeight: FontWeight.w800)),
                        ]),
                        const SizedBox(height: 20),
                        const Text('VERIFIED EMAIL',
                            style: TextStyle(fontSize: 11, color: Color(0xFF9DB2BD))),
                        const SizedBox(height: 4),
                        SelectableText(_profile!['email'] as String,
                            style: const TextStyle(fontSize: 15)),
                        const SizedBox(height: 20),
                        const Text('PERMANENT MINTTICK USER ID',
                            style: TextStyle(fontSize: 11, color: Color(0xFF9DB2BD))),
                        const SizedBox(height: 4),
                        SelectableText(_profile!['userId'] as String,
                            style: const TextStyle(fontSize: 12)),
                      ],
                    ),
                  ),
                const SizedBox(height: 20),
                const Text(
                  'Broker connections are completely separate from your MintTick sign-in.',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 12, color: Color(0xFF9FB3BF)),
                ),
                const SizedBox(height: 28),
                SizedBox(
                  height: 50,
                  child: OutlinedButton.icon(
                    key: const ValueKey('minttick-customer-sign-out'),
                    onPressed: _signingOut ? null : _signOut,
                    icon: const Icon(Icons.logout_rounded),
                    label: const Text('Sign out'),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: const Color(0xFFD3E5E9),
                      side: const BorderSide(color: Color(0xFF4A7175)),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
