// Prototype UI source may use existing explicit string concatenation for compact mock labels.
// ignore_for_file: prefer_interpolation_to_compose_strings
import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../data/mind_fixtures.dart';
import '../data/mind_readiness_repository.dart';
import '../data/mind_evidence_repository.dart';
import '../data/mind_strategy_replay_repository.dart';
import '../data/mind_ma_walkforward_repository.dart';
import '../data/mind_prospective_cohort_repository.dart';
import '../data/mind_prospective_signals_repository.dart';
import '../data/mind_prospective_outcomes_repository.dart';
import '../data/mind_prospective_evidence_completeness_repository.dart';
import 'mind_evidence_ledger_panel.dart';
import '../data/mind_agent_fixtures.dart';
import '../data/mind_research_engine_fixtures.dart';
import 'mind_widgets.dart';
import 'mintick_mind_page.dart';

part 'mind_analytics_screens.dart';
part 'mind_investment_screens.dart';
part 'mind_system_screens.dart';
part 'mind_research_screens.dart';
part 'mind_agent_screens.dart';
part 'mind_research_engine_screens.dart';

/// All prototype surfaces run inside one signed-in Flutter route. This
/// mock-only workspace does not import any broker, API or execution repository.
class MindScreenDescriptor {
  const MindScreenDescriptor(this.id, this.label, this.icon, this.group);
  final String id;
  final String label;
  final IconData icon;
  final String group;
}

