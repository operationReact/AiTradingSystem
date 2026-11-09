// Prototype UI source may use existing explicit string concatenation for compact mock labels.
// ignore_for_file: prefer_interpolation_to_compose_strings
part of 'mind_workspace_page.dart';

extension _MindResearchScreens on _MindWorkspaceState {
  static const _experiments=<(String,String,String,String,double,double,double,int)>[
    ('Momentum Breakout v3','Backtest','Complete','NIFTY 100',22.4,-14.2,1.45,623),
    ('Long-term Compounders v2','Shadow','Research','NIFTY 500',16.2,-18.8,1.22,118),
    ('News Momentum v1','Backtest','Running','NIFTY 200',9.3,-12.5,.86,321),
    ('Mean Reversion v4','Paper','Research','NIFTY 50',12.6,-9.4,1.14,894),
    ('CatBoost Alpha A','Backtest','Inconclusive','NIFTY 500',7.1,-21.4,.49,493),
    ('TimeMixer Challenger','Backtest','Research','Synthetic 5m',5.8,-16.4,.55,137),
    ('Anomaly Guard v1','Walk-forward','Research','India market',0,0,0,1038),
    ('Volatility Squeeze v2','Paper','Pending','NIFTY 100',10.7,-13.6,.92,305),
  ];

  Widget researchScreen() {
    final shown=_experiments.where((e)=>_researchMode=='All'||
      _researchMode==e.$2||(_researchMode=='Backtests'&&e.$2=='Backtest')).toList();
    return _surface('Research & Backtesting',
      'Experiments, validation, paper and shadow performance',[
      _chips(['All','Backtests','Paper','Shadow','Walk-forward'],
        _researchMode,(v)=>_updateMind(()=>_researchMode=v)),
      const SizedBox(height:10),
      _metricGrid([
        ('Running','8','Illustrative experiments'),
        ('Passed','5','Synthetic test outcomes'),
        ('Inconclusive','2','Research'),
        ('Failed','1','Hypothetical'),
      ]),
      const SizedBox(height:13),
      _section('Equity Curve · Momentum Breakout v3',Icons.show_chart,[
        MindPanel(highlight:true,child:Column(children:[
          Row(children:const [
            MindTag('BACKTEST ONLY',color:MindPalette.purple),
            Spacer(),
            Text('2018–2025 · NIFTY 100',
              style:TextStyle(color:MindPalette.muted,fontSize:11)),
          ]),
          const SizedBox(height:10),
          _mockChart(seed:37,height:205),
          _value('Fictional total return','+238.4%'),
          _value('Benchmark return','+68.1%'),
          _value('All transaction costs','Not independently validated',
            color:MindPalette.yellow),
        ])),
      ]),
      _metricGrid([
        ('CAGR','21.8%','Backtest sample'),
        ('Max drawdown','−12.9%','Synthetic'),
        ('Sharpe','1.72','Unverified'),
        ('Sample size','1,842','Simulated observations'),
      ]),
      const SizedBox(height:13),
      _section('Current Experiments',Icons.science_outlined,[
        for(final experiment in shown)
          Padding(padding:const EdgeInsets.only(bottom:8),
            child:_tile(experiment.$1,
              experiment.$2+' · '+experiment.$3+' · '+experiment.$4,
              Icons.biotech_outlined,
              trailing:experiment.$5.toStringAsFixed(1)+'% CAGR',
              color:experiment.$3=='Inconclusive'?MindPalette.yellow:MindPalette.mint,
              tap:()=>_selectExperiment(experiment.$1))),
        if(shown.isEmpty) _empty('No experiments in this mode.'),
      ]),
      _split(
        _block('Champion vs Challenger',Icons.emoji_events_outlined,[
          _value('MB v3 champion','+21.8% CAGR'),
          _value('MB v4 challenger','+24.1% CAGR'),
          _value('Drawdown delta','Challenger worse'),
          _value('Model promotion','No automatic promotion'),
        ]),
        _block('Research Evidence Quality',Icons.fact_check_outlined,[
          _value('Backtest','Complete (mock)'),
          _value('Walk-forward','Partial'),
          _value('Paper','Research'),
          _value('Shadow','Pending'),
          _value('Live','Not authorized',color:MindPalette.red),
        ]),
      ),
    ]);
  }

