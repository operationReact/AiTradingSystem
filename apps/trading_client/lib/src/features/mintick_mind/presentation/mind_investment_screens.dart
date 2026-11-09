part of 'mind_workspace_page.dart';

extension _MindInvestmentScreens on _MindWorkspaceState {
  static const _companies=<(String,String,String,int,int,int,String,String)>[
    ('TITAN','Titan Company Ltd.','Consumer Discretionary',91,86,58,
      'Brand, distribution and category expansion',
      'Premium valuation, gold demand and margin sensitivity'),
    ('CDSL','Central Depository Services','Financial Infrastructure',83,89,57,
      'Demat adoption and financial infrastructure scale',
      'Market volume cyclicality, pricing and valuation'),
    ('IREDA','Indian Renewable Energy Development Agency','Renewables',73,85,65,
      'Potential clean-energy financing growth',
      'Credit quality, funding costs and policy risk'),
    ('POLYCAB','Polycab India','Electrical Equipment',86,82,63,
      'Electrical distribution expansion and infrastructure growth',
      'Working capital and elevated expectations'),
    ('ASTERDM','Aster DM Healthcare','Healthcare',78,76,70,
      'Hospital capacity and operating leverage',
      'Competitive pressure and expansion execution'),
    ('KPITTECH','KPIT Technologies','Automotive Technology',82,87,55,
      'Engineering and software content growth',
      'Client concentration and high valuation'),
    ('KAYNES','Kaynes Technology','Electronics',75,89,48,
      'Electronics manufacturing opportunity',
      'Capex, working capital and scaling execution'),
    ('DIXON','Dixon Technologies','Electronics',81,88,49,
      'Manufacturing volume growth and partnerships',
      'Concentration, valuation and margin risk'),
    ('CAMPUS','Campus Activewear','Footwear',72,70,78,
      'Domestic brand distribution opportunities',
      'Consumer demand and competitive pricing'),
    ('HAPPSTMNDS','Happiest Minds Technologies','IT Services',74,73,70,
      'Digital services transformation exposure',
      'IT demand cycles and global competition'),
  ];