abstract final class MintickMindScreens {
  static const all = <MindScreenDescriptor>[
    MindScreenDescriptor('app-home', 'MintTick Home', Icons.home_outlined, 'PRIMARY'),
    MindScreenDescriptor('overview', 'Mind Overview', Icons.psychology_outlined, 'PRIMARY'),
    MindScreenDescriptor('intelligence-hub', 'Decision Intelligence', Icons.hub_outlined, 'AGENTS'),
    MindScreenDescriptor('agent-council', 'Agent Council & Debate', Icons.groups_outlined, 'AGENTS'),
    MindScreenDescriptor('agent-evidence', 'Evidence Ledger', Icons.source_outlined, 'AGENTS'),
    MindScreenDescriptor('regime-weights', 'Regime Weight Studio', Icons.tune_outlined, 'AGENTS'),
    MindScreenDescriptor('signal-scorecard', 'Signal Scorecard', Icons.assessment_outlined, 'AGENTS'),
    MindScreenDescriptor('confidence-lab', 'Confidence Calibration', Icons.track_changes_outlined, 'AGENTS'),
    MindScreenDescriptor('shadow-arena', 'Shadow Trading Arena', Icons.science_outlined, 'AGENTS'),
    MindScreenDescriptor('institutional-flow', 'FII / DII Flow', Icons.account_balance_outlined, 'AGENTS'),
    MindScreenDescriptor('daily-verdict', 'Daily Market Verdict', Icons.event_note_outlined, 'AGENTS'),
    MindScreenDescriptor('culprit-catcher', 'Culprit Catcher', Icons.bug_report_outlined, 'AGENTS'),
    MindScreenDescriptor('trading-rooms', 'Trading Rooms', Icons.meeting_room_outlined, 'PRIMARY'),
    MindScreenDescriptor('strategies', 'Strategy Universe', Icons.layers_outlined, 'PRIMARY'),
    MindScreenDescriptor('strategy-detail', 'Strategy Detail', Icons.insights_outlined, 'PRIMARY'),
    MindScreenDescriptor('charts', 'Chart Intelligence', Icons.candlestick_chart_outlined, 'PRIMARY'),
    MindScreenDescriptor('decisions', 'Decision Explorer', Icons.track_changes_outlined, 'PRIMARY'),
    MindScreenDescriptor('decision-detail', 'Decision Anatomy', Icons.account_tree_outlined, 'PRIMARY'),
    MindScreenDescriptor('decision-lab', 'Decision Simulator', Icons.tune_outlined, 'PRIMARY'),
    MindScreenDescriptor('dependencies', 'Dependency Map', Icons.hub_outlined, 'PRIMARY'),
    MindScreenDescriptor('models', 'Model Laboratory', Icons.memory_outlined, 'RESEARCH'),
    MindScreenDescriptor('model-detail', 'Model Deep Dive', Icons.biotech_outlined, 'RESEARCH'),
    MindScreenDescriptor('research', 'Research & Backtesting', Icons.science_outlined, 'RESEARCH'),
    MindScreenDescriptor('research-engines', 'Research Engines Lab', Icons.extension_outlined, 'RESEARCH'),
    MindScreenDescriptor('experiment-detail', 'Experiment Results', Icons.assessment_outlined, 'RESEARCH'),
    MindScreenDescriptor('performance', 'Performance & Learning', Icons.show_chart, 'RESEARCH'),
    MindScreenDescriptor('journal', 'Decision Journal', Icons.history_outlined, 'RESEARCH'),
    MindScreenDescriptor('multibagger', 'Multibagger Discovery', Icons.diamond_outlined, 'RESEARCH'),
    MindScreenDescriptor('thesis-detail', 'Investment Thesis', Icons.auto_graph_outlined, 'RESEARCH'),
    MindScreenDescriptor('regimes', 'Market Regimes', Icons.public, 'RESEARCH'),
    MindScreenDescriptor('events', 'Events & Knowledge Graph', Icons.schema_outlined, 'RESEARCH'),
    MindScreenDescriptor('portfolio', 'Portfolio Digital Twin', Icons.pie_chart_outline, 'CAPITAL'),
    MindScreenDescriptor('risk', 'Risk Center', Icons.shield_outlined, 'CAPITAL'),
    MindScreenDescriptor('derivatives', 'Futures & Options', Icons.layers_rounded, 'CAPITAL'),
    MindScreenDescriptor('options-chain', 'Option Chain', Icons.table_chart_outlined, 'CAPITAL'),
    MindScreenDescriptor('option-builder', 'Strategy Payoff Builder', Icons.stacked_line_chart, 'CAPITAL'),
    MindScreenDescriptor('controls', 'Engine Control', Icons.settings_outlined, 'SYSTEM'),
    MindScreenDescriptor('assistant', 'Ask MintTick Mind', Icons.forum_outlined, 'SYSTEM'),
    MindScreenDescriptor('room-detail', 'Trading Room Detail', Icons.sensors_outlined, 'SYSTEM'),
    MindScreenDescriptor('design-gallery', 'Visual Mockup Gallery', Icons.grid_view_outlined, 'SYSTEM'),
    MindScreenDescriptor('design-preview', 'Clickable Design Mode', Icons.touch_app_outlined, 'SYSTEM'),
    MindScreenDescriptor('coverage', 'Screen & Strategy Coverage', Icons.fact_check_outlined, 'SYSTEM'),
    MindScreenDescriptor('settings', 'Preferences', Icons.tune_rounded, 'SYSTEM'),
  ];
  static const primary = ['overview','strategies','models','decisions','multibagger'];
}

class MintickMindWorkspacePage extends StatefulWidget {
  const MintickMindWorkspacePage({
    this.embeddedInProductShell = false,
    this.readinessRepository,
    this.evidenceRepository,
    this.strategyReplayRepository,
    this.walkForwardRepository,
    this.prospectiveRepository,
    this.prospectiveSignalsRepository,
    this.prospectiveOutcomesRepository,
    this.prospectiveEvidenceCompletenessRepository,
    super.key,
  });

  /// Keep the app's navigation visible instead of stacking two bottom bars.
  final bool embeddedInProductShell;

  /// A production app injects authenticated evidence, never fake balances.
  final MindReadinessRepository? readinessRepository;

  /// Null only in isolated prototype/golden tests. Production uses Cognito.
  final MindEvidenceRepository? evidenceRepository;
  final MindStrategyReplayRepository? strategyReplayRepository;
  final MindMaWalkForwardRepository? walkForwardRepository;
  final MindProspectiveCohortRepository? prospectiveRepository;
  final MindProspectiveSignalsRepository? prospectiveSignalsRepository;
  final MindProspectiveOutcomesRepository? prospectiveOutcomesRepository;
  final MindProspectiveEvidenceCompletenessRepository?
      prospectiveEvidenceCompletenessRepository;
  @override
  State<MintickMindWorkspacePage> createState() => _MindWorkspaceState();
}

