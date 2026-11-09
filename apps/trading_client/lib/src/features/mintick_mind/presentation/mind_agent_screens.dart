// Prototype UI source may use existing explicit string concatenation for compact mock labels.
// ignore_for_file: prefer_interpolation_to_compose_strings
part of 'mind_workspace_page.dart';

/// Ten additional screens from the enhanced user-approved clickable prototype.
/// Read-only research fixtures; no broker, portfolio, or execution dependencies.
extension _MintickMindAgentScreens on _MindWorkspaceState {
  Widget _agentNotice() => MindPanel(child:Row(children:[
    const Icon(Icons.info_outline,color:MindPalette.yellow,size:20),
    const SizedBox(width:9),
    const Expanded(child:Text('AGENT RESEARCH · SYNTHETIC INDIAN-MARKET DATA · NO VERIFIED SIGNALS OR ORDERS',
      style:TextStyle(color:MindPalette.yellow,fontSize:10,fontWeight:FontWeight.w700))),
    TextButton(onPressed:()=>_go('coverage'),child:const Text('43 screens',
      style:TextStyle(color:MindPalette.mint,fontSize:11))),
  ]));

  Widget _agentLinks(){
    const routes=<(String,String,IconData)>[
      ('intelligence-hub','Decision Hub',Icons.hub_outlined),
      ('agent-council','Agent Council',Icons.groups_outlined),
      ('agent-evidence','Evidence',Icons.source_outlined),
      ('regime-weights','Regime Weights',Icons.tune_outlined),
      ('signal-scorecard','Scorecard',Icons.assessment_outlined),
      ('confidence-lab','Calibration',Icons.track_changes_outlined),
      ('shadow-arena','Shadow Arena',Icons.science_outlined),
      ('institutional-flow','FII / DII Flow',Icons.account_balance_outlined),
      ('daily-verdict','Daily Verdict',Icons.event_note_outlined),
      ('culprit-catcher','Culprit Catcher',Icons.bug_report_outlined),
    ];
    return Wrap(spacing:7,runSpacing:7,children:[
      for(final r in routes) OutlinedButton.icon(
        key:ValueKey('mind-agent-link-'+r.$1),
        onPressed:()=>_go(r.$1),
        style:OutlinedButton.styleFrom(
          foregroundColor:_route==r.$1?MindPalette.mint:MindPalette.text,
          side:BorderSide(color:_route==r.$1?MindPalette.green:MindPalette.border)),
        icon:Icon(r.$3,size:15),
        label:Text(r.$2,style:const TextStyle(fontSize:11))),
    ]);
  }
  Widget _agentPage(String title,String subtitle,List<Widget> content) =>
    _surface(title,subtitle,[
      _agentNotice(),const SizedBox(height:10),_agentLinks(),
      const SizedBox(height:16),...content,
    ]);
  Widget _agentStep(int n,String heading,String details,
      {String? route,Color color=MindPalette.mint})=>MindPanel(
    onTap:route==null?null:()=>_go(route),
    child:Row(crossAxisAlignment:CrossAxisAlignment.start,children:[
      CircleAvatar(radius:16,backgroundColor:color.withValues(alpha:.16),
        child:Text('$n',style:TextStyle(color:color,fontWeight:FontWeight.w800))),
      const SizedBox(width:12),
      Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Text(heading,style:const TextStyle(color:MindPalette.text,
          fontWeight:FontWeight.w800,fontSize:13)),
        const SizedBox(height:4),
        Text(details,style:const TextStyle(color:MindPalette.muted,fontSize:11,height:1.4)),
      ])),
      if(route!=null)const Icon(Icons.chevron_right,color:MindPalette.muted),
    ]));
  Widget _agentCaution(String heading,String details) => MindPanel(
    child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
      Text(heading,style:const TextStyle(color:MindPalette.yellow,
        fontWeight:FontWeight.w800,fontSize:14)),
      const SizedBox(height:7),
      Text(details,style:const TextStyle(color:MindPalette.muted,fontSize:12,height:1.5)),
    ]));

  Widget intelligenceHubScreen()=>_agentPage('Decision Intelligence Dashboard',
    'Source validation, council deliberation, uncertainty and deterministic capital authority',[
      _metricGrid([
        ('Market posture','SELECTIVE','Synthetic regime'),
        ('Research votes','3 / 5','Two disagree'),
        ('Calibration','Pending','No prospective cohort'),
        ('Capital authority','LOCKED','No orders'),
      ]),
      const SizedBox(height:15),
      _section('Decision pipeline',Icons.account_tree_outlined,[
        _agentStep(1,'Evidence acquisition','Known-at timestamps, freshness and provenance',
          route:'agent-evidence'),const SizedBox(height:7),
        _agentStep(2,'Regime and horizon routing',
          'Intraday, swing, positional, delivery, long-term, gated shorts and F&O',
          route:'regime-weights'),const SizedBox(height:7),
        _agentStep(3,'Strategy scoring',
          'Independent features, after-cost statistics and calibrated uncertainty',
          route:'signal-scorecard'),const SizedBox(height:7),
        _agentStep(4,'Bull / Bear / Auditor council',
          'Challenge assumptions, record dissent and permit abstention',
          route:'agent-council'),const SizedBox(height:7),
        _agentStep(5,'Deterministic capital veto',
          'Account, positions, risk, product eligibility, broker permission',
          color:MindPalette.yellow,route:'risk'),const SizedBox(height:7),
        _agentStep(6,'Broker execution disabled',
          'This prototype never places a real trade',color:MindPalette.red),
        const SizedBox(height:7),
        _agentStep(7,'Post-decision audit',
          'Shadow outcomes, uncertainty and root-cause investigation',
          route:'culprit-catcher'),
      ]),
      _section('Trading horizon routing',Icons.schedule_outlined,[
        MindPanel(child:Wrap(spacing:7,runSpacing:8,children:[
          for(final h in MindAgentFixtures.horizons)
            MindTag(h,color:['Cash Short','Futures','Options'].contains(h)
              ?MindPalette.yellow:MindPalette.mint),
        ])),
      ]),
      _tile('Daily Market Verdict','Briefing and blockers · mock only',
        Icons.event_note_outlined,route:'daily-verdict'),
    ]);

  Widget agentCouncilScreen(){
    final filtered=MindAgentFixtures.votes.where((v)=>
      _agentCouncilView=='All'||v.position==_agentCouncilView);
    final score=MindAgentFixtures.votes.fold<int>(
      0,(total,v)=>total+v.weight*v.score)~/100;
    return _agentPage('Agent Council & Debate',
      'Bull, Bear, Fundamental, Flow and Skeptical researchers defend and challenge evidence',[
      MindPanel(highlight:true,child:Column(children:[
        const Text('RESEARCH SYMBOL',style:TextStyle(color:MindPalette.muted)),
        DropdownButton<String>(
          key:const ValueKey('mind-agent-symbol'),value:_agentCouncilSymbol,
          isExpanded:true,dropdownColor:MindPalette.panel,
          style:const TextStyle(color:MindPalette.text,fontSize:17),
          items:[for(final symbol in MindAgentFixtures.councilSymbols)
            DropdownMenuItem(value:symbol,child:Text(symbol))],
          onChanged:(v){if(v!=null)_updateMind(()=>_agentCouncilSymbol=v);},
        ),
        const Text('Selecting a symbol changes the research context only; no model is called.',
          style:TextStyle(color:MindPalette.muted,fontSize:11)),
      ])),
      const SizedBox(height:12),
      _metricGrid([
        ('Weighted score','$score / 100','Not a probability'),
        ('Council views','5','Independent roles'),
        ('Final posture','REVIEW','No executable advice'),
        ('Risk gate','CLOSED','Mock only'),
      ]),
      const SizedBox(height:13),
      _chips(['All','BUY CANDIDATE','WAIT','RESEARCH','ABSTAIN CHECK'],
        _agentCouncilView,(v)=>_updateMind(()=>_agentCouncilView=v)),
      const SizedBox(height:8),
      for(final vote in filtered) Padding(padding:const EdgeInsets.only(bottom:9),
        child:MindPanel(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
          Row(children:[
            Expanded(child:Text(vote.role,style:const TextStyle(color:MindPalette.text,
              fontWeight:FontWeight.w800,fontSize:15))),
            MindTag(vote.position,color:vote.position=='BUY CANDIDATE'
              ?MindPalette.mint:MindPalette.yellow),
          ]),
          const SizedBox(height:9),
          _progress('Research score',vote.score/100),
          _value('Contribution',vote.weight.toString()+'%'),
          Text(vote.reason,style:const TextStyle(color:MindPalette.text,fontSize:12)),
          const SizedBox(height:6),
          Text('Evidence: '+vote.source,style:const TextStyle(
            color:MindPalette.muted,fontSize:11)),
          const SizedBox(height:5),
          Text('Challenge: '+vote.challenge,style:const TextStyle(
            color:MindPalette.yellow,fontSize:11)),
          TextButton(onPressed:()=>_go('agent-evidence'),
            child:const Text('Trace evidence',style:TextStyle(color:MindPalette.mint))),
        ]))),
      _agentCaution('Arbitration is not execution',
        'Evidence correlation, timestamps, owner identity, open positions and risk limits '
        'must be evaluated independently. No LLM vote overrides the capital authority.'),
    ]);
  }

  Widget agentEvidenceScreen(){
    final categories=<String>['All',
      ...{for(final e in MindAgentFixtures.evidence)e.category}];
    final filtered=MindAgentFixtures.evidence.where((e)=>
      _agentEvidenceScope=='All'||e.category==_agentEvidenceScope);
    return _agentPage('Evidence & Provenance Ledger',
      'Every argument must have a timestamp, provenance and usability state',[
      _chips(categories,_agentEvidenceScope,
        (v)=>_updateMind(()=>_agentEvidenceScope=v)),
      const SizedBox(height:8),
      for(final e in filtered)Padding(padding:const EdgeInsets.only(bottom:9),
        child:MindPanel(child:Column(children:[
          Row(children:[
            Expanded(child:Text(e.id+' · '+e.claim,style:const TextStyle(
              color:MindPalette.text,fontWeight:FontWeight.w800))),
            MindTag(e.usability,color:e.usability=='Synthetic'
              ?MindPalette.purple:MindPalette.yellow),
          ]),
          _value('Instrument',e.symbol),
          _value('Source',e.source),
          _value('Known at',e.knownAt),
          _value('Scope',e.category),
        ]))),
      const SizedBox(height:11),
      _section('Mandatory contract',Icons.fact_check_outlined,[
        for(final rule in [
          ('Observed at','Exchange time and collection latency'),
          ('Known at','First time information could affect a decision'),
          ('Feature lineage','Data snapshot and model version'),
          ('Correlated evidence','One logical family counts as one vote'),
          ('Risk identity','Account, instrument, horizon and policy version'),
          ('Outcome label','Only after the evaluation horizon'),
        ]) Padding(padding:const EdgeInsets.only(bottom:7),
          child:_tile(rule.$1,rule.$2,Icons.verified_outlined)),
      ]),
    ]);
  }

  Widget regimeWeightsScreen(){
    final values=MindAgentFixtures.regimeWeights[_agentRegime]!;
    const labels=['Momentum','Fundamentals','Mean Reversion','Defensive / Abstain'];
    final uncertain=_agentRegime=='Data Uncertain';
    return _agentPage('Regime Weight Studio',
      'Hypothetical research weighting for different market structures',[
      _chips(MindAgentFixtures.regimeWeights.keys.toList(),
        _agentRegime,(v)=>_updateMind(()=>_agentRegime=v)),
      const SizedBox(height:10),
      MindPanel(highlight:true,child:Column(children:[
        const MindSectionTitle(title:'Illustrative weighting',
          icon:Icons.tune_outlined),
        for(var i=0;i<labels.length;i++)
          _progress(labels[i],values[i]/100,
            color:uncertain?MindPalette.yellow:MindPalette.mint),
        _value('Posture',uncertain?'ABSTAIN':'Research only',
          color:uncertain?MindPalette.yellow:MindPalette.mint),
        const Text('Zero confidence in data means no eligible strategy.',
          style:TextStyle(color:MindPalette.muted,fontSize:11)),
      ])),
      const SizedBox(height:13),
      _section('Market-state routing',Icons.rule_outlined,[
        for(final pair in [
          ('Trending Up','Selective momentum with fresh liquidity evidence'),
          ('Range Bound','Constrained mean-reversion candidates'),
          ('Trending Down','Preserve capital; cash shorts remain restricted'),
          ('High Volatility','Reduce hypothetical budget and evaluate tails'),
          ('Data Uncertain','Abstain pending source verification'),
        ])Padding(padding:const EdgeInsets.only(bottom:7),
          child:_tile(pair.$1,pair.$2,Icons.track_changes_outlined,
            color:pair.$1==_agentRegime?MindPalette.mint:MindPalette.muted,
            tap:()=>_updateMind(()=>_agentRegime=pair.$1))),
      ]),
      _agentCaution('No strategy automatically activates',
        'Live deployment requires walk-forward evidence, borrow eligibility, '
        'liquidity, derivatives margin and the Java capital authority.'),
    ]);
  }

  Widget signalScorecardScreen(){
    final gated=['Futures','Options','Cash Short'].contains(_agentSignalHorizon);
    final seed=MindAgentFixtures.horizons.indexOf(_agentSignalHorizon)+1;
    const names=['Momentum Breakout','VWAP Recovery','Earnings Catalyst',
      'Quality Compounder','Bear Reversal'];
    return _agentPage('Signal Performance Scorecard',
      'Research scores by horizon, after-cost assumptions and sample uncertainty',[
      _chips(MindAgentFixtures.horizons,_agentSignalHorizon,
        (v)=>_updateMind(()=>_agentSignalHorizon=v)),
      const SizedBox(height:11),
      _metricGrid([
        ('Cohort','SYNTHETIC','No verified tests'),
        ('Costs','ASSUMED','Not broker measured'),
        ('Walk-forward','REQUIRED','Holdout not verified'),
        ('Permission','NONE','No real orders'),
      ]),
      const SizedBox(height:13),
      for(var i=0;i<names.length;i++)Padding(padding:const EdgeInsets.only(bottom:8),
        child:MindPanel(child:Column(children:[
          Row(children:[
            Expanded(child:Text(names[i],style:const TextStyle(
              color:MindPalette.text,fontWeight:FontWeight.w800))),
            MindTag(gated?'Gated':'Study',color:MindPalette.yellow),
          ]),
          _value('Mock win %',gated?'N/A':([64,52,57,63,47][i]+seed).toString()+'%'),
          _value('Mock profit factor',gated?'N/A':(1.1+i*.09+seed*.03).toStringAsFixed(2)),
          _value('Sample count',gated?'0':(12+i*3+seed).toString()),
          _progress('Confidence',gated?0:([60,51,57,62,44][i]+seed)/100),
        ]))),
      _tile('Confidence Calibration','Forecast bins and missing prospective labels',
        Icons.track_changes_outlined,route:'confidence-lab'),
      const SizedBox(height:8),
      _tile('Investigate failures','Root cause and correlated signal families',
        Icons.bug_report_outlined,route:'culprit-catcher'),
      const SizedBox(height:8),
      _agentCaution('Numbers are not performance claims',
        'All rates are invented demonstration values. Production research needs '
        'out-of-sample evaluation, survivorship-safe cohorts, taxes and slippage.'),
    ]);
  }

  Widget confidenceLabScreen(){
    final gated=['Cash Short','Futures','Options'].contains(_agentCalMode);
    return _agentPage('Confidence Calibration',
      'Separate raw model scores from actual probabilities and decision eligibility',[
      _chips(MindAgentFixtures.horizons,_agentCalMode,
        (v)=>_updateMind(()=>_agentCalMode=v)),
      const SizedBox(height:10),
      _metricGrid([
        ('Calibration','UNVERIFIED','Mock buckets'),
        ('Brier score','NOT MEASURED','Forward labels needed'),
        ('Sample source','SYNTHETIC','UI fixtures only'),
        ('Automation','DISABLED','Risk permission absent'),
      ]),
      const SizedBox(height:13),
      _section('Reliability diagram',Icons.stacked_bar_chart_outlined,[
        MindPanel(child:Column(children:[
          for(final b in MindAgentFixtures.calibrationBuckets)...[
            _progress('Forecast '+b.$1,gated?0:b.$3/100,
              color:gated?MindPalette.yellow:MindPalette.mint),
            Align(alignment:Alignment.centerRight,
              child:Text(gated?'GATED · no eligible sample':
                b.$4.toString()+' fictional outcomes · bin centre '+b.$2.toString()+'%',
                style:const TextStyle(color:MindPalette.muted,fontSize:10))),
            const SizedBox(height:8),
          ],
        ])),
      ]),
      _agentCaution('An 80% score is not an 80% probability',
        'Require horizon-aligned prospective labels, calibration error, '
        'data independence and net-of-cost expected value. Capital permission '
        'remains a separate deterministic decision.'),
    ]);
  }

  Widget shadowArenaScreen()=>_agentPage('Shadow Trading Arena',
    'Hypothetical model comparisons with aligned decision timestamps',[
      _chips(['Strategies','Regimes','Agent votes','Counterfactuals'],
        _agentShadowTab,(v)=>_updateMind(()=>_agentShadowTab=v)),
      const SizedBox(height:8),
      _chips(['Baseline','Adaptive','Strict Risk'],
        _agentShadowCompare,(v)=>_updateMind(()=>_agentShadowCompare=v)),
      const SizedBox(height:12),
      _metricGrid([
        ('Evaluation','SHADOW','No broker fills'),
        ('Control','100.0','Normalized example'),
        ('Comparison','103.2','Invented example'),
        ('Leakage gate','REQUIRED','Labels after horizon'),
      ]),
      const SizedBox(height:13),
      _section(_agentShadowTab+' · '+_agentShadowCompare,
        Icons.compare_arrows_outlined,[
        MindPanel(child:Column(children:[
          _mockChart(height:195,seed:_agentShadowCompare=='Baseline'?6:
            _agentShadowCompare=='Adaptive'?11:16,style:'Line'),
          _value('Timestamp cohort','Equal for compared policies'),
          _value('Cost assumptions','Mock slippage, taxes and fees'),
          _value('Risk budget','Same hypothetical constraints'),
          _value('Missing labels','Must be included in denominator'),
        ])),
      ]),
      _section('Evaluation process',Icons.history_toggle_off_outlined,[
        _agentStep(1,'Freeze a candidate','Version, evidence and known-at timestamps'),
        const SizedBox(height:8),
        _agentStep(2,'Compare control and challenger','No broker execution or fills'),
        const SizedBox(height:8),
        _agentStep(3,'Wait for the label','Never train on information from the future'),
        const SizedBox(height:8),
        _agentStep(4,'Audit divergence','Trace false signals and abstentions',
          route:'culprit-catcher'),
      ]),
    ]);

  Widget institutionalFlowScreen()=>_agentPage('FII / DII Flow Monitor',
    'Synthetic institutional participation research for Indian markets',[
      _chips(['2026-11-06','2026-11-09'],_agentFlowDate,
        (v)=>_updateMind(()=>_agentFlowDate=v)),
      const SizedBox(height:10),
      _agentCaution('NOT VERIFIED · official flow source unavailable',
        'Dates and amounts are placeholders, not authentic FII/DII statistics. '
        'Only official, licensed, timestamped feeds may unlock verification.'),
      const SizedBox(height:11),
      _metricGrid([
        ('FII net','-₹1,420 Cr','Fictional amount'),
        ('DII net','+₹1,810 Cr','Fictional amount'),
        ('Breadth','43%','Synthetic'),
        ('Data source','UNAVAILABLE','No live access'),
      ]),
      const SizedBox(height:14),
      _section('Flow breakdown',Icons.account_balance_outlined,[
        for(final r in MindAgentFixtures.flowRows)Padding(
          padding:const EdgeInsets.only(bottom:8),
          child:MindPanel(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
            _value(r.$1,r.$2,color:MindPalette.yellow),
            Text(r.$3,style:const TextStyle(color:MindPalette.purple,fontSize:11)),
            Text(r.$4,style:const TextStyle(color:MindPalette.muted,fontSize:11)),
          ]))),
      ]),
      _tile('Check flow provenance','Known-at cutoffs and official source requirements',
        Icons.source_outlined,route:'agent-evidence'),
    ]);

  Widget dailyVerdictScreen()=>_agentPage('Daily Market Verdict',
    'Research briefing combining uncertainty, horizon rules and action blockers',[
      _chips(['2026-11-09','2026-11-06'],_agentVerdictDate,
        (v)=>_updateMind(()=>_agentVerdictDate=v)),
      const SizedBox(height:11),
      MindPanel(highlight:true,child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        const MindTag('SELECTIVE · NO ORDERS',color:MindPalette.yellow),
        const SizedBox(height:9),
        Text('Research briefing · '+_agentVerdictDate,
          style:const TextStyle(color:MindPalette.text,fontSize:20,fontWeight:FontWeight.w800)),
        const SizedBox(height:6),
        const Text('Mixed breadth in this fictional scenario. '
          'No real institutional flow or trading-model inference is connected.',
          style:TextStyle(color:MindPalette.muted,fontSize:12,height:1.5)),
        _value('Regime','Selective / elevated uncertainty'),
        _value('Research leadership','Financials · select midcaps (mock)'),
        _value('High-conviction entries','None verified',color:MindPalette.yellow),
        _value('Authority','NO LIVE TRADING',color:MindPalette.red),
      ])),
      const SizedBox(height:13),
      _section('Watchpoints · not recommendations',Icons.visibility_outlined,[
        for(final pair in [
          ('CDSL','Independent volume and breakout confirmation'),
          ('TITAN','Verified earnings and valuation assumptions'),
          ('POLYCAB','Official filings and valuation downside'),
          ('NIFTY50','Actual breadth and event risk'),
        ])Padding(padding:const EdgeInsets.only(bottom:7),
          child:_tile(pair.$1,pair.$2,Icons.remove_red_eye_outlined,
            route:'agent-council')),
      ]),
      _agentCaution('Abstention is a valid decision',
        'Separate research from financial advice. Real decisions require '
        'verified sources, deterministic checks and an authenticated account.'),
    ]);

  Widget culpritCatcherScreen(){
    final incident=MindAgentFixtures.incidents.firstWhere(
      (e)=>e.id==_agentIncident,
      orElse:()=>MindAgentFixtures.incidents.first);
    return _agentPage('Culprit Catcher · Root Cause',
      'Root-cause review of failed signals, eligibility blocks and regime mistakes',[
      _chips([for(final e in MindAgentFixtures.incidents)e.id],
        _agentIncident,(v)=>_updateMind(()=>_agentIncident=v)),
      const SizedBox(height:10),
      MindPanel(highlight:true,child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Row(children:[
          Expanded(child:Text(incident.symbol+' · '+incident.kind,
            style:const TextStyle(color:MindPalette.text,
              fontWeight:FontWeight.w800,fontSize:19))),
          MindTag(incident.severity,color:incident.severity=='High'
            ?MindPalette.red:MindPalette.yellow),
        ]),
        _value('Incident',incident.id),
        _value('Decision',incident.decision),
        _value('Failure stage',incident.stage),
        const Divider(color:MindPalette.border),
        const Text('Likely root cause',style:TextStyle(
          color:MindPalette.mint,fontWeight:FontWeight.w800)),
        const SizedBox(height:7),
        Text(incident.cause,style:const TextStyle(
          color:MindPalette.text,fontSize:12,height:1.5)),
      ])),
      const SizedBox(height:13),
      _section('Incident timeline',Icons.timeline_outlined,[
        for(var i=0;i<incident.timeline.length;i++)Padding(
          padding:const EdgeInsets.only(bottom:8),
          child:_agentStep(i+1,'Checkpoint '+(i+1).toString(),incident.timeline[i],
            color:i==incident.timeline.length-1?MindPalette.yellow:MindPalette.mint)),
      ]),
      _section('Prevent a repeat',Icons.build_circle_outlined,[
        _agentCaution('Suggested change requires validation',incident.fix),
        const SizedBox(height:8),
        _tile('Evidence chain','Audit timestamps and correlated votes',
          Icons.source_outlined,route:'agent-evidence'),
        const SizedBox(height:8),
        _tile('Shadow-test this hypothesis','Same period and risk budget',
          Icons.science_outlined,route:'shadow-arena'),
        const SizedBox(height:8),
        _tile('Calibration study','Forward labels and horizon alignment',
          Icons.track_changes_outlined,route:'confidence-lab'),
      ]),
      _agentCaution('Audit tool only',
        'No incident represents a verified fill. Fixes cannot '
        'automatically enable strategies or broker execution.'),
    ]);
  }
}
