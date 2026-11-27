import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../domain/trading_room_draft.dart';
import '../domain/trading_workspace_mode.dart';
import 'minttick_mode_switcher.dart';
import '../data/customer_room_repository.dart';
import '../data/customer_execution_repository.dart';
import '../data/customer_paper_fno_repository.dart';
import '../data/customer_paper_trading_repository.dart';
import '../data/customer_market_repository.dart';
import '../data/customer_trade_intent_repository.dart';
import 'minttick_customer_widgets.dart';
import 'minttick_room_production_workspace.dart';
import 'minttick_room_rename_dialog.dart';


/// Stable user-facing room type, including existing legacy ALL rooms.
String _roomTypeLabel(TradingRoomDraft room) => switch (
    (room.marketScope, room.focus)) {
  ('EQUITY', 'Intraday') => 'Equity · Intraday',
  ('EQUITY', 'Swing') => 'Equity · Swing',
  ('EQUITY', 'Long-term') => 'Equity · Long-term',
  ('FNO', 'Intraday') => 'F&O · Intraday',
  ('FNO', 'Swing') => 'F&O · Positional',
  ('ALL', _) => 'All markets · ${room.focus}',
  _ => '${room.marketScope} · ${room.focus}',
};

class MintTickTradingRoomsPage extends StatefulWidget {
  const MintTickTradingRoomsPage({
    required this.store, required this.mode, required this.onCreate,
    required this.onOpen, this.metricsRepository, super.key,
  });

  final TradingRoomDraftStore store;
  final TradingWorkspaceMode mode;
  final VoidCallback onCreate;
  final ValueChanged<TradingRoomDraft> onOpen;
  final HttpCustomerRoomRepository? metricsRepository;

  @override
  State<MintTickTradingRoomsPage> createState() => _MintTickTradingRoomsPageState();
}

class _MintTickTradingRoomsPageState extends State<MintTickTradingRoomsPage> {
  Future<List<CustomerRoomMetric>>? _dashboard;

  @override
  void initState() {
    super.initState();
    _refreshMetrics();
  }

  void _refreshMetrics() {
    _dashboard = widget.metricsRepository?.dashboard();
  }

  Future<void> _refresh() async {
    await widget.store.load();
    if (mounted) setState(_refreshMetrics);
  }

