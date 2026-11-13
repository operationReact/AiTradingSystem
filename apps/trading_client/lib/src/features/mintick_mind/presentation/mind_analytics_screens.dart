// Prototype UI source may use existing explicit string concatenation for compact mock labels.
// ignore_for_file: prefer_interpolation_to_compose_strings
part of 'mind_workspace_page.dart';

extension _MindAnalyticsScreens on _MindWorkspaceState {
  Widget strategiesScreen() {
    final matches=MindFixtures.strategies.where((s)=>
      (_family=='All'||s.family==_family) &&
      (_strategyMode=='All'||s.modes.contains(_strategyMode)) &&
      (s.name.toLowerCase().contains(_strategyQuery.toLowerCase())||
       s.family.toLowerCase().contains(_strategyQuery.toLowerCase()))).toList();
    _ensureRegistry();
    return _surface('Strategy Universe','All strategies, grouped by family and trading horizon',[
      _metricGrid([
        ('Catalog entries','232','20 families · names are not engines'),
        ('Tested code',_countOf('STRATEGY',(i)=>i.status==MindProgressStatus.tested),'Unit/IT evidence · '+_registryState),
        ('Runtime verified',_countOf('STRATEGY',(i)=>i.done),'Deployed PAPER proof'),
        ('Data blocked',_countOf('STRATEGY',(i)=>i.status.isBlocked),'Awaiting permitted data/rights'),
      ]),
      const SizedBox(height:14),
      TextField(controller:_strategySearch,
        onChanged:(v)=>_updateMind((){_strategyQuery=v;_showStrategies=25;}),
        style:const TextStyle(color:MindPalette.text),
        decoration:InputDecoration(
          hintText:'Search strategies (VWAP, breakout, options...)',
          hintStyle:const TextStyle(color:MindPalette.muted,fontSize:12),
          prefixIcon:const Icon(Icons.search,color:MindPalette.muted),
          filled:true,fillColor:MindPalette.card,
          enabledBorder:OutlineInputBorder(borderSide:const BorderSide(color:MindPalette.border),
            borderRadius:BorderRadius.circular(12))),
      ),
      const SizedBox(height:11),
      _chips(['All','Intraday','Swing','Delivery','Long-Term','Research','F&O'],
        _strategyMode,(v)=>_updateMind((){_strategyMode=v;_showStrategies=25;})),
      _chips(['All',...MindFixtures.strategyFamilies],_family,
        (v)=>_updateMind((){_family=v;_showStrategies=25;})),
      const SizedBox(height:9),
      _section('Strategy Families',Icons.category_outlined,[
        MindPanel(child:Wrap(spacing:7,runSpacing:7,children:[
          for(final name in MindFixtures.strategyFamilies)
            ActionChip(label:Text(name+' ('+
              MindFixtures.strategies.where((s)=>s.family==name).length.toString()+')'),
              backgroundColor:MindPalette.panel,
              side:BorderSide(color:_family==name?MindPalette.mint:MindPalette.border),
              labelStyle:const TextStyle(color:MindPalette.text,fontSize:10),
              onPressed:()=>_updateMind(()=>_family=name)),
        ])),
      ]),
      _section('Strategy Catalog · '+matches.length.toString(),Icons.layers_outlined,[
        if(matches.isEmpty) _empty('No matching strategies. Clear the filters.'),
        for(final s in matches.take(_showStrategies))
          Padding(padding:const EdgeInsets.only(bottom:7),child:Builder(builder:(context){
            final cap=_capability('strategy.'+s.id);
            return _tile(s.name,
              s.id+' · '+s.family+' · '+s.modes.join('/')+
                (cap?.implementationCode==null?'':' · '+cap!.implementationCode!),
              Icons.insights_outlined,trailing:_statusLabel(cap),
              color:_statusColor(cap),
              tap:()=>_selectStrategy(s.id));
          })),
        if(matches.length>_showStrategies)
          OutlinedButton(onPressed:()=>_updateMind(()=>_showStrategies+=25),
            child:Text('Load more ('+(matches.length-_showStrategies).toString()+' remaining)',
              style:const TextStyle(color:MindPalette.mint))),
      ]),
      _split(
        _block('Strategy Relationships',Icons.account_tree_outlined,[
          _value('VWAP Reclaim','Liquidity filter'),
          _value('Momentum Breakout','Volume + Regime'),
          _value('Mean Reversion','Trend conflict'),
          _action('Open graph','dependencies'),
        ]),
        _block('Risk-adjusted Ranking',Icons.emoji_events_outlined,[
          _value('Momentum Breakout','2.41 (mock)'),
          _value('Compounders','2.18 (mock)'),
          _value('Volatility Squeeze','1.96 (mock)'),
          const Text('Illustrative scores, not observed profitability.',
            style:TextStyle(color:MindPalette.muted,fontSize:11)),
        ]),
      ),
    ]);
  }

