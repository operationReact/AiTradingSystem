import 'dart:async';

import 'package:flutter/material.dart';

import 'auth_components.dart';

enum OtpVerificationType { phone, email }

class OtpVerificationPage extends StatefulWidget {
  const OtpVerificationPage({
    required this.type,
    required this.destination,
    required this.onBack,
    required this.onVerified,
    super.key,
  });

  final OtpVerificationType type;
  final String destination;
  final VoidCallback onBack;
  final VoidCallback onVerified;

  @override
  State<OtpVerificationPage> createState() => _OtpVerificationPageState();
}

class _OtpVerificationPageState extends State<OtpVerificationPage> {
  final _controllers = List.generate(6, (_) => TextEditingController());
  final _focusNodes = List.generate(6, (_) => FocusNode());
  Timer? _timer;
  int _secondsRemaining = 28;

  @override
  void initState() {
    super.initState();
    _startTimer();
  }

  @override
  void dispose() {
    _timer?.cancel();
    for (final controller in _controllers) {
      controller.dispose();
    }
    for (final node in _focusNodes) {
      node.dispose();
    }
    super.dispose();
  }

  void _startTimer() {
    _timer?.cancel();
    setStateIfMounted(() => _secondsRemaining = 28);
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) return;
      if (_secondsRemaining <= 1) {
        timer.cancel();
        setState(() => _secondsRemaining = 0);
      } else {
        setState(() => _secondsRemaining -= 1);
      }
    });
  }

  void setStateIfMounted(VoidCallback action) {
    if (mounted) setState(action);
  }

  @override
  Widget build(BuildContext context) {
    final isPhone = widget.type == OtpVerificationType.phone;
    final title = isPhone ? 'Verify Your Number' : 'Verify Your Email';
    final icon = isPhone ? Icons.phone_android_rounded : Icons.mark_email_read_outlined;

    return AuthShell(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          AuthBackButton(onPressed: widget.onBack),
          const SizedBox(height: 12),
          Text(title, style: const TextStyle(color: Colors.white, fontSize: 23, fontWeight: FontWeight.w900, letterSpacing: -0.5)),
          const SizedBox(height: 25),
          Container(
            width: 86,
            height: 86,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(22),
              gradient: LinearGradient(colors: [AuthPalette.violet.withValues(alpha: .22), AuthPalette.cyan.withValues(alpha: .10)]),
              border: Border.all(color: AuthPalette.cyan.withValues(alpha: .65), width: 1.3),
              boxShadow: const [BoxShadow(color: Color(0x3039E8FF), blurRadius: 28)],
            ),
            child: Icon(icon, color: AuthPalette.cyan, size: 43),
          ),
          const SizedBox(height: 32),
          const Text('Enter the 6-digit OTP', style: TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.w900)),
          const SizedBox(height: 7),
          Text('We’ve sent a code to\n${widget.destination}', textAlign: TextAlign.center, style: const TextStyle(color: AuthPalette.muted, fontSize: 12.5, height: 1.45, fontWeight: FontWeight.w600)),
          const SizedBox(height: 26),
          _OtpFields(controllers: _controllers, focusNodes: _focusNodes),
          const SizedBox(height: 18),
          if (_secondsRemaining > 0)
            Text('Resend OTP in 00:${_secondsRemaining.toString().padLeft(2, '0')}', style: const TextStyle(color: AuthPalette.muted, fontSize: 12, fontWeight: FontWeight.w600))
          else
            TextButton(
              key: ValueKey('auth-resend-${isPhone ? 'phone' : 'email'}-otp'),
              onPressed: _startTimer,
              style: TextButton.styleFrom(foregroundColor: AuthPalette.cyan),
              child: const Text('Resend OTP', style: TextStyle(fontWeight: FontWeight.w800)),
            ),
          const SizedBox(height: 58),
          GradientAuthButton(
            key: ValueKey('auth-verify-${isPhone ? 'phone' : 'email'}-otp'),
            label: 'Verify & Continue',
            onPressed: _verify,
          ),
        ],
      ),
    );
  }

  void _verify() {
    final otp = _controllers.map((controller) => controller.text).join();
    if (otp.length != 6) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(behavior: SnackBarBehavior.floating, content: Text('Enter all 6 OTP digits.')));
      return;
    }
    widget.onVerified();
  }
}

class _OtpFields extends StatelessWidget {
  const _OtpFields({required this.controllers, required this.focusNodes});

  final List<TextEditingController> controllers;
  final List<FocusNode> focusNodes;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: List.generate(6, (index) {
        return SizedBox(
          width: 45,
          height: 50,
          child: TextField(
            key: ValueKey('auth-otp-digit-$index'),
            controller: controllers[index],
            focusNode: focusNodes[index],
            keyboardType: TextInputType.number,
            textAlign: TextAlign.center,
            maxLength: 1,
            style: const TextStyle(color: Colors.white, fontSize: 19, fontWeight: FontWeight.w900),
            decoration: InputDecoration(
              counterText: '',
              filled: true,
              fillColor: AuthPalette.surface,
              contentPadding: EdgeInsets.zero,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: AuthPalette.border)),
              enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: AuthPalette.border)),
              focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: AuthPalette.cyan, width: 1.4)),
            ),
            onChanged: (value) {
              if (value.isNotEmpty && index < 5) {
                focusNodes[index + 1].requestFocus();
              } else if (value.isEmpty && index > 0) {
                focusNodes[index - 1].requestFocus();
              }
            },
          ),
        );
      }),
    );
  }
}