  Future<void> _renameRoom(TradingRoomDraft room) async {
    var renamed = false;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => MintTickRoomRenameDialog(
        currentName: room.name,
        onSave: (name) async {
          await widget.store.rename(room.id, name);
          renamed = true;
        },
      ),
    );
    if (renamed && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Trading Room renamed.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final type = Theme.of(context).textTheme;
    return AnimatedBuilder(
      animation: Listenable.merge([widget.store, widget.mode]),
      builder: (context, _) => FutureBuilder<List<CustomerRoomMetric>>(
        future: _dashboard,
        builder: (context, snapshot) {
          final metrics = {
            for (final item in snapshot.data ?? <CustomerRoomMetric>[])
              item.roomId: item,
          };
          final rooms = widget.store.drafts
              .where((room) => room.mode == widget.mode.selected)
              .toList(growable: false);
          final planned = rooms.fold<int>(0, (sum, room) => sum + room.paperBudget);
          return MintTickCustomerScaffold(showHeader: false, children: [
            const SizedBox(height: 6),
            Row(children: [
              Expanded(child: Text('Trading Rooms',
                style: type.titleLarge?.copyWith(fontWeight: FontWeight.w800))),
              OutlinedButton.icon(
                key: const ValueKey('trading-rooms-create'),
                onPressed: widget.onCreate,
                icon: const Icon(Icons.add_rounded, size: 17),
                label: const Text('New room'),
              ),
            ]),
            const SizedBox(height: 7),
            MintTickModeSwitcher(mode: widget.mode),
            const SizedBox(height: 9),
            Container(
              decoration: BoxDecoration(color: scheme.surfaceContainerLow,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: scheme.outlineVariant)),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
              child: Row(children: [
                _RoomStat(label: 'Rooms', value: '${rooms.length}'),
                const SizedBox(width: 16),
                _RoomStat(label: 'Planned', value: formatPaperRupees(planned)),
                const Spacer(),
                IconButton(
                  tooltip: 'Refresh rooms and activity',
                  onPressed: _refresh,
                  icon: const Icon(Icons.refresh_rounded, size: 18),
                  visualDensity: VisualDensity.compact,
                ),
              ]),
            ),
            const SizedBox(height: 10),
            if (widget.store.isLoading)
              const LinearProgressIndicator(minHeight: 2)
            else if (widget.store.lastError != null) ...[
              MintTickInfoText(widget.store.lastError!,
                icon: Icons.error_outline_rounded),
              TextButton(onPressed: _refresh, child: const Text('Retry')),
            ] else if (rooms.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 24),
                child: Text(
                  widget.mode.isPaper
                    ? 'No Paper rooms. Create one to simulate orders.'
                    : 'No Live rooms. Create one to prepare a broker workspace.',
                  textAlign: TextAlign.center,
                  style: type.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
              )
            else ...[
              for (final room in rooms) ...[
                Material(
                  color: scheme.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(10),
                  child: InkWell(
                    key: ValueKey('draft-room-${room.id}'),
                    borderRadius: BorderRadius.circular(10),
                    onTap: () => widget.onOpen(room),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: scheme.outlineVariant)),
                      child: Row(children: [
                        Icon(Icons.layers_outlined, size: 18, color: scheme.primary),
                        const SizedBox(width: 10),
                        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(room.name, maxLines: 1, overflow: TextOverflow.ellipsis,
                              style: type.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
                            const SizedBox(height: 3),
                            Text(
                              '${_roomTypeLabel(room)} · ${room.mode == 'PAPER' && metrics[room.id]?.availableCashPaise != null
                                ? 'Virtual cash ${_formatPaise(metrics[room.id]!.availableCashPaise!)} · ${metrics[room.id]!.positionCount} positions'
                                : '${formatPaperRupees(room.paperBudget)} planned'}',
                              maxLines: 1, overflow: TextOverflow.ellipsis,
                              style: type.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
                          ])),
                        const SizedBox(width: 6),
                        Text(room.status == 'PAPER_FUNDED' ? 'FUNDED' : 'DRAFT',
                          style: type.labelSmall?.copyWith(
                            color: scheme.onSurfaceVariant, fontWeight: FontWeight.w700)),
                        const SizedBox(width: 3),
                        IconButton(
                          key: ValueKey('room-rename-${room.id}'),
                          tooltip: 'Rename ${room.name}',
                          onPressed: widget.store.repository is RoomDraftRenaming
                            ? () => _renameRoom(room) : null,
                          visualDensity: VisualDensity.compact,
                          constraints: const BoxConstraints.tightFor(
                            width: 32, height: 32),
                          padding: EdgeInsets.zero,
                          icon: const Icon(Icons.edit_outlined, size: 17),
                        ),
                        const Icon(Icons.chevron_right_rounded, size: 19),
                      ]),
                    ),
                  ),
                ),
                const SizedBox(height: 6),
              ],
            ],
            if (snapshot.hasError) Padding(
              padding: const EdgeInsets.only(top: 5),
              child: Text('Activity metrics unavailable. Room plans remain accessible.',
                style: type.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
            ),
            const SizedBox(height: 8),
            Text(widget.mode.isPaper
              ? 'PAPER uses virtual cash only. Open a funded room to place a simulated order.'
              : 'LIVE planned capital is not a broker balance. Real execution requires server authorization.',
              style: type.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          ]);
        },
      ),
    );
  }
}

class _RoomStat extends StatelessWidget {
  const _RoomStat({required this.label, required this.value});
  final String label, value;
  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label.toUpperCase(), style: type.labelSmall?.copyWith(
        color: scheme.onSurfaceVariant, fontSize: 10)),
      const SizedBox(height: 2),
      Text(value, style: type.bodyMedium?.copyWith(
        fontWeight: FontWeight.w800, fontFeatures: const [FontFeature.tabularFigures()])),
    ]);
  }
}

/// One-screen, room-level planning form. No client-generated risk limits or
/// executable strategy selection: the backend remains the trading authority.
class MintTickRoomCreationPage extends StatefulWidget {
  const MintTickRoomCreationPage({this.initialMode = 'PAPER', super.key});
  final String initialMode;

  @override
  State<MintTickRoomCreationPage> createState() => _MintTickRoomCreationPageState();
}

