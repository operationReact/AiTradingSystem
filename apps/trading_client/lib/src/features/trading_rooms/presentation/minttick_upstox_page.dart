import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../data/authenticated_api_client.dart';

import '../data/customer_upstox_repository.dart';
import '../data/customer_portfolio_models.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_broker_compact_widgets.dart';
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
  final Map<String, Object?> _brokerData = {};
  final Map<String, String> _readErrors = {};
  final Set<String> _reading = {};
  int _readEpoch = 0;

  void _resetBrokerReads({required bool connected}) {
    ++_readEpoch;
    _brokerData.clear();
    _readErrors.clear();
    _reading.clear();
    _section = connected ? 'holdings' : null;
  }

  void _beginBrokerReads() {
    if (_status?.connected != true) return;
    unawaited(_readBroker('holdings', widget.repository.holdings));
    unawaited(_readBroker('funds', widget.repository.funds));
    unawaited(_readBroker('positions', widget.repository.positions));
  }

  String _readFailure(String section, Object failure) {
    if (failure is ApiHttpFailure) {
      if (section == 'funds' &&
          failure.code == 'UPSTOX_FUNDS_MAINTENANCE') {
        return 'Funds service is paused daily 12:00–5:30 AM IST. '
            'Holdings remain accessible; retry after 5:30 AM.';
      }
      if (failure.code == 'UPSTOX_SESSION_EXPIRED' ||
          failure.code == 'UPSTOX_NOT_CONNECTED') {
        return 'Broker session expired. Refresh your connection or '
            'sign in to Upstox again.';
      }
    }
    return '${section[0].toUpperCase()}${section.substring(1)} '
        'temporarily unavailable. Other broker data remains accessible.';
  }

  Future<void> _readBroker(
      String section, Future<Object?> Function() fetch) async {
    if (_reading.contains(section) || _status?.connected != true) return;
    final epoch = _readEpoch;
    setState(() {
      _reading.add(section);
      _readErrors.remove(section);
    });
    try {
      final data = await fetch();
      if (!mounted || epoch != _readEpoch) return;
      final valid = switch (section) {
        'holdings' => PortfolioInstrument.decode(data, broker: 'UPSTOX') != null,
        'positions' => PortfolioInstrument.decode(
            data, broker: 'UPSTOX', position: true) != null,
        'funds' => portfolioAmount(data, broker: 'UPSTOX') != null,
        'orders' => data is List || portfolioRecords(data, 'data') != null,
        'IP status' => data is Map,
        _ => false,
      };
      setState(() {
        if (valid) {
          _brokerData[section] = data;
        } else {
          _readErrors[section] = '${section[0].toUpperCase()}${section.substring(1)} '
              'response could not be verified. No zero balance was assumed.';
        }
      });
    } catch (error) {
      if (mounted && epoch == _readEpoch) {
        setState(() => _readErrors[section] = _readFailure(section, error));
      }
    } finally {
      if (mounted && epoch == _readEpoch) {
        setState(() => _reading.remove(section));
      }
    }
  }

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
        _resetBrokerReads(connected: status.connected);
      });
      if (status.connected) _beginBrokerReads();
    } catch (_) {
      if (mounted) {
        setState(() {
          // Fail closed on refresh errors: a cached CONNECTED label or
          // owner-login capability is not evidence of current broker state.
          _status = null;
          _ownerLoginAvailable = false;
          _resetBrokerReads(connected: false);
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
        _resetBrokerReads(connected: true);
        _awaitingReturn = false;
      });
      _beginBrokerReads();
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
          _resetBrokerReads(connected: false);
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
    if (_status?.connected != true) return;
    setState(() => _section = section);
    // A selected tab may retry a previously failed request, but switching
    // between already verified snapshots must not cause needless API calls.
    if (!_brokerData.containsKey(section)) {
      await _readBroker(section, fetch);
    }
  }

  void _showAccountDetails(BuildContext context) {
    final status = _status;
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        final theme = Theme.of(context);
        final scheme = theme.colorScheme;
        return SafeArea(child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 6, 22, 24),
          child: Column(mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Upstox connection', style: theme.textTheme.titleLarge),
              const SizedBox(height: 10),
              Text('Account ${status?.accountMasked ?? '—'}'),
              const SizedBox(height: 7),
              Text(status?.orderExecutionEnabled == true
                  ? 'Broker-authorized order execution'
                  : 'Read-only link · Real-money order execution disabled',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant)),
              if (status?.expiresAt != null) ...[
                const SizedBox(height: 7),
                Text('Session expires ${status!.expiresAt!.toLocal()}',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: scheme.onSurfaceVariant)),
              ],
              const SizedBox(height: 14),
              Text('Paper and Live portfolios are separate. Broker access '
                   'does not automatically authorize orders.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant)),
              const SizedBox(height: 12),
              TextButton.icon(
                key: const ValueKey('upstox-disconnect'),
                icon: const Icon(Icons.link_off_rounded),
                label: const Text('Disconnect Upstox'),
                onPressed: _loading ? null : () {
                  Navigator.pop(context);
                  _disconnect();
                },
              ),
            ],
          ),
        ));
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final connected = _status?.connected == true;
    final holdings = PortfolioInstrument.decode(
        _brokerData['holdings'], broker: 'UPSTOX');
    final marketValue = portfolioHoldingValue(holdings);
    num? invested = holdings == null ? null : 0;
    num? pnl = holdings == null ? null : 0;
    for (final holding in holdings ?? const <PortfolioInstrument>[]) {
      final quantity = holding.quantity;
      final average = holding.averagePrice;
      final cost = quantity != null && quantity >= 0 &&
          average != null && average >= 0 ? quantity * average : null;
      if (invested != null && cost != null) {
        invested += cost;
      } else {
        invested = null;
      }
      final linePnl = holding.pnl ?? (cost != null &&
          holding.marketValue != null ? holding.marketValue! - cost : null);
      if (pnl != null && linePnl != null) {
        pnl += linePnl;
      } else {
        pnl = null;
      }
    }

    return MintTickCustomerScaffold(showHeader: false, children: [
      const SizedBox(height: 8),
      MintTickBrokerHeader(
        logo: const UpstoxSymbol(size: 38),
        broker: 'Upstox',
        account: connected ? _status?.accountMasked : null,
        connected: connected,
        refreshKey: const ValueKey('upstox-refresh-status'),
        onRefresh: _loading ? null : _refresh,
        menu: connected ? PopupMenuButton<String>(
          key: const ValueKey('upstox-account-menu'),
          tooltip: 'Broker settings',
          icon: const Icon(Icons.more_vert_rounded, size: 21),
          onSelected: (option) {
            if (option == 'ip') {
              _load('IP status', widget.repository.registeredIps);
            } else {
              _showAccountDetails(context);
            }
          },
          itemBuilder: (context) => const [
            PopupMenuItem(value: 'ip', child: Text('Registered IP')),
            PopupMenuItem(value: 'details', child: Text('Account details')),
            PopupMenuItem(value: 'disconnect', child: Text('Disconnect…')),
          ],
        ) : null,
      ),
      const SizedBox(height: 9),
      if (_loading) const LinearProgressIndicator(minHeight: 2),
      if (_error != null) ...[
        const SizedBox(height: 9),
        MintTickInfoText(_error!, icon: Icons.error_outline_rounded),
      ],
      if (!connected) ...[
        const SizedBox(height: 10),
        MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Connect Upstox', style: text.titleMedium?.copyWith(
              fontWeight: FontWeight.w800)),
            const SizedBox(height: 5),
            Text(_status?.available == false
                ? 'MintTick needs its Upstox developer app configured '
                  'before you can connect.'
                : 'Authorize your account using the official Upstox login.',
              style: text.bodySmall?.copyWith(
                color: colors.onSurfaceVariant)),
            if (_ownerLoginAvailable) ...[
              const SizedBox(height: 12),
              SizedBox(width: double.infinity, child: FilledButton.icon(
                key: const ValueKey('upstox-connect-saved-owner'),
                onPressed: _loading ? null : _connectOwner,
                icon: const Icon(Icons.verified_user_outlined),
                label: const Text('Connect using saved Upstox login'),
              )),
              const SizedBox(height: 4),
              Text('Only your verified MintTick owner account can use '
                  'the protected login. No sensitive codes are entered here.',
                style: text.labelSmall?.copyWith(
                  color: colors.onSurfaceVariant)),
            ],
            const SizedBox(height: 9),
            SizedBox(width: double.infinity, child: FilledButton.icon(
              key: const ValueKey('upstox-start-oauth'),
              onPressed: _loading || _status?.available != true
                  ? null : _start,
              icon: const Icon(Icons.open_in_new_rounded, size: 18),
              label: Text(_ownerLoginAvailable
                  ? 'Use official Upstox login instead'
                  : 'Connect with Upstox'),
            )),
            if (_awaitingReturn) ...[
              const SizedBox(height: 10),
              const MintTickInfoText(
                'Complete authentication on Upstox and return to MintTick. '
                'The connection refreshes when you return. '
                'Your credentials stay on Upstox.',
                icon: Icons.verified_user_outlined,
              ),
            ],
          ],
        )),
      ] else ...[
        const SizedBox(height: 4),
        MintTickBrokerPortfolioHero(
          broker: 'Upstox',
          value: marketValue,
          invested: invested,
          pnl: pnl,
          holdingsCount: holdings?.length,
          isLoading: _reading.contains('holdings'),
          orderExecutionEnabled: _status?.orderExecutionEnabled == true,
          valueKey: const ValueKey('upstox-portfolio-value'),
        ),
        const SizedBox(height: 12),
        MintTickBrokerTabs(
          selected: _section ?? 'holdings',
          keyPrefix: 'upstox-tab-',
          onSelected: (section) {
            final fetch = switch (section) {
              'holdings' => widget.repository.holdings,
              'positions' => widget.repository.positions,
              'funds' => widget.repository.funds,
              'orders' => widget.repository.orders,
              _ => widget.repository.holdings,
            };
            _load(section, fetch);
          },
        ),
        if (_readErrors['funds'] != null) ...[
          const SizedBox(height: 10),
          Container(
            key: const ValueKey('upstox-funds-alert'),
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              color: colors.secondaryContainer.withValues(alpha: 0.25),
              border: Border.all(color: colors.outlineVariant),
            ),
            child: Row(children: [
              Icon(Icons.info_outline_rounded, size: 18,
                color: colors.onSurfaceVariant),
              const SizedBox(width: 8),
              Expanded(child: Text(_readErrors['funds']!,
                style: text.bodySmall?.copyWith(
                  color: colors.onSurfaceVariant))),
              IconButton(
                key: const ValueKey('upstox-retry-funds'),
                tooltip: 'Retry funds',
                visualDensity: VisualDensity.compact,
                onPressed: _reading.contains('funds')
                    ? null : () => _readBroker('funds', widget.repository.funds),
                icon: const Icon(Icons.refresh_rounded, size: 18),
              ),
            ]),
          ),
        ],
        const SizedBox(height: 9),
        if (_section != null)
          MintTickBrokerSection(
            title: _section == 'holdings'
                ? 'My holdings' : '${_section![0].toUpperCase()}${_section!.substring(1)}',
            loading: _reading.contains(_section),
            error: _readErrors[_section],
            refreshKey: const ValueKey('upstox-retry-section'),
            onRefresh: _reloadSelected,
            children: _readErrors[_section] != null ||
                (_reading.contains(_section) && !_brokerData.containsKey(_section))
                ? const [] : _brokerSummary(text, colors),
          ),
      ],
    ]);
  }

  void _reloadSelected() {
    final Future<Object?> Function()? fetch = switch (_section) {
      'holdings' => widget.repository.holdings,
      'positions' => widget.repository.positions,
      'funds' => widget.repository.funds,
      'orders' => widget.repository.orders,
      'IP status' => widget.repository.registeredIps,
      _ => null,
    };
    if (_section != null && fetch != null) {
      unawaited(_readBroker(_section!, fetch));
    }
  }

  /// The official Upstox holding payload contains quantity, last_price,
  /// average_price and pnl. Do not present broker funds or paper cash as
  /// holdings, and never replace missing source prices with a guessed zero.
  List<Widget> _holdingSummary(
      Object? source, TextTheme text, ColorScheme scheme) {
    final raw = portfolioRecords(source, 'holdings');
    final holdings = PortfolioInstrument.decode(source, broker: 'UPSTOX');
    if (raw == null || holdings == null) {
      return [const Text('Upstox holdings are unavailable. Refresh to retry.')];
    }
    if (raw.isEmpty) {
      return [const Text('No holdings reported by Upstox.')];
    }

    return [MintTickBrokerHoldingsList(rows: holdings)];
  }

  List<Widget> _brokerSummary(TextTheme text, ColorScheme scheme) {
    final value = _brokerData[_section];
    if (value == null) {
      return [const Text('No verified broker data loaded.')];
    }
    if (_section == 'holdings') {
      return _holdingSummary(value, text, scheme);
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
            ? 'Primary static IP registered with Upstox'
            : 'No primary static IP reported by broker',
          style: text.bodySmall)];
      }
      if (_section == 'funds') {
        final amount = portfolioAmount(value, broker: 'UPSTOX');
        final v3 = value['available_to_trade'] is Map;
        final equity = value['equity'];
        final breakdown = equity is Map ? equity : value;
        final secondary = breakdown['used_margin'];
        return [
          Row(children: [
            Expanded(child: Text(v3 ? 'Available cash' : 'Available margin',
              style: text.bodySmall)),
            Text(portfolioRupees(amount),
              style: text.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
          ]),
          if (secondary is num && secondary.toDouble().isFinite) ...[
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: Text('Margin used', style: text.bodySmall)),
              Text(portfolioRupees(secondary),
                style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
            ]),
          ],
          const SizedBox(height: 8),
          Text('Funds exclude unverified balances. Pledged collateral is '
              'not counted as cash.',
            style: text.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        ];
      }
    }
    return [const Text('No supported broker details to show.')];
  }
}