  Widget _block(String title,IconData icon,List<Widget> children)=>
    MindPanel(child:Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
      MindSectionTitle(title:title,icon:icon),...children,
    ]));

  Widget _action(String label,String route)=>TextButton(
    onPressed:()=>_go(route),
    child:Text(label+' →',style:const TextStyle(color:MindPalette.mint)));

  Widget strategyDetailScreen() {
    final s=MindFixtures.strategies.firstWhere((e)=>e.id==_selectedStrategy,
      orElse:()=>MindFixtures.strategies.first);
    final idx=MindFixtures.strategies.indexOf(s);
    final cap=_capability('strategy.'+s.id);
    return _surface(s.name,'Strategy configuration · '+s.id,[
      _split(
        _block('Strategy Identity',Icons.info_outline,[
          _value('Family',s.family),
          _value('Horizons',s.modes.join(', ')),
          _value('Status',_statusLabel(cap),color:_statusColor(cap)),
          _value('Implementation',cap?.implementationCode==null?'None in repository'
            :cap!.implementationCode!+' ('+(cap.implementationMatch??'-')+')'),
          _value('Blocker',cap?.blockerCode??(cap==null?_registryState:'None')),
          _value('Evidence',cap==null?'Not verified':cap.evidence.length.toString()+' linked files/APIs'),
          _value('LIVE execution','DISABLED',color:MindPalette.red),
        ]),
        _block('Dependency Requirements',Icons.hub_outlined,[
          _value('Input freshness','Required'),
          _value('Market regime','Required'),
          _value('Liquidity gate','Required'),
          _value('Capital limits','Required'),
          _value('Correlation','Deduplicate'),
          _action('View dependencies','dependencies'),
        ]),
      ),
      const SizedBox(height:12),
      if (cap!=null) _section('Before DONE',Icons.checklist_outlined,[
        MindPanel(key:const ValueKey('mind-strategy-missing'),child:Column(
          crossAxisAlignment:CrossAxisAlignment.stretch,children:[
          if (cap.note!=null) Text(cap.note!,style:const TextStyle(color:MindPalette.text,fontSize:12)),
          for(final m in cap.missing) _value(m,'Missing',color:MindPalette.yellow),
          if (cap.runtime.lastVerifiedAt!=null)
            _value('Runtime verified',cap.runtime.lastVerifiedAt!.toUtc().toIso8601String().substring(0,16)+'Z'),
          _action('Open in coverage','coverage'),
        ])),
      ]),
      _section('Historical Performance · Synthetic',Icons.show_chart,[
        MindPanel(child:Column(children:[
          const MindTag('MOCK BACKTEST CURVE',color:MindPalette.purple),
          const SizedBox(height:9),
          _mockChart(seed:idx+5,height:210),
          _value('Sample observations','Not measured',color:MindPalette.muted),
          _value('Backtest return','No recorded backtest for this strategy',color:MindPalette.muted),
          _value('Independent validation','Missing',color:MindPalette.yellow),
        ])),
      ]),
      _section('Strategy Workflows',Icons.science_outlined,[
        _tile('Research engine benchmarks',
          'VectorBT pilot and independent validators · NOT CONNECTED',
          Icons.extension_outlined,route:'research-engines',
          trailing:'PLANNED',color:MindPalette.yellow),
        _tile('Experiment results','Backtests, paper and shadow',Icons.science_outlined,
          route:'research'),
        _tile('Decision journal','Track contribution and alternatives',Icons.history,
          route:'journal'),
        _tile('Compare related strategies','Relationship graph and conflict checks',
          Icons.hub_outlined,route:'dependencies'),
      ]),
    ],backTo:'strategies');
  }

  Widget modelsScreen() {
    final visible=MindFixtures.models.where((m)=>_modelFilter=='All'||
      m.status==_modelFilter||m.role==_modelFilter).toList();
    return _surface('Model Laboratory','Models, challengers and performance health',[
      _section('Research tool integrations',Icons.extension_outlined,[
        _tile('Research Engines Lab',
          'Five optional frameworks · all NOT CONNECTED · view PR checklists',
          Icons.construction_outlined,route:'research-engines',
          trailing:'PLANNED',color:MindPalette.yellow),
      ]),
      _metricGrid([
        ('Model records','18','Catalog · '+_registryState),
        ('Tested research code',_countOf('MODEL',(i)=>i.status==MindProgressStatus.tested),'Python modules with tests'),
        ('Rights blocked',_countOf('MODEL',(i)=>i.status==MindProgressStatus.rightsBlocked),'Licence review'),
        ('Deployed & verified',_countOf('MODEL',(i)=>i.done),'Trained artifact in runtime'),
      ]),
      const SizedBox(height:13),
      _chips(['All','Champion','Challenger','Research','Planned','Adapter',
        'License review'],_modelFilter,(v)=>_updateMind(()=>_modelFilter=v)),
      const SizedBox(height:11),
      _section('Model Leaderboard',Icons.emoji_events_outlined,[
        for(final model in visible)
          Padding(padding:const EdgeInsets.only(bottom:7),child:Builder(builder:(context){
            final cap=_capability(_MindWorkspaceState._modelCapabilityId(model.name));
            return _tile(model.name,
              model.family+' · '+model.role+' · '+(cap?.blockerCode??model.description),
              Icons.memory,trailing:_statusLabel(cap),color:_statusColor(cap),
              tap:()=>_selectModel(model.name));
          })),
      ]),
      _split(
        _block('Champion vs Challenger',Icons.compare_arrows,[
          _value('LightGBM · AUC','Not measured',color:MindPalette.muted),
          _value('CatBoost · AUC','Not measured',color:MindPalette.muted),
          _value('Static ensemble · Sharpe','Not measured',color:MindPalette.muted),
          _value('Adaptive ensemble · Sharpe','Not measured',color:MindPalette.muted),
          const Text('No out-of-sample evaluation on permitted data is recorded yet. Earlier fixture metrics were removed.',
            style:TextStyle(color:MindPalette.muted,fontSize:11)),
        ]),
        _block('Drift & Calibration',Icons.track_changes_outlined,[
          _value('Feature drift','Not monitored',color:MindPalette.muted),
          _value('Calibration','Not measured',color:MindPalette.muted),
          _value('Retraining','No lifecycle yet'),
          _action('Model lifecycle status','coverage'),
        ]),
      ),
    ]);
  }

  Widget modelDetailScreen() {
    final model=MindFixtures.models.firstWhere((m)=>m.name==_selectedModel,
      orElse:()=>MindFixtures.models.first);
    final idx=MindFixtures.models.indexOf(model);
    final cap=_capability(_MindWorkspaceState._modelCapabilityId(model.name));
    return _surface(model.name,'Model deep dive · no deployed version',[
      _split(
        _block('Identity & Readiness',Icons.memory,[
          _value('Family',model.family),
          _value('Planned role',model.role),
          _value('Status',_statusLabel(cap),color:_statusColor(cap)),
          _value('Blocker',cap?.blockerCode??(cap==null?_registryState:'None')),
          _value('Code evidence',cap==null?'Not verified':
            cap.evidence.where((e)=>e.kind=='SOURCE').map((e)=>e.display.split('/').last).join(', ')),
          _value('Live execution','Never',color:MindPalette.red),
        ]),
        _block('Model Health',Icons.monitor_heart_outlined,[
          _value('Coverage','Not measured',color:MindPalette.muted),
          _value('Calibration','Not measured',color:MindPalette.muted),
          _value('Drift stability','Not monitored',color:MindPalette.muted),
          _value('Latency','No serving path',color:MindPalette.muted),
          _value('Promotion','Requires trained artifact + validation'),
        ]),
      ),
      const SizedBox(height:12),
      _section('Model Performance History',Icons.show_chart,[
        MindPanel(child:Column(children:[
          _mockChart(seed:idx+20,height:205),
          _value('Model accuracy','Synthetic only'),
          _value('Research evidence','Not production verified'),
        ])),
      ]),
      _section('Contributions & Dependencies',Icons.hub_outlined,[
        _tile('Strategy dependencies','See correlated signals and gates',
          Icons.account_tree_outlined,route:'dependencies'),
        _tile('Champion/challenger experiments','Compare controlled research',
          Icons.science_outlined,route:'research'),
        _tile('Decision evidence','Understand the model vote in context',
          Icons.track_changes_outlined,route:'decisions'),
      ]),
    ],backTo:'models');
  }

  Widget chartIntelligenceScreen() {
    return _surface('Chart Intelligence','Multi-timeframe technical interpretation',[
      _chips(['RELIANCE','TCS','HDFCBANK','TITAN','CDSL','IREDA','INFY'],
        _instrument,(v)=>_updateMind((){_instrument=v;_chartSeed++;})),
      _chips(['5m','15m','1H','1D','1W','1M'],_chartPeriod,
        (v)=>_updateMind((){_chartPeriod=v;_chartSeed++;})),
      _chips(['Candlestick','Line','OHLC','Heikin Ashi','Renko',
        'Point & Figure','Range','Volume','Kagi'],_chartStyle,
        (v)=>_updateMind((){_chartStyle=v;_chartSeed++;})),
      const SizedBox(height:9),
      MindPanel(highlight:true,child:Column(children:[
        Row(children:[
          Expanded(child:Text('NSE: '+_instrument+' · '+_chartPeriod,
            style:const TextStyle(color:MindPalette.text,fontSize:17,fontWeight:FontWeight.w800))),
          const MindTag('SIMULATED',color:MindPalette.yellow),
        ]),
        const SizedBox(height:5),
        Text(_chartStyle+' view · EMA20 / EMA50 / VWAP',
          style:const TextStyle(color:MindPalette.muted,fontSize:12)),
        const SizedBox(height:12),
        _mockChart(seed:_chartSeed+_instrument.length,height:265,
          style:_chartStyle),
        _value('Fictional price','₹2,956.40'),
        _value('Support / Resistance','₹2,880 / ₹2,955'),
      ])),
      const SizedBox(height:12),
      _split(
        _block('Signal Stack',Icons.layers_outlined,[
          _value('Trend','Bullish'),
          _value('Momentum · RSI','Bullish'),
          _value('Volume','Neutral',color:MindPalette.yellow),
          _value('ATR / volatility','Moderate'),
          _value('Pattern','Breakout attempt'),
          _value('News','Unverified',color:MindPalette.yellow),
        ]),
        _block('Cross-Chart Interpretation',Icons.hub_outlined,[
          _value('Candlestick','Bullish'),
          _value('Heikin Ashi','Bullish'),
          _value('Renko','Neutral',color:MindPalette.yellow),
          _value('Line','Mixed',color:MindPalette.purple),
          _value('Point & Figure','Research'),
          const Text('Derived chart types share the same OHLCV evidence.',
            style:TextStyle(color:MindPalette.muted,fontSize:11)),
        ]),
      ),
      const SizedBox(height:12),
      _section('What MintTick Mind Sees',Icons.psychology_outlined,[
        MindPanel(highlight:true,child:const Text(
          'The synthetic chart suggests a breakout attempt, but higher-timeframe and volume confirmation are not strong enough. Wait until independent inputs justify entry; do not count derived charts as separate votes.',
          style:TextStyle(color:MindPalette.text,height:1.6))),
      ]),
      _section('Action Bias',Icons.track_changes_outlined,[
        _tile('Intraday','Selective entry / no real order',Icons.bolt,
          trailing:'WAIT',color:MindPalette.yellow,route:'decisions'),
        _tile('Swing','Higher timeframe remains mixed',Icons.calendar_month,
          trailing:'WATCH',color:MindPalette.purple,route:'decision-lab'),
        _tile('Long-term','Requires fundamental thesis',
          Icons.diamond_outlined,trailing:'RESEARCH',route:'multibagger'),
      ]),
    ]);
  }

  static const _decisionRows=<(String,String,String,String)>[
    ('TITAN','Intraday','WAIT','Regime and breakout volume conflict'),
    ('CDSL','Swing','BUY CANDIDATE','Relative strength improved; risk pending'),
    ('IREDA','Long-Term','WATCH','Credit quality requires research'),
    ('HDFCBANK','Intraday','SELL HOLDINGS','Illustrative exit assessment'),
    ('RELIANCE','Delivery','HOLD','Trend and valuation do not align'),
    ('NTPC','Cash Short','ABSTAIN','Borrow and delivery eligibility missing'),
    ('INFY','Swing','BUY CANDIDATE','Hypothetical volume confirmation'),
    ('POLYCAB','Long-Term','THESIS REVIEW','Growth versus elevated valuation'),
    ('TCS','Intraday','WAIT','Higher-timeframe conflict'),
    ('BHARTIARTL','Positional','WATCH','Sector relative strength improved'),
    ('NIFTY50','Futures','ABSTAIN','Derivatives disabled'),
    ('BANKNIFTY','Options','ABSTAIN','No derivatives capital authority'),
  ];

  Widget decisionsScreen() {
    final matches=_decisionRows.where((r)=>_decisionFilter=='All'||
      r.$3==_decisionFilter||r.$2==_decisionFilter).toList();
    return _surface('Decision Explorer','Why the engine bought, waited or abstained',[
      _chips(['All','WAIT','BUY CANDIDATE','WATCH','HOLD','ABSTAIN',
        'Intraday','Swing','Long-Term','Futures','Options'],
        _decisionFilter,(v)=>_updateMind(()=>_decisionFilter=v)),
      _section('Recent Decisions · Sample Timeline',Icons.history_outlined,[
        for(final row in matches)
          Padding(padding:const EdgeInsets.only(bottom:7),child:_tile(
            row.$1+' · '+row.$3,row.$2+' · '+row.$4,
            Icons.track_changes_outlined,trailing:'View',
            color:row.$3=='WAIT'?MindPalette.yellow:
              row.$3=='ABSTAIN'?MindPalette.red:MindPalette.mint,
            tap:()=>_selectDecision(row.$1))),
      ]),
    ]);
  }

  Widget decisionDetailScreen() {
    final row=_decisionRows.firstWhere((r)=>r.$1==_selectedDecision,
      orElse:()=>_decisionRows.first);
    return _surface('Decision Anatomy · '+row.$1,
      'Traceable hypothetical evidence and counterfactuals',[
      MindPanel(highlight:true,child:Column(children:[
        _value('Category',row.$2),
        _value('Decision',row.$3),
        _value('Execution','SIGNAL ONLY',color:MindPalette.yellow),
        _value('Time','2026-11-09 10:24 IST · mock'),
        _value('Reason',row.$4),
      ])),
      const SizedBox(height:12),
      _split(
        _block('Evidence Breakdown',Icons.layers_outlined,[
          _value('Momentum','Bullish'),
          _value('Regime','Neutral',color:MindPalette.yellow),
          _value('Volume','Insufficient',color:MindPalette.yellow),
          _value('News','Unverified'),
          _value('Confidence','Not calibrated'),
        ]),
        _block('Portfolio Risk Authority',Icons.shield_outlined,[
          _value('Liquidity gate','Review'),
          _value('Exposure','Within mock cap'),
          _value('Cash','Hypothetical'),
          _value('Broker','Not connected'),
          _value('Order intent','NONE',color:MindPalette.red),
        ]),
      ),
      const SizedBox(height:12),
      _section('Reasoning Summary',Icons.psychology_outlined,[
        MindPanel(highlight:true,child:Text(
          'Decision: '+row.$3+'. '+row.$4+
          '. This assessment reflects synthetic evidence and is not a broker-connected recommendation.',
          style:const TextStyle(color:MindPalette.text,height:1.6))),
      ]),
      _section('Alternative Paths',Icons.alt_route_outlined,[
        _tile('Would enter if','Volume and regime agree and risk passes',
          Icons.trending_up,route:'decision-lab'),
        _tile('Would abstain if','Data stale, risk veto, or liquidity failure',
          Icons.block,color:MindPalette.yellow,route:'risk'),
      ]),
      _tile('Outcome Journal','No broker fills or real P&L',
        Icons.history_outlined,route:'journal'),
    ],backTo:'decisions');
  }

  Widget decisionLabScreen() {
    final good=_dataFresh&&_riskApproved&&_modelWeight>=65;
    final verdict=!_dataFresh?'ABSTAIN · DATA STALE':
      !_riskApproved?'ABSTAIN · RISK VETO':
      good?'BUY CANDIDATE · RESEARCH':'WAIT · DISAGREEMENT';
    return _surface('Decision Simulator','Change evidence, freshness and portfolio vetoes',[
      MindPanel(highlight:true,child:Column(children:[
        const MindSectionTitle(title:'Scenario Conditions',icon:Icons.tune),
        _value('Model agreement',_modelWeight.toStringAsFixed(0)+'%'),
        Slider(value:_modelWeight,min:0,max:100,divisions:20,
          activeColor:MindPalette.mint,
          onChanged:(v)=>_updateMind(()=>_modelWeight=v)),
        SwitchListTile.adaptive(value:_dataFresh,
          activeTrackColor:MindPalette.mint,
          onChanged:(v)=>_updateMind(()=>_dataFresh=v),
          title:const Text('Fresh market data',style:TextStyle(color:MindPalette.text)),
          subtitle:const Text('Turn off to force abstention',
            style:TextStyle(color:MindPalette.muted,fontSize:11))),
        SwitchListTile.adaptive(value:_riskApproved,
          activeTrackColor:MindPalette.mint,
          onChanged:(v)=>_updateMind(()=>_riskApproved=v),
          title:const Text('Independent risk gate passes',
            style:TextStyle(color:MindPalette.text)),
          subtitle:const Text('Risk veto overrides all model votes',
            style:TextStyle(color:MindPalette.muted,fontSize:11))),
      ])),
      const SizedBox(height:12),
      MindPanel(highlight:true,child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        const MindSectionTitle(title:'Simulated Decision',icon:Icons.psychology_outlined),
        MindTag(verdict,color:good?MindPalette.mint:MindPalette.yellow),
        const SizedBox(height:10),
        Text(!_dataFresh?'Data are stale: no candidate may advance.':
          !_riskApproved?'Independent risk authority rejects the proposal.':
          good?'Evidence supports review of a candidate, not live execution.':
          'Model agreement is too weak for an entry.',
          style:const TextStyle(color:MindPalette.text,height:1.6)),
        _value('Live trading','DISABLED',color:MindPalette.red),
      ])),
      const SizedBox(height:12),
      _tile('Decision history','Inspect the mock evidence journal',
        Icons.history_outlined,route:'journal'),
    ]);
  }

  Widget dependenciesScreen() {
    return _surface('Dependency Map','How models, strategies, filters and risk authority connect',[
      _chips(['Graph','Matrix'],_graphMode?'Graph':'Matrix',
        (v)=>_updateMind(()=>_graphMode=v=='Graph')),
      const SizedBox(height:10),
      MindPanel(highlight:true,child:_graphMode
        ? _dependencyDiagram()
        : Column(children:[
          _value('Momentum Breakout','Volume → Trend → Risk'),
          _value('VWAP Reclaim','Liquidity → Trend → Risk'),
          _value('Mean Reversion','Regime → Volatility → Risk'),
          _value('Compounders','Quality → Valuation → Risk'),
          _value('Options','Greeks → Margin → Risk'),
        ])),
      const SizedBox(height:12),
      _split(
        _block('Key Dependencies',Icons.link_outlined,[
          _value('Momentum Breakout','Volume filter'),
          _value('VWAP Reclaim','Liquidity filter'),
          _value('Mean Reversion','Regime model'),
          _value('Long-term growth','Quality + valuation'),
        ]),
        _block('Conflict & Signal Overlap',Icons.warning_amber_outlined,[
          _value('Momentum vs mean reversion','Conflict',color:MindPalette.red),
          _value('Two trend indicators','Correlated',color:MindPalette.yellow),
          _value('F&O capital','Disabled',color:MindPalette.red),
          _progress('Signal overlap',.68,color:MindPalette.yellow),
        ]),
      ),
      const SizedBox(height:12),
      _block('Portfolio Exposure',Icons.pie_chart_outline,[
        _progress('Technology',.41),_progress('Industrials',.17),
        _progress('Consumer',.17),_progress('Financials',.08),
        _action('Inspect correlated exposure','portfolio'),
      ]),
    ]);
  }

  Widget _dependencyDiagram()=>SizedBox(height:205,child:SingleChildScrollView(
    scrollDirection:Axis.horizontal,
    child:SizedBox(width:650,child:Column(
      mainAxisAlignment:MainAxisAlignment.spaceEvenly,
      children:[
        Row(mainAxisAlignment:MainAxisAlignment.spaceAround,children:const [
          MindTag('VWAP Reclaim'),MindTag('Mean Reversion'),MindTag('Regime Model',color:MindPalette.blue),
        ]),
        Row(mainAxisAlignment:MainAxisAlignment.spaceEvenly,children:const [
          MindTag('Momentum Breakout'),Icon(Icons.arrow_forward,color:MindPalette.mint),
          MindTag('Volume Filter',color:MindPalette.purple),
          Icon(Icons.arrow_forward,color:MindPalette.mint),
          MindTag('Trend Model',color:MindPalette.blue),
        ]),
        const Icon(Icons.arrow_downward,color:MindPalette.mint),
        Row(mainAxisAlignment:MainAxisAlignment.center,children:const [
          MindTag('CENTRAL DECISION ENGINE'),
          Icon(Icons.arrow_forward,color:MindPalette.mint),
          MindTag('RISK AUTHORITY',color:MindPalette.yellow),
        ]),
      ],
    )),
  ));
}
