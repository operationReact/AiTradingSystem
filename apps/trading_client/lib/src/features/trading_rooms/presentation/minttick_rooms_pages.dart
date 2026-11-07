import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../domain/trading_room_draft.dart';
import 'minttick_customer_widgets.dart';

class MintTickTradingRoomsPage extends StatelessWidget {
  const MintTickTradingRoomsPage({
    required this.store,
    required this.onCreate,
    required this.onOpen,
    super.key,
  });

  final TradingRoomDraftStore store;
  final VoidCallback onCreate;
  final ValueChanged<TradingRoomDraft> onOpen;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: store,
      builder: (context, _) => MintTickCustomerScaffold(
        showHeader: false,
        children: [
          const SizedBox(height: 19),
          const MintTickEyebrow('Your workspaces'),
          const SizedBox(height: 7),
          Text('Trading Rooms', style: Theme.of(context).textTheme.headlineSmall
              ?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 7),
          Text('Build one room for each goal. Plans are saved in this app '
              'session, not on the server.',
            style: Theme.of(context).textTheme.bodyMedium
                ?.copyWith(color: scheme.onSurfaceVariant)),
          const SizedBox(height: 20),
          SizedBox(width: double.infinity, child: FilledButton.icon(
            key: const ValueKey('trading-rooms-create'),
            onPressed: onCreate,
            icon: const Icon(Icons.add_rounded),
            label: const Text('Create a Trading Room'),
          )),
          const SizedBox(height: 22),
          const MintTickSectionHeading('Your local drafts'),
          const SizedBox(height: 10),
          if (store.drafts.isEmpty)
            const MintTickInfoText(
              'No rooms yet. Your first room begins as a local preview. '
              'No paper engine or live broker orders are started.',
              icon: Icons.layers_outlined,
            ),
          for (final room in store.drafts) ...[
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
                          style: const TextStyle(fontWeight: FontWeight.w800)),
                        const SizedBox(height: 4),
                        Text('${room.focus} · ${formatPaperRupees(room.paperBudget)}',
                          style: Theme.of(context).textTheme.bodySmall),
                      ],
                    )),
                    const SizedBox(width: 5),
                    const MintTickStatusLabel('DRAFT'),
                    const Icon(Icons.chevron_right_rounded, size: 18),
                  ]),
                ),
              ),
            ),
            const SizedBox(height: 9),
          ],
          const SizedBox(height: 17),
          const MintTickInfoText(
            'Trading Rooms are virtual workspaces, not separate brokerage '
            'accounts. Live operation will require server-side capital '
            'reservation, broker reconciliation, and approved risk limits.',
            icon: Icons.shield_outlined,
          ),
        ],
      ),
    );
  }
}

class MintTickRoomCreationPage extends StatefulWidget {
  const MintTickRoomCreationPage({super.key});

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
  bool _review = false;
  bool _acknowledged = false;