  Widget multibaggerScreen() {
    final visible=_companies.where((c)=>_mbStage=='All'||
      (_mbStage=='Deep Review'&&(c.$1=='CDSL'||c.$1=='KPITTECH'))||
      (_mbStage=='Thesis Active'&&(c.$1=='TITAN'||c.$1=='POLYCAB'))||
      (_mbStage=='Screening'&&!['TITAN','POLYCAB','CDSL','KPITTECH'].contains(c.$1))
    ).toList();
    return _surface('Multibagger Discovery','Long-horizon compounder research · Indian equities',[
      MindPanel(highlight:true,child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        const Row(children:[
          Icon(Icons.diamond_outlined,color:MindPalette.mint,size:29),
          SizedBox(width:11),
          Expanded(child:Text('Multibagger Discovery Engine',
            style:TextStyle(color:MindPalette.text,fontSize:18,fontWeight:FontWeight.w800))),
        ]),
        const SizedBox(height:7),
        const Text('Research companies that might compound over years. No guaranteed 5x–10x returns.',
          style:TextStyle(color:MindPalette.muted,fontSize:12)),
        const SizedBox(height:10),
        _metricGrid([
          ('Universe','1,248','Mock screened'),
          ('Shortlisted','42','Hypothetical queue'),
          ('Deep reviews','12','Research tasks'),
          ('Verified 10x','0','No validated forecasts'),
        ]),
      ])),
      const SizedBox(height:13),
      _chips(['All','Screening','Deep Review','Thesis Active'],_mbStage,
        (v)=>setState(()=>_mbStage=v)),
      const SizedBox(height:9),
      _section('Investment Candidates',Icons.track_changes_outlined,[
        for(final company in visible)
          Padding(padding:const EdgeInsets.only(bottom:8),
            child:MindPanel(onTap:()=>_selectCompany(company.$1),
              child:Column(children:[
                Row(children:[
                  CircleAvatar(radius:20,backgroundColor:const Color(0xFF15372E),
                    child:Text(company.$1.substring(0,1),
                      style:const TextStyle(color:MindPalette.mint,fontWeight:FontWeight.w800))),
                  const SizedBox(width:10),
                  Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
                    Text(company.$1+' · '+company.$2,maxLines:1,overflow:TextOverflow.ellipsis,
                      style:const TextStyle(color:MindPalette.text,fontWeight:FontWeight.w800)),
                    Text(company.$3,style:const TextStyle(color:MindPalette.muted,fontSize:11)),
                  ])),
                  MindTag(company.$1=='TITAN'||company.$1=='POLYCAB'?'Thesis Active':
                    company.$1=='CDSL'||company.$1=='KPITTECH'?'Deep Review':'Screening',
                    color:company.$1=='TITAN'?MindPalette.mint:MindPalette.purple),
                  const Icon(Icons.chevron_right,color:MindPalette.muted),
                ]),
                const SizedBox(height:10),
                Row(children:[
                  Expanded(child:_qualityTile('Quality',company.$4,MindPalette.mint)),
                  const SizedBox(width:6),
                  Expanded(child:_qualityTile('Growth',company.$5,MindPalette.blue)),
                  const SizedBox(width:6),
                  Expanded(child:_qualityTile('Valuation',company.$6,MindPalette.yellow)),
                ]),
              ]),
            ),
          )),
      ]),
      _section('Research Priorities',Icons.fact_check_outlined,[
        _tile('Early business inflections','Orders, margins, operating leverage',
          Icons.trending_up,route:'events'),
        _tile('Financial statement quality','Cash flows, leverage, corporate governance',
          Icons.receipt_long_outlined,route:'thesis-detail'),
        _tile('Company and sector exposure','Avoid crowded positions',
          Icons.pie_chart_outline,route:'portfolio'),
      ]),
    ]);
  }

  Widget _qualityTile(String name,int score,Color color)=>Container(
    padding:const EdgeInsets.all(8),
    decoration:BoxDecoration(color:color.withValues(alpha:.06),
      border:Border.all(color:color.withValues(alpha:.4)),
      borderRadius:BorderRadius.circular(10)),
    child:Column(children:[
      Text(name,style:const TextStyle(color:MindPalette.muted,fontSize:10)),
      Text(score.toString()+'/100',style:TextStyle(color:color,fontWeight:FontWeight.w800)),
      const SizedBox(height:5),
      LinearProgressIndicator(value:score/100,minHeight:4,
        color:color,backgroundColor:MindPalette.border),
    ]),
  );

  Widget thesisScreen() {
    final company=_companies.firstWhere((c)=>c.$1==_selectedCompany,
      orElse:()=>_companies.first);
    return _surface(company.$1+' · Investment Thesis','Fundamental research · not an investment recommendation',[
      MindPanel(highlight:true,child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Row(children:[
          CircleAvatar(radius:24,backgroundColor:const Color(0xFF153E32),
            child:Text(company.$1[0],style:const TextStyle(color:MindPalette.text,fontSize:19))),
          const SizedBox(width:10),
          Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
            Text(company.$2,style:const TextStyle(color:MindPalette.text,
              fontSize:17,fontWeight:FontWeight.w800)),
            Text(company.$3,style:const TextStyle(color:MindPalette.muted,fontSize:11)),
          ])),
          IconButton(
            tooltip:_watchlist.contains(company.$1)?'Remove watch':'Add to watch',
            onPressed:()=>setState((){
              if(!_watchlist.add(company.$1)) _watchlist.remove(company.$1);
            }),
            icon:Icon(_watchlist.contains(company.$1)?Icons.star:Icons.star_border,
              color:MindPalette.mint)),
        ]),
        const SizedBox(height:11),
        const MindSectionTitle(title:'Investment Thesis',icon:Icons.track_changes_outlined),
        Text(company.$7+'. The scenario should be updated after each relevant financial filing.',
          style:const TextStyle(color:MindPalette.text,fontSize:13,height:1.6)),
      ])),
      const SizedBox(height:12),
      _section('Return Scenarios · 5–7 Years',Icons.auto_graph_outlined,[
        _scenarioCards(),
        const SizedBox(height:7),
        const Text('Scenario multiples are hypothetical, not calibrated forecasts or price targets.',
          style:TextStyle(color:MindPalette.muted,fontSize:11)),
      ]),
      _split(
        _block('Business Quality',Icons.emoji_events_outlined,[
          _value('Quality research score',company.$4.toString()+'/100'),
          _value('Growth research score',company.$5.toString()+'/100'),
          _value('Valuation research score',company.$6.toString()+'/100'),
          _value('Management review','Pending'),
          _value('Cash-flow validation','Required'),
          _value('Historical financials','Point-in-time required'),
        ]),
        _block('Risk & Red Flags',Icons.warning_amber_outlined,[
          Text(company.$8,style:const TextStyle(color:MindPalette.red,fontSize:12)),
          const SizedBox(height:8),
          _value('Governance','Review disclosures'),
          _value('Promoter pledging','Verify filings'),
          _value('Dilution','Track shares outstanding'),
          _value('Cyclicality','Stress test'),
          _value('Liquidity','Verify spread / volume'),
        ]),
      ),
      const SizedBox(height:13),
      _section('What Would Change The Thesis?',Icons.alt_route_outlined,[
        _tile('Stronger thesis','Earnings revisions, FCF, sustained ROCE',
          Icons.trending_up),
        _tile('Weaker thesis','Debt, governance, slower compounding',
          Icons.trending_down,color:MindPalette.red),
        _tile('Market events','Cross-check company filings and catalysts',
          Icons.public,route:'events'),
        _tile('Portfolio sizing','Separate long-term capital allocation',
          Icons.pie_chart_outline,route:'portfolio'),
      ]),
    ],backTo:'multibagger');
  }

  Widget _scenarioCards()=>Row(children:[
    for(final (label,multiple,color) in [
      ('Bull','5–7x',MindPalette.mint),
      ('Base','2–4x',MindPalette.yellow),
      ('Bear','0.5–1.5x',MindPalette.purple)
    ])
      Expanded(child:Padding(padding:const EdgeInsets.only(right:6),
        child:Container(
          padding:const EdgeInsets.symmetric(vertical:15,horizontal:5),
          decoration:BoxDecoration(
            borderRadius:BorderRadius.circular(12),
            color:color.withValues(alpha:.1),
            border:Border.all(color:color.withValues(alpha:.5))),
          child:Column(children:[
            Text(label,style:TextStyle(color:color,fontSize:11)),
            const SizedBox(height:5),
            Text(multiple,style:TextStyle(color:color,fontSize:18,fontWeight:FontWeight.w800)),
          ]),
        ))),
  ]);

  Widget portfolioScreen() {
    final portfolioValue=2846730;
    final stressed=portfolioValue*(1+_scenarioShock/100);
    return _surface('Portfolio Digital Twin','Capital allocation, correlated exposure and hypothetical stress',[
      _metricGrid([
        ('Net Portfolio','₹28,46,730','Illustrative holdings'),
        ('Day move','+1.97%','Simulated only'),
        ('Cash','₹4,32,510','Not broker-verified'),
        ('Risk used','34%','Fictional risk budget'),
      ]),
      const SizedBox(height:13),
      _split(
        _block('Capital Allocation',Icons.pie_chart_outline,[
          _progress('Core equity',.42),
          _progress('Intraday',.12),
          _progress('Swing',.14),
          _progress('Long-term',.18),
          _progress('Cash',.10),
          _progress('F&O research',.04,color:MindPalette.purple),
        ]),
        _block('Exposure Watch',Icons.shield_outlined,[
          _progress('Banking & Finance',.248),
          _progress('IT & Services',.185),
          _progress('Auto',.117),
          _progress('Healthcare',.096),
          _progress('Energy',.079),
          const MindTag('Banking sector near sample cap',
            color:MindPalette.yellow),
        ]),
      ),
      const SizedBox(height:13),
      _section('Capital Allocation Suggestions',Icons.track_changes_outlined,[
        _tile('Large-cap banks','Proposed capital: 8% · simulated',
          Icons.account_balance_outlined,trailing:'Review'),
        const SizedBox(height:7),
        _tile('IT services','Proposed capital: 5% · simulated',
          Icons.computer_outlined,trailing:'Review'),
        const SizedBox(height:7),
        _tile('Renewable energy','Proposed capital: 4% · simulated',
          Icons.bolt_outlined,trailing:'Research'),
      ]),
      _section('Portfolio Digital Twin',Icons.auto_graph_outlined,[
        MindPanel(highlight:true,child:Column(children:[
          _value('Scenario shock',_scenarioShock.toStringAsFixed(0)+'%'),
          Slider(value:_scenarioShock,min:-40,max:20,divisions:60,
            activeColor:MindPalette.mint,onChanged:(v)=>setState(()=>_scenarioShock=v)),
          _value('Current fictional portfolio','₹28,46,730'),
          _value('Stressed value','₹'+stressed.round().toString(),
            color:_scenarioShock<0?MindPalette.red:MindPalette.mint),
          _value('Change vs sample baseline',
            (_scenarioShock>0?'+':'')+_scenarioShock.toStringAsFixed(1)+'%',
            color:_scenarioShock<0?MindPalette.red:MindPalette.mint),
          const Text('A linear what-if approximation, not a prediction or portfolio stress model.',
            style:TextStyle(color:MindPalette.muted,fontSize:11)),
        ])),
      ]),
      _section('Portfolio Controls',Icons.shield_outlined,[
        _tile('Risk center','Pre-trade checks and kill switch',
          Icons.shield_outlined,route:'risk'),
        _tile('Short-selling eligibility','Cash intraday vs delivery/borrow rules',
          Icons.short_text,route:'derivatives'),
      ]),
    ]);
  }

  Widget riskScreen()=>_surface('Risk & Capital Authority',
    'Independent risk checks remain mandatory regardless of intelligence votes',[
    MindPanel(highlight:true,child:Column(children:[
      const MindSectionTitle(title:'Risk Limit Simulation',icon:Icons.shield_outlined),
      _value('Max daily loss',_riskDaily.toStringAsFixed(1)+'%'),
      Slider(value:_riskDaily,min:.5,max:5,divisions:18,
        activeColor:MindPalette.mint,onChanged:(v)=>setState(()=>_riskDaily=v)),
      _value('Capital per strategy',_riskPerStrategy.toStringAsFixed(0)+'%'),
      Slider(value:_riskPerStrategy,min:1,max:50,divisions:49,
        activeColor:MindPalette.mint,onChanged:(v)=>setState(()=>_riskPerStrategy=v)),
      _value('Emergency stop',_emergencyStop?'SIMULATED ACTIVE':'Not activated',
        color:_emergencyStop?MindPalette.red:MindPalette.mint),
      SwitchListTile.adaptive(title:const Text('Demo emergency stop',
        style:TextStyle(color:MindPalette.text)),
        subtitle:const Text('Local visual state only — production controls unchanged',
          style:TextStyle(color:MindPalette.muted,fontSize:11)),
        activeTrackColor:MindPalette.red,value:_emergencyStop,
        onChanged:(v)=>setState(()=>_emergencyStop=v)),
    ])),
    const SizedBox(height:12),
    _split(
      _block('Deterministic Risk Gates',Icons.verified_user_outlined,[
        _value('Order mode','Mock only'),
        _value('Risk budget','Requires Java authority'),
        _value('Capital availability','Not broker-connected'),
        _value('Sector caps','Check before entry'),
        _value('Liquidity','Required'),
        _value('Daily loss circuit breaker','Required'),
      ]),
      _block('Position & Short Rules',Icons.rule_outlined,[
        _value('Intraday cash short','Same-day eligibility'),
        _value('CNC naked short','Not permitted'),
        _value('Borrow / SLB','Separate eligibility'),
        _value('Futures contracts','Not enabled'),
        _value('Options exposures','No capital authority'),
        _value('Broker reconciliation','Mandatory'),
      ]),
    ),
    const SizedBox(height:12),
    _section('Stress and Protection',Icons.warning_amber_outlined,[
      _tile('Stress-test hypothetical capital','Explore a portfolio price shock',
        Icons.pie_chart_outline,route:'portfolio'),
      _tile('Decision veto simulation','See risk override a model recommendation',
        Icons.psychology_outlined,route:'decision-lab'),
      _tile('Execution safety configuration','Mock-only controls',
        Icons.settings_outlined,route:'controls'),
    ]),
  ]);

  Widget regimesScreen()=>_surface('Market Regimes',
    'Identify trending, ranging, volatile and low-liquidity conditions',[
    MindPanel(highlight:true,child:Column(children:[
      const MindSectionTitle(title:'Indian Market Posture',icon:Icons.public),
      const MindTag('SELECTIVE BULLISH · SYNTHETIC'),
      const SizedBox(height:12),
      _mockChart(seed:19,height:195),
      _value('NIFTY 50','Trending · moderate confidence'),
      _value('Bank NIFTY','Range-bound · conflicting breadth'),
      _value('India VIX','Volatility watch'),
      _value('Advance / decline','Mixed'),
    ])),
    const SizedBox(height:13),
    _section('Strategy Eligibility by Regime',Icons.layers_outlined,[
      _tile('Trending market','Momentum, breakout, trend following',
        Icons.trending_up,trailing:'Eligible',route:'strategies'),
      const SizedBox(height:7),
      _tile('Range-bound market','Mean reversion and spreads',
        Icons.repeat,trailing:'Selective',route:'strategies'),
      const SizedBox(height:7),
      _tile('High volatility','Reduce leverage and size',
        Icons.warning_amber,trailing:'Risk',color:MindPalette.yellow,route:'risk'),
      const SizedBox(height:7),
      _tile('Low liquidity','Abstain from execution candidates',
        Icons.block,trailing:'ABSTAIN',color:MindPalette.red,route:'decision-lab'),
    ]),
    _split(
      _block('Regime Probability · Sample',Icons.analytics_outlined,[
        _progress('Trending',.46),
        _progress('Range bound',.28),
        _progress('Volatile',.18),
        _progress('Uncertain',.08),
      ]),
      _block('Cross-Asset Context',Icons.public,[
        _value('Crude oil','Scenario watch'),
        _value('USD / INR','FX sensitivity'),
        _value('US equities','Global risk-on proxy'),
        _value('Interest rates','Growth / valuation impact'),
        _value('Sector rotation','Event intelligence'),
      ]),
    ),
  ]);

  Widget eventsScreen()=>_surface('Events & Knowledge Graph',
    'Trace corporate announcements, macro shocks and sector relationships',[
    _metricGrid([
      ('Event records','6','Synthetic feed'),
      ('Corporate','3','Unverified sample'),
      ('Macro','2','Scenario links'),
      ('Risk triggers','1','Mock abstention'),
    ]),
    const SizedBox(height:12),
    _section('Event Intelligence Timeline',Icons.history_outlined,[
      for(final entry in <(String,String,String,String,String)>[
        ('09:20','TCS · Results','Corporate filing monitored','Earnings catalyst','High'),
        ('10:04','POLYCAB · Sector','Industrials relative strength','Sector rotation','Watch'),
        ('10:31','RELIANCE · Crude','Commodity shock scenario','Macro context','High'),
        ('11:22','IREDA · Governance','Illustrative filing review','Research gate','Watch'),
        ('12:14','NIFTY50 · Regime','Trend conviction declined','Strategy gate','Medium'),
        ('13:42','CDSL · Liquidity','Wider spread condition','No-entry gate','High'),
      ])
        Padding(padding:const EdgeInsets.only(bottom:7),
          child:_tile(entry.$2,entry.$1+' IST · '+entry.$3+' · '+entry.$4,
            Icons.article_outlined,trailing:entry.$5,
            color:entry.$5=='High'?MindPalette.yellow:MindPalette.mint,
            tap:()=>_mockAction('Event details opened'))),
    ]),
    _section('Event Propagation Graph',Icons.hub_outlined,[
      MindPanel(highlight:true,child:Column(children:[
        const MindTag('Rising input commodity costs',color:MindPalette.yellow),
        const Icon(Icons.arrow_downward,color:MindPalette.mint),
        Row(children:const [
          Expanded(child:MindTag('Manufacturers: margin pressure',color:MindPalette.red)),
          SizedBox(width:7),
          Expanded(child:MindTag('Producers: possible benefit')),
        ]),
        const Icon(Icons.arrow_downward,color:MindPalette.mint),
        const MindTag('Check company exposure + valuation + regime'),
        const SizedBox(height:10),
        const Text('These relationships are hypotheses, not price causation claims.',
          style:TextStyle(color:MindPalette.muted,fontSize:11)),
      ])),
    ]),
    _section('Investigate Impact',Icons.search_outlined,[
      _tile('Fundamental company discovery','Revisit company-specific investment theses',
        Icons.diamond_outlined,route:'multibagger'),
      _tile('Market regime transitions','React to changing market conditions',
        Icons.public,route:'regimes'),
      _tile('Decision outcomes','Check whether an event affected proposals',
        Icons.history_outlined,route:'journal'),
    ]),
  ]);
}
