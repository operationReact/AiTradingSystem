import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../domain/trading_room_draft.dart';
import '../domain/trading_workspace_mode.dart';
import 'minttick_mode_switcher.dart';
import '../data/customer_room_repository.dart';
import '../data/customer_execution_repository.dart';
import '../data/customer_paper_trading_repository.dart';
import '../data/customer_market_repository.dart';
import 'minttick_paper_order_panel.dart';
import 'minttick_paper_performance_panel.dart';
import 'minttick_customer_widgets.dart';

class MintTickTradingRoomsPage extends StatelessWidget {
  const MintTickTradingRoomsPage({
    required this.store,
    required this.mode,
    required this.onCreate,
    required this.onOpen,
    super.key,
  });

  final TradingRoomDraftStore store;
  final TradingWorkspaceMode mode;
  final VoidCallback onCreate;
  final ValueChanged<TradingRoomDraft> onOpen;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final styles = Theme.of(context).textTheme;
    return AnimatedBuilder(
      animation: Listenable.merge([store, mode]),
      builder: (context, _) {
        final rooms = store.drafts
          .where((room) => room.mode == mode.selected)
          .toList(growable: false);
        return MintTickCustomerScaffold(
          showHeader: false,
          children: [
            const SizedBox(height: 12),
            Text('Trading Rooms',
              style: styles.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 5),
            Text('Keep Paper and Live allocations, holdings and risk controls separate.',
              style: styles.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
            const SizedBox(height: 16),
            MintTickModeSwitcher(mode: mode),
            const SizedBox(height: 19),
            MintTickSectionHeading(
              mode.isPaper ? 'Paper Trading Rooms' : 'Live Trading Rooms'),
            const SizedBox(height: 8),
            if (store.isLoading)
              const Center(child: CircularProgressIndicator())
            else if (store.lastError != null) ...[
              MintTickInfoText(store.lastError!,
                icon: Icons.error_outline_rounded),
              const SizedBox(height: 9),
              OutlinedButton.icon(onPressed: store.load,
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('Retry loading rooms')),
            ] else if (rooms.isEmpty) ...[
              MintTickSurface(child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    const MintTickIconTile(Icons.layers_outlined),
                    const SizedBox(width: 12),
                    Expanded(child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(mode.isPaper ? 'No Paper Rooms yet'
                            : 'No Live Rooms yet',
                          style: styles.titleSmall?.copyWith(
                            fontWeight: FontWeight.w700)),
                        const SizedBox(height: 3),
                        Text(mode.isPaper
                          ? 'Create a room to plan and test simulated trades.'
                          : 'Plan your Live room; broker connection and approval are separate.',
                          style: styles.bodySmall?.copyWith(
                            color: scheme.onSurfaceVariant)),
                      ],
                    )),
                  ]),
                  const SizedBox(height: 15),
                  SizedBox(width: double.infinity,
                    child: FilledButton.icon(
                      key: const ValueKey('trading-rooms-create'),
                      onPressed: onCreate,
                      icon: const Icon(Icons.add_rounded),
                      label: Text(mode.isPaper
                        ? 'Create Paper Room' : 'Plan a Live Room'))),
                ],
              )),
            ] else ...[
              for (final room in rooms) ...[
                Material(
                  color: scheme.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(16),
                  child: InkWell(
                    key: ValueKey('draft-room-${room.id}'),
                    borderRadius: BorderRadius.circular(16),
                    onTap: () => onOpen(room),
                    child: Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        border: Border.all(color: scheme.outlineVariant),
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Row(children: [
                        const MintTickIconTile(Icons.layers_outlined),
                        const SizedBox(width: 11),
                        Expanded(child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(room.name, maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: styles.titleSmall?.copyWith(
                                fontWeight: FontWeight.w700)),
                            const SizedBox(height: 4),
                            Text('${room.mode} · ${room.focus} · ${formatPaperRupees(room.paperBudget)} planned',
                              style: styles.bodySmall?.copyWith(
                                color: scheme.onSurfaceVariant)),
                          ],
                        )),
                        const SizedBox(width: 6),
                        MintTickStatusLabel(room.status == 'PAPER_FUNDED'
                          ? 'PAPER FUNDED' : 'DRAFT'),
                        const Icon(Icons.chevron_right_rounded, size: 18),
                      ]),
                    ),
                  ),
                ),
                const SizedBox(height: 9),
              ],
              const SizedBox(height: 7),
              OutlinedButton.icon(
                key: const ValueKey('trading-rooms-create'),
                onPressed: onCreate,
                icon: const Icon(Icons.add_rounded),
                label: Text(mode.isPaper ? 'Create Paper Room' : 'Plan a Live Room')),
            ],
            const SizedBox(height: 16),
            const MintTickInfoText(
              'Planned capital is not a broker balance. Each room requires '
              'its own risk and execution readiness checks.',
              icon: Icons.shield_outlined),
          ],
        );
      },
    );
  }
}

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
  final _scroll = ScrollController();
  String _focus = 'Intraday';
  String _risk = 'Balanced';
  late String _mode;
  int _step = 0;
  bool _acknowledged = false;

  static const _focuses = <(String, String, IconData)>[
    ('Intraday', 'Same-day trading', Icons.bolt_outlined),
    ('Swing', 'Days to weeks', Icons.trending_up_rounded),
    ('Long-term', 'Longer horizon', Icons.layers_outlined),
    ('Help me choose', 'Explore approaches', Icons.psychology_outlined),
  ];

  @override
  void initState() {
    super.initState();
    _mode = widget.initialMode == 'LIVE' ? 'LIVE' : 'PAPER';
  }

  @override
  void dispose() {
    _name.dispose();
    _paperBudget.dispose();
    _scroll.dispose();
    super.dispose();
  }

  TradingRoomDraft get _draft => TradingRoomDraft(
    id: DateTime.now().microsecondsSinceEpoch.toString(),
    name: _name.text.trim(),
    focus: _focus,
    paperBudget: int.tryParse(_paperBudget.text.trim()) ?? 0,
    riskPreference: _risk,
    mode: _mode,
  );

  void _top() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      if (MediaQuery.of(context).disableAnimations) {
        _scroll.jumpTo(0);
      } else {
        _scroll.animateTo(0, duration: kMintTickMotion, curve: Curves.easeOut);
      }
    });
  }

  void _continue() {
    if (_step == 0 && !(_formKey.currentState?.validate() ?? false)) return;
    if (_step < 3) {
      setState(() => _step++);
      _top();
    } else if (_acknowledged) {
      // Only a draft is persisted by the authenticated customer API.
      Navigator.of(context).pop(_draft);
    }
  }

  void _back() {
    if (_step > 0) {
      setState(() => _step--);
      _top();
    } else {
      Navigator.of(context).pop();
    }
  }

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
              child: Column(children: [
                Row(children: [
                  IconButton(
                    key: const ValueKey('room-creation-back'),
                    tooltip: 'Back',
                    onPressed: _back,
                    icon: const Icon(Icons.arrow_back_rounded),
                  ),
                  Expanded(child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(_step == 3 ? 'Review Trading Room' : 'New Trading Room',
                        style: style.titleSmall?.copyWith(fontWeight: FontWeight.w800)),
                      Text('Step ${_step + 1} of 4 · ${_mode == 'LIVE' ? 'Live' : 'Paper'}',
                        style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
                    ],
                  )),
                  MintTickStatusLabel(_mode),
                ]),
                const SizedBox(height: 13),
                Row(children: [
                  for (var index = 0; index < 4; index++) ...[
                    if (index > 0) const SizedBox(width: 8),
                    Expanded(child: _ProgressSegment(active: index <= _step)),
                  ],
                ]),
              ]),
            ),
            Expanded(child: ListView(
              controller: _scroll,
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 22),
              children: [
                AnimatedSwitcher(
                  duration: MediaQuery.of(context).disableAnimations
                      ? Duration.zero : kMintTickMotion,
                  child: _step == 3
                      ? KeyedSubtree(
                          key: const ValueKey('room-plan'),
                          child: _buildPlan(context))
                      : KeyedSubtree(
                          key: const ValueKey('room-details'),
                          child: _buildDetails(context)),
                ),
              ],
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
              onPressed: _step == 3 && !_acknowledged ? null : _continue,
              child: Text(_step == 3 ? 'Save Trading Room plan' : const ['Continue to Strategy', 'Continue to Risk', 'Review Plan'][_step]),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDetails(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme;
    final headings = ['Room details', 'Choose a strategy focus', 'Set a risk preference'];
    return Form(
      key: _formKey,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(headings[_step], style: style.headlineSmall
            ?.copyWith(fontWeight: FontWeight.w800)),
        const SizedBox(height: 6),
        Text(_step == 0
            ? 'Create a plan for your selected workspace.'
            : _step == 1
                ? 'Select the intended trading horizon. This does not activate a strategy.'
                : 'Choose a planning preference; actual risk limits require server validation.',
          style: style.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 20),
        if (_step == 0) ...[
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
            onSelectionChanged: (choices) => setState(() => _mode = choices.first),
          ),
          const SizedBox(height: 8),
          Text(_mode == 'PAPER'
              ? 'Uses simulated funds. No broker order is placed.'
              : 'A Live plan does not grant broker order authorization.',
            style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
          const SizedBox(height: 18),
          const _FieldLabel('Room name'),
          const SizedBox(height: 8),
          TextFormField(
            key: const ValueKey('room-name-input'),
            controller: _name,
            maxLength: 38,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              hintText: 'Paper Trading 1', counterText: ''),
            validator: (value) => (value?.trim().isEmpty ?? true)
                ? 'Give your room a name' : null,
          ),
          const SizedBox(height: 18),
          const _FieldLabel('Asset class'),
          const SizedBox(height: 8),
          MintTickSurface(child: Row(children: [
            const Icon(Icons.stacked_line_chart_rounded),
            const SizedBox(width: 10),
            const Expanded(child: Text('Equity · Cash market')),
            Text('Current scope',
              style: style.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          ])),
          const SizedBox(height: 18),
          const _FieldLabel('Planned capital allocation (₹)'),
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
          const SizedBox(height: 6),
          Text('A planned amount, not funded cash or a broker balance.',
            style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        ],
        if (_step == 1) ...[
          const _FieldLabel('Strategy / holding horizon'),
          const SizedBox(height: 12),
          LayoutBuilder(builder: (context, constraints) {
            final half = (constraints.maxWidth - 10) / 2;
            return Wrap(spacing: 10, runSpacing: 10, children: [
              for (final (label, subtitle, icon) in _focuses)
                SizedBox(width: half, child: _FocusChoice(
                  label: label, subtitle: subtitle, icon: icon,
                  selected: label == _focus,
                  onTap: () => setState(() => _focus = label),
                )),
            ]);
          }),
          const SizedBox(height: 14),
          const MintTickInfoText(
            'Intraday and delivery describe trade holding styles. Equity is '
            'the supported asset class. Strategy configuration is a separate step.'),
        ],
        if (_step == 2) ...[
          const _FieldLabel('How do you approach risk?'),
          const SizedBox(height: 12),
          Wrap(spacing: 9, runSpacing: 9, children: [
            for (final label in ['Cautious', 'Balanced', 'Aggressive'])
              ChoiceChip(
                key: ValueKey('room-risk-${label.toLowerCase()}'),
                label: Text(label),
                selected: label == _risk,
                onSelected: (_) => setState(() => _risk = label),
              ),
          ]),
          const SizedBox(height: 15),
          const MintTickInfoText(
            'This is a preference, not a suitability assessment or an '
            'enforced risk policy. Live order risk is always checked on the server.'),
        ],
      ]),
    );
  }

  Widget _buildPlan(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme;
    final draft = _draft;
    final limits = IllustrativeRoomLimits.forDraft(draft);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Your proposed plan.', style: style.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w800)),
      const SizedBox(height: 6),
      Text('Review your ${draft.mode == 'LIVE' ? 'Live' : 'Paper'} mode plan '
          'before saving. No trading starts automatically.',
        style: style.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
      const SizedBox(height: 18),
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(children: [
            MintTickIconTile(Icons.psychology_outlined),
            SizedBox(width: 10),
            Expanded(child: Text('MintTick Mind · Plan preview',
              style: TextStyle(fontWeight: FontWeight.w800))),
          ]),
          const SizedBox(height: 7),
          Text('Examples only — not model-generated recommendations',
            style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
          const SizedBox(height: 14),
          _Pair('Room', draft.name),
          _Pair('Approach', draft.focus),
          _Pair('Mode', draft.mode),
          _Pair('Planned budget', formatPaperRupees(draft.paperBudget)),
          _Pair('Risk preference', draft.riskPreference),
        ],
      )),
      const SizedBox(height: 22),
      const MintTickSectionHeading('Illustrative risk boundaries'),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(child: MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Example daily loss cap',
              style: style.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
            const SizedBox(height: 8),
            Text(formatPaperRupees(limits.daily),
              style: style.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
          ],
        ))),
        const SizedBox(width: 9),
        Expanded(child: MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Per-trade risk example',
              style: style.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
            const SizedBox(height: 8),
            Text(formatPaperRupees(limits.trade),
              style: style.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
          ],
        ))),
      ]),
      const SizedBox(height: 10),
      MintTickSurface(child: _Pair('Example maximum position',
          formatPaperRupees(limits.position))),
      const SizedBox(height: 16),
      MintTickInfoText(
        draft.mode == 'PAPER'
          ? 'These risk examples are not enforced. Saving creates a '
            'customer-owned Paper Room and credits its planned budget '
            'in virtual cash. No real money or orders are used. '
            'Paper order execution remains separately controlled.'
          : 'These risk examples are not enforced. Saving creates a '
            'Live Room plan, not authorization to access or trade '
            'with any broker. Real-money orders remain disabled.',
        icon: Icons.shield_outlined,
      ),
      const SizedBox(height: 12),
      CheckboxListTile(
        key: const ValueKey('room-draft-acknowledge'),
        contentPadding: EdgeInsets.zero,
        value: _acknowledged,
        controlAffinity: ListTileControlAffinity.leading,
        onChanged: (v) => setState(() => _acknowledged = v ?? false),
        title: Text('I understand this saves a Trading Room plan, not an '
            'authorization to begin Paper or Live trading.',
          style: style.bodySmall),
      ),
    ]);
  }
}

