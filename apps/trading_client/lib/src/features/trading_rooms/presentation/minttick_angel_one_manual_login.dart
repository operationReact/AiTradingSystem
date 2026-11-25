import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Inputs are held only until the one-time API request. Never persist or log.
class MintTickAngelOneLoginDetails {
  const MintTickAngelOneLoginDetails(this.apiKey, this.clientCode, this.pin, this.totp);
  final String apiKey;
  final String clientCode;
  final String pin;
  final String totp;
}

/// Manual SmartAPI login is available to each authenticated customer.
/// The long authenticator enrollment seed is deliberately not accepted.
Future<MintTickAngelOneLoginDetails?> showMintTickAngelOneLogin(
    BuildContext context) {
  return showModalBottomSheet<MintTickAngelOneLoginDetails>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    showDragHandle: true,
    builder: (_) => const _LoginSheet(),
  );
}

class _LoginSheet extends StatefulWidget {
  const _LoginSheet();
  @override
  State<_LoginSheet> createState() => _LoginSheetState();
}

class _LoginSheetState extends State<_LoginSheet> {
  final _form = GlobalKey<FormState>();
  final _key = TextEditingController();
  final _client = TextEditingController();
  final _pin = TextEditingController();
  final _totp = TextEditingController();

  @override
  void dispose() {
    for (final input in [_key, _client, _pin, _totp]) {
      input.clear();
      input.dispose();
    }
    super.dispose();
  }

  Widget _field({
    required String label,
    required TextEditingController controller,
    required String? Function(String?) validator,
    bool private = false,
    bool numeric = false,
    int? limit,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 13),
      child: TextFormField(
        controller: controller,
        obscureText: private,
        autocorrect: false,
        enableSuggestions: false,
        textCapitalization: TextCapitalization.none,
        keyboardType: numeric ? TextInputType.number : TextInputType.text,
        inputFormatters: [
          if (numeric) FilteringTextInputFormatter.digitsOnly,
          if (limit != null) LengthLimitingTextInputFormatter(limit),
        ],
        validator: validator,
        decoration: InputDecoration(labelText: label),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        20, 0, 20, MediaQuery.viewInsetsOf(context).bottom + 24),
      child: SingleChildScrollView(
        child: Form(
          key: _form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Connect your Angel One account',
                style: text.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
              const SizedBox(height: 7),
              const Text('Use your own SmartAPI app credentials. Only the current six-digit authenticator code is required; never enter the long enrollment seed.'),
              const SizedBox(height: 20),
              _field(label: 'SmartAPI key', controller: _key, private: true,
                validator: (v) => (v == null || v.trim().isEmpty || v.length > 256)
                    ? 'Enter your API key' : null),
              _field(label: 'Client code', controller: _client,
                validator: (v) => RegExp(r'^[A-Za-z0-9]{4,20}$').hasMatch(v ?? '')
                    ? null : 'Enter a valid client code'),
              _field(label: 'Angel One PIN', controller: _pin,
                private: true, numeric: true, limit: 8,
                validator: (v) => RegExp(r'^[0-9]{4,8}$').hasMatch(v ?? '')
                    ? null : 'Use your 4–8 digit PIN'),
              _field(label: 'Current 6-digit TOTP', controller: _totp,
                private: true, numeric: true, limit: 6,
                validator: (v) => RegExp(r'^[0-9]{6}$').hasMatch(v ?? '')
                    ? null : 'Enter the current 6-digit code'),
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('angel-one-manual-submit'),
                  onPressed: () {
                    if (!(_form.currentState?.validate() ?? false)) return;
                    Navigator.of(context).pop(MintTickAngelOneLoginDetails(
                      _key.text.trim(), _client.text.trim(),
                      _pin.text, _totp.text));
                  },
                  child: const Text('Verify & connect'),
                ),
              ),
              const SizedBox(height: 9),
              Text('Credentials are transmitted to the authenticated API and are not saved on this device.',
                style: text.bodySmall),
            ],
          ),
        ),
      ),
    );
  }
}
