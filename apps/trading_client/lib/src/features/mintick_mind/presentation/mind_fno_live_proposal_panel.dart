import 'package:flutter/material.dart';
import '../data/mind_fno_live_proposal_repository.dart';
import 'mind_widgets.dart';

/// Read-only LIVE proposal and broker/risk evidence. Never submits an order.
class MindFnoLiveProposalPanel extends StatefulWidget {
  const MindFnoLiveProposalPanel({required this.repository,super.key});
  final MindFnoLiveProposalRepository repository;

  @override
  State<MindFnoLiveProposalPanel> createState()=>_MindFnoLiveProposalPanelState();
}

class _MindFnoLiveProposalPanelState extends State<MindFnoLiveProposalPanel> {
  late final Future<List<MindFnoLiveRoom>> _rooms=widget.repository.rooms();
  String? _selected;
  Future<MindFnoLiveProposal>? _evaluation;

  void _check(String id) => setState(() {
    _selected=id;
    _evaluation=widget.repository.evaluate(id);
  });

  @override
  Widget build(BuildContext context)=>MindPanel(
    key:const ValueKey('mind-fno-live-proposal-panel'),
    child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
      const Row(children:[
        Icon(Icons.fact_check_outlined,color:MindPalette.mint,size:18),
        SizedBox(width:7),
        Expanded(child:Text('LIVE F&O · Risk proposal',
          style:TextStyle(color:MindPalette.text,fontSize:13,fontWeight:FontWeight.w800))),
        MindTag('READ ONLY',color:MindPalette.yellow),
      ]),
      const SizedBox(height:5),
      const Text('Verified broker, room, quote and risk checks. '
        'This panel never places or authorizes real-money orders.',
        style:TextStyle(color:MindPalette.muted,fontSize:11)),
      const SizedBox(height:9),
      FutureBuilder<List<MindFnoLiveRoom>>(
        future:_rooms,builder:(context,snapshot) {
          if(!snapshot.hasData) {
            return Text(snapshot.hasError?'LIVE F&O rooms unavailable':'Checking LIVE rooms…',
              style:const TextStyle(color:MindPalette.muted,fontSize:11));
          }
          final rooms=snapshot.data!;
          if(rooms.isEmpty) {
            return const Text('Create a dedicated LIVE F&O room for risk preflight.',
              style:TextStyle(color:MindPalette.yellow,fontSize:11));
          }
          return DropdownButtonFormField<String>(
            key:const ValueKey('mind-fno-live-proposal-room'),
            initialValue:_selected,isExpanded:true,
            dropdownColor:MindPalette.panel,
            decoration:const InputDecoration(labelText:'LIVE F&O room',isDense:true),
            items:[for(final r in rooms)DropdownMenuItem(
              value:r.id,child:Text(r.name,overflow:TextOverflow.ellipsis))],
            onChanged:(id){if(id!=null) {_check(id);}},
          );
        },
      ),
      if(_evaluation!=null) ...[
        const SizedBox(height:8),
        Row(children:[
          const Expanded(child:Text('Source and risk evidence',
            style:TextStyle(color:MindPalette.muted,fontSize:11))),
          IconButton(
            key:const ValueKey('mind-fno-live-proposal-refresh'),
            onPressed:_selected==null?null:()=>_check(_selected!),
            icon:const Icon(Icons.refresh,color:MindPalette.muted)),
        ]),
        FutureBuilder<MindFnoLiveProposal>(
          future:_evaluation,builder:(context,snapshot) {
            if(snapshot.connectionState!=ConnectionState.done) {
              return const Text('Evaluating risk and broker evidence…',
                style:TextStyle(color:MindPalette.muted,fontSize:11));
            }
            if(snapshot.hasError || !snapshot.hasData) {
              return const Text('Risk preflight unavailable. No order proposed.',
                key:ValueKey('mind-fno-live-proposal-error'),
                style:TextStyle(color:MindPalette.yellow,fontSize:11));
            }
            final p=snapshot.data!;
            return Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
              Text(p.isRiskPassed?'RISK PASSED · DRY RUN':'BLOCKED · NO TRADE',
                key:const ValueKey('mind-fno-live-proposal-status'),
                style:TextStyle(
                  color:p.isRiskPassed?MindPalette.mint:MindPalette.yellow,
                  fontWeight:FontWeight.w800,fontSize:12)),
              const SizedBox(height:5),
              if(!p.isRiskPassed)
                for(final reason in p.reasons.take(5))
                  Text(reason.replaceAll('_',' '),
                    style:const TextStyle(color:MindPalette.muted,fontSize:10)),
              if(p.isRiskPassed) ...[
                Text('${p.strategyId} · ${p.instrumentKey}',
                  style:const TextStyle(color:MindPalette.text,fontSize:11)),
                Text('Expiry ${p.expiry} · 1 lot / ${p.units} units',
                  style:const TextStyle(color:MindPalette.muted,fontSize:10)),
                Text('Indicative limit ₹${p.limitPrice} · Premium ₹${p.premiumInr}',
                  style:const TextStyle(color:MindPalette.text,fontSize:11)),
                Text('Risk cap ₹${p.maximumPremiumInr} · Stop ₹${p.stopReference} · Target ₹${p.targetReference}',
                  style:const TextStyle(color:MindPalette.muted,fontSize:10)),
              ],
              Text('Checked ${p.evaluatedAt.toUtc().toIso8601String()}',
                style:const TextStyle(color:MindPalette.muted,fontSize:10)),
              const SizedBox(height:5),
              const Text('NO ORDER PLACED. Reference stops are not guaranteed; '
                'the entire option premium can be lost.',
                key:ValueKey('mind-fno-live-proposal-no-orders'),
                style:TextStyle(color:MindPalette.yellow,fontSize:10)),
            ]);
          },
        ),
      ],
    ]),
  );
}
