import 'package:flutter/material.dart';
import '../data/mind_fno_auto_paper_repository.dart';
import 'mind_widgets.dart';

/// Real opt-in PAPER-only automated NIFTY F&O cycles with audited outcomes.
class MindFnoAutoPaperPanel extends StatefulWidget {
  const MindFnoAutoPaperPanel({required this.repository,super.key});
  final MindFnoAutoPaperRepository repository;
  @override
  State<MindFnoAutoPaperPanel> createState()=>_MindFnoAutoPaperPanelState();
}

class _MindFnoAutoPaperPanelState extends State<MindFnoAutoPaperPanel> {
  late final Future<List<FnoPaperRoom>> _rooms=widget.repository.rooms();
  String? _selected;
  FnoPaperAutomationState? _status;
  List<FnoPaperCycleEvent> _events=const [];
  bool _loading=false;
  String? _error;

  Future<void> _load(String id) async {
    setState((){_selected=id;_loading=true;_error=null;_status=null;_events=const [];});
    try {
      final state=await widget.repository.state(id);
      final events=await widget.repository.history(id);
      if(mounted && _selected==id) { setState((){
        _status=state;_events=events;_loading=false;
      }); }
    } catch(_) {
      if(mounted && _selected==id) { setState((){
        _loading=false;_error='Cannot verify PAPER automation status. No changes made.';
      }); }
    }
  }

  Future<void> _setEnabled(bool value) async {
    final id=_selected;
    if(id==null || _loading || _status==null) { return; }
    setState((){_loading=true;_error=null;});
    try {
      final actual=await widget.repository.setEnabled(id,value);
      if(mounted && id==_selected) {
        setState((){_status=actual;_loading=false;});
        await _load(id);
      }
    } catch(_) {
      if(mounted) { setState((){_loading=false;_error=
        'Control outcome not confirmed. Refresh status before retrying.';}); }
    }
  }

  @override
  Widget build(BuildContext context)=>MindPanel(
    key:const ValueKey('mind-fno-auto-paper-panel'),
    child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
      const Row(children:[
        Icon(Icons.autorenew,color:MindPalette.mint,size:19),
        SizedBox(width:8),
        Expanded(child:Text('Mintick Mind · F&O PAPER',
          style:TextStyle(color:MindPalette.text,fontSize:13,fontWeight:FontWeight.w800))),
        MindTag('VIRTUAL ONLY',color:MindPalette.yellow),
      ]),
      const SizedBox(height:5),
      const Text('Automated one-lot NIFTY CE/PE research, virtual orders, exits and verified P&L. '
        'Runs server-side only after you enable a funded F&O PAPER room. '
        'No LIVE broker orders.',
        style:TextStyle(color:MindPalette.muted,fontSize:11)),
      const SizedBox(height:10),
      FutureBuilder<List<FnoPaperRoom>>(
        future:_rooms,builder:(context,snapshot) {
          if(!snapshot.hasData) {
            return Text(snapshot.hasError?'F&O PAPER rooms unavailable':'Loading PAPER rooms…',
              style:const TextStyle(color:MindPalette.muted,fontSize:11));
          }
          final rooms=snapshot.data!;
          if(rooms.isEmpty) { return const Text(
            'Create and fund a dedicated F&O PAPER Intraday/Swing room first.',
            style:TextStyle(color:MindPalette.yellow,fontSize:11)); }
          return DropdownButtonFormField<String>(
            key:const ValueKey('mind-fno-auto-room'),
            initialValue:_selected,isExpanded:true,
            dropdownColor:MindPalette.panel,
            decoration:const InputDecoration(labelText:'F&O PAPER room',isDense:true),
            items:[for(final r in rooms)DropdownMenuItem(
              value:r.id,child:Text(r.name,overflow:TextOverflow.ellipsis))],
            onChanged:(id){if(id!=null)_load(id);},
          );
        },
      ),
      if(_selected!=null) ...[
        const SizedBox(height:8),
        Row(children:[
          Expanded(child:Text(
            _status==null?'Status unavailable':
            _status!.enabled?'AUTOMATION ENABLED':'AUTOMATION STOPPED',
            key:const ValueKey('mind-fno-auto-state'),
            style:TextStyle(color:_status?.enabled==true?
                MindPalette.mint:MindPalette.yellow,fontWeight:FontWeight.w800,fontSize:11))),
          IconButton(
            key:const ValueKey('mind-fno-auto-refresh'),
            onPressed:_loading?null:()=>_load(_selected!),
            icon:const Icon(Icons.refresh,color:MindPalette.muted)),
        ]),
        FilledButton(
          key:const ValueKey('mind-fno-auto-toggle'),
          onPressed:_loading||_status==null?null:()=>_setEnabled(!_status!.enabled),
          style:FilledButton.styleFrom(
            backgroundColor:_status?.enabled==true?MindPalette.red:MindPalette.mint,
            foregroundColor:Colors.black),
          child:Text(_loading?'Checking…':_status?.enabled==true?
              'Stop PAPER automation':'Enable PAPER automation'),
        ),
        if(_status?.lastStatus!=null)
          Text('Last: ${_status!.lastStatus!} · ${_status!.lastReason??''}',
            style:const TextStyle(color:MindPalette.muted,fontSize:10)),
        if(_error!=null)Text(_error!,style:const TextStyle(color:MindPalette.red,fontSize:11)),
        const SizedBox(height:8),
        const Text('RECENT VERIFIED SERVER CYCLES',style:
            TextStyle(color:MindPalette.muted,fontSize:10,fontWeight:FontWeight.w800)),
        if(_events.isEmpty)const Text('No recorded F&O PAPER cycles yet.',
          style:TextStyle(color:MindPalette.muted,fontSize:11)),
        for(final e in _events.take(8))Padding(
          padding:const EdgeInsets.only(top:5),
          child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
            Text('${e.at.toLocal().toIso8601String().substring(0,16)} · ${e.status} · ${e.action}',
              style:const TextStyle(color:MindPalette.text,fontSize:10.5)),
            Text('${e.reason}${e.fillId==null?'':' · Fill ${e.fillId!}'}',
              maxLines:2,overflow:TextOverflow.ellipsis,
              style:const TextStyle(color:MindPalette.muted,fontSize:10)),
            if(e.realizedPnlPaise!=null)Text(
              'Virtual realized P&L: ₹${(e.realizedPnlPaise!/100).toStringAsFixed(2)}',
              style:const TextStyle(color:MindPalette.mint,fontSize:10)),
          ])),
      ],
    ]),
  );
}