  Widget experimentDetailScreen() {
    final experiment=_experiments.firstWhere((e)=>e.$1==_selectedExperiment,
      orElse:()=>_experiments.first);
    return _surface(experiment.$1,
      'Experiment result · '+experiment.$4+' · '+experiment.$2,[
      MindPanel(highlight:true,child:Column(children:[
        _mockChart(seed:_experiments.indexOf(experiment)+31,height:236),
        _value('Experiment status',experiment.$3),
        _value('CAGR (synthetic)',experiment.$5.toStringAsFixed(1)+'%'),
        _value('Maximum drawdown',experiment.$6.toStringAsFixed(1)+'%',
          color:MindPalette.red),
        _value('Sharpe (synthetic)',experiment.$7.toStringAsFixed(2)),
        _value('Observation count',experiment.$8.toString()),
      ])),
      const SizedBox(height:13),
      _split(
        _block('Methodology & Reproducibility',Icons.science_outlined,[
          _value('Universe',experiment.$4),
          _value('Time split','Point-in-time required'),
          _value('Out-of-sample','Not certified'),
          _value('Slippage','Illustrative assumption'),
          _value('Survivorship correction','Must be validated'),
        ]),
        _block('Promotion Checks',Icons.shield_outlined,[
          _value('Research candidate','Yes'),
          _value('Champion override','Not permitted'),
          _value('Paper readiness','Review'),
          _value('Shadow approval','Pending'),
          _value('Capital authority','None',color:MindPalette.red),
        ]),
      ),
      const SizedBox(height:13),
      _section('Compare Experiments',Icons.compare_arrows,[
        _tile('Research laboratory','Compare champion, challenger, and baselines',
          Icons.science_outlined,route:'research'),
        _tile('Model performance','Inspect model contribution and health',
          Icons.memory_outlined,route:'models'),
      ]),
    ],backTo:'research');
  }

  Widget performanceScreen()=>_surface('Performance & Learning',
    'Measure model, strategy and portfolio value independently',[
    _metricGrid([
      ('Simulated net return','+12.4%','No actual trades'),
      ('Research win rate','61.4%','Synthetic samples'),
      ('Max drawdown','−8.7%','Unverified'),
      ('Data confidence','Provisional','Not calibrated'),
    ]),
    const SizedBox(height:13),
    _section('Performance Over Time',Icons.show_chart,[
      MindPanel(highlight:true,child:Column(children:[
        const Row(children:[
          MindTag('RESEARCH PERFORMANCE',color:MindPalette.purple),
          Spacer(),
          Text('Mock 12M',style:TextStyle(color:MindPalette.muted,fontSize:11)),
        ]),
        const SizedBox(height:11),
        _mockChart(seed:75,height:220),
        _value('After-cost evidence','Not independently established',
          color:MindPalette.yellow),
      ])),
    ]),
    _split(
      _block('Strategy Attribution',Icons.layers_outlined,[
        _progress('Momentum',.72),
        _progress('Breakout',.61),
        _progress('Compounders',.58),
        _progress('Mean Reversion',.42),
        _progress('Event Driven',.35),
      ]),
      _block('Model Attribution',Icons.memory_outlined,[
        _progress('LightGBM',.27),
        _progress('Meta decision',.24),
        _progress('Regime HMM',.19),
        _progress('Time-series',.16),
        _progress('News NLP',.14),
      ]),
    ),
    const SizedBox(height:12),
    _section('Continuous Learning & Health',Icons.psychology_outlined,[
      _tile('Strategy decay detection',
        'Compare recent performance with independent baselines',
        Icons.trending_down,color:MindPalette.yellow,route:'strategies'),
      const SizedBox(height:8),
      _tile('Counterfactual review','Compare rejected, accepted and abstained',
        Icons.alt_route_outlined,route:'journal'),
      const SizedBox(height:8),
      _tile('Model drift and retraining','Calibration, version and promotion gates',
        Icons.monitor_heart_outlined,route:'models'),
      const SizedBox(height:8),
      _tile('Market regime breakdown','Performance conditional on regime',
        Icons.public,route:'regimes'),
    ]),
  ]);