class _ProgressSegment extends StatelessWidget {
  const _ProgressSegment({required this.active});
  final bool active;
  @override
  Widget build(BuildContext context) => AnimatedContainer(
    duration: MediaQuery.of(context).disableAnimations
        ? Duration.zero : kMintTickMotion,
    height: 5,
    decoration: BoxDecoration(
      color: active ? Theme.of(context).colorScheme.primary
          : Theme.of(context).colorScheme.outlineVariant,
      borderRadius: BorderRadius.circular(4),
    ),
  );
}

class _FieldLabel extends StatelessWidget {
  const _FieldLabel(this.title);
  final String title;
  @override
  Widget build(BuildContext context) =>
      Text(title, style: Theme.of(context).textTheme.titleSmall
          ?.copyWith(fontWeight: FontWeight.w700));
}

class _FocusChoice extends StatelessWidget {
  const _FocusChoice({
    required this.label, required this.subtitle, required this.icon,
    required this.selected, required this.onTap,
  });
  final String label;
  final String subtitle;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AnimatedContainer(
      duration: MediaQuery.of(context).disableAnimations
          ? Duration.zero : kMintTickMotion,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        color: selected ? scheme.primary.withValues(alpha: 0.12)
            : scheme.surfaceContainerLow,
        border: Border.all(
          color: selected ? scheme.primary : scheme.outlineVariant,
          width: selected ? 1.4 : 1,
        ),
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(14),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 89),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Icon(icon, size: 18,
                      color: selected ? scheme.primary : scheme.onSurface),
                    const SizedBox(width: 5),
                    Expanded(child: Text(label, maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w800))),
                  ]),
                  const SizedBox(height: 7),
                  Text(subtitle,
                    style: Theme.of(context).textTheme.bodySmall
                        ?.copyWith(color: scheme.onSurfaceVariant)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Pair extends StatelessWidget {
  const _Pair(this.label, this.value);
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(children: [
        Expanded(child: Text(label, style: Theme.of(context).textTheme.bodySmall
            ?.copyWith(color: scheme.onSurfaceVariant))),
        const SizedBox(width: 8),
        Flexible(child: Text(value, textAlign: TextAlign.right,
          style: Theme.of(context).textTheme.bodySmall
              ?.copyWith(fontWeight: FontWeight.w800))),
      ]),
    );
  }
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
    this.marketRepository,
    this.onBroker,
    this.onRoomChanged,
    super.key,
  });

  final TradingRoomDraft room;
  final HttpCustomerRoomRepository? repository;
  final HttpCustomerExecutionRepository? executionRepository;
  final HttpCustomerPaperTradingRepository? paperTradingRepository;
  final HttpCustomerMarketRepository? marketRepository;
  final VoidCallback? onBroker;
  final Future<void> Function()? onRoomChanged;

  @override
  State<MintTickRoomDetailPage> createState() => _MintTickRoomDetailPageState();
}

