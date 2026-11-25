import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/backtest_repository.dart';

class BacktestsPage extends StatefulWidget {
  const BacktestsPage({required this.repository, super.key});
  final BacktestRepository repository;
  @override State<BacktestsPage> createState() => _BacktestsPageState();
}

class _BacktestsPageState extends State<BacktestsPage> {
  final _instrumentId = TextEditingController();
  final _initialCapital = TextEditingController(text: '100000');
  final _fromDate = TextEditingController();
  final _toDate = TextEditingController();
  String _interval = 'ONE_DAY';
  List<BacktestRunSummary> _runs = const [];
  String? _error; bool _loading = false; bool _running = false;
  @override void initState() { super.initState(); final today=DateTime.now(); _toDate.text=_date(today); _fromDate.text=_date(today.subtract(const Duration(days:180))); _refresh(); }
  @override void dispose() { _instrumentId.dispose(); _initialCapital.dispose(); _fromDate.dispose(); _toDate.dispose(); super.dispose(); }
  String _date(DateTime value) => '${value.year.toString().padLeft(4,'0')}-${value.month.toString().padLeft(2,'0')}-${value.day.toString().padLeft(2,'0')}';
  Future<void> _refresh() async { setState(() { _loading=true; _error=null; }); try { final runs=await widget.repository.recent(limit:20); if(!mounted)return; setState(()=>_runs=runs); } on ApiFailure catch(failure) { if(!mounted)return; setState(()=>_error=failure.code); } catch(_) { if(!mounted)return; setState(()=>_error='BACKTEST_HISTORY_READ_FAILED'); } finally { if(mounted)setState(()=>_loading=false); } }
  Future<void> _run() async { final instrumentId=int.tryParse(_instrumentId.text.trim()); final initialCapital=num.tryParse(_initialCapital.text.trim()); final fromDate=DateTime.tryParse(_fromDate.text.trim()); final toDate=DateTime.tryParse(_toDate.text.trim()); if(instrumentId==null||instrumentId<1||initialCapital==null||fromDate==null||toDate==null||toDate.isBefore(fromDate)){setState(()=>_error='INVALID_REQUEST');return;} setState((){_running=true;_error=null;}); try { await widget.repository.run(instrumentId:instrumentId,interval:_interval,fromDate:fromDate,toDate:toDate,initialCapital:initialCapital); await _refresh(); } on ApiFailure catch(failure){if(!mounted)return;setState(()=>_error=failure.code);} catch(_){if(!mounted)return;setState(()=>_error='BACKTEST_RUN_FAILED');} finally {if(mounted)setState(()=>_running=false);} }
  @override Widget build(BuildContext context) { final theme=Theme.of(context); return Scaffold(appBar:AppBar(title:const Text('Backtests')),body:SafeArea(child:ListView(padding:const EdgeInsets.fromLTRB(20,20,20,40),children:[
    Semantics(header:true,child:Text('PAPER backtest workspace',style:theme.textTheme.headlineSmall?.copyWith(fontWeight:FontWeight.w800))), const SizedBox(height:6), const Text('Runs historical simulation only. This screen does not submit or route broker orders.'),
    if(_error!=null)...[const SizedBox(height:16),Card(key:const ValueKey('backtests-error'),child:Padding(padding:const EdgeInsets.all(16),child:Text('Backtest evidence unavailable: $_error')))], const SizedBox(height:18),
    Card(child:Padding(padding:const EdgeInsets.all(16),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Text('Run historical simulation',style:theme.textTheme.titleLarge?.copyWith(fontWeight:FontWeight.w700)),const SizedBox(height:14),Wrap(spacing:12,runSpacing:12,children:[
      SizedBox(width:180,child:TextField(key:const ValueKey('backtest-instrument-id'),controller:_instrumentId,keyboardType:TextInputType.number,decoration:const InputDecoration(labelText:'Instrument ID'))),
      SizedBox(width:180,child:DropdownButtonFormField<String>(key:const ValueKey('backtest-interval'),isExpanded:true,initialValue:_interval,decoration:const InputDecoration(labelText:'Interval'),items:const [DropdownMenuItem(value:'ONE_DAY',child:Text('1 day')),DropdownMenuItem(value:'ONE_HOUR',child:Text('1 hour')),DropdownMenuItem(value:'FIFTEEN_MINUTE',child:Text('15 minutes')),DropdownMenuItem(value:'FIVE_MINUTE',child:Text('5 minutes'))],onChanged:(value){if(value!=null)setState(()=>_interval=value);})),
      SizedBox(width:180,child:TextField(key:const ValueKey('backtest-from-date'),controller:_fromDate,decoration:const InputDecoration(labelText:'From (YYYY-MM-DD)'))), SizedBox(width:180,child:TextField(key:const ValueKey('backtest-to-date'),controller:_toDate,decoration:const InputDecoration(labelText:'To (YYYY-MM-DD)'))), SizedBox(width:190,child:TextField(key:const ValueKey('backtest-capital'),controller:_initialCapital,keyboardType:const TextInputType.numberWithOptions(decimal:true),decoration:const InputDecoration(labelText:'Initial capital')))
    ]),const SizedBox(height:14),FilledButton.icon(key:const ValueKey('backtest-run'),onPressed:_running?null:_run,icon:_running?const SizedBox(width:16,height:16,child:CircularProgressIndicator(strokeWidth:2)):const Icon(Icons.play_arrow_rounded),label:Text(_running?'Running':'Run PAPER backtest'))]))), const SizedBox(height:18),
    Row(children:[Expanded(child:Semantics(header:true,child:Text('Recent runs',style:theme.textTheme.titleLarge?.copyWith(fontWeight:FontWeight.w700)))),IconButton(key:const ValueKey('backtest-refresh'),tooltip:'Refresh backtests',onPressed:_loading?null:_refresh,icon:const Icon(Icons.refresh))]),const SizedBox(height:10),
    if(_loading&&_runs.isEmpty) const Center(child:CircularProgressIndicator()) else if(_runs.isEmpty) const Card(key:ValueKey('backtests-empty'),child:Padding(padding:EdgeInsets.all(24),child:Text('No PAPER backtest runs are available yet.'))) else ..._runs.map((run)=>Card(key:ValueKey('backtest-${run.id}'),child:ListTile(title:Text(run.tradingSymbol.isEmpty?'Instrument ${run.instrumentId}':run.tradingSymbol),subtitle:Text('${run.interval} • ${run.fromDate?.toIso8601String().split('T').first ?? 'UNKNOWN'} → ${run.toDate?.toIso8601String().split('T').first ?? 'UNKNOWN'}\n${run.tradeCount} trades • Win ${run.winRatePercent}% • Drawdown ${run.maxDrawdownPercent}%'),isThreeLine:true,trailing:Semantics(label:'Net P and L ${run.netPnl}',child:Text('₹${run.netPnl.toStringAsFixed(2)}',style:const TextStyle(fontWeight:FontWeight.w700))))))
  ]))); }
}
