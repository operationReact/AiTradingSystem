// Prototype UI source may use existing explicit string concatenation for compact mock labels.
// ignore_for_file: prefer_interpolation_to_compose_strings
part of 'mind_workspace_page.dart';

extension _MindSystemScreens on _MindWorkspaceState {
  static const _rooms=<(String,String,String,String)>[
    ('Intraday Room','Paper','Intraday','Opening range, VWAP and risk rules'),
    ('Swing Room','Shadow','Swing','Multi-day trend and momentum research'),
    ('Wealth Builder Room','Research','Long-Term','Quality and multibagger theses'),
    ('Options Research Room','Disabled','F&O','Greeks and payoff simulation only'),
  ];

  Widget _realHomeScreen() {
    final repository=widget.homeSummaryRepository!;
    return _surface('MintTick Home','Your PAPER workspace · server summary · no live orders',[
      FutureBuilder<CustomerHomeSummary>(
        future:repository.load('PAPER'),
        builder:(context,snap){
          if(snap.connectionState!=ConnectionState.done) {
            return const MindPanel(child:Text('Loading your workspace…',
              style:TextStyle(color:MindPalette.muted,fontSize:12)));
          }
          final s=snap.data;
          if(snap.hasError||s==null) {
            return const MindPanel(key:ValueKey('mind-home-error'),child:Text(
              'Workspace summary unavailable. Nothing is shown as status.',
              style:TextStyle(color:MindPalette.red,fontSize:12)));
          }
          return Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
            MindPanel(key:const ValueKey('mind-home-summary'),highlight:true,child:Column(children:[
              _value('Next step',s.nextAction.code.replaceAll('_',' ').toLowerCase()),
              _value('PAPER rooms',s.rooms.funded.toString()+' funded · '+s.rooms.drafts.toString()+' draft'),
              _value('Virtual budget','₹'+s.rooms.plannedBudgetInr.toString()),
              _value('Broker connections',s.brokers.connected.toString()+(s.brokers.complete?'':' · incomplete')),
              _value('Mind market evidence',s.mind.market.status),
              _value('Mind research evidence',s.mind.research.status),
              _value('Live execution','Not authorized',color:MindPalette.red),
            ])),
          ]);
        }),
      const SizedBox(height:12),
      _tile('Mind Overview','Readiness, evidence and the audited progress tracker',
        Icons.psychology_outlined,route:'overview'),
      const SizedBox(height:9),
      _tile('Trading Rooms','Funded PAPER rooms and virtual positions',
        Icons.meeting_room_outlined,route:'trading-rooms'),
      const SizedBox(height:9),
      _tile('Strategy Universe','Catalog status from the audited registry',
        Icons.layers_outlined,route:'strategies'),
      const SizedBox(height:9),
      _tile('Risk Center','Enforced automatic PAPER limits',Icons.shield_outlined,route:'risk'),
    ]);
  }

  Widget homeScreen(){
    if(widget.homeSummaryRepository!=null) return _realHomeScreen();
    return _surface('MintTick Home · Mind Preview',
    'A mock landing page demonstrating access to the complete MintTick Mind workspace',[
    MindPanel(highlight:true,child:Column(children:[
      const Row(children:[
        Icon(Icons.psychology_outlined,color:MindPalette.mint,size:34),
        SizedBox(width:9),
        Expanded(child:Text('Your personal trading intelligence',
          style:TextStyle(color:MindPalette.text,fontSize:19,fontWeight:FontWeight.w800))),
      ]),
      const SizedBox(height:12),
      const Text('Research · Strategies · Market interpretation · Portfolio risk',
        style:TextStyle(color:MindPalette.muted,fontSize:12)),
      const SizedBox(height:14),
      FilledButton.icon(onPressed:()=>_go('overview'),
        icon:const Icon(Icons.psychology_outlined),
        label:const Text('Open MintTick Mind')),
    ])),
    const SizedBox(height:13),
    _metricGrid([
      ('Mind status','Mock active','No broker connection'),
      ('Strategy library','232','20 research families'),
      ('Model registry','18','Research/planned'),
      ('Capital authority','None','Mock mode enforced'),
    ]),
    const SizedBox(height:13),
    _section('Your Workspaces',Icons.dashboard_outlined,[
      _tile('Mind Overview','The full intelligence dashboard',
        Icons.psychology_outlined,route:'overview'),
      const SizedBox(height:8),
      _tile('Trading Rooms','Distinct decision horizons and budgets',
        Icons.meeting_room_outlined,route:'trading-rooms'),
      const SizedBox(height:8),
      _tile('Multibagger Discovery','Research long-term compounders',
        Icons.diamond_outlined,route:'multibagger'),
      const SizedBox(height:8),
      _tile('Strategy Universe','Explore all 232 research templates',
        Icons.layers_outlined,route:'strategies'),
    ]),
    _section('Existing MintTick App',Icons.home_outlined,[
      MindPanel(child:Column(children:[
        const Text('This is only the mock Mind workspace home. Your original MintTick Home, Markets, and broker-connected screens remain unchanged.',
          style:TextStyle(color:MindPalette.muted,fontSize:12)),
        const SizedBox(height:10),
        OutlinedButton(onPressed:()=>Navigator.of(context).pushNamedAndRemoveUntil(
          '/', (route)=>false),
          child:const Text('Return to actual MintTick Home',
            style:TextStyle(color:MindPalette.mint))),
      ])),
    ]),
  ]);
  }

  Widget _realRoomsScreen() {
    _ensurePortfolio();
    final p=_portfolio;
    if(p==null) return _portfolioPending('Trading Rooms');
    final paused=p.rooms.where((r)=>r.paused).length;
    return _surface('Trading Rooms','Your funded PAPER rooms · virtual money only',[
      _metricGrid([
        ('PAPER rooms',p.fundedRooms.toString(),'Funded virtual wallets'),
        ('Paused',paused.toString(),'New fills rejected'),
        ('Virtual cash',formatPaise(p.availableCashPaise),'Across rooms'),
        ('Open positions',p.positions.length.toString(),'Marked with verified ticks'),
      ]),
      const SizedBox(height:13),
      _section('Your Trading Rooms',Icons.meeting_room_outlined,[
        if(p.rooms.isEmpty) _empty('No funded PAPER room yet. Create and activate one in Trading Rooms.'),
        for(final r in p.rooms)
          Padding(padding:const EdgeInsets.only(bottom:9),child:_tile(r.name,
            formatPaise(r.availableCashPaise)+' cash · '+r.openPositions.toString()+' position(s) · realized '+
              formatPaise(r.realizedPnlPaise),
            Icons.meeting_room_outlined,trailing:r.paused?'PAUSED':'PAPER',
            color:r.paused?MindPalette.yellow:MindPalette.mint,
            tap:()=>_go('room-detail'))),
      ]),
      _section('Trading Room Safety',Icons.shield_outlined,[
        _value('Mode','PAPER virtual only'),
        _value('Live broker execution','Not authorized',color:MindPalette.red),
        _value('Risk authority','Server-enforced, automatic'),
        _tile('Pause or resume a room','Risk Center',Icons.pan_tool_outlined,route:'risk'),
      ]),
    ]);
  }

  Widget roomsScreen()=>widget.paperPortfolioRepository!=null ? _realRoomsScreen() : _surface('Trading Rooms',
    'A separate strategy and risk context for each trading horizon',[
    _metricGrid([
      ('Rooms','4','Mock configuration'),
      ('Paper','1','Research mode'),
      ('Shadow','1','No broker fills'),
      ('Derivatives','Disabled','Research only'),
    ]),
    const SizedBox(height:13),
    _section('Your Trading Rooms',Icons.meeting_room_outlined,[
      for(final room in _rooms)
        Padding(padding:const EdgeInsets.only(bottom:9),
          child:_tile(room.$1,room.$3+' · '+room.$4,
            Icons.meeting_room_outlined,trailing:room.$2,
            color:room.$2=='Disabled'?MindPalette.red:
              room.$2=='Research'?MindPalette.purple:MindPalette.mint,
            tap:()=>_selectRoom(room.$1))),
    ]),
    _section('Trading Room Safety',Icons.shield_outlined,[
      _value('Mode separation','Paper / Shadow / Research'),
      _value('Live broker execution','Not available here',
        color:MindPalette.red),
      _value('Shared risk authority','Required'),
      _value('Signal conflicts','Resolved centrally'),
      _tile('Explore decisions','View cross-room reasoning and conflicts',
        Icons.track_changes_outlined,route:'decisions'),
    ]),
  ]);

  Widget roomDetailScreen() {
    final room=_rooms.firstWhere((r)=>r.$1==_selectedRoom,orElse:()=>_rooms.first);
    return _surface(room.$1,room.$4,[
      if (widget.paperCycleRepository != null) ...[
        MindPaperCyclePanel(repository: widget.paperCycleRepository!),
        const SizedBox(height:12),
      ] else ...[
        const MindPanel(key:ValueKey('mind-paper-cycle-unavailable'),
          child:Text('Mind PAPER strategy cycle requires a signed-in customer session.',
            style:TextStyle(color:MindPalette.muted,fontSize:12))),
        const SizedBox(height:12),
      ],
      const MindTag('ROOM CARDS BELOW: MOCK FIXTURES',color:MindPalette.yellow),
      const SizedBox(height:10),
      MindPanel(highlight:true,child:Column(children:[
        _value('Lifecycle',room.$2),
        _value('Trading horizon',room.$3),
        _value('Broker connection','None in mock'),
        _value('Execution authority','None',color:MindPalette.red),
        _value('Decision engine','Research simulated'),
        _value('Capital allocation','Independent per room'),
      ])),
      const SizedBox(height:12),
      _section('Room Strategy Stack',Icons.layers_outlined,[
        for(final name in room.$3=='Long-Term'
          ? ['Quality Compounders','Earnings Acceleration','ROCE Improvement',
              'Capital Allocation Quality']
          : room.$3=='F&O'
            ? ['Iron Condor','Bull Call Debit Spread',
                'Option IV Rank Mean Reversion']
            : ['Opening Range Breakout','VWAP Reclaim',
                'RSI Momentum','Volume Spike Confirmation'])
          Padding(padding:const EdgeInsets.only(bottom:7),
            child:_tile(name,'Research signal · governed risk',
              Icons.show_chart,tap:()=>_mockAction('Viewed strategy '+name))),
      ]),
      _section('Room Status & Decisions',Icons.track_changes_outlined,[
        _tile('Pending evidence','3 research observations; not actionable',
          Icons.psychology_outlined,route:'decisions'),
        _tile('Risk budget','No real money allocated',
          Icons.shield_outlined,route:'risk'),
        _tile('Portfolio effect','Evaluate simulated exposure',
          Icons.pie_chart_outline,route:'portfolio'),
      ]),
    ],backTo:'trading-rooms');
  }

  Widget _registryPending(String title) => _surface(title,'Audited engine registry · server data',[
    MindPanel(key:const ValueKey('mind-registry-pending'),child:Row(children:[
      if(!_registryFailed) const SizedBox(width:14,height:14,child:CircularProgressIndicator(strokeWidth:2)),
      const SizedBox(width:9),
      Expanded(child:Text(_registryFailed?'Engine registry unavailable. Nothing is shown as running.'
        :'Loading engine registry…',
        style:TextStyle(color:_registryFailed?MindPalette.red:MindPalette.muted,fontSize:12))),
    ])),
  ]);

  Widget _capabilityRow(MindProgressItem item) => Padding(
    padding:const EdgeInsets.only(bottom:7),
    child:MindPanel(key:ValueKey('mind-engine-'+item.id),onTap:()=>_go('coverage'),
      padding:const EdgeInsets.symmetric(horizontal:12,vertical:10),
      child:Row(children:[
        Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
          Text(item.name,maxLines:1,overflow:TextOverflow.ellipsis,
            style:const TextStyle(color:MindPalette.text,fontWeight:FontWeight.w700,fontSize:13)),
          Text([item.id,if(item.blockerCode!=null) item.blockerCode!,
              if(item.runtime.lastVerifiedAt!=null)
                'verified '+item.runtime.lastVerifiedAt!.toUtc().toIso8601String().substring(0,16)+'Z'].join(' · '),
            maxLines:2,overflow:TextOverflow.ellipsis,
            style:const TextStyle(color:MindPalette.muted,fontSize:10.5)),
        ])),
        const SizedBox(width:8),
        MindStatusPill(item.status),
      ])));

  Widget _realControlScreen() {
    _ensureRegistry();
    final snap=_registrySnapshot;
    if(snap==null) return _registryPending('Engine Control');
    List<MindProgressItem> of(String kind)=>snap.items.where((i)=>i.kind==kind).toList();
    return _surface('Engine Control','Real engine pipeline state from the audited server registry',[
      MindPanel(key:const ValueKey('mind-engine-status'),highlight:true,child:Column(children:[
        const MindSectionTitle(title:'Engine Status',icon:Icons.settings_outlined),
        _value('Backend revision',snap.revisionKnown?snap.backendRevision:'Unknown · nothing can be verified',
          color:snap.revisionKnown?MindPalette.mint:MindPalette.yellow),
        _value('PAPER stage',snap.paperStage.name+' · '+snap.paperStage.status.label,
          color:MindStatusPill.colorFor(snap.paperStage.status)),
        _value('Execution mode','PAPER virtual only'),
        _value('LIVE broker execution','Not authorized',color:MindPalette.red),
        _value('Automation','Owner-initiated cycles only · no scheduler'),
        _value('Registry','Manifest '+snap.manifestVersion),
      ])),
      const SizedBox(height:12),
      _section('Engine Pipeline',Icons.account_tree_outlined,[
        for(final i in [...of('ENGINE'),...of('PAPER'),...of('LIVE')]) _capabilityRow(i),
      ]),
      _section('Data Dependencies',Icons.storage_outlined,[
        for(final i in of('DATA')) _capabilityRow(i),
      ]),
      _section('Safety & Interlocks',Icons.shield_outlined,[
        _tile('Pause a PAPER room','Real emergency stop for virtual fills (Risk Center)',
          Icons.pan_tool_outlined,route:'risk'),
        const SizedBox(height:7),
        _tile('Enforced risk policy','Automatic limits applied to every cycle',Icons.shield_outlined,route:'risk'),
      ]),
    ]);
  }

  Widget controlScreen()=>widget.progressRepository!=null ? _realControlScreen() : _surface('Engine Control',
    'Personal model participation and emergency controls · local UI state only',[
    MindPanel(highlight:true,child:Column(children:[
      const MindSectionTitle(title:'Engine Status',icon:Icons.settings_outlined),
      _value('Runtime','Offline prototype'),
      _value('Authenticated user','Same MintTick session'),
      _value('Broker execution','No calls · disabled',color:MindPalette.red),
      _value('Java risk authority','Unchanged'),
      const SizedBox(height:7),
      MindTag(_emergencyStop?'MOCK EMERGENCY STOP ACTIVE':'MOCK CONTROLS ONLY',
        color:_emergencyStop?MindPalette.red:MindPalette.yellow),
    ])),
    const SizedBox(height:12),
    _section('Monitoring & Evaluation',Icons.tune_outlined,[
      _toggle('Live monitoring','Display-only mode; no market data is streamed'),
      const SizedBox(height:7),
      _toggle('Paper mode','Simulate ideas without real orders'),
      const SizedBox(height:7),
      _toggle('Shadow mode','Generate non-executable comparisons'),
      const SizedBox(height:7),
      _toggle('Allow new entries','Local visual preference; never enables orders'),
      const SizedBox(height:7),
      _toggle('Auto execution','Always disconnected from trade routing',locked:true),
      const SizedBox(height:7),
      _toggle('F&O execution','Derivative execution intentionally unavailable',locked:true),
    ]),
    _section('Safety & Interlocks',Icons.shield_outlined,[
      MindPanel(child:SwitchListTile.adaptive(
        title:const Text('Simulated emergency stop',
          style:TextStyle(color:MindPalette.text,fontWeight:FontWeight.w700)),
        subtitle:const Text('The real emergency stop and backend remain untouched.',
          style:TextStyle(color:MindPalette.muted,fontSize:11)),
        activeTrackColor:MindPalette.red,
        value:_emergencyStop,
        onChanged:(v)=>_updateMind(()=>_emergencyStop=v),
      )),
      const SizedBox(height:10),
      _tile('Capital limits','See the independent portfolio risk gate',
        Icons.shield_outlined,route:'risk'),
      const SizedBox(height:7),
      _tile('Model registry','Champion/challenger and calibration',
        Icons.memory_outlined,route:'models'),
      const SizedBox(height:7),
      _tile('Learning governance','Experiments and paper/shadow evidence',
        Icons.science_outlined,route:'research'),
    ]),
  ]);

  Future<void> _askAssistant() async {
    final repository = widget.assistantRepository;
    final text = _assistantController.text.trim();
    if (repository == null || text.isEmpty || _assistantBusy) return;
    _updateMind(() { _assistantBusy = true; _assistantHistory.add('You: $text'); _assistantController.clear(); });
    try {
      final answer = await repository.ask(text);
      final sources = answer.sources.map((s) => s.name + (s.asOf == null ? '' : ' @ ' +
          s.asOf!.toUtc().toIso8601String().substring(0, 16) + 'Z')).join(' · ');
      _updateMind(() => _assistantHistory.add('Mind: ${answer.answer}'
          '${sources.isEmpty ? '' : '\nSources: $sources'}'));
    } catch (_) {
      _updateMind(() => _assistantHistory.add(
          'Mind: Could not answer from recorded data right now. Nothing was changed.'));
    } finally {
      _updateMind(() => _assistantBusy = false);
    }
  }

  Widget _realAssistantScreen() {
    return _surface('Ask MintTick Mind','Read-only · answers from recorded server data · no orders',[
      MindPanel(key:const ValueKey('mind-assistant-panel'),highlight:true,child:Column(
        crossAxisAlignment:CrossAxisAlignment.stretch,children:[
        const MindSectionTitle(title:'Ask about your data',icon:Icons.forum_outlined),
        const Text('Answers come only from the audited registry, your virtual PAPER portfolio and the recorded decision journal. Trade requests are refused.',
          style:TextStyle(color:MindPalette.muted,fontSize:11)),
        const SizedBox(height:10),
        for(var i=0;i<_assistantHistory.length;i++)
          Padding(padding:const EdgeInsets.only(bottom:9),child:Container(
            key:ValueKey('mind-assistant-entry-$i'),
            padding:const EdgeInsets.all(12),
            decoration:BoxDecoration(color:_assistantHistory[i].startsWith('You:')?const Color(0xFF12402F):MindPalette.card,
              border:Border.all(color:MindPalette.border),borderRadius:BorderRadius.circular(13)),
            child:Text(_assistantHistory[i],style:const TextStyle(color:MindPalette.text,fontSize:12,height:1.5)))),
        if(_assistantBusy) const Padding(padding:EdgeInsets.only(bottom:8),child:Text('Checking recorded data…',
          style:TextStyle(color:MindPalette.muted,fontSize:12))),
        Row(children:[
          Expanded(child:TextField(controller:_assistantController,
            key:const ValueKey('mind-assistant-input'),
            style:const TextStyle(color:MindPalette.text),
            onSubmitted:(_)=>_askAssistant(),
            maxLength:280,
            decoration:InputDecoration(
              counterText:'',
              hintText:'What is my PAPER P&L?',
              hintStyle:const TextStyle(color:MindPalette.muted,fontSize:12),
              filled:true,fillColor:MindPalette.panel,
              enabledBorder:OutlineInputBorder(borderSide:const BorderSide(color:MindPalette.border),
                borderRadius:BorderRadius.circular(12))))),
          const SizedBox(width:7),
          IconButton.filled(tooltip:'Ask',key:const ValueKey('mind-assistant-send'),
            onPressed:_assistantBusy?null:_askAssistant,
            style:IconButton.styleFrom(backgroundColor:const Color(0xFF1F7655)),
            icon:const Icon(Icons.send,color:Colors.white)),
        ]),
      ])),
    ]);
  }

  Widget assistantScreen(){
    if(widget.assistantRepository!=null) return _realAssistantScreen();
    return _surface('Ask MintTick Mind',
    'Conversational research explorer · scripted answers from mock evidence',[
    MindPanel(highlight:true,child:Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
      const MindSectionTitle(title:'Research Conversation',icon:Icons.forum_outlined),
      for(final entry in _assistantHistory)
        Padding(padding:const EdgeInsets.only(bottom:9),
          child:Container(padding:const EdgeInsets.all(12),
            decoration:BoxDecoration(color:entry.startsWith('You:')?
              const Color(0xFF12402F):MindPalette.card,
              border:Border.all(color:MindPalette.border),
              borderRadius:BorderRadius.circular(13)),
            child:Text(entry,style:const TextStyle(color:MindPalette.text,
              fontSize:12,height:1.5)))),
      const SizedBox(height:8),
      Row(children:[
        Expanded(child:TextField(controller:_assistantController,
          style:const TextStyle(color:MindPalette.text),
          onSubmitted:(_)=>_sendQuestion(),
          decoration:InputDecoration(
            hintText:'Why did MintTick Mind wait?',
            hintStyle:const TextStyle(color:MindPalette.muted,fontSize:12),
            filled:true,fillColor:MindPalette.panel,
            enabledBorder:OutlineInputBorder(
              borderSide:const BorderSide(color:MindPalette.border),
              borderRadius:BorderRadius.circular(12)))),
        ),
        const SizedBox(width:7),
        IconButton.filled(tooltip:'Ask scripted research assistant',
          onPressed:_sendQuestion,
          style:IconButton.styleFrom(backgroundColor:const Color(0xFF1F7655)),
          icon:const Icon(Icons.send,color:Colors.white)),
      ]),
    ])),
    const SizedBox(height:13),
    _section('Try a Question',Icons.help_outline,[
      for(final q in [
        'Why did TITAN receive WAIT?',
        'Compare LightGBM and CatBoost',
        'Which strategy handles a sideways market?',
        'What are the multibagger risks?',
        'Can MintTick Mind trade options?',
      ])
        Padding(padding:const EdgeInsets.only(bottom:7),child:_tile(q,
          'Read-only simulated explanation',Icons.chat_bubble_outline,
          tap:(){_assistantController.text=q;_sendQuestion();})),
    ]),
  ]);
  }

  void _sendQuestion() {
    final message=_assistantController.text.trim();
    if(message.isEmpty)return;
    final l=message.toLowerCase();
    final reply=l.contains('option')||l.contains('f&o')
      ? 'The options research module has mocked strikes, OI and Greeks. Derivatives order execution is disabled.'
      :l.contains('titan')||l.contains('wait')
      ? 'The example TITAN decision was WAIT because momentum and breakout volume conflicted. It is not a real recommendation.'
      :l.contains('lightgbm')||l.contains('catboost')
      ? 'The fixture reports LightGBM AUC 0.81 and CatBoost AUC 0.79. These are unverified research metrics and do not prove live alpha.'
      :l.contains('multibagger')||l.contains('compound')
      ? 'The discovery process compares growth, ROCE, cash flow, valuation, governance and point-in-time historical evidence. No 10x outcome is assured.'
      :l.contains('sideways')||l.contains('mean')
      ? 'Mean reversion is commonly researched for ranging regimes, but tradeability depends on costs, regime stability and independent validation.'
      :l.contains('risk')
      ? 'Every proposed action must pass independent capital and risk checks. This demo cannot modify or invoke those controls.'
      : 'The prototype can explain its sample strategies, models, portfolio exposure, decisions and research workflows. It has no live market feed or external language model.';
    _updateMind(() {
      _assistantHistory.add('You: '+message);
      _assistantHistory.add('Mind: '+reply);
      _assistantController.clear();
    });
  }

  static const _references=<(String,String,String)>[
    ('Mind Overview','overview','Engine status, performance, decision cards'),
    ('Strategy Universe','strategies','Families, filters, relationships'),
    ('Model Laboratory','models','Leaderboard, health, champion comparison'),
    ('Chart Intelligence','charts','Candles, signals, multi-timeframe'),
    ('Decision Explorer','decisions','Decision evidence and alternatives'),
    ('Dependency Map','dependencies','Model and strategy relationships'),
    ('Research & Backtesting','research','Experiments, equity curves, validation'),
    ('Multibagger Discovery','multibagger','Screening, growth, valuation, theses'),
    ('Portfolio & Risk','portfolio','Capital, stress and risk controls'),
  ];

  Widget galleryScreen()=>_surface('Visual Mockup Gallery',
    'Nine original design references represented as Flutter navigation previews',[
    const MindPanel(child:Text(
      'The nine source mockup PNGs are not embedded in this code branch. These interactive Flutter previews follow the same near-black/mint design system; pixel-perfect comparison is still pending.',
      style:TextStyle(color:MindPalette.yellow,fontSize:12))),
    const SizedBox(height:12),
    LayoutBuilder(builder:(context,c){
      final cols=c.maxWidth>720?3:2;
      return GridView.count(
        shrinkWrap:true,physics:const NeverScrollableScrollPhysics(),
        crossAxisCount:cols,crossAxisSpacing:10,mainAxisSpacing:10,
        childAspectRatio:cols==3?1.13:.91,
        children:[
          for(var i=0;i<_references.length;i++)
            MindPanel(
              onTap:()=>_updateMind((){
                _reference=_references[i].$1;
                _route='design-preview';
              }),
              child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
                Expanded(child:Container(
                  width:double.infinity,
                  decoration:BoxDecoration(color:MindPalette.panel,
                    borderRadius:BorderRadius.circular(12),
                    border:Border.all(color:MindPalette.border)),
                  child:Column(mainAxisAlignment:MainAxisAlignment.center,children:[
                    Icon(Icons.psychology_outlined,color:MindPalette.mint,
                      size:38-i%3*4),
                    const SizedBox(height:9),
                    MindSparkline(seed:i+7,width:85,height:34),
                    const SizedBox(height:10),
                    Text(_references[i].$1,textAlign:TextAlign.center,
                      style:const TextStyle(color:MindPalette.text,fontSize:12)),
                  ]),
                )),
                const SizedBox(height:9),
                Text(_references[i].$1,
                  style:const TextStyle(color:MindPalette.text,fontWeight:FontWeight.w800)),
                Text(_references[i].$3,maxLines:2,overflow:TextOverflow.ellipsis,
                  style:const TextStyle(color:MindPalette.muted,fontSize:10)),
              ]),
            ),
        ],
      );
    }),
  ]);

  Widget designPreviewScreen() {
    final ref=_references.firstWhere((r)=>r.$1==_reference,
      orElse:()=>_references.first);
    return _surface('Clickable Design Mode',
      'Interactive Flutter preview mapped to the reference screen layout',[
      _chips(_references.map((r)=>r.$1).toList(),_reference,
        (v)=>_updateMind(()=>_reference=v)),
      const SizedBox(height:12),
      Center(child:ConstrainedBox(constraints:const BoxConstraints(maxWidth:420),
        child:Container(padding:const EdgeInsets.all(12),
          decoration:BoxDecoration(
            borderRadius:BorderRadius.circular(30),
            color:const Color(0xFF020607),
            border:Border.all(color:MindPalette.border,width:2)),
          child:Column(children:[
            const SizedBox(height:8),
            const Row(children:[
              Icon(Icons.stacked_line_chart_rounded,color:MindPalette.mint,size:23),
              SizedBox(width:7),
              Text('MintTick',style:TextStyle(color:MindPalette.text,
                fontSize:20,fontWeight:FontWeight.w800)),
              Spacer(),MindTag('Owner'),
            ]),
            const SizedBox(height:20),
            Text(ref.$1,style:const TextStyle(color:MindPalette.text,
              fontSize:25,fontWeight:FontWeight.w800)),
            Text(ref.$3,textAlign:TextAlign.center,
              style:const TextStyle(color:MindPalette.muted,fontSize:12)),
            const SizedBox(height:16),
            MindPanel(highlight:true,child:Column(children:[
              const Row(children:[
                Icon(Icons.psychology_outlined,color:MindPalette.mint,size:31),
                SizedBox(width:9),
                Expanded(child:Text('MintTick Mind',
                  style:TextStyle(color:MindPalette.text,fontWeight:FontWeight.w800))),
              ]),
              const SizedBox(height:10),
              _mockChart(seed:_references.indexOf(ref)+6,height:140),
              _value('Reference experience','Mock-only'),
              _value('Navigation','Interactive'),
            ])),
            const SizedBox(height:12),
            _tile('Explore '+ref.$1,ref.$3,
              Icons.touch_app_outlined,
              tap:()=>_go(ref.$2)),
            const SizedBox(height:14),
            const Text('Home        Markets          Mind          Portfolio',
              textAlign:TextAlign.center,
              style:TextStyle(color:MindPalette.muted,fontSize:10)),
            const SizedBox(height:9),
          ]),
        )),
      ),
      const SizedBox(height:10),
      const Text('This is a native Flutter recreation, not an embedded bitmap or the original standalone HTML. Exact pixel parity requires screenshot comparison.',
        style:TextStyle(color:MindPalette.yellow,fontSize:11)),
      _actionButton('View all nine references',()=>_go('design-gallery')),
    ]);
  }

  Widget _actionButton(String label,VoidCallback onPressed)=>
    OutlinedButton(onPressed:onPressed,
      child:Text(label,style:const TextStyle(color:MindPalette.mint)));

  Widget coverageScreen() {
    final repository = widget.progressRepository;
    if (repository != null) {
      return _surface('Screen & Strategy Coverage',
        'Audited inventory with server-side evidence. DONE only after runtime verification.',[
        MindProgressCoverageView(
          key: const ValueKey('mind-progress-coverage'),
          repository: repository,
          onOpenRoute: _go,
        ),
      ]);
    }
    final groups=['PRIMARY','AGENTS','RESEARCH','CAPITAL','SYSTEM'];
    return _surface('Screen & Strategy Coverage',
      'Progress registry not connected · nothing on this page is verified',[
      MindPanel(key:const ValueKey('mind-progress-not-connected'),
        child:const Text('Progress not verified: sign in to load the audited server registry. '
          'Screens below are listed for navigation only.',
          style:TextStyle(color:MindPalette.text,fontSize:12))),
      const SizedBox(height:13),
      for(final group in groups)
        _section(group,Icons.fact_check_outlined,[
          for(final screen in MintickMindScreens.all.where((s)=>s.group==group))
            Padding(padding:const EdgeInsets.only(bottom:7),
              child:_tile(screen.label,'Route: '+screen.id+' · Not verified',
                screen.icon,trailing:'Open',tap:()=>_go(screen.id))),
        ]),
    ]);
  }

  Widget _realSettingsScreen() {
    _ensurePortfolio();
    _ensureRegistry();
    final p=_portfolio;
    final snap=_registrySnapshot;
    return _surface('Preferences','Real controls only · PAPER virtual money · LIVE is not authorized here',[
      MindPanel(highlight:true,child:Column(children:[
        const MindSectionTitle(title:'Execution Boundary',icon:Icons.shield_outlined),
        _value('Execution mode','PAPER virtual only'),
        _value('LIVE broker orders','Not authorized',color:MindPalette.red),
        _value('Automatic risk limits','Enforced by the server; not user-adjustable'),
        _value('Backend revision',snap==null?'Loading…':(snap.revisionKnown?snap.backendRevision:'Unknown')),
      ])),
      const SizedBox(height:12),
      _section('PAPER Room Controls',Icons.pan_tool_outlined,[
        if(p==null) const MindPanel(child:Text('Loading PAPER rooms…',
          style:TextStyle(color:MindPalette.muted,fontSize:12))),
        if(p!=null && p.rooms.isEmpty)
          _empty('No funded PAPER room. Activate one in Trading Rooms first.'),
        if(p!=null) for(final r in p.rooms)
          Padding(padding:const EdgeInsets.only(bottom:7),child:MindPanel(
            key:ValueKey('mind-settings-room-${r.roomId}'),child:Row(children:[
              Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
                Text(r.name,style:const TextStyle(color:MindPalette.text,fontWeight:FontWeight.w700)),
                Text(r.paused?'Paused · new virtual fills are rejected':'Active · fills allowed inside the server gates',
                  style:const TextStyle(color:MindPalette.muted,fontSize:11)),
              ])),
              if(_pausing.contains(r.roomId))
                const SizedBox(width:18,height:18,child:CircularProgressIndicator(strokeWidth:2))
              else Switch.adaptive(
                key:ValueKey('mind-settings-pause-${r.roomId}'),
                activeTrackColor:MindPalette.red,value:r.paused,
                onChanged:(v)=>_setRoomPaused(r.roomId,v)),
            ]))),
      ]),
      _section('Reference & Completeness',Icons.fact_check_outlined,[
        _tile('Every route and family','Coverage and audited progress',
          Icons.fact_check_outlined,route:'coverage'),
        const SizedBox(height:8),
        _tile('Ask MintTick Mind','Read-only answers from recorded data',
          Icons.forum_outlined,route:'assistant'),
        const SizedBox(height:8),
        _tile('Original design gallery','Layout reference only, not a product feature',
          Icons.grid_view_outlined,route:'design-gallery'),
      ]),
    ]);
  }

  Widget settingsScreen(){
    if(widget.paperPortfolioRepository!=null) return _realSettingsScreen();
    return _surface('Preferences',
    'Single-owner MintTick Mind mock UI settings; account settings are unchanged',[
    MindPanel(highlight:true,child:Column(children:[
      const MindSectionTitle(title:'Personal Workspace',icon:Icons.tune_outlined),
      _value('User authentication','Existing MintTick login'),
      _value('Additional admin account','Not required'),
      _value('UI theme','Dark preview / near-black'),
      _value('Accent','MintTick green'),
      _value('Demo data','Always clearly labeled'),
      _value('Phone / desktop','Responsive Flutter layouts'),
    ])),
    const SizedBox(height:12),
    _section('Personal Monitoring Preferences',Icons.settings_outlined,[
      _toggle('Paper mode','Research-only user interface preference'),
      const SizedBox(height:8),
      _toggle('Shadow mode','Mock comparison mode'),
      const SizedBox(height:8),
      _tile('Engine settings','Review mock controls and safeguards',
        Icons.settings_outlined,route:'controls'),
    ]),
    _section('Reference & Completeness',Icons.fact_check_outlined,[
      _tile('Original design gallery','Nine reference mockup screens',
        Icons.grid_view_outlined,route:'design-gallery'),
      const SizedBox(height:8),
      _tile('Every route and family','Coverage and implementation mapping',
        Icons.fact_check_outlined,route:'coverage'),
      const SizedBox(height:8),
      _tile('Help & research questions','Scripted read-only explanations',
        Icons.forum_outlined,route:'assistant'),
    ]),
  ]);
  }
}
