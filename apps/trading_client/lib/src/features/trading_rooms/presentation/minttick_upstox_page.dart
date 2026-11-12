import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../data/authenticated_api_client.dart';

import '../data/customer_upstox_repository.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_upstox_symbol.dart';

/// Broker-hosted OAuth login: Upstox handles credentials, not MintTick.
class MintTickUpstoxPage extends StatefulWidget {
  const MintTickUpstoxPage({
    required this.repository,
    this.launchAuthentication,
    super.key,
  });
  final HttpCustomerUpstoxRepository repository;
  final Future<bool> Function(Uri url)? launchAuthentication;

  @override
  State<MintTickUpstoxPage> createState() => _MintTickUpstoxPageState();
}

class _MintTickUpstoxPageState extends State<MintTickUpstoxPage>
    with WidgetsBindingObserver {
  CustomerUpstoxStatus? _status;
  bool _loading = false;
  bool _awaitingReturn = false;
  bool _ownerLoginAvailable = false;
  String? _error;
  String? _section;
  Object? _data;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // On returning from the official broker-hosted OAuth page, re-read the
    // one-time server-side result. Never start a new OAuth flow silently.
    if (state == AppLifecycleState.resumed && _awaitingReturn) {
      _refresh();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // Only fixed server-defined codes are rendered. Never show a provider URL,
  // one-time authorization code, state, raw upstream error or broker token.
  String? _explainOAuthFailure(String? code) => switch (code) {
    'UPSTOX_AUTH_DECLINED' =>
      'Upstox login was cancelled or declined. Start a fresh connection.',
    'UPSTOX_AUTH_CODE_INVALID' =>
      'The Upstox authorization code expired or was already used. '
      'Start a fresh login and finish it without reopening an old browser tab.',
    'UPSTOX_OAUTH_APP_CREDENTIALS_INVALID' =>
      'Upstox rejected the developer API key or API secret during token exchange. '
      'Verify the protected server credentials belong to the same Upstox app.',
    'UPSTOX_OAUTH_REDIRECT_REJECTED' =>
      'Upstox rejected the callback URL during token exchange. '
      'Verify the registered redirect URI is an exact match.',
    'UPSTOX_ACCOUNT_SEGMENTS_INACTIVE' =>
      'Upstox reports no active trading segments for this account. '
      'Reactivate them with Upstox before connecting.',
    'UPSTOX_PROFILE_REJECTED' =>
      'Upstox did not verify an active account profile. '
      'Check your account status with Upstox.',
    'UPSTOX_ACCOUNT_ALREADY_CONNECTED' =>
      'This Upstox account is already connected to another MintTick user.',
    'UPSTOX_AUTH_TRANSPORT_UNAVAILABLE' =>
      'MintTick could not complete the Upstox token exchange due to a '
      'connection or timeout issue. Check backend connectivity.',
    'UPSTOX_AUTH_INVALID_RESPONSE' =>
      'The Upstox token response could not be decoded safely. '
      'Check MintTick backend integration.',
    'UPSTOX_AUTH_CLIENT_FAILURE' =>
      'MintTick encountered an unexpected token-exchange client failure. '
      'This does not establish an Upstox outage.',
    'UPSTOX_AUTH_UNAVAILABLE' =>
      'MintTick could not reach Upstox to finish authorization. '
      'Start a fresh connection when the service is available.',
    'UPSTOX_AUTH_FAILED' =>
      'Upstox rejected the authorization exchange. '
      'Start a fresh login; check the developer app credentials if it repeats.',
    _ => null,
  };

  Future<void> _refresh() async {
    if (_loading) return;
    setState(() { _loading = true; _error = null; });
    try {
      final status = await widget.repository.status();
      bool ownerLoginAvailable = false;
      if (status.available && !status.connected) {
        try {
          // This endpoint returns true only for the exact Cognito-bound owner.
          // Never fetch broker PIN, TOTP seed or any developer credentials.
          ownerLoginAvailable =
              await widget.repository.configuredLoginAvailable();
        } catch (_) {
          // Older backend, unavailable role or transport: retain official OAuth.
        }
      }
      if (!mounted) return;
      setState(() {
        _ownerLoginAvailable = ownerLoginAvailable;
        _status = status;
        _error = status.connected ? null : _explainOAuthFailure(status.lastAuthError);
        _awaitingReturn = false;
        _data = null;
        _section = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          // Fail closed on refresh errors: a cached CONNECTED label or
          // owner-login capability is not evidence of current broker state.
          _status = null;
          _ownerLoginAvailable = false;
          _section = null;
          _data = null;
          _error =
              'Unable to verify Upstox connection. Check your network and retry.';
        });
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  String _explainOwnerLoginFailure(Object error) {
    if (error is ApiHttpFailure) {
      return switch (error.code) {
        'UPSTOX_OWNER_LOGIN_COOLDOWN' =>
          'Please refresh your Upstox connection status before trying again. '
          'The broker login cooldown is active.',
        'UPSTOX_OWNER_SSM_INVALID' || 'UPSTOX_OWNER_SSM_UNAVAILABLE' =>
          'Saved Upstox login configuration could not be verified on the server. '
          'Check the protected AWS settings; no credentials are needed here.',
        'UPSTOX_TOTP_APP_NOT_ELIGIBLE' =>
          'This Upstox developer app is not eligible for PIN + TOTP login. '
          'Use an eligible individual app or official Upstox login.',
        'UPSTOX_TOTP_APP_KEY_INVALID' =>
          'The configured Upstox developer API key was rejected. '
          'Check the app key in secure AWS settings against Upstox Developer Apps.',
        'UPSTOX_TOTP_CREDENTIALS_INVALID' =>
          'Upstox rejected the saved PIN or TOTP. Verify the account PIN '
          'and authenticator setup in the protected server configuration.',
        'UPSTOX_TOTP_NOT_ENABLED' =>
          'TOTP is not enabled for this Upstox account. '
          'Enable it in Upstox before using saved login.',
        'UPSTOX_TOTP_RATE_LIMITED' =>
          'Upstox is rate limiting saved login. Do not retry immediately; '
          'refresh your status before another attempt.',
        'UPSTOX_TOTP_AUTH_REJECTED' ||
        'UPSTOX_TOTP_CONFIGURATION_INVALID' =>
          'Upstox rejected the saved login. Confirm the developer app supports '
          'individual-app PIN + TOTP, and check your TOTP setup. '
          'Official Upstox login remains available.',
        _ => 'Automatic Upstox connection was not verified. Refresh the '
             'connection before trying again or use official Upstox login.',
      };
    }
    return 'Automatic Upstox connection was not verified. Refresh the '
        'connection before retrying; the request may have completed.';
  }

  Future<void> _connectOwner() async {
    if (_loading || !_ownerLoginAvailable ||
        _status?.connected == true || _status?.available != true) {
      return;
    }
    setState(() { _loading = true; _error = null; });
    try {
      // Owner authorization is checked again by the backend; never send a
      // credential or a user-selected account in this request.
      final status = await widget.repository.connectConfigured();
      if (!mounted) return;
      if (!status.available || !status.connected) {
        throw const FormatException('No verified Upstox account');
      }
      setState(() {
        _status = status;
        _ownerLoginAvailable = false;
        _section = null;
        _data = null;
        _awaitingReturn = false;
      });
    } catch (error) {
      if (mounted) {
        setState(() => _error = _explainOwnerLoginFailure(error));
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _start() async {
    if (_loading) return;
    setState(() { _loading = true; _error = null; });
    try {
      final loginUrl = await widget.repository.startAuthorization();
      final opened = await (widget.launchAuthentication?.call(loginUrl) ??
          launchUrl(loginUrl, mode: LaunchMode.externalApplication));
      if (!opened) throw StateError('Browser unavailable');
      if (mounted) {
        setState(() => _awaitingReturn = true);
      }
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Could not open Upstox authorization. '
            'Confirm the MintTick Upstox developer app is configured.');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _disconnect() async {
    if (_loading) return;
    setState(() { _loading = true; _error = null; });
    try {
      final status = await widget.repository.disconnect();
      if (mounted) {
        setState(() {
          _status = status;
          _section = null;
          _data = null;
          _awaitingReturn = false;
          _ownerLoginAvailable = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not verify disconnection.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _load(String section, Future<Object?> Function() fetch) async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _error = null;
      _data = null;
      _section = section;
    });
    try {
      final result = await fetch();
      if (mounted) setState(() => _data = result);
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Upstox $section could not be loaded. '
            'Re-authentication may be required.');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final connected = _status?.connected == true;
    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 18),
      const MintTickEyebrow('Connected brokers · Live'),
      const SizedBox(height: 8),
      Row(children: [
        const UpstoxSymbol(size: 46),
        const SizedBox(width: 12),
        Expanded(child: Text('Upstox', style: text.headlineSmall
            ?.copyWith(fontWeight: FontWeight.w800))),
      ]),
      const SizedBox(height: 7),
      Text('Secure login through Upstox. No brokerage PIN, password or OTP '
          'is entered into MintTick.',
        style: text.bodyMedium?.copyWith(
          color: colors.onSurfaceVariant, height: 1.5)),
      const SizedBox(height: 18),
      MintTickSurface(accent: connected, child: Column(
        crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const UpstoxSymbol(size: 36),
            const SizedBox(width: 11),
            Expanded(child: Text('Upstox Account',
              style: text.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
            MintTickStatusLabel(connected ? 'CONNECTED' : 'NOT CONNECTED'),
          ]),
          const SizedBox(height: 11),
          if (connected) ...[
            Text('Verified broker account: ${_status?.accountMasked ?? 'connected'}'),
            const SizedBox(height: 5),
            Text('Read-only link · Real-money order execution disabled',
              style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
            if (_status?.expiresAt != null) ...[
              const SizedBox(height: 6),
              Text('Session expires ${_status!.expiresAt!.toLocal()}',
                style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
            ],
          ] else
            Text(_status?.available == false
                ? 'MintTick needs its Upstox developer app configured '
                  'before you can connect.'
                : 'Sign in on the official Upstox page to authorize '
                  'access to this brokerage account.',
              style: text.bodyMedium?.copyWith(color: colors.onSurfaceVariant)),
        ],
      )),
      if (_loading) ...[
        const SizedBox(height: 15),
        const LinearProgressIndicator(),
      ],
      if (_error != null) ...[
        const SizedBox(height: 12),
        MintTickInfoText(_error!, icon: Icons.error_outline_rounded),
      ],
      const SizedBox(height: 16),
      if (!connected) ...[
        if (_ownerLoginAvailable) ...[
          SizedBox(width: double.infinity, child: FilledButton.icon(
            key: const ValueKey('upstox-connect-saved-owner'),
            onPressed: _loading ? null : _connectOwner,
            icon: const Icon(Icons.verified_user_outlined),
            label: const Text('Connect using saved Upstox login'),
          )),
          const SizedBox(height: 9),
          Text('Only your verified MintTick owner account can use the '
              'server-protected PIN + TOTP login. No codes are entered here.',
            style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
          const SizedBox(height: 12),
        ],
        SizedBox(width: double.infinity, child: FilledButton.icon(
          key: const ValueKey('upstox-start-oauth'),
          onPressed: _loading || _status?.available != true ? null : _start,
          icon: const Icon(Icons.open_in_new_rounded),
          label: Text(_ownerLoginAvailable
              ? 'Use official Upstox login instead'
              : 'Connect with Upstox'),
        )),
        if (_awaitingReturn) ...[
          const SizedBox(height: 14),
          const MintTickInfoText(
            'Complete authentication on Upstox and return to MintTick. '
            'The connection refreshes when you return. '
            'Your credentials stay on Upstox.',
            icon: Icons.verified_user_outlined,
          ),
        ],
      ] else ...[
        const MintTickSectionHeading('Live broker information'),
        const SizedBox(height: 9),
        Wrap(spacing: 8, runSpacing: 8, children: [
          OutlinedButton(onPressed: _loading ? null
              : () => _load('funds', widget.repository.funds),
            child: const Text('Funds')),
          OutlinedButton(onPressed: _loading ? null
              : () => _load('holdings', widget.repository.holdings),
            child: const Text('Holdings')),
          OutlinedButton(onPressed: _loading ? null
              : () => _load('positions', widget.repository.positions),
            child: const Text('Positions')),
          OutlinedButton(onPressed: _loading ? null
              : () => _load('orders', widget.repository.orders),
            child: const Text('Orders')),
          OutlinedButton(onPressed: _loading ? null
              : () => _load('IP status', widget.repository.registeredIps),
            child: const Text('IP status')),
        ]),
        if (_section != null) ...[
          const SizedBox(height: 12),
          MintTickSurface(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${_section![0].toUpperCase()}${_section!.substring(1)}',
                style: text.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 9),
              ..._brokerSummary(text, colors),
            ],
          )),
        ],
        const SizedBox(height: 11),
        OutlinedButton.icon(
          key: const ValueKey('upstox-disconnect'),
          onPressed: _loading ? null : _disconnect,
          icon: const Icon(Icons.link_off_rounded),
          label: const Text('Disconnect Upstox'),
        ),
      ],
      const SizedBox(height: 13),
      OutlinedButton.icon(
        key: const ValueKey('upstox-refresh-status'),
        onPressed: _loading ? null : _refresh,
        icon: const Icon(Icons.refresh_rounded),
        label: const Text('Refresh connection'),
      ),
      const SizedBox(height: 16),
      const MintTickInfoText(
        'Paper and Live portfolios are separate. Connecting a broker '
        'enables account reads, not automatic orders. Live trading '
        'requires additional consent, broker eligibility, registered '
        'IP verification and server-enforced risk controls.',
        icon: Icons.shield_outlined,
      ),
    ]);
  }

  List<Widget> _brokerSummary(TextTheme text, ColorScheme scheme) {
    final value = _data;
    if (value == null) {
      return [const Text('No verified broker data loaded.')];
    }
    if (value is List) {
      return [
        Text('${value.length} records returned by Upstox',
          style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 7),
        if (value.isEmpty) const Text('No records.'),
        for (final row in value.take(15))
          if (row is Map)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: Text(
                '${row['trading_symbol'] ?? 'Broker record'}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Text('Qty: ${row['quantity'] ?? '—'}  '
                  'Status: ${row['status'] ?? '—'}'),
            ),
        if (value.length > 15)
          const Text('Showing first 15 broker records'),
      ];
    }
    if (value is Map) {
      if (_section == 'IP status') {
        final primary = value['primary_ip'];
        return [Text(primary is String && primary.isNotEmpty
            ? 'Primary static IP registered'
            : 'No primary static IP reported by broker')];
      }
      final equity = value['equity'];
      final data = equity is Map ? equity : value;
      const fields = [
        ('available_margin', 'Available margin'),
        ('used_margin', 'Margin used'),
        ('payin_amount', 'Pay-in'),
        ('notional_cash', 'Notional cash'),
      ];
      final available = fields.where((field) => data[field.$1] is num).toList();
      if (available.isEmpty) {
        return [const Text('The broker returned no supported summary fields.')];
      }
      return [
        for (final (field, name) in available)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 5),
            child: Row(children: [
              Expanded(child: Text(name, style: text.bodySmall)),
              Text('₹${data[field]}',
                style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
            ]),
          ),
      ];
    }
    return [const Text('No supported broker details to show.')];
  }
}
