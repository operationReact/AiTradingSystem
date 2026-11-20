import 'package:flutter/material.dart';
import '../data/mind_fno_live_proposal_repository.dart';
import '../../trading_rooms/data/customer_upstox_fno_live_repository.dart';
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
      if(_selected!=null)
        MindFnoLiveOrderAudit(
          key:ValueKey('mind-fno-live-audit-$_selected'),
          roomId:_selected!,repository:widget.repository),
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


/// The only action is an owner-initiated *history read* for an existing receipt.
/// No broker order submit, retry, cancel or modify action is exposed.
class MindFnoLiveOrderAudit extends StatefulWidget {
  const MindFnoLiveOrderAudit({
    required this.roomId,required this.repository,super.key,
  });
  final String roomId;
  final MindFnoLiveProposalRepository repository;

  @override
  State<MindFnoLiveOrderAudit> createState()=>_MindFnoLiveOrderAuditState();
}

class _MindFnoLiveOrderAuditState extends State<MindFnoLiveOrderAudit> {
  late Future<List<UpstoxFnoLiveReceipt>> _receipts=
      widget.repository.orderHistory(widget.roomId);
  bool _rechecking=false;
  String? _error;

  Future<void> _recheck(UpstoxFnoLiveReceipt receipt) async {
    if(_rechecking || !_unresolved(receipt.state)) { return; }
    final room=widget.roomId;
    setState(() {_rechecking=true;_error=null;});
    try {
      final checked=await widget.repository.recheckBrokerOrder(room,receipt.id);
      if(!mounted||widget.roomId!=room) { return; }
      if(checked.id!=receipt.id||checked.roomId!=room) {
        throw const FormatException('Broker receipt identity mismatch');
      }
      setState(() {
        _receipts=widget.repository.orderHistory(room);
      });
    } catch (_) {
      if(mounted && widget.roomId==room) {
        setState(() => _error=
          'Upstox order status unverified. Do not resubmit this order.');
      }
    } finally {
      if(mounted && widget.roomId==room) {
        setState(() => _rechecking=false);
      }
    }
  }

  static bool _unresolved(String status)=>
      const {'SUBMITTING','ACKNOWLEDGED','UNKNOWN'}.contains(status);

  @override
  Widget build(BuildContext context)=>Column(
    key:const ValueKey('mind-fno-live-broker-audit'),
    crossAxisAlignment:CrossAxisAlignment.start,children:[
      const SizedBox(height:10),
      Row(children:[
        const Expanded(child:Text('UPSTOX LIVE · ORDER AUDIT',
          style:TextStyle(color:MindPalette.muted,fontSize:10,
            fontWeight:FontWeight.w800))),
        IconButton(
          key:const ValueKey('mind-fno-live-orders-refresh'),
          tooltip:'Refresh saved LIVE order receipts',
          onPressed:_rechecking?null:()=>setState(() {
            _error=null;
            _receipts=widget.repository.orderHistory(widget.roomId);
          }),
          icon:const Icon(Icons.refresh,color:MindPalette.muted,size:18)),
      ]),
      if(_error!=null)
        Text(_error!,style:const TextStyle(color:MindPalette.red,fontSize:11)),
      FutureBuilder<List<UpstoxFnoLiveReceipt>>(
        future:_receipts,builder:(context,snapshot) {
          if(!snapshot.hasData) {
            return Text(snapshot.hasError
              ? 'Cannot verify order history. Never assume a prior order failed.'
              : 'Checking saved LIVE order receipts…',
              style:const TextStyle(color:MindPalette.yellow,fontSize:10));
          }
          final receipts=snapshot.data!;
          if(receipts.isEmpty) {
            return const Text('No recorded LIVE F&O orders for this room.',
              style:TextStyle(color:MindPalette.muted,fontSize:10));
          }
          return Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
            for(final r in receipts.take(5)) Padding(
              padding:const EdgeInsets.only(bottom:7),
              child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
                Row(children:[
                  Expanded(child:Text(
                    '${r.side} · ${r.instrumentKey} · ${r.quantity} units',
                    style:const TextStyle(color:MindPalette.text,fontSize:10.5),
                    overflow:TextOverflow.ellipsis)),
                  Text(r.state,
                    style:TextStyle(fontWeight:FontWeight.w800,fontSize:10,
                      color:r.state=='COMPLETE'?MindPalette.mint:
                        _unresolved(r.state)?MindPalette.yellow:MindPalette.muted)),
                ]),
                if(r.outcomeCode!=null) Text(r.outcomeCode!.replaceAll('_',' '),
                  style:const TextStyle(color:MindPalette.muted,fontSize:10)),
                if(_unresolved(r.state))
                  TextButton(
                    key:ValueKey('mind-fno-live-recheck-${r.id}'),
                    onPressed:_rechecking?null:()=>_recheck(r),
                    child:const Text('Check broker history · no resend'),
                  ),
              ])),
            const Text('Unknown or partial outcomes require broker reconciliation. '
              'There is no automatic real-money order retry.',
              style:TextStyle(color:MindPalette.yellow,fontSize:10)),
          ]);
        },
      ),
    ]);
}