class _MindWorkspaceState extends State<MintickMindWorkspacePage> {
  String _route = 'overview';
  String _agentCouncilSymbol = 'CDSL';
  String _agentCouncilView = 'All';
  String _agentEvidenceScope = 'All';
  String _agentRegime = 'Trending Up';
  String _agentSignalHorizon = 'Swing';
  String _agentCalMode = 'Swing';
  String _agentShadowTab = 'Strategies';
  String _agentShadowCompare = 'Baseline';
  String _agentFlowDate = '2026-11-09';
  String _agentVerdictDate = '2026-11-09';
  String _agentIncident = 'INC-102';
  String _selectedStrategy = 'STR-001';
  String _selectedModel = 'LightGBM';
  String _selectedDecision = 'TITAN';
  String _selectedCompany = 'TITAN';
  String _selectedExperiment = 'Momentum Breakout v3';
  String _selectedRoom = 'Intraday Room';
  String _instrument = 'RELIANCE';
  String _chartPeriod = '1D';
  String _chartStyle = 'Candlestick';
  String _family = 'All';
  String _strategyMode = 'All';
  String _strategyQuery = '';
  String _modelFilter = 'All';
  String _researchMode = 'Backtests';
  String _researchEngineFilter = 'All';
  String _selectedResearchEngine = 'VECTORBT';
  String _mbStage = 'All';
  String _decisionFilter = 'All';
  String _optionUnderlying = 'NIFTY';
  String _optionStructure = 'Bull Call Spread';
  String _reference = 'Mind Overview';
  double _strike = 25500;
  double _scenarioShock = -12;
  double _riskDaily = 2;
  double _riskPerStrategy = 25;
  double _modelWeight = 50;
  bool _dataFresh = true;
  bool _riskApproved = true;
  bool _emergencyStop = false;
  bool _graphMode = true;
  int _showStrategies = 25;
  int _chartSeed = 12;
  final Map<String,bool> _switches = {
    'Live monitoring': true,
    'Paper mode': true,
    'Shadow mode': true,
    'Allow new entries': false,
    'Auto execution': false,
    'F&O execution': false,
  };
  final Set<String> _watchlist = {'TITAN', 'CDSL'};
  final List<String> _assistantHistory = [
    'Mind: Ask about strategy evidence, model comparisons or portfolio risk. All answers use static sample data.',
  ];
  final TextEditingController _assistantController = TextEditingController();
  final TextEditingController _strategySearch = TextEditingController();

  @override
  void dispose() {
    _assistantController.dispose();
    _strategySearch.dispose();
    super.dispose();
  }

  // Extensions are in the same Dart library but State.setState is protected.
  // Route all prototype-only state updates through this owner-state helper.
  void _updateMind(VoidCallback update) {
    if (mounted) setState(update);
  }