class _MintTickRoomCreationPageState extends State<MintTickRoomCreationPage> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _paperBudget = TextEditingController(text: '20000');
  String _roomType = 'EQUITY_INTRADAY';
  String _risk = 'Balanced';
  late String _mode;

  /// A single visible room type maps to the existing independent server fields.
  /// Never persist the presentation label or use a room name for authorization.
  String get _marketScope => _roomType.startsWith('FNO_') ? 'FNO' : 'EQUITY';
  String get _focus => switch (_roomType) {
    'EQUITY_SWING' || 'FNO_POSITIONAL' => 'Swing',
    'EQUITY_LONG_TERM' => 'Long-term',
    _ => 'Intraday',
  };

  @override
  void initState() {
    super.initState();
    _mode = widget.initialMode == 'LIVE' ? 'LIVE' : 'PAPER';
  }

  @override
  void dispose() {
    _name.dispose();
    _paperBudget.dispose();
    super.dispose();
  }

  TradingRoomDraft get _draft => TradingRoomDraft(
    id: DateTime.now().microsecondsSinceEpoch.toString(),
    name: _name.text.trim(),
    focus: _focus,
    paperBudget: int.tryParse(_paperBudget.text.trim()) ?? 0,
    riskPreference: _risk,
    mode: _mode,
    marketScope: _marketScope,
  );

  void _createRoom() {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    // This returns a customer-owned room draft for authenticated persistence.
    // It does not grant broker rights or activate a Mind strategy or live order.
    Navigator.of(context).pop(_draft);
  }

  String get _availabilityNote => switch ((_marketScope, _mode, _roomType)) {
    ('FNO', 'LIVE', _) =>
      'LIVE F&O execution remains blocked until independently verified.',
    ('FNO', 'PAPER', 'FNO_POSITIONAL') =>
      'Positional F&O is a plan only; the current PAPER pilot does not execute it.',
    ('FNO', 'PAPER', _) =>
      'PAPER pilot: NIFTY long options only; no futures or stock options.',
    ('EQUITY', 'LIVE', _) =>
      'LIVE execution requires separate broker and risk authorization.',
    _ => 'NSE/BSE cash equities. Execution remains server-controlled.',
  };

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme;
    return Scaffold(
      body: SafeArea(
        child: Center(child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 680),
          child: Column(children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(15, 10, 15, 0),
              child: Row(children: [
                IconButton(
                  key: const ValueKey('room-creation-back'),
                  tooltip: 'Back',
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.arrow_back_rounded),
                ),
                Expanded(child: Text('New Trading Room',
                  style: style.titleSmall?.copyWith(fontWeight: FontWeight.w800))),
                MintTickStatusLabel(_mode),
              ]),
            ),
            Expanded(child: Form(
              key: _formKey,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 22),
                children: [
                  Text('Room setup', style: style.headlineSmall
                      ?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 20),
                  const _FieldLabel('Trading mode'),
                  const SizedBox(height: 8),
                  SegmentedButton<String>(
                    key: const ValueKey('room-mode-toggle'),
                    segments: const [
                      ButtonSegment(value: 'PAPER', label: Text('Paper'),
                        icon: Icon(Icons.science_outlined)),
                      ButtonSegment(value: 'LIVE', label: Text('Live'),
                        icon: Icon(Icons.candlestick_chart_rounded)),
                    ],
                    selected: {_mode},
                    onSelectionChanged: (choices) =>
                        setState(() => _mode = choices.first),
                  ),
                  const SizedBox(height: 18),
                  const _FieldLabel('Room name'),
                  const SizedBox(height: 8),
                  TextFormField(
                    key: const ValueKey('room-name-input'),
                    controller: _name,
                    maxLength: 38,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(
                      hintText: 'My trading room', counterText: ''),
                    validator: (value) => (value?.trim().isEmpty ?? true)
                        ? 'Give your room a name' : null,
                  ),
                  const SizedBox(height: 16),
                  const _FieldLabel('Room type'),
                  const SizedBox(height: 8),
                  MintTickSurface(child: Row(children: [
                    const Icon(Icons.stacked_line_chart_rounded),
                    const SizedBox(width: 10),
                    Expanded(child: DropdownButton<String>(
                      key: const ValueKey('room-type-dropdown'),
                      isExpanded: true,
                      value: _roomType,
                      underline: const SizedBox.shrink(),
                      items: const [
                        DropdownMenuItem(value: 'EQUITY_INTRADAY',
                          child: Text('Equity · Intraday')),
                        DropdownMenuItem(value: 'EQUITY_SWING',
                          child: Text('Equity · Swing')),
                        DropdownMenuItem(value: 'EQUITY_LONG_TERM',
                          child: Text('Equity · Long-term')),
                        DropdownMenuItem(value: 'FNO_INTRADAY',
                          child: Text('F&O · Intraday')),
                        DropdownMenuItem(value: 'FNO_POSITIONAL',
                          child: Text('F&O · Positional')),
                      ],
                      onChanged: (value) {
                        if (value != null) setState(() => _roomType = value);
                      },
                    )),
                  ])),
                  const SizedBox(height: 6),
                  Text(_availabilityNote,
                    key: const ValueKey('room-supported-instruments'),
                    style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
                  const SizedBox(height: 18),
                  const _FieldLabel('Planned capital (₹)'),
                  const SizedBox(height: 8),
                  TextFormField(
                    key: const ValueKey('room-budget-input'),
                    controller: _paperBudget,
                    keyboardType: TextInputType.number,
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    decoration: const InputDecoration(prefixText: '₹  ', hintText: '20000'),
                    validator: (value) {
                      final n = int.tryParse(value?.trim() ?? '');
                      return n == null || n < 1000 || n > 10000000
                          ? 'Enter ₹1,000 to ₹1 crore' : null;
                    },
                  ),
                  const SizedBox(height: 18),
                  const _FieldLabel('Room risk preference'),
                  const SizedBox(height: 9),
                  Wrap(spacing: 9, runSpacing: 8, children: [
                    for (final label in ['Cautious', 'Balanced', 'Aggressive'])
                      ChoiceChip(
                        key: ValueKey('room-risk-${label.toLowerCase()}'),
                        label: Text(label),
                        selected: label == _risk,
                        onSelected: (_) => setState(() => _risk = label),
                      ),
                  ]),
                  const SizedBox(height: 8),
                  Text('MintTick Mind will determine strategy and risk proposals. '
                      'Server checks govern actual orders.',
                    style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
                ],
              ),
            )),
          ]),
        )),
      ),
      bottomNavigationBar: SafeArea(
        top: false,
        child: Container(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 14),
          decoration: BoxDecoration(
            color: scheme.surface,
            border: Border(top: BorderSide(color: scheme.outlineVariant)),
          ),
          child: SizedBox(
            height: 50,
            child: FilledButton(
              key: const ValueKey('room-creation-next'),
              onPressed: _createRoom,
              child: const Text('Create Trading Room'),
            ),
          ),
        ),
      ),
    );
  }
}

