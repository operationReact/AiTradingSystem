import 'package:flutter/material.dart';

import '../data/customer_angel_one_repository.dart';
import 'minttick_customer_widgets.dart';

/// Customer-owned, interactive, READ-ONLY broker connection. Never renders
/// provider access tokens, PIN, TOTP or API key after submission.
class MintTickCustomerBrokerPage extends StatefulWidget {
  const MintTickCustomerBrokerPage({required this.repository, super.key});
  final HttpCustomerAngelOneRepository repository;

  @override
  State<MintTickCustomerBrokerPage> createState() => _MintTickCustomerBrokerPageState();
}

class _MintTickCustomerBrokerPageState extends State<MintTickCustomerBrokerPage> {
  final _form = GlobalKey<FormState>();
  final _clientCode = TextEditingController();
  final _apiKey = TextEditingController();
  final _pin = TextEditingController();
  final _totp = TextEditingController();
  CustomerAngelOneConnection? _connection;
  bool _busy = true;
  bool _showForm = false;
  String? _error;
  Object? _data;
  String? _dataLabel;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _apiKey.dispose();
    _clientCode.dispose();
    _pin.dispose();
    _totp.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (!mounted) return;
    setState(() { _busy = true; _error = null; });
    try {
      final result = await widget.repository.status();
      if (!mounted) return;
      setState(() { _connection = result; _data = null; _dataLabel = null; });
    } catch (_) {
      if (mounted) {
        setState(() {
          _connection = null;
          _error = 'Broker status could not be verified. Please retry.';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _connect() async {
    if (_busy || !(_form.currentState?.validate() ?? false)) return;
    setState(() { _busy = true; _error = null; });
    try {
      final connection = await widget.repository.connect(
        apiKey: _apiKey.text.trim(),
        clientCode: _clientCode.text.trim(),
        pin: _pin.text,
        totp: _totp.text.trim(),
      );
      if (!mounted) return;
      setState(() {
        _connection = connection;
        _showForm = false;
        _data = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = 'Angel One did not confirm the connection. Verify your '
              'details, current TOTP and broker API access, then retry.';
        });
      }
    } finally {
      // One-time authentication secrets must not remain in editable controls.
      _pin.clear();
      _totp.clear();
      _apiKey.clear();
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _disconnect() async {
    if (_busy) return;
    setState(() { _busy = true; _error = null; });
    try {
      await widget.repository.disconnect();
      if (mounted) setState(() { _data = null; _connection = null; _showForm = false; });
      await _refresh();
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not confirm disconnection. Try again.');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _load(String label, Future<Object?> Function() request) async {
    if (_busy) return;
    setState(() { _busy = true; _error = null; _data = null; _dataLabel = label; });
    try {
      final data = await request();
      if (mounted) setState(() => _data = data);
    } catch (_) {
      if (mounted) {
        setState(() => _error =
            'Broker $label could not be loaded. The session may have expired '
            'or the provider may be rate limiting requests.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final connection = _connection;
    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 19),
      const MintTickEyebrow('Connected brokers'),
      const SizedBox(height: 7),
      Text('Angel One', style: theme.textTheme.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 7),
      Text('Connect your own account to view actual broker data.',
        style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
      const SizedBox(height: 17),
      MintTickSurface(
        accent: connection?.connected == true,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const MintTickIconTile(Icons.account_balance_outlined),
            const SizedBox(width: 11),
            Expanded(child: Text('Angel One SmartAPI',
              style: theme.textTheme.titleSmall
                  ?.copyWith(fontWeight: FontWeight.w800))),
            MintTickStatusLabel(connection?.connected == true ? 'CONNECTED' : 'NOT CONNECTED'),
          ]),
          const SizedBox(height: 11),
          if (connection?.connected == true) ...[
            Text('Account: ${connection?.clientCodeMasked ?? 'Verified'}',
              style: theme.textTheme.bodyMedium),
            const SizedBox(height: 4),
            Text('Read-only connection · Live order placement not yet available',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: scheme.onSurfaceVariant)),
            if (connection?.expiresAt != null) ...[
              const SizedBox(height: 5),
              Text('Re-authentication required by the session expiry shown below:',
                style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
              Text(connection!.expiresAt!.toLocal().toString().split('.')[0],
                style: theme.textTheme.bodySmall),
            ],
          ] else
            Text('No broker account connected. A MintTick sign-in is '
                'not a broker login.',
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: scheme.onSurfaceVariant)),
        ]),
      ),
      if (_busy) ...[
        const SizedBox(height: 14),
        const Center(child: CircularProgressIndicator()),
      ],
      if (_error != null) ...[
        const SizedBox(height: 14),
        MintTickInfoText(_error!, icon: Icons.error_outline_rounded),
      ],
      const SizedBox(height: 17),
      if (connection?.connected == true) ...[
        const MintTickSectionHeading('Live broker information'),
        const SizedBox(height: 9),
        Wrap(spacing: 8, runSpacing: 8, children: [
          OutlinedButton(onPressed: _busy ? null :
              () => _load('funds', widget.repository.funds),
            child: const Text('Funds')),
          OutlinedButton(onPressed: _busy ? null :
              () => _load('holdings', widget.repository.holdings),
            child: const Text('Holdings')),
          OutlinedButton(onPressed: _busy ? null :
              () => _load('positions', widget.repository.positions),
            child: const Text('Positions')),
          OutlinedButton(onPressed: _busy ? null :
              () => _load('orders', widget.repository.orders),
            child: const Text('Orders')),
        ]),
        if (_dataLabel != null) ...[
          const SizedBox(height: 13),
          MintTickSurface(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('${_dataLabel![0].toUpperCase()}${_dataLabel!.substring(1)}',
                style: theme.textTheme.titleSmall
                    ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 7),
              ..._dataSummary(theme),
            ],
          )),
        ],
        const SizedBox(height: 14),
        OutlinedButton.icon(
          key: const ValueKey('broker-disconnect'),
          onPressed: _busy ? null : _disconnect,
          icon: const Icon(Icons.link_off_rounded),
          label: const Text('Disconnect Angel One'),
        ),
      ] else ...[
        SizedBox(width: double.infinity, child: FilledButton.icon(
          key: const ValueKey('broker-show-connect'),
          onPressed: _busy ? null : () => setState(() => _showForm = !_showForm),
          icon: const Icon(Icons.link_rounded),
          label: const Text('Connect Angel One'),
        )),
        if (_showForm) ...[
          const SizedBox(height: 15),
          MintTickSurface(child: Form(
            key: _form,
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text('Secure connection',
                style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 7),
              Text('Use your own Angel One SmartAPI key, client code, '
                  'PIN and a current authenticator code. No TOTP seed is requested.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant, height: 1.4)),
              const SizedBox(height: 14),
              TextFormField(
                key: const ValueKey('broker-client-code'),
                controller: _clientCode,
                decoration: const InputDecoration(labelText: 'Angel One client code'),
                textCapitalization: TextCapitalization.characters,
                validator: (v) => RegExp(r'^[A-Za-z0-9]{4,20}$')
                        .hasMatch(v?.trim() ?? '') ? null : 'Enter a valid client code',
              ),
              const SizedBox(height: 11),
              TextFormField(
                key: const ValueKey('broker-api-key'),
                controller: _apiKey,
                obscureText: true,
                enableSuggestions: false,
                autocorrect: false,
                decoration: const InputDecoration(labelText: 'Your SmartAPI key'),
                validator: (v) => (v?.trim().isNotEmpty ?? false)
                    ? null : 'Your API key is required',
              ),
              const SizedBox(height: 11),
              TextFormField(
                key: const ValueKey('broker-pin'),
                controller: _pin,
                obscureText: true,
                enableSuggestions: false,
                autocorrect: false,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Angel One PIN'),
                validator: (v) => RegExp(r'^\d{4,8}$')
                        .hasMatch(v ?? '') ? null : 'Enter your broker PIN',
              ),
              const SizedBox(height: 11),
              TextFormField(
                key: const ValueKey('broker-totp'),
                controller: _totp,
                keyboardType: TextInputType.number,
                obscureText: true,
                enableSuggestions: false,
                autocorrect: false,
                decoration: const InputDecoration(labelText: 'Current 6-digit TOTP'),
                validator: (v) => RegExp(r'^\d{6}$')
                        .hasMatch(v?.trim() ?? '') ? null : 'Enter the current code',
              ),
              const SizedBox(height: 13),
              FilledButton(
                key: const ValueKey('broker-connect-submit'),
                onPressed: _busy ? null : _connect,
                child: const Text('Verify & connect'),
              ),
            ]),
          )),
        ],
      ],
      const SizedBox(height: 18),
      const MintTickInfoText(
        'Paper and Live are separate Trading Room modes. This '
        'connection permits broker account reads only. Live orders require '
        'broker-compliant IP setup, explicit execution consent, portfolio '
        'reconciliation and enforced risk limits.',
        icon: Icons.shield_outlined,
      ),
      const SizedBox(height: 14),
      OutlinedButton.icon(
        onPressed: _busy ? null : _refresh,
        icon: const Icon(Icons.refresh_rounded),
        label: const Text('Refresh connection status'),
      ),
    ]);
  }

  List<Widget> _dataSummary(ThemeData theme) {
    final data = _data;
    if (data == null) return [const Text('No broker data loaded yet.')];
    if (data is List) {
      return [Text('${data.length} provider records received.',
        style: theme.textTheme.bodyMedium)];
    }
    if (data is Map) {
      // Only show a bounded list of approved, non-secret financial summary
      // labels. Do not dump the entire raw SmartAPI JSON response in the UI.
      const allowed = {
        'availablecash', 'availableCash', 'net', 'utiliseddebits',
        'collateral', 'cash', 'holdingvalue', 'totalholdingvalue',
        'availableintradaypayin', 'availablelimitmargin',
      };
      final items = data.entries.where((e) =>
          allowed.contains('${e.key}') &&
          (e.value is num || e.value is String)).take(6).toList();
      if (items.isEmpty) {
        return [const Text('The provider returned data, '
            'but no verified summary fields are available in this view.')];
      }
      return items.map((entry) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(children: [
          Expanded(child: Text('${entry.key}',
            style: theme.textTheme.bodySmall)),
          Flexible(child: Text('${entry.value}',
            textAlign: TextAlign.end,
            style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w800))),
        ]),
      )).toList();
    }
    return [const Text('Broker data is unavailable in this format.')];
  }
}
