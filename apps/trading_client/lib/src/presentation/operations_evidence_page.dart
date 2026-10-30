import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/operations_evidence_repository.dart';

enum OperationsEvidenceSection { news, notifications, audit, settings }

class OperationsEvidencePage extends StatefulWidget {
  const OperationsEvidencePage({required this.repository, required this.section, super.key});

  final OperationsEvidenceRepository repository;
  final OperationsEvidenceSection section;

  @override
  State<OperationsEvidencePage> createState() => _OperationsEvidencePageState();
}

class _OperationsEvidencePageState extends State<OperationsEvidencePage> {
  OperationsEvidenceSnapshot? _snapshot;
  ApiFailure? _failure;
  bool _loading=false;
  String? _acknowledgingId;

  @override
  void initState(){ super.initState(); _refresh(); }

  Future<void> _refresh() async {
    if (_loading) return;
    setState(() { _loading=true; _failure=null; });
    try {
      final snapshot=await widget.repository.fetch();
      if (!mounted) return;
      setState(()=>_snapshot=snapshot);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(()=>_failure=failure);
    } finally {
      if (mounted) setState(()=>_loading=false);
    }
  }

  Future<void> _acknowledge(NotificationEvidence notification) async {
    if (_acknowledgingId != null || notification.acknowledged || notification.id.isEmpty) return;
    setState(() { _acknowledgingId=notification.id; _failure=null; });
    try {
      await widget.repository.acknowledge(notification.id);
      if (!mounted) return;
      await _refresh();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(()=>_failure=failure);
    } finally {
      if (mounted) setState(()=>_acknowledgingId=null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final snapshot=_snapshot;
    return Scaffold(
      appBar: AppBar(title: Text(_title(widget.section))),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh:_refresh,
          child: ListView(
            key: ValueKey('operations-evidence-${widget.section.name}'),
            padding: const EdgeInsets.fromLTRB(16,16,16,96),
            children:[
              Semantics(header:true, child:Text(_heading(widget.section), style:Theme.of(context).textTheme.headlineSmall)),
              const SizedBox(height:8),
              Text(_description(widget.section)),
              if (_failure != null) ...[
                const SizedBox(height:12),
                Card(
                  key:ValueKey(snapshot==null?'operations-evidence-error':'operations-evidence-stale'),
                  child:Padding(
                    padding:const EdgeInsets.all(16),
                    child:Text(snapshot==null
                      ? 'Evidence unavailable: ${_failure!.code}${_failure!.correlationId==null?'':' • correlation ${_failure!.correlationId}'}'
                      : 'Refresh/action failed (${_failure!.code}). Values below are last-known evidence.'),
                  ),
                ),
              ],
              const SizedBox(height:16),
              if (_loading && snapshot==null)
                const Center(key:ValueKey('operations-evidence-loading'),child:Padding(padding:EdgeInsets.all(32),child:CircularProgressIndicator()))
              else if (snapshot!=null)
                ..._section(snapshot)
              else if (_failure==null)
                const Card(child:Padding(padding:EdgeInsets.all(16),child:Text('No authoritative evidence is available.'))),
            ],
          ),
        ),
      ),
      floatingActionButton:FloatingActionButton.extended(
        key:const ValueKey('operations-evidence-refresh'),
        onPressed:_loading?null:_refresh,
        icon:const Icon(Icons.refresh),
        label:const Text('Refresh'),
      ),
    );
  }

  List<Widget> _section(OperationsEvidenceSnapshot snapshot) => switch(widget.section) {
    OperationsEvidenceSection.news => _news(snapshot.news),
    OperationsEvidenceSection.notifications => _notifications(snapshot.notifications),
    OperationsEvidenceSection.audit => _audit(snapshot.audit),
    OperationsEvidenceSection.settings => _settings(snapshot.executionMode),
  };