  Widget journalScreen() {
    final items=_MindAnalyticsScreens._decisionRows.where((r)=>
      _decisionFilter=='All'||r.$3==_decisionFilter||r.$2==_decisionFilter).toList();
    return _surface('Decision Journal','Immutable-looking audit timeline of mock signals and outcomes',[
      _chips(['All','Intraday','Swing','Long-Term','ABSTAIN','WAIT',
        'Futures','Options'],_decisionFilter,
        (v)=>_updateMind(()=>_decisionFilter=v)),
      const SizedBox(height:10),
      _section('Decision Records',Icons.history_outlined,[
        for(var i=0;i<items.length;i++)
          Padding(padding:const EdgeInsets.only(bottom:8),
            child:_tile(items[i].$1+' · '+items[i].$3,
              'DEC-'+(2401+i).toString()+' · '+items[i].$2+
              ' · '+items[i].$4,
              Icons.receipt_long_outlined,
              trailing:i.isEven?'No fill':'Research',
              color:items[i].$3=='ABSTAIN'?MindPalette.yellow:MindPalette.mint,
              tap:()=>_selectDecision(items[i].$1))),
      ]),
      _section('Audit and Exports',Icons.fact_check_outlined,[
        MindPanel(child:Column(children:[
          _value('Market data source','Mock only'),
          _value('Broker order IDs','None'),
          _value('Model versioning','Illustrative'),
          _value('Event timestamps','Synthetic'),
          _value('Realized P&L','Not available'),
          const SizedBox(height:9),
          OutlinedButton.icon(
            icon:const Icon(Icons.file_download_outlined,color:MindPalette.mint),
            label:const Text('Export journal preview'),
            onPressed:()=>_mockAction('Decision journal export preview'),
          ),
        ])),
      ]),
    ]);
  }

  Widget derivativesScreen()=>_surface('Futures & Options',
    'India derivatives research: contract-aware and non-executable',[
    MindPanel(highlight:true,child:Column(children:[
      const MindSectionTitle(title:'F&O Research Modules',icon:Icons.layers_outlined),
      const MindTag('ORDER EXECUTION DISABLED',color:MindPalette.red),
      const SizedBox(height:12),
      _value('NIFTY / BANKNIFTY','Index derivatives'),
      _value('Futures','Basis, rollover, OI, margins'),
      _value('Options','Greeks, IV, strikes, expiry, payoff'),
      _value('Short selling','Borrow and delivery eligibility'),
      const Text('All prices, strikes, margin assumptions and positions are fictional.',
        style:TextStyle(color:MindPalette.muted,fontSize:11)),
    ])),
    const SizedBox(height:12),
    _section('Derivatives Strategy Families',Icons.layers_outlined,[
      for(final family in ['Futures','Options — Directional',
        'Options — Volatility','Options — Hedging','Short Selling'])
        Padding(padding:const EdgeInsets.only(bottom:8),child:_tile(family,
          MindFixtures.strategies.where((s)=>s.family==family).length.toString()+
            ' research templates · no execution authority',
          Icons.analytics_outlined,
          trailing:'Review',color:MindPalette.purple,
          tap:()=>_updateMind((){
            _family=family;
            _strategyMode='All';
            _route='strategies';
          }))),
    ]),
    _section('Derivatives Tools',Icons.calculate_outlined,[
      _tile('Option Chain','Strike, expiry, mock OI, IV and Greeks',
        Icons.table_chart_outlined,route:'options-chain'),
      const SizedBox(height:8),
      _tile('Strategy Payoff Builder','Inspect payoff and downside scenarios',
        Icons.show_chart,route:'option-builder'),
      const SizedBox(height:8),
      _tile('Risk and margin protection','Cash and derivatives capital segregation',
        Icons.shield_outlined,route:'risk'),
    ]),
    _section('Market & Broker Eligibility',Icons.rule_outlined,[
      _value('Futures orders','DISABLED',color:MindPalette.red),
      _value('Options orders','DISABLED',color:MindPalette.red),
      _value('Strategy activation','Research only',color:MindPalette.yellow),
      _value('Actual margin checks','Not connected'),
      _value('Spread and market depth','Mock values'),
      _value('Regulatory/broker checks','Required before live use'),
    ]),
  ]);