  static const _focuses = <(String, String, IconData)>[
    ('Intraday', 'Same-day trading', Icons.bolt_outlined),
    ('Swing', 'Days to weeks', Icons.trending_up_rounded),
    ('Long-term', 'Longer horizon', Icons.layers_outlined),
    ('Help me choose', 'Explore approaches', Icons.psychology_outlined),
  ];

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
    if (!_review) {
      if (!(_formKey.currentState?.validate() ?? false)) return;
      setState(() => _review = true);
      _top();
    } else if (_acknowledged) {
      // This is a local design draft. No backend entity or trade is created.
      Navigator.of(context).pop(_draft);
    }
  }

  void _back() {
    if (_review) {
      setState(() => _review = false);
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
                      Text(_review ? 'Review Mantic Mind plan' : 'Create a Trading Room',
                        style: style.titleSmall?.copyWith(fontWeight: FontWeight.w800)),
                      Text('Step ${_review ? 2 : 1} of 2 · Paper preview',
                        style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
                    ],
                  )),
                  const MintTickStatusLabel('PAPER'),
                ]),
                const SizedBox(height: 13),
                Row(children: [
                  Expanded(child: _ProgressSegment(active: true)),
                  const SizedBox(width: 8),
                  Expanded(child: _ProgressSegment(active: _review)),
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
                  child: _review
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
              onPressed: _review && !_acknowledged ? null : _continue,
              child: Text(_review ? 'Save local preview draft' : 'Preview Mantic Mind plan'),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDetails(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme;
    return Form(
      key: _formKey,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Start with your goal.', style: style.headlineSmall
            ?.copyWith(fontWeight: FontWeight.w800)),
        const SizedBox(height: 6),
        Text("A few choices. We'll handle the technical planning.",
          style: style.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 21),
        const _FieldLabel('Room name'),
        const SizedBox(height: 8),
        TextFormField(
          key: const ValueKey('room-name-input'),
          controller: _name,
          maxLength: 38,
          textInputAction: TextInputAction.next,
          decoration: const InputDecoration(
            hintText: 'e.g. Morning opportunities', counterText: ''),
          validator: (value) => (value?.trim().isEmpty ?? true)
              ? 'Give your room a name' : null,
        ),
        const SizedBox(height: 18),
        const _FieldLabel("What's your focus?"),
        const SizedBox(height: 10),
        LayoutBuilder(builder: (context, constraints) {
          final half = (constraints.maxWidth - 10) / 2;
          return Wrap(spacing: 10, runSpacing: 10, children: [
            for (final (label, subtitle, icon) in _focuses)
              SizedBox(
                width: half,
                child: _FocusChoice(
                  label: label, subtitle: subtitle, icon: icon,
                  selected: label == _focus,
                  onTap: () => setState(() => _focus = label),
                ),
              ),
          ]);
        }),
        const SizedBox(height: 19),
        const _FieldLabel('Virtual paper budget (₹)'),
        const SizedBox(height: 8),
        TextFormField(
          key: const ValueKey('room-budget-input'),
          controller: _paperBudget,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: const InputDecoration(
            prefixText: '₹  ', hintText: '20000'),
          validator: (value) {
            final n = int.tryParse(value?.trim() ?? '');
            if (n == null || n < 1000 || n > 10000000) {
              return 'Use a virtual budget between ₹1,000 and ₹1 crore';
            }
            return null;
          },
        ),
        const SizedBox(height: 5),
        Text('Simulated budget only. No deposit or broker connection.',
          style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 19),
        const _FieldLabel('How do you approach risk?'),
        const SizedBox(height: 9),
        Wrap(spacing: 7, runSpacing: 7, children: [
          for (final label in ['Cautious', 'Balanced', 'Aggressive'])
            ChoiceChip(
              key: ValueKey('room-risk-${label.toLowerCase()}'),
              label: Text(label),
              selected: label == _risk,
              onSelected: (_) => setState(() => _risk = label),
            ),
        ]),
        const SizedBox(height: 8),
        Text('This is a preference, not a suitability assessment.',
          style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 18),
        const MintTickInfoText(
          'Mantic Mind may recommend fewer trades or none. '
          'Automation never overrides approved risk limits.',
        ),
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
      Text('Understand the boundaries before keeping this draft.',
        style: style.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
      const SizedBox(height: 18),
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(children: [
            MintTickIconTile(Icons.psychology_outlined),
            SizedBox(width: 10),
            Expanded(child: Text('Mantic Mind · Plan preview',
              style: TextStyle(fontWeight: FontWeight.w800))),
          ]),
          const SizedBox(height: 7),
          Text('Examples only — not model-generated recommendations',
            style: style.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
          const SizedBox(height: 14),
          _Pair('Room', draft.name),
          _Pair('Approach', draft.focus),
          _Pair('Paper budget', formatPaperRupees(draft.paperBudget)),
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
            Text('Daily paper loss cap',
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
      const MintTickInfoText(
        'This is a UI-only sample plan. These figures are not enforced '
        'by a risk engine, and no loss cap guarantees realized losses. '
        'No server room or order is created.',
        icon: Icons.shield_outlined,
      ),
      const SizedBox(height: 12),
      CheckboxListTile(
        key: const ValueKey('room-draft-acknowledge'),
        contentPadding: EdgeInsets.zero,
        value: _acknowledged,
        controlAffinity: ListTileControlAffinity.leading,
        onChanged: (v) => setState(() => _acknowledged = v ?? false),
        title: Text('I understand this saves a temporary local draft only. '
            'It does not enable paper or live trading.',
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

class MintTickRoomDetailPage extends StatefulWidget {
  const MintTickRoomDetailPage({required this.room, super.key});
  final TradingRoomDraft room;

  @override
  State<MintTickRoomDetailPage> createState() => _MintTickRoomDetailPageState();
}

class _MintTickRoomDetailPageState extends State<MintTickRoomDetailPage> {
  String _tab = 'Overview';

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final room = widget.room;
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
        const MintTickStatusLabel('DRAFT'),
      ]),
      const SizedBox(height: 7),
      Text('${room.focus} · ${formatPaperRupees(room.paperBudget)} virtual allocation',
        style: Theme.of(context).textTheme.bodyMedium
            ?.copyWith(color: scheme.onSurfaceVariant)),
      const SizedBox(height: 18),
      SegmentedButton<String>(
        key: const ValueKey('room-detail-tabs'),
        showSelectedIcon: false,
        segments: const [
          ButtonSegment(value: 'Overview', label: Text('Overview')),
          ButtonSegment(value: 'Plan', label: Text('Plan')),
          ButtonSegment(value: 'Activity', label: Text('Activity')),
        ],
        selected: {_tab},
        onSelectionChanged: (value) => setState(() => _tab = value.first),
      ),
      const SizedBox(height: 18),
      if (_tab == 'Overview') ...[
        MintTickSurface(accent: true, child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(children: [
              MintTickIconTile(Icons.psychology_outlined),
              SizedBox(width: 12),
              Expanded(child: Text('Mantic Mind',
                style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16))),
              MintTickStatusLabel('NOT RUNNING'),
            ]),
            const SizedBox(height: 12),
            Text('This room is a local design draft. Mantic Mind is not '
                'scanning markets, making trade decisions or executing orders.',
              style: Theme.of(context).textTheme.bodyMedium
                  ?.copyWith(color: scheme.onSurfaceVariant)),
          ],
        )),
        const SizedBox(height: 19),
        const MintTickSectionHeading('Room overview'),
        const SizedBox(height: 8),
        MintTickSurface(child: Column(children: [
          _Pair('Focus', room.focus),
          _Pair('Risk preference', room.riskPreference),
          const _Pair('Execution', 'Unavailable · Design draft'),
          const _Pair('Open positions', 'No connected trading engine'),
        ])),
      ] else if (_tab == 'Plan') ...[
        const MintTickSectionHeading('Example paper guardrails'),
        const SizedBox(height: 8),
        MintTickSurface(child: Column(children: [
          _Pair('Daily example loss cap', formatPaperRupees(limits.daily)),
          _Pair('Example trade risk', formatPaperRupees(limits.trade)),
          _Pair('Example position budget', formatPaperRupees(limits.position)),
        ])),
        const SizedBox(height: 12),
        const MintTickInfoText(
          'Illustrative values only. They are not configured or enforced '
          'on any backend. Live trading requires separate authorization.',
        ),
      ] else ...[
        const MintTickSectionHeading('Decisions & activity'),
        const SizedBox(height: 8),
        const MintTickInfoText(
          'No trading activity exists. This draft has not been connected '
          'to a strategy, market-data service, or execution engine.',
          icon: Icons.history_rounded,
        ),
      ],
      const SizedBox(height: 18),
      const MintTickInfoText(
        'Automated trading controls will become available only when the '
        'room backend, risk engine, and relevant permissions are verified.',
        icon: Icons.shield_outlined,
      ),
    ]);
  }
}