class _MintTickRoomDetailPageState extends State<MintTickRoomDetailPage> {
  String _tab = 'Overview';
  CustomerPaperWallet? _wallet;
  CustomerExecutionReadiness? _readiness;
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
      if (mounted) setState(() => _readiness = data);
    } catch (_) {
      if (mounted) setState(() => _readiness = null);
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

  static String _explainBlocker(String code) => switch (code) {
    'PAPER_WALLET_NOT_FUNDED' => 'Fund a virtual Paper wallet',
    'CUSTOMER_PAPER_ORDER_ENGINE_NOT_ENABLED' => 'Paper order engine pending',
    'ANGEL_ONE_NOT_CONNECTED' => 'Connect your Angel One broker',
    'BROKER_NOT_CONNECTED' => 'Connect Angel One or Upstox',
    'ROOM_BROKER_ASSIGNMENT_NOT_CONFIGURED' =>
      'Choose a specific broker account for this room',
    'BROKER_APPROVED_HOSTING_NOT_VERIFIED' => 'Broker-approved hosting pending',
    'REGISTERED_STATIC_IP_NOT_VERIFIED' => 'Registered IP approval pending',
    'ACCOUNT_WIDE_RISK_AND_RECONCILIATION_NOT_ENABLED' =>
      'Account risk limits and reconciliation pending',
    'EXPLICIT_LIVE_EXECUTION_CONSENT_NOT_CONFIGURED' =>
      'Separate Live authorization required',
    'EXECUTION_DISABLED' => 'Execution disabled for safety',
    _ => 'Additional verification required',
  };

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final room = widget.room;
    final funded = room.mode == 'PAPER' &&
        (_wallet != null || room.status == 'PAPER_FUNDED');
    final limits = IllustrativeRoomLimits.forDraft(room);
    return MintTickCustomerScaffold(showHeader: false, children: [
      Align(alignment: Alignment.centerLeft, child: TextButton.icon(
        onPressed: () => Navigator.of(context).pop(),
        icon: const Icon(Icons.arrow_back_rounded),
        label: const Text('Trading Rooms'),
      )),
      const SizedBox(height: 10),
      Row(children: [
        Expanded(child: Text(room.name,
          style: Theme.of(context).textTheme.headlineSmall
              ?.copyWith(fontWeight: FontWeight.w800))),
        MintTickStatusLabel(funded ? 'PAPER FUNDED' : 'DRAFT'),
      ]),
      const SizedBox(height: 7),
      Text('${room.mode} · ${room.focus} · ${formatPaperRupees(room.paperBudget)} planned',
        style: Theme.of(context).textTheme.bodyMedium
            ?.copyWith(color: scheme.onSurfaceVariant)),
      const SizedBox(height: 17),
      if (_busy)
        const Padding(padding: EdgeInsets.symmetric(vertical: 8),
            child: LinearProgressIndicator()),
      if (_error != null) ...[
        MintTickInfoText(_error!, icon: Icons.error_outline_rounded),
        const SizedBox(height: 8),
        if (funded)
          OutlinedButton.icon(
            onPressed: _loadWallet,
            icon: const Icon(Icons.refresh_rounded),
            label: const Text('Retry Paper wallet'),
          ),
        const SizedBox(height: 13),
      ],
      if (room.mode == 'PAPER' && funded && _wallet != null) ...[
        MintTickSurface(accent: true, child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            const MintTickEyebrow('Virtual PAPER cash · Not real money'),
            const SizedBox(height: 8),
            Text(_formatPaise(_wallet!.availableCashPaise),
              key: const ValueKey('paper-wallet-cash'),
              style: Theme.of(context).textTheme.headlineMedium
                  ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 11),
            _Pair('Initial allocation', _formatPaise(_wallet!.initialCashPaise)),
            _Pair('Reserved for orders', _formatPaise(_wallet!.reservedCashPaise)),
            const _Pair('Automated execution', 'Disabled'),
          ],
        )),
        const SizedBox(height: 17),
      ],
      if (room.mode == 'PAPER' && !funded) ...[
        MintTickSurface(accent: true, child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            const MintTickEyebrow('Optional next step'),
            const SizedBox(height: 10),
            Text('Prepare a virtual Paper wallet',
              style: Theme.of(context).textTheme.titleMedium
                  ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 7),
            Text('Allocate your planned amount as simulated funds. '
                'No broker account or real money is involved.',
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: scheme.onSurfaceVariant)),
            const SizedBox(height: 13),
            SizedBox(width: double.infinity, child: FilledButton.icon(
              key: const ValueKey('activate-paper-wallet'),
              onPressed: _busy || widget.repository == null ? null : _activate,
              icon: const Icon(Icons.account_balance_wallet_outlined),
              label: const Text('Fund virtual Paper wallet'),
            )),
          ],
        )),
        const SizedBox(height: 17),
      ],
      if (room.mode == 'PAPER' && _wallet != null &&
          widget.paperTradingRepository != null && widget.marketRepository != null) ...[
        MintTickManualPaperTradingPanel(
          roomId: room.id,
          wallet: _wallet!,
          orders: widget.paperTradingRepository!,
          market: widget.marketRepository!,
          onWalletChanged: () async {
            await _loadWallet();
            await _loadReadiness();
          },
        ),
        const SizedBox(height: 17),
      ],
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const MintTickIconTile(Icons.verified_user_outlined),
            const SizedBox(width: 11),
            Expanded(child: Text('Automated / LIVE readiness',
              style: Theme.of(context).textTheme.titleMedium
                  ?.copyWith(fontWeight: FontWeight.w800))),
            const MintTickStatusLabel('NOT ACTIVE'),
          ]),
          const SizedBox(height: 11),
          Text(_readiness == null
              ? 'Automated / LIVE readiness has not been verified. '
                'This does not determine manual Paper order status.'
              : 'Server-checked ${_readiness!.mode} automated / LIVE readiness. '
                'Manual virtual Paper fills are governed separately above.',
            style: Theme.of(context).textTheme.bodySmall
                ?.copyWith(color: scheme.onSurfaceVariant)),
          if (_readiness != null) ...[
            const SizedBox(height: 10),
            for (final blocker in _readiness!.blockers.take(5))
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Row(children: [
                  Icon(Icons.lock_outline_rounded, color: scheme.onSurfaceVariant, size: 15),
                  const SizedBox(width: 7),
                  Expanded(child: Text(
                    _explainBlocker(blocker),
                    style: Theme.of(context).textTheme.bodySmall,
                  )),
                ]),
              ),
          ],
          const SizedBox(height: 13),
          Row(children: [
            OutlinedButton.icon(
              onPressed: widget.executionRepository == null
                  ? null : _loadReadiness,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Check readiness'),
            ),
          ]),
          if (room.mode == 'LIVE' && widget.onBroker != null) ...[
            const SizedBox(height: 8),
            SizedBox(width: double.infinity, child: FilledButton.icon(
              onPressed: widget.onBroker,
              icon: const Icon(Icons.account_balance_outlined),
              label: const Text('Connect or view your broker'),
            )),
          ],
        ],
      )),
      const SizedBox(height: 18),
      SegmentedButton<String>(
        key: const ValueKey('room-detail-tabs'),
        showSelectedIcon: false,
        segments: [
          const ButtonSegment(value: 'Overview', label: Text('Overview')),
          if (room.mode == 'PAPER' && _wallet != null &&
              widget.paperTradingRepository != null)
            const ButtonSegment(value: 'Performance', label: Text('Performance')),
          const ButtonSegment(value: 'Plan', label: Text('Plan')),
          const ButtonSegment(value: 'Activity', label: Text('Activity')),
        ],
        selected: {_tab},
        onSelectionChanged: (value) => setState(() => _tab = value.first),
      ),
      const SizedBox(height: 18),
      if (_tab == 'Overview') ...[
        MintTickSurface(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(children: [
              MintTickIconTile(Icons.psychology_outlined),
              SizedBox(width: 12),
              Expanded(child: Text('MintTick Mind',
                style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16))),
              MintTickStatusLabel('NOT RUNNING'),
            ]),
            const SizedBox(height: 12),
            Text('The ${room.mode} room is saved. No automated strategy '
                'or order execution is active.',
              style: Theme.of(context).textTheme.bodyMedium
                  ?.copyWith(color: scheme.onSurfaceVariant)),
          ],
        )),
        const SizedBox(height: 18),
        const MintTickSectionHeading('Room overview'),
        const SizedBox(height: 8),
        MintTickSurface(child: Column(children: [
          _Pair('Focus', room.focus),
          _Pair('Risk preference', room.riskPreference),
          _Pair('Execution', room.mode == 'LIVE'
              ? 'Live broker authorization required'
              : _wallet == null ? 'Unfunded' : _wallet!.halted
                  ? 'Paused · Manual only' : 'Enabled · Manual only'),
          _Pair('Open positions', room.mode == 'PAPER' && _wallet != null
              ? 'See Performance and Paper positions above'
              : 'Not available until broker authorization'),
        ])),
      ] else if (_tab == 'Performance' && room.mode == 'PAPER' &&
          _wallet != null && widget.paperTradingRepository != null) ...[
        MintTickPaperPerformancePanel(
          roomId: room.id,
          wallet: _wallet!,
          repository: widget.paperTradingRepository!,
        ),
      ] else if (_tab == 'Plan') ...[
        const MintTickSectionHeading('Illustrative risk guardrails'),
        const SizedBox(height: 8),
        MintTickSurface(child: Column(children: [
          _Pair('Daily example loss cap', formatPaperRupees(limits.daily)),
          _Pair('Example trade risk', formatPaperRupees(limits.trade)),
          _Pair('Example position budget', formatPaperRupees(limits.position)),
        ])),
        const SizedBox(height: 12),
        const MintTickInfoText(
          'Examples are not enforced risk limits. MintTick Mind must '
          'obtain explicit approval before executable strategies begin.',
        ),
      ] else ...[
        const MintTickSectionHeading('Account activity'),
        const SizedBox(height: 8),
        if (_wallet != null && _events.isEmpty)
          const MintTickInfoText('No cash events returned from this room.')
        else if (_events.isNotEmpty)
          for (final event in _events) ...[
            MintTickSurface(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Initial virtual allocation',
                  style: TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                _Pair('Amount', _formatPaise(event.cashDeltaPaise)),
                _Pair('Balance after', _formatPaise(event.cashAfterPaise)),
              ],
            )),
            const SizedBox(height: 8),
          ]
        else
          const MintTickInfoText(
            'Review the manual Paper trading panel for order receipts and positions.',
            icon: Icons.history_rounded,
          ),
      ],
      const SizedBox(height: 18),
      const MintTickInfoText(
        'Paper and Live remain separate. A funded Paper wallet is '
        'simulated cash only; it does not enable real orders or '
        'automated trading.',
        icon: Icons.shield_outlined,
      ),
    ]);
  }
}