  Widget optionChainScreen()=>_surface('Option Chain',
    'Illustrative NIFTY and BANKNIFTY contract research',[
    _chips(['NIFTY','BANKNIFTY'],_optionUnderlying,
      (v)=>_updateMind((){
        _optionUnderlying=v;
        _strike=v=='NIFTY'?25500:56000;
      })),
    _chips(['26 Nov 2026','03 Dec 2026','31 Dec 2026'],'26 Nov 2026',
      (v)=>_mockAction('Expiry selected: '+v)),
    const SizedBox(height:8),
    MindPanel(highlight:true,child:Column(children:[
      _value('Underlying',_optionUnderlying+' · mock'),
      _value('Reference spot',_optionUnderlying=='NIFTY'?'25,480':'56,160'),
      _value('Selected strike',_strike.toStringAsFixed(0)),
      _value('Contract eligibility','Not verified',color:MindPalette.yellow),
    ])),
    const SizedBox(height:12),
    _section('Calls · Strike · Puts',Icons.table_chart_outlined,[
      MindPanel(child:SingleChildScrollView(scrollDirection:Axis.horizontal,
        child:DataTable(
          dataRowMinHeight:44,dataRowMaxHeight:48,
          headingTextStyle:const TextStyle(color:MindPalette.mint,fontSize:11),
          dataTextStyle:const TextStyle(color:MindPalette.text,fontSize:11),
          columnSpacing:15,horizontalMargin:7,
          columns:const [
            DataColumn(label:Text('Call LTP')),
            DataColumn(label:Text('Call OI')),
            DataColumn(label:Text('IV%')),
            DataColumn(label:Text('Strike')),
            DataColumn(label:Text('Put OI')),
            DataColumn(label:Text('Put LTP')),
            DataColumn(label:Text('Delta')),
          ],
          rows:[
            for(var i=-5;i<=5;i++)
              DataRow(selected:_strike==(_optionUnderlying=='NIFTY'?25500:56000)+i*100,
                onSelectChanged:(_)=>_updateMind(()=>_strike=
                  (_optionUnderlying=='NIFTY'?25500:56000)+i*100.0),
                cells:[
                  DataCell(Text((210-i*21).toStringAsFixed(1))),
                  DataCell(Text((120+i.abs()*19).toString()+'K')),
                  DataCell(Text((16+i.abs()*.6).toStringAsFixed(1))),
                  DataCell(Text(((_optionUnderlying=='NIFTY'?25500:56000)+i*100).toString(),
                    style:const TextStyle(color:MindPalette.mint,fontWeight:FontWeight.w800))),
                  DataCell(Text((138+i.abs()*15).toString()+'K')),
                  DataCell(Text((90+i*20).toStringAsFixed(1))),
                  DataCell(Text((.52-i*.07).toStringAsFixed(2))),
                ],
              ),
          ],
        ),
      )),
    ]),
    _section('Greeks & Liquidity',Icons.ssid_chart_outlined,[
      _split(
        _block('Greeks · Sample',Icons.calculate,[
          _value('Delta','0.52'),_value('Gamma','0.0023'),
          _value('Theta','−8.2'),_value('Vega','12.1'),
        ]),
        _block('Liquidity · Sample',Icons.bar_chart_outlined,[
          _value('Bid / Ask','Simulated'),
          _value('Open interest','Mock'),
          _value('Spread','Unverified'),
          _value('Freshness','Not connected'),
        ]),
      ),
      const SizedBox(height:12),
      _tile('Build an options payoff','Use selected strike · no execution',
        Icons.show_chart,route:'option-builder'),
    ]),
  ]);

