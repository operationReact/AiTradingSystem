import 'package:flutter/material.dart';

import '../data/mind_fixtures.dart';
import 'mind_widgets.dart';

/// MintTick Mind — mock-only first vertical slice, translated into native
/// Flutter from the approved standalone HTML/screenshot. No API calls or
/// trading execution methods are reachable from this widget.
class MintickMindPage extends StatefulWidget {
  const MintickMindPage({super.key});
  @override
  State<MintickMindPage> createState() => _MintickMindPageState();
}

class _MintickMindPageState extends State<MintickMindPage> {
  int _tab = 0;
  String _search = '';
  String _family = 'All';
  String _modelFilter = 'All';
  String _decisionFilter = 'All';
  int _limit = 24;
  static const _tabs = ['Overview','Strategies','Models','Decisions'];
  static const _head = TextStyle(color:MindPalette.text,fontSize:15,fontWeight:FontWeight.w800);
  static const _sub = TextStyle(color:MindPalette.muted,fontSize:11);
  static const _decisions = <(String,String,String,String)>[
    ('TITAN','Intraday','WAIT','Volume and regime do not justify a new entry'),
    ('CDSL','Swing','BUY CANDIDATE','Relative strength improving; risk approval pending'),
    ('IREDA','Long-term','WATCH','Investment thesis needs credit-quality verification'),
    ('HDFCBANK','Intraday','SELL HOLDINGS','Simulated intraday weakness; no order'),
    ('RELIANCE','Delivery','HOLD','Wait for valuation and price confirmation'),
    ('NTPC','Cash short','ABSTAIN','Cash short eligibility is not confirmed'),
    ('INFY','Swing','BUY CANDIDATE','Trend and volume aligned for paper research'),
    ('NIFTY50','Futures','ABSTAIN','Derivative execution disabled'),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: const ValueKey('mintick-mind-mock-scaffold'),
      backgroundColor: MindPalette.background,
      body: SafeArea(
        bottom: false,
        child: Center(child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1110),
          child: ListView(
            key: const ValueKey('mintick-mind-scroll'),
            padding: const EdgeInsets.fromLTRB(14, 14, 14, 28),
            children: [
              _header(),
              const SizedBox(height: 25),
              Row(children: [
                Expanded(child: Column(crossAxisAlignment:CrossAxisAlignment.start,children: const [
                  Text('Mintick Mind',style:TextStyle(color:MindPalette.text,fontSize:30,fontWeight:FontWeight.w800,letterSpacing:-.8)),
                  SizedBox(height:2),
                  Text('Personal Trading Intelligence',style:TextStyle(color:MindPalette.muted,fontSize:14)),
                ])),
                const MindSparkline(seed:26,width:105,height:53),
              ]),
              const SizedBox(height: 20),
              _tabsBar(),
              const SizedBox(height: 16),
              _demoNotice(),
              const SizedBox(height: 13),
              if(_tab==0) _overview(),
              if(_tab==1) _strategies(),
              if(_tab==2) _models(),
              if(_tab==3) _decisionsView(),
              if(_tab==4) _multibaggers(),
            ],
          ),
        )),
      ),
    );
  }

  Widget _header() => Row(children: [
    const Icon(Icons.stacked_line_chart_rounded,color:MindPalette.mint,size:27),
    const SizedBox(width:6),
    const Expanded(child:Text('MintTick',maxLines:1,overflow:TextOverflow.ellipsis,style:TextStyle(color:MindPalette.text,fontSize:21,fontWeight:FontWeight.w800,letterSpacing:-.6))),
    const MindTag('Owner'),
    IconButton(tooltip:'Search strategies',onPressed:()=>_setTab(1),icon:const Icon(Icons.search_rounded,color:MindPalette.text,size:21)),
    IconButton(tooltip:'Mock notifications',onPressed:()=>_showDetails('Notifications',const ['3 research model events','4 decision candidates','1 simulated risk notice']),icon:const Icon(Icons.notifications_none_rounded,color:MindPalette.text,size:21)),
  ]);

  Widget _tabsBar() => Container(
    decoration:BoxDecoration(color:MindPalette.panel,border:Border.all(color:MindPalette.border),borderRadius:BorderRadius.circular(15)),
    padding:const EdgeInsets.all(3),
    child:Row(children: List.generate(_tabs.length,(index){
      final active = _tab==index;
      return Expanded(child:Material(color:Colors.transparent,child:InkWell(
        key:ValueKey('mind-tab-$index'),
        borderRadius:BorderRadius.circular(12),
        onTap:()=>_setTab(index),
        child:AnimatedContainer(duration:const Duration(milliseconds:170),
          padding:const EdgeInsets.symmetric(vertical:12,horizontal:3),
          decoration:BoxDecoration(
            color:active?const Color(0xFF1C644A):Colors.transparent,
            borderRadius:BorderRadius.circular(12),
            border:active?Border.all(color:MindPalette.mint):null,
          ),
          child:Text(_tabs[index],textAlign:TextAlign.center,maxLines:1,style:TextStyle(color:active?MindPalette.text:MindPalette.muted,fontSize:12,fontWeight:active?FontWeight.w800:FontWeight.w600)),
        ),
      )));
    })),
  );

  Widget _demoNotice()=> Row(children: const [
    Icon(Icons.science_outlined,color:MindPalette.yellow,size:13),
    SizedBox(width:5),
    Expanded(child:Text('MOCK UI · All market values, model scores and signals are fictional. No broker connection.',style:TextStyle(color:MindPalette.yellow,fontSize:10.5))),
  ]);

  void _setTab(int index){setState((){_tab=index;_search='';_limit=24;});}

  Widget _overview() => Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
    MindPanel(key:const ValueKey('mind-engine-hero'),highlight:true,child:Row(children:[
      Container(width:64,height:64,decoration:BoxDecoration(color:const Color(0xFF103C2D),borderRadius:BorderRadius.circular(17),border:Border.all(color:MindPalette.green)),child:const Icon(Icons.psychology_outlined,color:MindPalette.mint,size:35)),
      const SizedBox(width:13),
      Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Wrap(crossAxisAlignment:WrapCrossAlignment.center,spacing:9,runSpacing:5,children:const [
          Text('Mintick Mind Engine',style:TextStyle(fontSize:17,color:MindPalette.text,fontWeight:FontWeight.w800)),
          MindTag('Demo simulation'),
        ]),
        const SizedBox(height:7),
        const Text('Market Posture: Selective bullish',style:TextStyle(color:MindPalette.mint,fontWeight:FontWeight.w700,fontSize:14)),
        const SizedBox(height:5),
        const Text('23 recently scanned · 18 model records · 4 ideas under review',style:TextStyle(color:MindPalette.muted,fontSize:11)),
      ])),
    ])),
    const SizedBox(height:13),
    LayoutBuilder(builder:(context,size){
      final count=size.maxWidth >= 650?4:2;
      return GridView.count(
        physics:const NeverScrollableScrollPhysics(),shrinkWrap:true,crossAxisCount:count,
        crossAxisSpacing:10,mainAxisSpacing:10,childAspectRatio:count==4?1.55:1.48,
        children:const [
          MindMetric(label:'Today P&L',value:'+₹38,420',caption:'+1.92% · Mock',seed:3),
          MindMetric(label:'Win Rate',value:'68.4%',caption:'Synthetic research sample',color:MindPalette.text,seed:4),
          MindMetric(label:'Open Ideas',value:'12',caption:'2 more than yesterday',color:MindPalette.text,seed:11),
          MindMetric(label:'Risk Usage',value:'34%',caption:'Of illustrative limit',color:MindPalette.text,seed:7),
        ],
      );
    }),
    const SizedBox(height:17),
    MindSectionTitle(title:'Decision Engine',icon:Icons.psychology_outlined,onSeeAll:()=>_setTab(3)),
    Row(children:[
      _decisionTile('Intraday','BUY', '74%',MindPalette.mint,7),
      const SizedBox(width:8),
      _decisionTile('Swing','WAIT','58%',MindPalette.yellow,13),
      const SizedBox(width:8),
      _decisionTile('Long-term','WATCH','71%',MindPalette.purple,6),
    ]),
    const SizedBox(height:18),
    MindSectionTitle(title:'Top Active Strategies',icon:Icons.emoji_events_outlined,onSeeAll:()=>_setTab(1)),
    MindPanel(child:Column(children:[
      _previewStrategy('Momentum Breakout','Trend · Volume · Strength','Active','+6.82%',2),
      _divider(),
      _previewStrategy('VWAP Reclaim','Mean reversion · Liquidity','Active','+4.17%',7),
      _divider(),
      _previewStrategy('Mean Reversion','Oversold · Statistical Edge','Watch','+1.26%',6),
      _divider(),
      _previewStrategy('Fundamental Compounders','Quality · Growth · Longevity','Research','+8.91%',9),
    ])),
    const SizedBox(height:13),
    LayoutBuilder(builder:(context,size)=>size.maxWidth>=680
      ?Row(crossAxisAlignment:CrossAxisAlignment.start,children:[Expanded(child:_modelPulse()),const SizedBox(width:12),Expanded(child:_radar())])
      :Column(children:[_modelPulse(),const SizedBox(height:12),_radar()])),
    const SizedBox(height:12),
    MindPanel(onTap:()=>_showDetails('Next screens',const ['Chart Intelligence','Dependency Map','Research & Backtesting','Portfolio & Risk','Futures & Options']),child:const Row(children:[
      Icon(Icons.account_tree_outlined,color:MindPalette.mint),SizedBox(width:9),
      Expanded(child:Text('Explore the complete Mind workspace',style:TextStyle(color:MindPalette.text,fontWeight:FontWeight.w700))),
      Icon(Icons.chevron_right,color:MindPalette.muted),
    ])),
  ]);

  Widget _decisionTile(String name,String verdict,String score,Color color,int seed)=> Expanded(
    child:MindPanel(
      padding:const EdgeInsets.fromLTRB(9,12,8,10),
      onTap:()=>_setTab(3),
      child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Text(name,maxLines:1,overflow:TextOverflow.ellipsis,style:const TextStyle(color:MindPalette.text,fontSize:11,fontWeight:FontWeight.w700)),
        const SizedBox(height:9),
        Text(verdict,style:TextStyle(color:color,fontSize:12,fontWeight:FontWeight.w800)),
        const SizedBox(height:6),
        Text('Confidence $score',maxLines:1,overflow:TextOverflow.ellipsis,style:const TextStyle(color:MindPalette.muted,fontSize:10)),
        const SizedBox(height:6),
        MindSparkline(seed:seed,color:color,width:79,height:23),
      ]),
    ),
  );

  Widget _previewStrategy(String title,String description,String status,String performance,int seed) => InkWell(
    onTap:()=>_showDetails(title,[description,'Status: $status (mock)','Illustrative MTD return: $performance','No real trading decisions are connected.']),
    child:Padding(
      padding:const EdgeInsets.symmetric(vertical:7),
      child:Row(children:[
        Container(width:36,height:36,decoration:BoxDecoration(color:const Color(0xFF10352A),borderRadius:BorderRadius.circular(10)),child:const Icon(Icons.show_chart,color:MindPalette.mint,size:20)),
        const SizedBox(width:9),
        Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
          Text(title,style:_head,maxLines:1,overflow:TextOverflow.ellipsis),
          Text(description,style:_sub,maxLines:1,overflow:TextOverflow.ellipsis),
        ])),
        const SizedBox(width:4),
        if(MediaQuery.sizeOf(context).width>370) MindTag(status,color:status=='Watch'?MindPalette.yellow:status=='Research'?MindPalette.purple:MindPalette.mint),
        const SizedBox(width:7),
        Column(crossAxisAlignment:CrossAxisAlignment.end,children:[
          Text(performance,style:const TextStyle(color:MindPalette.mint,fontWeight:FontWeight.w800,fontSize:12)),
          MindSparkline(seed:seed,width:47,height:18),
        ]),
        const Icon(Icons.chevron_right,color:MindPalette.muted,size:15),
      ]),
    ),
  );

  Widget _modelPulse()=> MindPanel(child:Column(children:[
    const MindSectionTitle(title:'Model Pulse',icon:Icons.layers_outlined),
    for(final model in MindFixtures.models.take(5)) Padding(
      padding:const EdgeInsets.symmetric(vertical:7),
      child:Row(children:[
        const Icon(Icons.multiline_chart,color:MindPalette.mint,size:18),
        const SizedBox(width:9),
        Expanded(child:Text(model.name,style:const TextStyle(color:MindPalette.text,fontSize:13),maxLines:1,overflow:TextOverflow.ellipsis)),
        const MindTag('Research'),
        const SizedBox(width:8),
        Text(model.metric,style:_sub),
      ]),
    ),
    TextButton(onPressed:()=>_setTab(2),child:const Text('View all models →',style:TextStyle(color:MindPalette.mint))),
  ]));

  Widget _radar()=>MindPanel(child:Column(children:[
    MindSectionTitle(title:'Multibagger Radar',icon:Icons.track_changes_outlined,onSeeAll:()=>_setTab(4)),
    for(final company in MindFixtures.companies) InkWell(
      onTap:()=>_companyDetails(company),
      child:Padding(
        padding:const EdgeInsets.symmetric(vertical:9),
        child:Row(children:[
          CircleAvatar(radius:18,backgroundColor:const Color(0xFF14313B),child:Text(company.symbol.substring(0,1),style:const TextStyle(color:MindPalette.text))),
          const SizedBox(width:10),
          Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
            Text(company.symbol,style:const TextStyle(color:MindPalette.text,fontWeight:FontWeight.w800,fontSize:13)),
            Text(company.name,maxLines:1,overflow:TextOverflow.ellipsis,style:_sub),
          ])),
          Column(crossAxisAlignment:CrossAxisAlignment.end,children:[
            MindTag(company.stage,color:company.stage=='Screening'?MindPalette.yellow:company.stage=='Deep Review'?MindPalette.purple:MindPalette.mint),
            const SizedBox(height:3),
            Text(company.change,style:const TextStyle(color:MindPalette.mint,fontSize:10)),
          ]),
        ]),
      ),
    ),
  ]));

  Widget _strategies(){
    final strategies=MindFixtures.strategies.where((s)=>
      (_family=='All'||s.family==_family)&&
      (s.name.toLowerCase().contains(_search.toLowerCase())||s.family.toLowerCase().contains(_search.toLowerCase()))
    ).toList();
    return Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
      const MindSectionTitle(title:'Strategy Universe',icon:Icons.layers_rounded),
      Text('${MindFixtures.strategies.length} strategy templates across ${MindFixtures.strategyFamilies.length} families',style:_sub),
      const SizedBox(height:12),
      TextField(key:const ValueKey('mind-strategy-search'),onChanged:(value)=>setState((){_search=value;_limit=24;}),style:const TextStyle(color:MindPalette.text),decoration:InputDecoration(prefixIcon:const Icon(Icons.search,color:MindPalette.muted),hintText:'Search strategies (e.g., VWAP, Breakout...)',hintStyle:_sub,filled:true,fillColor:MindPalette.card,enabledBorder:OutlineInputBorder(borderSide:const BorderSide(color:MindPalette.border),borderRadius:BorderRadius.circular(11)))),
      const SizedBox(height:12),
      SizedBox(height:43,child:ListView(scrollDirection:Axis.horizontal,children:[
        for(final family in ['All',...MindFixtures.strategyFamilies]) Padding(
          padding:const EdgeInsets.only(right:7),
          child:ChoiceChip(
            label:Text(family),
            selected:_family==family,
            onSelected:(_)=>setState((){_family=family;_limit=24;}),
            selectedColor:const Color(0xFF185C42),backgroundColor:MindPalette.card,side:const BorderSide(color:MindPalette.border),
            labelStyle:TextStyle(color:_family==family?MindPalette.mint:MindPalette.text,fontSize:11),
          ),
        ),
      ])),
      const SizedBox(height:11),
      MindPanel(child:Column(children:[
        for(final strategy in strategies.take(_limit))
          InkWell(
            onTap:()=>_showStrategy(strategy),
            child:Padding(padding:const EdgeInsets.symmetric(vertical:9),child:Row(children:[
              const Icon(Icons.trending_up,color:MindPalette.mint,size:20),
              const SizedBox(width:10),
              Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
                Text(strategy.name,style:const TextStyle(color:MindPalette.text,fontWeight:FontWeight.w700)),
                Text('${strategy.id} · ${strategy.family} · ${strategy.modes.join(" / ")}',style:_sub,maxLines:1,overflow:TextOverflow.ellipsis),
              ])),
              MindTag(strategy.status,color:strategy.status=='Research'?MindPalette.purple:MindPalette.mint),
              const Icon(Icons.chevron_right,color:MindPalette.muted,size:16),
            ])),
          ),
        if(strategies.isEmpty) const MindEmptyState('No matching strategies'),
      ])),
      const SizedBox(height:10),
      if(strategies.length>_limit) OutlinedButton(onPressed:()=>setState(()=>_limit+=24),child:Text('Show more (${strategies.length-_limit} remaining)',style:const TextStyle(color:MindPalette.mint))),
    ]);
  }

  Widget _models(){
    final visible=MindFixtures.models.where((model)=>_modelFilter=='All'||model.status==_modelFilter).toList();
    return Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
      const MindSectionTitle(title:'Model Laboratory',icon:Icons.psychology_rounded),
      const Text('Models, challengers, and performance health · research fixtures',style:_sub),
      const SizedBox(height:12),
      Wrap(spacing:8,runSpacing:7,children:[
        for(final status in ['All','Research','Adapter','Integration','License review','Planned','Advanced R&D'])
          ChoiceChip(label:Text(status),selected:_modelFilter==status,onSelected:(_)=>setState(()=>_modelFilter=status),
            selectedColor:const Color(0xFF185C42),backgroundColor:MindPalette.card,side:const BorderSide(color:MindPalette.border),
            labelStyle:TextStyle(color:_modelFilter==status?MindPalette.mint:MindPalette.text,fontSize:11)),
      ]),
      const SizedBox(height:12),
      MindPanel(child:Column(children:[
        for(final model in visible)
          InkWell(onTap:()=>_showDetails(model.name,['Family: ${model.family}','Role: ${model.role}','Status: ${model.status}','Research metric: ${model.metric}',model.description,'All numbers are synthetic and cannot authorize capital.']),child:Padding(
            padding:const EdgeInsets.symmetric(vertical:10),
            child:Row(children:[
              const Icon(Icons.insights_rounded,color:MindPalette.mint,size:20),const SizedBox(width:10),
              Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
                Text(model.name,style:_head,maxLines:1,overflow:TextOverflow.ellipsis),
                Text('${model.family} · ${model.role}',style:_sub),
              ])),
              const SizedBox(width:5),
              Column(crossAxisAlignment:CrossAxisAlignment.end,children:[
                MindTag(model.status,color:model.status=='Research'?MindPalette.purple:MindPalette.yellow),
                const SizedBox(height:4),
                Text(model.metric,style:_sub),
              ]),
              const Icon(Icons.chevron_right,color:MindPalette.muted,size:14),
            ]),
          )),
      ])),
    ]);
  }

  Widget _decisionsView()=>Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
    const MindSectionTitle(title:'Decision Explorer',icon:Icons.track_changes_outlined),
    const Text('Why the engine bought, waited, or abstained — simulated evidence',style:_sub),
    const SizedBox(height:12),
    SizedBox(height:43,child:ListView(scrollDirection:Axis.horizontal,children:[
      for(final status in ['All','WAIT','BUY CANDIDATE','HOLD','ABSTAIN','WATCH','SELL HOLDINGS'])
        Padding(padding:const EdgeInsets.only(right:7),child:ChoiceChip(label:Text(status),selected:_decisionFilter==status,
          onSelected:(_)=>setState(()=>_decisionFilter=status),selectedColor:const Color(0xFF185C42),
          backgroundColor:MindPalette.card,side:const BorderSide(color:MindPalette.border),
          labelStyle:TextStyle(color:_decisionFilter==status?MindPalette.mint:MindPalette.text,fontSize:11))),
    ])),
    const SizedBox(height:13),
    for(final decision in _decisions)
      if(_decisionFilter=='All'||_decisionFilter==decision.$3)
        Padding(padding:const EdgeInsets.only(bottom:9),child:MindPanel(
          onTap:()=>_showDetails('Decision: ${decision.$1}',[
            'Category: ${decision.$2}',
            'Decision: ${decision.$3}',
            'Reason: ${decision.$4}',
            'Evidence: synthetic strategy, market-regime and model scores',
            'Execution: NO ORDER · demonstration only',
          ]),
          child:Row(children:[
            const Icon(Icons.psychology_outlined,color:MindPalette.mint,size:20),
            const SizedBox(width:9),
            Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
              Text(decision.$1,style:_head),
              Text(decision.$2,style:_sub),
              Text(decision.$4,maxLines:2,overflow:TextOverflow.ellipsis,style:_sub),
            ])),
            const SizedBox(width:6),
            MindTag(decision.$3,color:decision.$3=='WAIT'?MindPalette.yellow:decision.$3=='ABSTAIN'?MindPalette.red:MindPalette.mint),
          ]),
        )),
  ]);

  Widget _multibaggers()=>Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
    TextButton.icon(onPressed:()=>_setTab(0),icon:const Icon(Icons.arrow_back,color:MindPalette.mint),label:const Text('Overview',style:TextStyle(color:MindPalette.mint))),
    const MindSectionTitle(title:'Multibagger Discovery',icon:Icons.diamond_outlined),
    const Text('Long-horizon compounder research · none of these are recommendations',style:_sub),
    const SizedBox(height:12),
    for(final company in MindFixtures.companies)
      Padding(padding:const EdgeInsets.only(bottom:12),child:MindPanel(
        onTap:()=>_companyDetails(company),highlight:true,
        child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
          Row(children:[Expanded(child:Text('${company.symbol} · ${company.name}',style:_head)),MindTag(company.stage)]),
          const SizedBox(height:10),
          const Text('Illustrative investment thesis · growth, quality, valuation and governance require independent verification.',style:_sub),
          const SizedBox(height:11),
          Row(children:[
            for(final (label,value,color) in [('Bull','5–7x',MindPalette.mint),('Base','2–4x',MindPalette.yellow),('Bear','0.5–1.5x',MindPalette.purple)])
              Expanded(child:Padding(padding:const EdgeInsets.only(right:5),child:Container(padding:const EdgeInsets.all(8),
                decoration:BoxDecoration(color:color.withValues(alpha:.08),borderRadius:BorderRadius.circular(10),border:Border.all(color:color.withValues(alpha:.4))),
                child:Column(children:[Text(label,style:TextStyle(color:color,fontSize:11)),Text(value,style:TextStyle(color:color,fontSize:14,fontWeight:FontWeight.w800))]),
              ))),
          ]),
          const SizedBox(height:9),
          const Text('Scenarios are placeholders, not calibrated price targets.',style:_sub),
        ]),
      )),
  ]);

  Widget _divider() => const Divider(height:1,color:MindPalette.border);

  void _showStrategy(MindStrategy strategy)=>_showDetails(strategy.name,[
    'ID: ${strategy.id}',
    'Family: ${strategy.family}',
    'Applicable horizons: ${strategy.modes.join(" / ")}',
    'Lifecycle: ${strategy.status}',
    'Research/backtest/paper eligibility must be validated individually.',
    'No live order is submitted by this prototype.',
  ]);

  void _companyDetails(MindCompany company)=>_showDetails('${company.symbol} · Investment thesis',[
    company.name,
    'Research stage: ${company.stage}',
    'Potential catalysts: growth and market positioning (illustrative)',
    'Risks: valuation, governance, liquidity, business execution',
    'Bull/base/bear outcomes are hypothetical, not measured probabilities.',
    'No investment recommendations or trading orders.',
  ]);

  void _showDetails(String name,List<String> details){
    showModalBottomSheet<void>(context:context,isScrollControlled:true,backgroundColor:MindPalette.panel,showDragHandle:true,
      builder:(context)=>SafeArea(child:Padding(
        padding:const EdgeInsets.fromLTRB(18,9,18,28),
        child:Column(mainAxisSize:MainAxisSize.min,crossAxisAlignment:CrossAxisAlignment.stretch,children:[
          Text(name,style:const TextStyle(color:MindPalette.text,fontSize:20,fontWeight:FontWeight.w800)),
          const SizedBox(height:10),
          for(final detail in details) Padding(padding:const EdgeInsets.symmetric(vertical:6),child:Row(crossAxisAlignment:CrossAxisAlignment.start,children:[
            const Icon(Icons.circle,color:MindPalette.mint,size:6),const SizedBox(width:9),
            Expanded(child:Text(detail,style:const TextStyle(color:MindPalette.muted,fontSize:13))),
          ])),
          const SizedBox(height:10),
          FilledButton(onPressed:()=>Navigator.pop(context),child:const Text('Close')),
        ]),
      )),
    );
  }
}
