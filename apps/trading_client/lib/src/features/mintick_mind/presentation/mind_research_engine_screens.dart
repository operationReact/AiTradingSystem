// Prototype UI source may use existing explicit string concatenation for compact mock labels.
// ignore_for_file: prefer_interpolation_to_compose_strings
part of 'mind_workspace_page.dart';

/// Owner-visible planning cockpit for optional open-source research engines.
/// The content is static and intentionally has no provider, broker or job API.
extension _MindResearchEngineScreens on _MindWorkspaceState {
  Widget _realResearchEnginesScreen() {
    final repository = widget.researchEngineRepository!;
    return _surface('Research Engines Lab',
        'Server engine registry · an engine is connected only with its own runtime evidence', [
      FutureBuilder<MindEngineRegistry>(
        future: repository.load(),
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const MindPanel(child: Text('Loading engine registry…',
                style: TextStyle(color: MindPalette.muted, fontSize: 12)));
          }
          final r = snap.data;
          if (snap.hasError || r == null) {
            return const MindPanel(key: ValueKey('mind-engines-error'), child: Text(
                'Engine registry unavailable. No engine is shown as available.',
                style: TextStyle(color: MindPalette.red, fontSize: 12)));
          }
          return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            MindPanel(key: const ValueKey('mind-engines-summary'), highlight: true, child: Column(children: [
              _value('Engines in registry', r.total.toString()),
              _value('Connected', r.connected.toString(),
                  color: r.connected == 0 ? MindPalette.yellow : MindPalette.mint),
              _value('Enabled for any Mind run', r.engines.where((e) => e.enabled).length.toString()),
              _value('Registry generated', r.generatedAt.toUtc().toIso8601String().substring(0, 16) + 'Z'),
            ])),
            const SizedBox(height: 12),
            for (final e in r.engines)
              Padding(padding: const EdgeInsets.only(bottom: 8), child: MindPanel(
                key: ValueKey('mind-engine-${e.id}'),
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  Row(children: [
                    Expanded(child: Text(e.name, style: const TextStyle(color: MindPalette.text,
                        fontSize: 13, fontWeight: FontWeight.w800))),
                    MindTag(e.status.replaceAll('_', ' '),
                        color: e.connected ? MindPalette.mint : MindPalette.yellow),
                  ]),
                  const SizedBox(height: 4),
                  Text(e.category, style: const TextStyle(color: MindPalette.muted, fontSize: 11)),
                  const SizedBox(height: 6),
                  _value('Enabled', e.enabled ? 'Yes' : 'No', color: MindPalette.muted),
                  _value('Dependencies', e.dependencies.join(' · ')),
                  _value('Missing', e.missing, color: MindPalette.yellow),
                ]))),
          ]);
        },
      ),
    ]);
  }

  Widget researchEnginesScreen() {
    if (widget.researchEngineRepository != null) return _realResearchEnginesScreen();
    final visible = MindResearchEngineFixtures.all.where(
      (engine) => _researchEngineFilter == 'All' ||
          engine.category == _researchEngineFilter,
    ).toList();
    final selected = MindResearchEngineFixtures.byId(_selectedResearchEngine);

    return _surface(
      'Research Engines Lab',
      'Five optional frameworks · integration roadmap, not running services',
      [
        MindPanel(
          highlight: true,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Wrap(
                spacing: 8,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Icon(Icons.construction_outlined,
                      color: MindPalette.yellow, size: 22),
                  MindTag('PLANNED · NOT CONNECTED',
                      color: MindPalette.yellow),
                  MindTag('NO BROKER / ORDER AUTHORITY',
                      color: MindPalette.purple),
                ],
              ),
              const SizedBox(height: 11),
              const Text(
                'This is an implementation checklist, not a running trading engine.',
                style: TextStyle(
                  color: MindPalette.text,
                  fontWeight: FontWeight.w700,
                  fontSize: 14,
                ),
              ),
              const SizedBox(height: 7),
              const Text(
                'All five tools are uninstalled research candidates or references. '
                'No performance result, broker connection, model inference, '
                'backtest or external API is activated by this screen. '
                'Each requires licensing, data and validation gates.',
                style: TextStyle(color: MindPalette.muted, fontSize: 12),
              ),
            ],
          ),
        ),
        const SizedBox(height: 13),
        _metricGrid([
          ('Reviewed repositories', '5', 'Source-level assessment'),
          ('Connected engines', '0', 'No production integration'),
          ('First pilot', 'VectorBT', 'After prerequisite review'),
          ('Live order authority', 'NONE', 'Java risk gate unchanged'),
        ]),
        const SizedBox(height: 14),
        _section('Integration candidates', Icons.account_tree_outlined, [
          _chips(
            ['All', 'Pilot', 'Validator', 'Advanced', 'Reference', 'Patterns'],
            _researchEngineFilter,
            (value) => _updateMind(() => _researchEngineFilter = value),
          ),
          const SizedBox(height: 12),
          for (final engine in visible)
            Padding(
              padding: const EdgeInsets.only(bottom: 9),
              child: KeyedSubtree(
                key: ValueKey('mind-research-engine-' + engine.id),
                child: _tile(
                  engine.name,
                  engine.category + ' · ' + engine.role,
                  Icons.science_outlined,
                  trailing: 'PLANNED',
                  color: selected.id == engine.id
                      ? MindPalette.mint
                      : MindPalette.yellow,
                  tap: () => _updateMind(
                    () => _selectedResearchEngine = engine.id,
                  ),
                ),
              ),
            ),
          if (visible.isEmpty)
            _empty('No research engines match this filter.'),
        ]),
        _section(
          'Selected candidate · ' + selected.name,
          Icons.fact_check_outlined,
          [
            MindPanel(
              highlight: true,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: MindTag('PLACEHOLDER · NO DATA CONNECTED',
                        color: MindPalette.yellow),
                  ),
                  const SizedBox(height: 12),
                  _value('Role', selected.role),
                  _value('Decision', selected.decision),
                  _value('Implementation PRs', selected.plannedPrs),
                  _value('Licence review', selected.license,
                      color: MindPalette.yellow),
                  _value('Installed/runtime status', 'NOT CONNECTED',
                      color: MindPalette.yellow),
                  _value('Performance evidence', 'NOT AVAILABLE',
                      color: MindPalette.yellow),
                  _value('Execution permission', 'NONE',
                      color: MindPalette.red),
                ],
              ),
            ),
          ],
        ),
        _section('What must be completed first', Icons.rule_outlined, [
          MindPanel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var i = 0; i < selected.prerequisites.length; i++)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 11),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Icon(
                          Icons.radio_button_unchecked,
                          color: MindPalette.yellow,
                          size: 18,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            selected.prerequisites[i],
                            style: const TextStyle(
                                color: MindPalette.text, fontSize: 12),
                          ),
                        ),
                      ],
                    ),
                  ),
                const Text(
                  'All checklist entries are planned; no completion is implied.',
                  style: TextStyle(
                      color: MindPalette.muted, fontSize: 11),
                ),
              ],
            ),
          ),
        ]),
        _section('Upstream source files to evaluate', Icons.code_outlined, [
          MindPanel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final path in selected.sourceFiles)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 9),
                    child: SelectableText(
                      path,
                      style: const TextStyle(
                        color: MindPalette.muted,
                        fontSize: 11,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ]),
        _section('MintTick implementation touchpoints', Icons.hub_outlined, [
          MindPanel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final path in selected.mintTickTouchpoints)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 9),
                    child: SelectableText(
                      path,
                      style: const TextStyle(
                        color: MindPalette.muted,
                        fontSize: 11,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ]),
        _section('Affected MintTick Mind screens', Icons.grid_view_outlined, [
          for (final id in selected.targetScreens)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: _tile(
                _label(id),
                'Open existing screen · integration placeholder',
                Icons.open_in_new_outlined,
                route: id,
              ),
            ),
        ]),
        _section('Research-only next steps', Icons.science_outlined, [
          _tile(
            'Research & Backtesting',
            'Existing experiment workflows; no external jobs active',
            Icons.science_outlined,
            route: 'research',
          ),
          const SizedBox(height: 8),
          _tile(
            'Strategy Universe',
            '232 strategy templates, not 232 validated algorithms',
            Icons.layers_outlined,
            route: 'strategies',
          ),
          const SizedBox(height: 8),
          _tile(
            'Screen & Strategy Coverage',
            'Track route and placeholder availability',
            Icons.fact_check_outlined,
            route: 'coverage',
          ),
        ]),
      ],
    );
  }
}