  Widget optionBuilderScreen() {
    final spot=_optionUnderlying=='NIFTY'?25480.0:56160.0;
    final width=math.max(100.0,spot*.01);
    final samplePayoff=_estimatePayoff(spot,spot,width);
    return _surface('Options Payoff Builder',
      'Evaluate illustrative defined-risk options scenarios',[
      _chips(['Bull Call Spread','Bear Put Spread','Long Straddle',
        'Iron Condor','Covered Call','Protective Put'],
        _optionStructure,(v)=>_updateMind(()=>_optionStructure=v)),
      const SizedBox(height:10),
      _chips(['NIFTY','BANKNIFTY'],_optionUnderlying,
        (v)=>_updateMind((){
          _optionUnderlying=v;
          _strike=v=='NIFTY'?25500:56000;
        })),
      MindPanel(highlight:true,child:Column(children:[
        _value('Structure',_optionStructure),
        _value('Underlying',_optionUnderlying),
        _value('Illustrative short/long strike',_strike.toStringAsFixed(0)),
        Slider(value:_strike,
          min:_optionUnderlying=='NIFTY'?24500:54000,
          max:_optionUnderlying=='NIFTY'?26500:58000,
          divisions:20,activeColor:MindPalette.mint,
          onChanged:(v)=>_updateMind(()=>_strike=v)),
        _value('Reference spot',spot.toStringAsFixed(0)),
        _value('Premium assumption','Illustrative · 100 points'),
        _value('Value at unchanged spot',samplePayoff.toStringAsFixed(0)+' points'),
        const MindTag('NO LIVE ORDER PERMITTED',color:MindPalette.red),
      ])),
      const SizedBox(height:13),
      _section('Expiry Payoff · Hypothetical',Icons.show_chart,[
        MindPanel(child:Column(children:[
          SizedBox(height:220,width:double.infinity,
            child:CustomPaint(painter:_MindPayoffPainter(
              strike:_strike,spot:spot,structure:_optionStructure))),
          const SizedBox(height:8),
          _value('Underlying -5%',
            _estimatePayoff(spot*.95,spot,width).toStringAsFixed(0)+' points',
            color:MindPalette.yellow),
          _value('Underlying +5%',
            _estimatePayoff(spot*1.05,spot,width).toStringAsFixed(0)+' points'),
          _value('Portfolio margin','Not calculated; verify with broker'),
        ])),
      ]),
      _section('Risk and Eligibility',Icons.shield_outlined,[
        _tile('Review margin and Greeks',
          'The broker risk engine must check every leg',
          Icons.shield_outlined,route:'risk'),
        _tile('Back to option chain','Select contract and expiry',
          Icons.table_chart_outlined,route:'options-chain'),
        const SizedBox(height:8),
        const MindTag('Research only · Not financial advice',color:MindPalette.yellow),
      ]),
    ]);
  }

  double _estimatePayoff(double atExpiry,double spot,double width) {
    final x=atExpiry-_strike;
    const premium=100.0;
    return switch(_optionStructure) {
      'Bull Call Spread' => math.min(math.max(x,0),width)-premium,
      'Bear Put Spread' => math.min(math.max(-x,0),width)-premium,
      'Long Straddle' => x.abs()-premium*2,
      'Iron Condor' => premium-math.min(math.max(x.abs()-width/2,0),width),
      'Covered Call' => atExpiry-spot-math.max(x,0)+premium,
      'Protective Put' => atExpiry-spot+math.max(-x,0)-premium,
      _ => 0.0,
    };
  }
}

class _MindPayoffPainter extends CustomPainter {
  const _MindPayoffPainter({required this.spot,required this.strike,required this.structure});
  final double spot;
  final double strike;
  final String structure;
  double _payoff(double s) {
    final x=s-strike;
    final w=spot*.01;
    return switch(structure) {
      'Bull Call Spread' => math.min(math.max(x,0),w)-100,
      'Bear Put Spread' => math.min(math.max(-x,0),w)-100,
      'Long Straddle' => x.abs()-200,
      'Iron Condor' => 100-math.min(math.max(x.abs()-w/2,0),w),
      'Covered Call' => s-spot-math.max(x,0)+100,
      'Protective Put' => s-spot+math.max(-x,0)-100,
      _ => 0.0,
    };
  }
  @override
  void paint(Canvas canvas, Size size) {
    final axis=Paint()..color=const Color(0xFF34534A)..strokeWidth=1;
    canvas.drawLine(Offset(0,size.height*.55),
      Offset(size.width,size.height*.55),axis);
    final values=<double>[];
    for(var i=0;i<=64;i++){
      final s=spot*(.92+.16*i/64);
      values.add(_payoff(s));
    }
    final bound=math.max(1.0,values.map((v)=>v.abs()).reduce(math.max));
    final path=Path();
    for(var i=0;i<values.length;i++){
      final y=(size.height*.5-values[i]/bound*size.height*.43)
        .clamp(5.0,size.height-5.0).toDouble();
      final x=size.width*i/64;
      if(i==0){path.moveTo(x,y);}else{path.lineTo(x,y);}
    }
    canvas.drawPath(path,Paint()..color=MindPalette.mint
      ..style=PaintingStyle.stroke..strokeWidth=2.2);
    canvas.drawLine(Offset(size.width/2,0),Offset(size.width/2,size.height),
      Paint()..color=MindPalette.yellow.withValues(alpha:.35)..strokeWidth=1);
  }
  @override
  bool shouldRepaint(covariant _MindPayoffPainter old)=>
    old.spot!=spot||old.strike!=strike||old.structure!=structure;
}