  void _go(String route) {
    if (!MintickMindScreens.all.any((e) => e.id == route)) return;
    setState(() => _route = route);
  }
  void _selectStrategy(String id) => setState(() { _selectedStrategy = id; _route = 'strategy-detail'; });
  void _selectModel(String name) => setState(() { _selectedModel = name; _route = 'model-detail'; });
  void _selectDecision(String id) => setState(() { _selectedDecision = id; _route = 'decision-detail'; });
  void _selectCompany(String symbol) => setState(() { _selectedCompany = symbol; _route = 'thesis-detail'; });
  void _selectExperiment(String name) => setState(() { _selectedExperiment = name; _route = 'experiment-detail'; });
  void _selectRoom(String name) => setState(() { _selectedRoom = name; _route = 'room-detail'; });
  void _notice(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message), backgroundColor: const Color(0xFF18231A),
      duration: const Duration(seconds: 3),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.sizeOf(context).width >= 870;
    return Scaffold(
      backgroundColor: MindPalette.background,
      floatingActionButton: !wide && (widget.embeddedInProductShell || _route == 'overview')
        ? Padding(padding:EdgeInsets.only(bottom: widget.embeddedInProductShell ? 0 : 66),
            child:FloatingActionButton.small(
              tooltip:'All MintTick Mind screens',
              backgroundColor:MindPalette.mint,
              onPressed:_showRouteSheet,
              child:const Icon(Icons.apps_rounded,color:Colors.white)))
        : null,
      body: SafeArea(
        child: Row(children: [
          if (wide) _sidebar(),
          Expanded(child: Column(children: [
            if (_route != 'overview') _topbar(wide),
            Expanded(child: _route == 'overview'
                ? MintickMindPage(
                    key: const ValueKey('mind-original-overview'),
                    readinessRepository: widget.readinessRepository,
                    strategyReplayRepository: widget.strategyReplayRepository,
                    walkForwardRepository: widget.walkForwardRepository,
                    prospectiveRepository: widget.prospectiveRepository,
                    prospectiveSignalsRepository: widget.prospectiveSignalsRepository,
                    prospectiveOutcomesRepository: widget.prospectiveOutcomesRepository,
                    prospectiveEvidenceCompletenessRepository:
                        widget.prospectiveEvidenceCompletenessRepository,
                  )
                : _screen()),
            if (!wide && !widget.embeddedInProductShell) _mobileNavigation(),
          ])),
        ]),
      ),
    );
  }

  Widget _sidebar() => Container(
    width: 235, padding: const EdgeInsets.fromLTRB(12, 18, 12, 12),
    decoration: const BoxDecoration(color: MindPalette.background, border: Border(right: BorderSide(color: MindPalette.border))),
    child: Column(children: [
      const Row(children: [
        Icon(Icons.stacked_line_chart, color: MindPalette.mint, size: 27),
        SizedBox(width: 9),
        Expanded(child:Text('MintTick',maxLines:1,overflow:TextOverflow.ellipsis,style:TextStyle(fontSize:22,fontWeight:FontWeight.w800,color:MindPalette.text))),
      ]),
      const SizedBox(height: 7),
      const Text('MINTTICK MIND · MOCK WORKSPACE', style: TextStyle(color: MindPalette.purple, fontSize: 9, letterSpacing: 1.0)),
      const SizedBox(height: 18),
      Expanded(child: SingleChildScrollView(child: Column(children: [
        for (final group in ['PRIMARY','AGENTS','RESEARCH','CAPITAL','SYSTEM']) ...[
          Padding(padding: const EdgeInsets.fromLTRB(11, 14, 11, 6),
            child: Text(group, style: const TextStyle(color: MindPalette.muted, fontSize: 10, letterSpacing: 1.3))),
          for (final screen in MintickMindScreens.all.where((s) => s.group == group))
            _menuItem(screen),
        ],
      ]))),
      const Text('Research only · No real orders', style: TextStyle(color: MindPalette.muted, fontSize: 11)),
    ]),
  );

  Widget _menuItem(MindScreenDescriptor screen) {
    final selected = _route == screen.id;
    return Material(color: selected ? MindPalette.panel : Colors.transparent,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        key: ValueKey('mind-route-' + screen.id),
        onTap: () => _go(screen.id),
        child: Padding(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 11),
          child: Row(children: [
            Icon(screen.icon, size: 19, color: selected ? MindPalette.mint : MindPalette.muted),
            const SizedBox(width: 11),
            Expanded(child: Text(screen.label, maxLines: 1, overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: selected ? MindPalette.mint : MindPalette.muted,
              fontWeight: selected ? FontWeight.w800 : FontWeight.w600))),
          ]),
        ),
      ),
    );
  }

  Widget _topbar(bool wide) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
    decoration: const BoxDecoration(color: MindPalette.background, border: Border(bottom: BorderSide(color: MindPalette.border))),
    child: Row(children: [
      if (!wide)
        IconButton(tooltip:'All Mind screens', onPressed:_showRouteSheet,
          icon:const Icon(Icons.menu_rounded,color:MindPalette.text)),
      const Icon(Icons.stacked_line_chart, color: MindPalette.mint, size: 20),
      const SizedBox(width: 7),
      const Text('MintTick', style: TextStyle(color: MindPalette.text, fontSize: 17, fontWeight: FontWeight.w800)),
      if (wide) ...[
        const SizedBox(width: 16),
        Flexible(child: Text('Mind / ' + _label(_route),
          overflow: TextOverflow.ellipsis, style: const TextStyle(color: MindPalette.muted))),
      ],
      const Spacer(),
      if (MediaQuery.sizeOf(context).width >= 480)
        const MindTag('DEMO DATA', color: MindPalette.yellow),
      const SizedBox(width: 4),
      IconButton(tooltip:'Screen coverage',onPressed:()=>_go('coverage'),
        icon:const Icon(Icons.fact_check_outlined,color:MindPalette.muted,size:20)),
    ]),
  );

  Widget _mobileNavigation() => Container(
    padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 3),
    decoration: const BoxDecoration(color: MindPalette.background, border: Border(top: BorderSide(color: MindPalette.border))),
    height: 65,
    child: Row(children: [
      for(final (id,label,icon) in [
        ('app-home','Home',Icons.home_outlined),
        ('charts','Markets',Icons.candlestick_chart_outlined),
        ('overview','Mind',Icons.psychology_outlined),
        ('portfolio','Portfolio',Icons.account_balance_wallet_outlined),
        ('settings','Profile',Icons.person_outlined),
      ])
        Expanded(child: InkWell(
          onTap:()=>_go(id),borderRadius:BorderRadius.circular(12),
          child:Container(
            margin:const EdgeInsets.symmetric(horizontal:3),
            decoration:BoxDecoration(
              color:_route==id?MindPalette.panel:Colors.transparent,
              borderRadius:BorderRadius.circular(12)),
            child:Column(mainAxisAlignment:MainAxisAlignment.center,children:[
              Icon(icon,color:_route==id?MindPalette.mint:MindPalette.muted,size:22),
              Text(label,style:TextStyle(fontSize:10,color:_route==id?MindPalette.mint:MindPalette.muted)),
            ]),
          ),
        )),
    ]),
  );

  String _label(String id) {
    for (final screen in MintickMindScreens.all) { if (screen.id == id) return screen.label; }
    return id;
  }

  void _showRouteSheet() => showModalBottomSheet<void>(
    context:context, isScrollControlled:true, showDragHandle:true,
    backgroundColor:MindPalette.panel,
    builder:(sheetContext)=>SafeArea(child:FractionallySizedBox(heightFactor:.82,
      child:ListView.builder(
        itemCount:MintickMindScreens.all.length,
        itemBuilder:(context,index) {
          final screen = MintickMindScreens.all[index];
          return ListTile(
            leading:Icon(screen.icon,color:MindPalette.mint),
            title:Text(screen.label,style:const TextStyle(color:MindPalette.text)),
            subtitle:Text(screen.group,style:const TextStyle(color:MindPalette.muted,fontSize:10)),
            onTap:(){ Navigator.pop(sheetContext);_go(screen.id); },
          );
        },
      ),
    )),
  );

  Widget _surface(String heading, String subtitle, List<Widget> children, {String? backTo}) =>
    Center(child:ConstrainedBox(constraints:const BoxConstraints(maxWidth:1120),
      child:ListView(key:ValueKey('mind-screen-' + _route),
        padding:const EdgeInsets.fromLTRB(14,16,14,30),
        children:[
          if (backTo != null) Align(alignment:Alignment.centerLeft,
            child:TextButton.icon(onPressed:()=>_go(backTo),
              icon:const Icon(Icons.arrow_back,size:16),
              label:const Text('Back'),
              style:TextButton.styleFrom(foregroundColor:MindPalette.mint))),
          const Text('MINTTICK MIND · PERSONAL TRADING INTELLIGENCE',
            style:TextStyle(color:MindPalette.mint,fontSize:10,letterSpacing:1.0,fontWeight:FontWeight.w700)),
          const SizedBox(height:7),
          Text(heading,style:const TextStyle(color:MindPalette.text,fontSize:28,fontWeight:FontWeight.w800,letterSpacing:-.8)),
          const SizedBox(height:3),
          Text(subtitle,style:const TextStyle(color:MindPalette.muted,fontSize:12)),
          const SizedBox(height:13),
          const MindTag('ILLUSTRATIVE / OFFLINE · NO LIVE BROKER'),
          const SizedBox(height:17),
          ...children,
        ],
      ),
    ));

  Widget _screen() => switch(_route) {
    'app-home' => homeScreen(),
    'intelligence-hub' => intelligenceHubScreen(),
    'agent-council' => agentCouncilScreen(),
    'agent-evidence' => agentEvidenceScreen(),
    'regime-weights' => regimeWeightsScreen(),
    'signal-scorecard' => signalScorecardScreen(),
    'confidence-lab' => confidenceLabScreen(),
    'shadow-arena' => shadowArenaScreen(),
    'institutional-flow' => institutionalFlowScreen(),
    'daily-verdict' => dailyVerdictScreen(),
    'culprit-catcher' => culpritCatcherScreen(),
    'trading-rooms' => roomsScreen(),
    'room-detail' => roomDetailScreen(),
    'strategies' => strategiesScreen(),
    'strategy-detail' => strategyDetailScreen(),
    'models' => modelsScreen(),
    'model-detail' => modelDetailScreen(),
    'charts' => chartIntelligenceScreen(),
    'decisions' => decisionsScreen(),
    'decision-detail' => decisionDetailScreen(),
    'decision-lab' => decisionLabScreen(),
    'dependencies' => dependenciesScreen(),
    'research' => researchScreen(),
    'research-engines' => researchEnginesScreen(),
    'experiment-detail' => experimentDetailScreen(),
    'performance' => performanceScreen(),
    'journal' => journalScreen(),
    'multibagger' => multibaggerScreen(),
    'thesis-detail' => thesisScreen(),
    'portfolio' => portfolioScreen(),
    'risk' => riskScreen(),
    'controls' => controlScreen(),
    'regimes' => regimesScreen(),
    'events' => eventsScreen(),
    'assistant' => assistantScreen(),
    'derivatives' => derivativesScreen(),
    'options-chain' => optionChainScreen(),
    'option-builder' => optionBuilderScreen(),
    'coverage' => coverageScreen(),
    'design-gallery' => galleryScreen(),
    'design-preview' => designPreviewScreen(),
    'settings' => settingsScreen(),
    _ => _surface('Not found','This screen is not in the demo catalogue',[
      TextButton(onPressed:()=>_go('coverage'),child:const Text('View screen coverage')),
    ]),
  };

  Widget _section(String title, IconData icon, List<Widget> children, {String? action}) =>
    Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
      MindSectionTitle(title:title,icon:icon,onSeeAll:action==null?null:()=>_go(action)),
      ...children,
      const SizedBox(height:14),
    ]);

  Widget _tile(String title,String caption,IconData icon,{String? route, String? trailing, Color color=MindPalette.mint,VoidCallback? tap})=>
    MindPanel(onTap:tap??(route==null?null:()=>_go(route)),
      child:Row(children:[
        Container(width:39,height:39,decoration:BoxDecoration(
          color:color.withValues(alpha:.10),borderRadius:BorderRadius.circular(11)),
          child:Icon(icon,color:color,size:21)),
        const SizedBox(width:10),
        Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
          Text(title,maxLines:1,overflow:TextOverflow.ellipsis,
            style:const TextStyle(color:MindPalette.text,fontWeight:FontWeight.w700,fontSize:14)),
          const SizedBox(height:2),
          Text(caption,maxLines:2,overflow:TextOverflow.ellipsis,
            style:const TextStyle(color:MindPalette.muted,fontSize:11)),
        ])),
        if (trailing != null) Text(trailing,style:TextStyle(color:color,fontWeight:FontWeight.w800,fontSize:12)),
        if (route != null || tap != null) const Icon(Icons.chevron_right,color:MindPalette.muted,size:17),
      ]),
    );

  Widget _chips(List<String> options,String selected,ValueChanged<String> setSelected) =>
    SizedBox(height:42,child:ListView(scrollDirection:Axis.horizontal,children:[
      for(final value in options) Padding(padding:const EdgeInsets.only(right:7),
        child:ChoiceChip(
          label:Text(value),
          selected:selected==value,
          onSelected:(_)=>setSelected(value),
          backgroundColor:MindPalette.card,
          selectedColor:MindPalette.panel,
          side:const BorderSide(color:MindPalette.border),
          labelStyle:TextStyle(fontSize:11,color:selected==value?MindPalette.mint:MindPalette.text),
        )),
    ]));

  Widget _metricGrid(List<(String,String,String)> values) => LayoutBuilder(
    builder:(context,constraints) {
      final cols=constraints.maxWidth>=720?4:2;
      return GridView.count(
        crossAxisCount:cols,
        crossAxisSpacing:9,mainAxisSpacing:9,shrinkWrap:true,
        physics:const NeverScrollableScrollPhysics(),
        childAspectRatio:cols==4?1.08:1.0,
        children:[
          for(var i=0;i<values.length;i++) MindMetric(
            label:values[i].$1,value:values[i].$2,caption:values[i].$3,seed:i+4),
        ],
      );
    },
  );

  Widget _value(String label,String value,{Color color=MindPalette.mint}) =>
    Padding(padding:const EdgeInsets.symmetric(vertical:7),
      child:Row(children:[
        Expanded(child:Text(label,style:const TextStyle(color:MindPalette.muted,fontSize:12))),
        Flexible(child:Text(value,textAlign:TextAlign.right,maxLines:2,
          overflow:TextOverflow.ellipsis,
          style:TextStyle(color:color,fontSize:12,fontWeight:FontWeight.w700))),
      ]),
    );

  Widget _empty(String text) => MindEmptyState(text);
  Widget _split(Widget left, Widget right) => LayoutBuilder(builder:(context, constraints) =>
    constraints.maxWidth < 710
      ? Column(children:[left,const SizedBox(height:11),right])
      : Row(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Expanded(child:left),const SizedBox(width:11),Expanded(child:right),
      ]),
  );

  Widget _mockChart({int seed=7,double height=210,bool candles=false,
    String? style, Color color=MindPalette.mint}) =>
    SizedBox(height:height,width:double.infinity,
      child:CustomPaint(painter:_MindResearchChart(seed:seed,color:color,
        style:style??(candles?'Candlestick':'Line'))));

  Widget _toggle(String title,String description,{bool locked=false}) => MindPanel(
    child:Row(children:[
      Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Text(title,style:const TextStyle(color:MindPalette.text,fontWeight:FontWeight.w700)),
        Text(description,style:const TextStyle(color:MindPalette.muted,fontSize:11)),
      ])),
      if(locked) const MindTag('Future module',color:MindPalette.yellow)
      else Switch.adaptive(
        activeTrackColor:MindPalette.green,value:_switches[title]??false,
        onChanged:(value)=>setState(()=>_switches[title]=value)),
    ]),
  );

  void _mockAction(String message)=>_notice(message + ' · mock only, no API request');

  Widget _progress(String label,double value, {Color color=MindPalette.mint})=>
    Padding(padding:const EdgeInsets.symmetric(vertical:6),
      child:Row(children:[
        SizedBox(width:115,child:Text(label,maxLines:1,overflow:TextOverflow.ellipsis,
          style:const TextStyle(color:MindPalette.muted,fontSize:11))),
        Expanded(child:ClipRRect(borderRadius:BorderRadius.circular(5),
          child:LinearProgressIndicator(value:value.clamp(0.0,1.0),minHeight:7,
            backgroundColor:MindPalette.border,color:color))),
        const SizedBox(width:10),
        SizedBox(width:43,child:Text((value*100).round().toString()+'%',
          style:const TextStyle(color:MindPalette.text,fontSize:11))),
      ]),
    );
}

