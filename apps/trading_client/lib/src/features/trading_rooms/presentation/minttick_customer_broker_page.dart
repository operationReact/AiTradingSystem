import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/customer_angel_one_repository.dart';
import 'minttick_angel_one_manual_login.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_angel_one_dashboard.dart';

/// Customer-owned, interactive, READ-ONLY broker connection. Never renders
/// provider access tokens, PIN, TOTP or API key after submission.
class MintTickCustomerBrokerPage extends StatefulWidget {
  const MintTickCustomerBrokerPage({required this.repository, this.onBack, super.key});
  final HttpCustomerAngelOneRepository repository;
  final VoidCallback? onBack;

  @override
  State<MintTickCustomerBrokerPage> createState() => _MintTickCustomerBrokerPageState();
}

class _MintTickCustomerBrokerPageState extends State<MintTickCustomerBrokerPage> {
  CustomerAngelOneConnection? _connection;
  bool _busy = true;
  bool _configuredLoginAvailable = false;
  bool _statusVerified = false;
  bool _availabilityCheckFailed = false;
  // One transparent read-only login attempt per broker page visit.
  // Manual four-field entry is never required for a configured owner.
  bool _autoConfiguredAttempted = false;
  String? _ownerReference;
  String? _error;
  Object? _data;
  String? _dataLabel;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    if (!mounted) return;
    setState(() { _busy = true; _error = null; });
    try {
      var result = await widget.repository.status();
      // Capability is derived from verified server-side identity, never cached.
      // A network error is NOT a negative owner authorization response.
      bool configured = false;
      bool lookupFailed = false;
      try {
        configured = await widget.repository.configuredLoginAvailable();
      } catch (_) {
        lookupFailed = true;
      }
      String? autoConnectError;
      if (configured && !result.connected && !_autoConfiguredAttempted) {
        // The backend alone verifies the exact Cognito owner and reads SSM.
        // No credential or TOTP ever crosses the Flutter boundary.
        _autoConfiguredAttempted = true;
        try {
          result = await widget.repository.connectConfigured();
        } catch (_) {
          autoConnectError = 'Saved Angel One login could not be completed. '
              'Check the protected owner binding and tap Secure connect to retry.';
        }
      }
      String? linkReference;
      if (!configured && !lookupFailed) {
        try {
          linkReference = await widget.repository.ownerLinkReference();
        } catch (_) {
          // Fallback manual login still works for every other customer.
        }
      }
      if (!mounted) return;
      setState(() {
        _connection = result;
        _configuredLoginAvailable = configured;
        _statusVerified = true;
        _availabilityCheckFailed = lookupFailed;
        _ownerReference = linkReference;
        _error = lookupFailed
            ? 'Saved Angel One connection status could not be verified. '
                'Retry without re-entering any broker credentials.'
            : autoConnectError;
        _data = null;
        _dataLabel = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _connection = null;
          _configuredLoginAvailable = false;
          _statusVerified = false;
          _availabilityCheckFailed = true;
          _ownerReference = null;
          _error = 'Broker status could not be verified. Please retry.';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _connectConfigured() async {
    if (_busy || !_configuredLoginAvailable) return;
    setState(() { _busy = true; _error = null; });
    try {
      final connection = await widget.repository.connectConfigured();
      if (!mounted) return;
      setState(() {
        _connection = connection;
        _data = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Angel One did not complete authentication. '
            'Check the account configuration or try again.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _connectManual() async {
    if (_busy) return;
    final input = await showMintTickAngelOneLogin(context);
    if (!mounted || input == null) return;
    setState(() { _busy = true; _error = null; });
    try {
      final connection = await widget.repository.connect(
        apiKey: input.apiKey,
        clientCode: input.clientCode,
        pin: input.pin,
        totp: input.totp,
      );
      if (mounted) setState(() { _connection = connection; _data = null; });
    } catch (_) {
      if (mounted) {
        setState(() => _error =
          'Angel One could not verify this login. Check SmartAPI access, '
          'the current six-digit authenticator code, and your broker account.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _disconnect() async {
    if (_busy) return;
    setState(() { _busy = true; _error = null; });
    try {
      _autoConfiguredAttempted = true; // Explicit disconnect must not auto-reconnect.
      await widget.repository.disconnect();
      if (mounted) setState(() { _data = null; _connection = null; });
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
    if (connection?.connected == true) {
      return MintTickAngelOneDashboard(
        repository: widget.repository,
        connection: connection!,
        busy: _busy,
        error: _error,
        onRefresh: _refresh,
        onDisconnect: _disconnect,
        onBack: widget.onBack,
      );
    }
    return PopScope(
      canPop: widget.onBack == null,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) widget.onBack?.call();
      },
      child: MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 19),
      if (widget.onBack != null)
        Align(alignment: Alignment.centerLeft, child: IconButton(
          key: const ValueKey('broker-back-to-hub'),
          tooltip: 'Back to connected brokers',
          icon: const Icon(Icons.arrow_back_rounded),
          onPressed: widget.onBack,
        )),
      const MintTickEyebrow('Connected brokers'),
      const SizedBox(height: 7),
      Text('Angel One', style: theme.textTheme.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 7),
      Text('Connect your own account to view actual broker data.',
        style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
      const SizedBox(height: 17),
      MintTickSurface(
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
      ] else if (_configuredLoginAvailable) ...[
        SizedBox(width: double.infinity, child: FilledButton.icon(
          key: const ValueKey('broker-connect-configured'),
          onPressed: _busy ? null : _connectConfigured,
          icon: const Icon(Icons.lock_outline_rounded),
          label: const Text('Secure connect'),
        )),
        const SizedBox(height: 12),
        const MintTickInfoText(
          'Your verified MintTick account can connect with credentials '
          'held by the server. Your PIN, API key and TOTP seed never '
          'appear in this app.',
          icon: Icons.verified_user_outlined,
        ),
      ] else if (_statusVerified && !_availabilityCheckFailed) ...[
        const MintTickInfoText(
          'If your Angel One credentials are already saved in AWS SSM, '
          'do not enter them again. Secure connect requires the exact '
          'signed-in Cognito owner to be linked in protected AWS settings. '
          'This account is not authorized for saved login yet.',
          icon: Icons.lock_outline_rounded,
        ),
        if (_ownerReference case final subject?) ...[
          const SizedBox(height: 12),
          MintTickSurface(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Your secure owner-link reference',
                style: theme.textTheme.labelMedium
                    ?.copyWith(fontWeight: FontWeight.w700)),
              const SizedBox(height: 7),
              SelectableText(subject, style: theme.textTheme.bodySmall),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                key: const ValueKey('broker-copy-owner-reference'),
                onPressed: () => Clipboard.setData(ClipboardData(text: subject)),
                icon: const Icon(Icons.copy_rounded, size: 17),
                label: const Text('Copy reference for AWS setup'),
              ),
              const SizedBox(height: 6),
              Text('This is not an API key, PIN, TOTP seed or broker token.',
                style: theme.textTheme.bodySmall?.copyWith(
                    color: scheme.onSurfaceVariant)),
            ],
          )),
        ],
        const SizedBox(height: 12),
        SizedBox(width: double.infinity, child: OutlinedButton.icon(
          key: const ValueKey('broker-check-secure-connection'),
          onPressed: _busy ? null : _refresh,
          icon: const Icon(Icons.refresh_rounded),
          label: const Text('Check secure connection'),
        )),
      ],
      // A failed availability check must never silently fall back to a
      // manual credential form, because the server might already hold SSM.
      if (connection?.connected != true && _statusVerified &&
          !_availabilityCheckFailed && !_configuredLoginAvailable) ...[
        const SizedBox(height: 12),
        ExpansionTile(
          key: const ValueKey('broker-alternate-account-expander'),
          initiallyExpanded: false,
          tilePadding: EdgeInsets.zero,
          title: const Text('Connect a different Angel One account'),
          subtitle: const Text(
            'Only for a separate SmartAPI account not saved in AWS SSM.'),
          children: [
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                key: const ValueKey('broker-connect-manual'),
                onPressed: _busy ? null : _connectManual,
                icon: const Icon(Icons.account_circle_outlined),
                label: const Text('Enter separate account credentials'),
              ),
            ),
            const SizedBox(height: 8),
            const MintTickInfoText(
              'Manual login requires a separate account API key, client code, '
              'PIN and current six-digit TOTP. Never enter the enrollment seed.',
              icon: Icons.privacy_tip_outlined,
            ),
          ],
        ),
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
      ]),
    );
  }

  List<Widget> _dataSummary(ThemeData theme) {
    final data = _data;
    if (data == null) return [const Text('No broker data loaded yet.')];
    if (data is List) {
      return _brokerRecords(data, theme);
    }
    if (data is Map) {
      final nested = data['holdings'] ?? data['positions'] ??
          data['orders'] ?? data['orderBook'] ?? data['data'];
      if (nested is List) {
        return _brokerRecords(nested, theme);
      }
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

  List<Widget> _brokerRecords(List<dynamic> records, ThemeData theme) {
    final ink = theme.colorScheme.onSurfaceVariant;
    return [
      Text('${records.length} records returned by Angel One',
        style: theme.textTheme.bodySmall?.copyWith(color: ink)),
      const SizedBox(height: 7),
      if (records.isEmpty) const Text('No open records for this account.'),
      for (final entry in records.take(20))
        if (entry is Map)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(
                '${entry['tradingsymbol'] ?? entry['tradingSymbol'] ?? entry['symbol'] ?? 'Broker record'}',
                style: theme.textTheme.bodyMedium
                    ?.copyWith(fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 4),
              Wrap(spacing: 12, runSpacing: 5, children: [
                for (final (field, label) in [
                  ('quantity', 'Quantity'),
                  ('netqty', 'Net quantity'),
                  ('ltp', 'Last price'),
                  ('pnl', 'P&L'),
                  ('status', 'Status'),
                ])
                  if (entry[field] != null)
                    Text('$label: ${entry[field]}',
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: ink)),
              ]),
              const Divider(height: 13),
            ]),
          ),
      if (records.length > 20)
        Text('Showing first 20 records. More rows require pagination.',
          style: theme.textTheme.bodySmall?.copyWith(color: ink)),
    ];
  }
}
