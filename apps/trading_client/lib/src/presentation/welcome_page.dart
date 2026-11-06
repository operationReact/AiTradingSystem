import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../brand/minttick_brand.dart';
import '../theme/app_theme.dart';

/// Brand-first inline sign-in. No credentials leave this UI before
/// production authentication is implemented.
class MintTickWelcomePage extends StatefulWidget {
  const MintTickWelcomePage({super.key});

  @override
  State<MintTickWelcomePage> createState() => _MintTickWelcomePageState();
}

class _MintTickWelcomePageState extends State<MintTickWelcomePage>
    with SingleTickerProviderStateMixin {
  static const _navy = MintTickPalette.navy;
  static const _muted = Color(0xFF9AADB5);
  static const _fieldColor = Color(0xFF162632);
  static const _fieldBorder = Color(0xFF385661);

  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _passwordFocus = FocusNode();

  late final AnimationController _reveal = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1450),
  )..forward();

  bool _obscurePassword = true;
  bool _rememberMe = false;

  @override
  void dispose() {
    _reveal.dispose();
    _username.dispose();
    _password.dispose();
    _passwordFocus.dispose();
    super.dispose();
  }

  void _signIn() {
    FocusScope.of(context).unfocus();
    if (!(_formKey.currentState?.validate() ?? false)) return;

    // Fail closed: do not send or persist a password until authentication
    // is implemented. The checkbox is display-only in this preview.
    _password.clear();
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text(
          'Secure sign-in is not connected in this preview. '
          'No credentials were submitted.',
        ),
      ),
    );
  }

  void _forgotPassword() {
    FocusScope.of(context).unfocus();
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Password recovery will be enabled with secure accounts.'),
      ),
    );
  }

  Widget _brandLockup(double availableWidth, bool reduceMotion) {
    return AnimatedBuilder(
      animation: _reveal,
      builder: (context, child) {
        final amount = reduceMotion
            ? 1.0
            : Curves.easeOutCubic.transform(_reveal.value);
        return Opacity(
          opacity: amount.clamp(0.0, 1.0),
          child: Transform.translate(
            offset: Offset(0, 15 * (1 - amount)),
            child: Transform.scale(
              scale: 0.97 + amount * 0.03,
              child: child,
            ),
          ),
        );
      },
      child: SizedBox(
        width: math.min(availableWidth, 356),
        child: FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.center,
          child: SizedBox(
            width: 390,
            child: Theme(
              data: buildTheme(Brightness.dark),
              child: const MintTickLockup(markWidth: 104, fontSize: 39),
            ),
          ),
        ),
      ),
    );
  }

  InputDecoration _inputDecoration({
    required String hint,
    required IconData icon,
    Widget? suffix,
  }) {
    OutlineInputBorder line(Color color, [double width = 1]) =>
        OutlineInputBorder(
          borderRadius: BorderRadius.circular(13),
          borderSide: BorderSide(color: color, width: width),
        );
    return InputDecoration(
      hintText: hint,
      hintStyle: const TextStyle(color: _muted, fontSize: 15),
      prefixIcon: Icon(icon, color: const Color(0xFF9AB7BF), size: 21),
      suffixIcon: suffix,
      filled: true,
      fillColor: _fieldColor,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 17),
      border: line(_fieldBorder),
      enabledBorder: line(_fieldBorder),
      focusedBorder: line(MintTickPalette.teal, 1.5),
      errorBorder: line(const Color(0xFFE89999)),
      focusedErrorBorder: line(const Color(0xFFE89999), 1.5),
    );
  }

  Widget _authForm(bool reduceMotion) {
    final progress = reduceMotion
        ? 1.0
        : Curves.easeOutCubic.transform(
            ((_reveal.value - 0.18) / 0.82).clamp(0.0, 1.0),
          );
    return Opacity(
      opacity: progress,
      child: Transform.translate(
        offset: Offset(0, (1 - progress) * 9),
        child: Container(
          key: const ValueKey('minttick-auth-panel'),
          width: double.infinity,
          decoration: BoxDecoration(
            color: const Color(0xFF111D27).withValues(alpha: 0.84),
            border: Border.all(color: const Color(0xFF29434B), width: 0.8),
            borderRadius: BorderRadius.circular(23),
          ),
          padding: const EdgeInsets.fromLTRB(17, 20, 17, 21),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextFormField(
                  key: const ValueKey('minttick-username-input'),
                  controller: _username,
                  style: const TextStyle(color: MintTickPalette.ice, fontSize: 15),
                  keyboardType: TextInputType.emailAddress,
                  textInputAction: TextInputAction.next,
                  autocorrect: false,
                  enableSuggestions: false,
                  onFieldSubmitted: (_) => _passwordFocus.requestFocus(),
                  decoration: _inputDecoration(
                    hint: 'Username or email',
                    icon: Icons.person_outline_rounded,
                  ),
                  validator: (value) =>
                      value == null || value.trim().isEmpty
                          ? 'Enter your username or email'
                          : null,
                ),
                const SizedBox(height: 13),
                TextFormField(
                  key: const ValueKey('minttick-password-input'),
                  controller: _password,
                  focusNode: _passwordFocus,
                  style: const TextStyle(color: MintTickPalette.ice, fontSize: 15),
                  obscureText: _obscurePassword,
                  keyboardType: TextInputType.visiblePassword,
                  textInputAction: TextInputAction.done,
                  autocorrect: false,
                  enableSuggestions: false,
                  onFieldSubmitted: (_) => _signIn(),
                  decoration: _inputDecoration(
                    hint: 'Password',
                    icon: Icons.lock_outline_rounded,
                    suffix: IconButton(
                      key: const ValueKey('minttick-password-visibility'),
                      tooltip: _obscurePassword ? 'Show password' : 'Hide password',
                      onPressed: () => setState(
                        () => _obscurePassword = !_obscurePassword,
                      ),
                      icon: Icon(
                        _obscurePassword
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined,
                        color: _muted,
                      ),
                    ),
                  ),
                  validator: (value) =>
                      value == null || value.isEmpty
                          ? 'Enter your password'
                          : null,
                ),
                const SizedBox(height: 9),
                Row(
                  children: [
                    SizedBox(
                      width: 36,
                      height: 40,
                      child: Checkbox(
                        key: const ValueKey('minttick-remember-me'),
                        value: _rememberMe,
                        activeColor: MintTickPalette.teal,
                        onChanged: (value) => setState(
                          () => _rememberMe = value ?? false,
                        ),
                      ),
                    ),
                    const Flexible(
                      child: Text(
                        'Remember me',
                        maxLines: 1,
                        style: TextStyle(
                          fontSize: 13,
                          color: Color(0xFFCDD9DD),
                        ),
                      ),
                    ),
                    const Spacer(),
                    TextButton(
                      key: const ValueKey('minttick-forgot-password'),
                      onPressed: _forgotPassword,
                      style: TextButton.styleFrom(
                        foregroundColor: const Color(0xFF57DDB9),
                        padding: const EdgeInsets.symmetric(horizontal: 4),
                        textStyle: const TextStyle(
                          fontSize: 12.5,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      child: const Text('Forgot password?'),
                    ),
                  ],
                ),
                const SizedBox(height: 15),
                DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      colors: [Color(0xFF46E7C0), Color(0xFF00D69A)],
                      begin: Alignment.centerLeft,
                      end: Alignment.centerRight,
                    ),
                    borderRadius: BorderRadius.circular(13),
                  ),
                  child: Material(
                    color: Colors.transparent,
                    child: InkWell(
                      key: const ValueKey('minttick-inline-sign-in'),
                      borderRadius: BorderRadius.circular(13),
                      onTap: _signIn,
                      child: const SizedBox(
                        height: 54,
                        child: Center(
                          child: Text(
                            'Sign in',
                            style: TextStyle(
                              fontSize: 17,
                              fontWeight: FontWeight.w800,
                              color: MintTickPalette.navy,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 22),
                const Row(
                  children: [
                    Expanded(
                      child: Divider(color: Color(0xFF3D545D), thickness: 0.7),
                    ),
                    Padding(
                      padding: EdgeInsets.symmetric(horizontal: 17),
                      child: Text(
                        'or',
                        style: TextStyle(color: _muted, fontSize: 13),
                      ),
                    ),
                    Expanded(
                      child: Divider(color: Color(0xFF3D545D), thickness: 0.7),
                    ),
                  ],
                ),
                const SizedBox(height: 17),
                OutlinedButton(
                  key: const ValueKey('minttick-inline-create-account'),
                  onPressed: () =>
                      Navigator.of(context).pushNamed('/sign-up'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: MintTickPalette.ice,
                    minimumSize: const Size(double.infinity, 54),
                    side: const BorderSide(color: Color(0xFF4DAE9D)),
                    backgroundColor: const Color(0xFF152631),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(13),
                    ),
                    textStyle: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  child: const Text('Create account'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final reduceMotion = MediaQuery.of(context).disableAnimations;
    final keyboardOpen = MediaQuery.viewInsetsOf(context).bottom > 0;
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: Scaffold(
        key: const ValueKey('minttick-welcome-screen'),
        backgroundColor: _navy,
        resizeToAvoidBottomInset: true,
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, bounds) {
              final compact = keyboardOpen || bounds.maxHeight < 700;
              final horizontal = bounds.maxWidth < 370 ? 18.0 : 24.0;
              final brandHeight = compact
                  ? 150.0
                  : math.min(bounds.maxHeight * 0.32, 268.0);
              return SingleChildScrollView(
                keyboardDismissBehavior:
                    ScrollViewKeyboardDismissBehavior.onDrag,
                padding: EdgeInsets.fromLTRB(
                  horizontal,
                  compact ? 8 : 18,
                  horizontal,
                  28,
                ),
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 430),
                    child: Column(
                      children: [
                        SizedBox(
                          height: brandHeight,
                          width: double.infinity,
                          child: Stack(
                            alignment: Alignment.center,
                            children: [
                              Positioned.fill(
                                child: DecoratedBox(
                                  decoration: BoxDecoration(
                                    gradient: RadialGradient(
                                      colors: [
                                        MintTickPalette.teal.withValues(alpha: 0.075),
                                        _navy.withValues(alpha: 0),
                                      ],
                                    ),
                                  ),
                                ),
                              ),
                              Center(
                                child: _brandLockup(
                                  bounds.maxWidth - 2 * horizontal,
                                  reduceMotion,
                                ),
                              ),
                            ],
                          ),
                        ),
                        SizedBox(height: compact ? 11 : 23),
                        AnimatedBuilder(
                          animation: _reveal,
                          builder: (context, _) => _authForm(reduceMotion),
                        ),
                        if (!compact) const SizedBox(height: 20),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