  List<Widget> _news(List<NewsEvidence> items) {
    if(items.isEmpty) return const [Card(key:ValueKey('news-empty'),child:Padding(padding:EdgeInsets.all(16),child:Text('No news evidence is reported by the backend.')))];
    return items.map((item)=>Card(
      key:ValueKey('news-${item.id}'),
      child:Padding(
        padding:const EdgeInsets.all(16),
        child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
          Text(item.title,style:Theme.of(context).textTheme.titleMedium),
          const SizedBox(height:6),
          Text('${item.source}${item.publisher==null?'':' • ${item.publisher}'} • published ${item.publishedAt?.toIso8601String() ?? 'UNKNOWN'}'),
          Text('Fetched ${item.fetchedAt?.toIso8601String() ?? 'UNKNOWN'} • ingestion ${item.ingestionStatus ?? 'UNKNOWN'}'),
          if(item.linkedSymbols.isNotEmpty) Text('Symbols: ${item.linkedSymbols.join(', ')}'),
          if(item.summary!=null) ...[
            const SizedBox(height:8),
            Text(item.summary!),
            Text('Summary provenance: ${item.summaryProvider ?? 'UNKNOWN'} / ${item.summaryModel ?? 'UNKNOWN'} • ${item.summaryGeneratedAt?.toIso8601String() ?? 'UNKNOWN'}'),
          ],
          if(item.sourceUrl!=null) Text('Source: ${item.sourceUrl}'),
        ]),
      ),
    )).toList(growable:false);
  }

  List<Widget> _notifications(List<NotificationEvidence> items) {
    if(items.isEmpty) return const [Card(key:ValueKey('notifications-empty'),child:Padding(padding:EdgeInsets.all(16),child:Text('No active notifications are reported by the backend.')))];
    return items.map((item)=>Card(
      key:ValueKey('notification-${item.id}'),
      child:Padding(
        padding:const EdgeInsets.all(16),
        child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
          Text('${item.severity} • ${item.title}',style:Theme.of(context).textTheme.titleMedium),
          const SizedBox(height:6),
          Text(item.message),
          Text('${item.type} • ${item.entityType ?? 'UNKNOWN'} ${item.entityId ?? ''} • ${item.createdAt?.toIso8601String() ?? 'UNKNOWN'}'),
          if(item.acknowledged)
            Text('Acknowledged ${item.acknowledgedAt?.toIso8601String() ?? 'UNKNOWN'} by ${item.acknowledgedBy ?? 'UNKNOWN'}')
          else ...[
            const SizedBox(height:10),
            FilledButton.tonal(
              key:ValueKey('acknowledge-${item.id}'),
              onPressed:_acknowledgingId==null?()=>_acknowledge(item):null,
              child:Text(_acknowledgingId==item.id?'Acknowledging…':'Acknowledge'),
            ),
          ],
        ]),
      ),
    )).toList(growable:false);
  }

  List<Widget> _audit(List<AuditEvidence> items) {
    if(items.isEmpty) return const [Card(key:ValueKey('audit-empty'),child:Padding(padding:EdgeInsets.all(16),child:Text('No audit events are reported for the backend default window.')))];
    return items.map((item)=>Card(
      key:ValueKey('audit-${item.id}'),
      child:ListTile(
        title:Text(item.eventType),
        subtitle:Text('${item.entityType} ${item.entityId} • ${item.createdAt?.toIso8601String() ?? 'UNKNOWN'}\nActor ${item.actor ?? 'UNKNOWN'} (${item.actorSource ?? 'UNKNOWN'}) • correlation ${item.correlationId ?? 'UNKNOWN'}\nDetails ${item.details ?? 'UNKNOWN'}'),
        isThreeLine:true,
      ),
    )).toList(growable:false);
  }

  List<Widget> _settings(ExecutionModeEvidence mode) => [
    Card(
      key:const ValueKey('execution-mode-evidence'),
      child:Padding(
        padding:const EdgeInsets.all(16),
        child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
          Text('Execution mode: ${mode.activeMode}',style:Theme.of(context).textTheme.titleLarge),
          const SizedBox(height:8),
          Text(mode.liveAvailable?'LIVE is reported available by the backend.':'LIVE is NOT available. No LIVE-switch control is exposed by this Flutter screen.'),
          Text('Checked: ${mode.checkedAt?.toIso8601String() ?? 'UNKNOWN'}'),
          if(mode.liveBlockers.isNotEmpty) ...[
            const SizedBox(height:12),
            Text('Backend LIVE blockers',style:Theme.of(context).textTheme.titleMedium),
            ...mode.liveBlockers.map((blocker)=>Text('• $blocker')),
          ],
        ]),
      ),
    ),
  ];

  static String _title(OperationsEvidenceSection section)=>switch(section){
    OperationsEvidenceSection.news=>'News evidence',
    OperationsEvidenceSection.notifications=>'Notifications',
    OperationsEvidenceSection.audit=>'Audit trail',
    OperationsEvidenceSection.settings=>'Trading settings',
  };
  static String _heading(OperationsEvidenceSection section)=>switch(section){
    OperationsEvidenceSection.news=>'Provenance-first market news',
    OperationsEvidenceSection.notifications=>'Operational notifications',
    OperationsEvidenceSection.audit=>'Security and trading audit evidence',
    OperationsEvidenceSection.settings=>'Backend execution-mode authority',
  };
  static String _description(OperationsEvidenceSection section)=>switch(section){
    OperationsEvidenceSection.news=>'Shows persisted source/fetch/summary provenance. Missing provenance remains UNKNOWN instead of being inferred.',
    OperationsEvidenceSection.notifications=>'Shows active backend notifications and explicit acknowledgement state.',
    OperationsEvidenceSection.audit=>'Shows server-redacted audit events. The Flutter client does not reconstruct or enrich audit details.',
    OperationsEvidenceSection.settings=>'Shows the backend mode and LIVE blockers. LIVE activation remains separately gated in Phase 15.',
  };
}