class _FieldLabel extends StatelessWidget {
  const _FieldLabel(this.title);
  final String title;

  @override
  Widget build(BuildContext context) =>
      Text(title, style: Theme.of(context).textTheme.titleSmall
          ?.copyWith(fontWeight: FontWeight.w700));
}

String _formatPaise(int paise) {
  final minus = paise < 0 ? '-' : '';
  final abs = paise.abs();
  return '$minus${formatPaperRupees(abs ~/ 100)}.${(abs % 100).toString().padLeft(2, '0')}';
}

class MintTickRoomDetailPage extends StatefulWidget {
  const MintTickRoomDetailPage({
    required this.room,
    this.repository,
    this.executionRepository,
    this.paperTradingRepository,
    this.paperFnoRepository,
    this.marketRepository,
    this.intentsRepository,
    this.onBroker,
    this.onRoomChanged,
    super.key,
  });

  final TradingRoomDraft room;
  final HttpCustomerRoomRepository? repository;
  final HttpCustomerExecutionRepository? executionRepository;
  final HttpCustomerPaperTradingRepository? paperTradingRepository;
  final CustomerPaperFnoRepository? paperFnoRepository;
  final HttpCustomerMarketRepository? marketRepository;
  final HttpCustomerTradeIntentRepository? intentsRepository;
  final VoidCallback? onBroker;
  final Future<void> Function()? onRoomChanged;

  @override
  State<MintTickRoomDetailPage> createState() => _MintTickRoomDetailPageState();
}