class _MindResearchChart extends CustomPainter {
  const _MindResearchChart({required this.seed,required this.color,required this.style});
  final int seed;
  final Color color;
  final String style;

  @override
  void paint(Canvas canvas, Size size) {
    final grid=Paint()..color=MindPalette.border..strokeWidth=.65;
    for(var i=0;i<=4;i++) {
      final y=size.height*i/4;
      canvas.drawLine(Offset(0,y),Offset(size.width,y),grid);
    }
    for(var i=0;i<=6;i++) {
      final x=size.width*i/6;
      canvas.drawLine(Offset(x,0),Offset(x,size.height),grid);
    }
    final points=<Offset>[];
    var y=size.height*.65;
    for(var i=0;i<65;i++) {
      final x=(size.width-8)*i/64;
      final diff=math.sin(i*1.6+seed)*size.height*.027+
        math.cos(i*.39+seed)*size.height*.026-size.height*.004;
      y=(y+diff).clamp(size.height*.10,size.height*.88).toDouble();
      points.add(Offset(x,y));
    }

    if(style=='Line'||style=='Kagi'){
      final path=Path()..moveTo(points.first.dx,points.first.dy);
      for(var i=1;i<points.length;i++){
        final p=points[i];
        if(style=='Kagi'){
          path.lineTo(p.dx,points[i-1].dy);
          path.lineTo(p.dx,p.dy);
        } else {
          path.lineTo(p.dx,p.dy);
        }
      }
      canvas.drawPath(path,Paint()
        ..color=color
        ..style=PaintingStyle.stroke
        ..strokeWidth=style=='Kagi'?2.8:2.0
        ..strokeCap=StrokeCap.round);
      return;
    }

    final cellWidth=math.max(2.0,size.width/79);
    for(var i=1;i<points.length;i++) {
      final prev=points[i-1],now=points[i];
      final up=now.dy<prev.dy;
      final positive=up?MindPalette.mint:MindPalette.red;
      final paint=Paint()..color=positive..strokeWidth=1.1;
      final high=math.min(now.dy,prev.dy);
      final low=math.max(now.dy,prev.dy);
      final bodyHeight=math.max(2.0,low-high);
      final boxWidth=cellWidth*1.65;
      switch(style) {
        case 'Volume':
          final amplitude=(.12+(.5+.5*math.sin(i*1.8+seed))*.73);
          canvas.drawRect(Rect.fromLTWH(now.dx,size.height*(1-amplitude),
            boxWidth,size.height*amplitude),paint);
          break;
        case 'Point & Figure':
          if(i%3!=0)break;
          if(up){
            canvas.drawLine(Offset(now.dx-3,now.dy-3),Offset(now.dx+4,now.dy+4),paint);
            canvas.drawLine(Offset(now.dx+4,now.dy-3),Offset(now.dx-3,now.dy+4),paint);
          } else {
            canvas.drawCircle(now,4,paint..style=PaintingStyle.stroke);
          }
          break;
        case 'Renko':
          if(i%3!=0)break;
          canvas.drawRect(Rect.fromLTWH(now.dx,now.dy,
            cellWidth*3,math.max(6.0,bodyHeight)),paint..style=PaintingStyle.stroke);
          break;
        case 'OHLC':
          canvas.drawLine(Offset(now.dx,high-4),Offset(now.dx,low+4),paint);
          canvas.drawLine(Offset(now.dx-3,prev.dy),Offset(now.dx,prev.dy),paint);
          canvas.drawLine(Offset(now.dx,now.dy),Offset(now.dx+3,now.dy),paint);
          break;
        case 'Heikin Ashi':
          final smoothed=now.dy*.5+prev.dy*.5;
          canvas.drawLine(Offset(now.dx,high-4),Offset(now.dx,low+4),paint);
          canvas.drawRect(Rect.fromLTWH(now.dx-cellWidth*.5,
            math.min(smoothed,prev.dy),cellWidth,math.max(3.0,(smoothed-prev.dy).abs())),
            paint..style=PaintingStyle.fill);
          break;
        case 'Range':
          if(i%2!=0)break;
          canvas.drawRect(Rect.fromLTWH(now.dx,high,cellWidth*2,
            math.max(7.0,bodyHeight)),paint..style=PaintingStyle.stroke);
          break;
        default:
          canvas.drawLine(Offset(now.dx,high-4),Offset(now.dx,low+4),paint);
          canvas.drawRect(Rect.fromLTWH(now.dx-cellWidth*.45,high,
            boxWidth,bodyHeight),paint..style=PaintingStyle.fill);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _MindResearchChart old)=>
      old.seed!=seed||old.style!=style||old.color!=color;
}