class _MintTickRoomDetailPageState extends State<MintTickRoomDetailPage> {
  TradingRoomDraft? _renamedRoom;
  CustomerPaperWallet? _wallet;
  CustomerExecutionReadiness? _readiness;
  LiveEquityCapability? _liveEquityCapability;
  List<CustomerPaperCashEvent> _events = const [];
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _loadReadiness();
    if (widget.room.status == 'PAPER_FUNDED' &&
        widget.room.mode == 'PAPER' &&
        widget.repository != null) {
      _loadWallet();
    }
  }

  Future<void> _loadReadiness() async {
    if (widget.executionRepository == null) return;
    try {
      final data = await widget.executionRepository!.readiness(widget.room.id);
      LiveEquityCapability? equityCapability;
      if (widget.room.mode == 'LIVE' && widget.room.marketScope != 'FNO') {
        try {
          equityCapability = await widget.executionRepository!
              .liveEquityCapability(widget.room.id);
          if (equityCapability.roomId != widget.room.id ||
              equityCapability.executionEnabled ||
              equityCapability.orderRouteImplemented) {
            throw const FormatException('Unexpected LIVE equity capability');
          }
        } catch (_) {
          equityCapability = null;
        }
      }
      if (mounted) {
        setState(() {
          _readiness = data;
          _liveEquityCapability = equityCapability;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _readiness = null;
          _liveEquityCapability = null;
        });
      }
    }
  }

  Future<void> _selectLiveBroker(String broker) async {
    if (_busy || widget.room.mode != 'LIVE' ||
        widget.executionRepository == null ||
        (broker != 'ANGEL_ONE' && broker != 'UPSTOX')) {
      return;
    }
    setState(() { _busy = true; _error = null; });
    try {
      // The backend validates Cognito ownership and LIVE-only room mode.
      // This changes a broker preference, never an order or consent.
      final readiness = await widget.executionRepository!
          .selectLiveBroker(widget.room.id, broker);
      if (!mounted) return;
      if (readiness.mode != 'LIVE' || readiness.selectedBroker != broker ||
          readiness.executionEnabled) {
        throw const FormatException('Unexpected broker assignment readiness');
      }
      setState(() => _readiness = readiness);
      await _loadReadiness();
    } catch (_) {
      if (mounted) {
        setState(() {
          _readiness = null;
          _error = 'Live broker selection could not be verified. '
              'Refresh readiness before trying again.';
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _loadWallet() async {
    if (widget.repository == null || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final wallet = await widget.repository!.paperAccount(widget.room.id);
      final events = await widget.repository!.paperLedger(widget.room.id);
      if (!mounted) return;
      setState(() {
        _wallet = wallet;
        _events = events;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _wallet = null;
          _error = 'Paper account could not be verified. Try again.';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _activate() async {
    if (_busy || widget.repository == null || widget.room.mode != 'PAPER' ||
        widget.room.status != 'DRAFT') {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Fund your Paper room?'),
        content: Text(
          'MintTick will allocate ${formatPaperRupees(widget.room.paperBudget)} '
          'in virtual cash to this room. This is not a deposit and cannot '
          'place any trades yet.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('confirm-paper-activation'),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Fund virtual wallet'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final wallet = await widget.repository!.activatePaper(widget.room.id);
      if (!mounted) return;
      setState(() => _wallet = wallet);
      if (widget.onRoomChanged != null) {
        await widget.onRoomChanged!();
      }
    } catch (_) {
      if (mounted) {
        setState(() =>
            _error = 'Could not activate your Paper wallet. Please retry.');
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
    if (_wallet != null && mounted) {
      await _loadWallet();
      await _loadReadiness();
    }
  }

  Future<void> _refreshWorkspace() async {
    await _loadReadiness();
    if (widget.room.mode == 'PAPER' &&
        (widget.room.status == 'PAPER_FUNDED' || _wallet != null)) {
      await _loadWallet();
    }
  }

  Future<void> _renameRoom() async {
    final repo = widget.repository;
    if (repo == null) return;
    final current = _renamedRoom ?? widget.room;
    var renamed = false;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => MintTickRoomRenameDialog(
        currentName: current.name,
        onSave: (name) async {
          final saved = await repo.rename(current.id, name);
          if (saved.id != current.id || saved.mode != current.mode ||
              saved.status != current.status ||
              saved.paperBudget != current.paperBudget ||
              saved.focus != current.focus ||
              saved.riskPreference != current.riskPreference) {
            throw const FormatException('Unexpected room state after rename');
          }
          if (mounted) setState(() => _renamedRoom = saved);
          renamed = true;
          try {
            await widget.onRoomChanged?.call();
          } catch (_) {
            // The server rename succeeded. A transient room-list refresh
            // failure must never re-submit or roll back that valid change.
          }
        },
      ),
    );
    if (renamed && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Trading Room renamed.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return MintTickRoomProductionWorkspace(
      room: _renamedRoom ?? widget.room,
      onRename: widget.repository == null ? null : _renameRoom,
      wallet: _wallet,
      readiness: _readiness,
      liveEquityCapability: _liveEquityCapability,
      events: _events,
      market: widget.marketRepository,
      paper: widget.paperTradingRepository,
      fno: widget.paperFnoRepository,
      execution: widget.executionRepository,
      intents: widget.intentsRepository,
      onBack: () => Navigator.of(context).pop(),
      onRefresh: _refreshWorkspace,
      onFundPaper: _activate,
      onSelectBroker: _selectLiveBroker,
      onBrokers: widget.onBroker,
      error: _error,
      busy: _busy,
    );
  }
}
