part of 'mind_workspace_page.dart';

/// Deliberately local, clearly labelled preview records. The News Analyzer UI
/// does not infer a strategy decision or create any trading side effect.
class _MindNewsPreview {
  const _MindNewsPreview({
    required this.id,
    required this.source,
    required this.time,
    required this.title,
    required this.summary,
    required this.symbols,
    required this.impact,
    required this.confidence,
    required this.status,
    required this.category,
    this.article,
  });

  final String id;
  final String source;
  final String time;
  final String title;
  final String summary;
  final List<String> symbols;
  final String impact;
  final int confidence;
  final String status;
  final String category;
  final MarketNewsArticle? article;

  factory _MindNewsPreview.fromArticle(MarketNewsArticle article) {
    final impact = article.stockImpact;
    final sentiment = switch (impact?.sentiment) {
      'POSITIVE' => 'Positive',
      'NEGATIVE' => 'Negative',
      'NEUTRAL' => 'Neutral',
      'MIXED' => 'Mixed',
      _ => 'Unavailable',
    };
    final status = switch (article.analysisStatus) {
      'ANALYZED' => 'Analyzed',
      'FAILED' => 'Failed',
      _ => 'Not analyzed',
    };
    return _MindNewsPreview(
      id: article.id,
      source: article.source,
      time: _mindNewsTimestamp(article.publishedAt),
      title: article.title,
      summary: impact?.evidence ?? article.excerpt ??
          'No publisher summary or completed analysis is available yet.',
      symbols: impact?.impactedSymbols ?? const <String>[],
      impact: sentiment,
      confidence: ((impact?.confidence ?? 0) * 100).round(),
      status: status,
      category: impact?.category ?? 'Not classified',
      article: article,
    );
  }
}

String _mindNewsTimestamp(DateTime input) {
  final ist = input.toUtc().add(const Duration(hours: 5, minutes: 30));
  String two(int value) => value.toString().padLeft(2, '0');
  return '${two(ist.day)}/${two(ist.month)} ${two(ist.hour)}:${two(ist.minute)} IST';
}

const _mindNewsPreviewItems = <_MindNewsPreview>[
  _MindNewsPreview(
    id: 'N-2841', source: 'Economic Times', time: '18:42 IST',
    title: 'RBI holds repo rate; inflation risk remains in focus',
    summary: 'The MPC kept the repo rate unchanged, noting inflation risks and resilient domestic demand. Any bank impact is indirect and may vary by issuer.',
    symbols: ['HDFCBANK', 'SBIN'], impact: 'Mixed', confidence: 78,
    status: 'Analyzed', category: 'Monetary policy',
  ),
  _MindNewsPreview(
    id: 'N-2840', source: 'Moneycontrol', time: '18:39 IST',
    title: 'IT stocks rise as deal wins improve sector outlook',
    summary: 'TCS and Infosys moved higher as brokerages cited deal momentum and demand. The article does not establish a company-specific forecast.',
    symbols: ['TCS', 'INFY'], impact: 'Positive', confidence: 84,
    status: 'Analyzed', category: 'Corporate / sector',
  ),
  _MindNewsPreview(
    id: 'N-2839', source: 'SEBI', time: '18:31 IST',
    title: 'Consultation paper proposes derivatives risk disclosures',
    summary: 'Potential market-wide relevance. No issuer-level stock effect has been established from this consultation notice.',
    symbols: ['NIFTY', 'BANKNIFTY'], impact: 'Uncertain', confidence: 61,
    status: 'Review', category: 'Regulatory',
  ),
  _MindNewsPreview(
    id: 'N-2838', source: 'Economic Times', time: '18:18 IST',
    title: 'Crude prices retreat as supply concerns ease',
    summary: 'Global crude benchmarks eased. Refiners may be indirectly exposed; direction and materiality remain conditional.',
    symbols: ['IOC', 'BPCL', 'ONGC'], impact: 'Mixed', confidence: 69,
    status: 'Analyzed', category: 'Commodities',
  ),
];

const _newsTabs = <String>['Feed', 'Sources', 'Activity'];

extension _MindNewsScreens on _MindWorkspaceState {
  bool get _newsDemoMode => widget.marketNewsRepository == null;

  List<_MindNewsPreview> get _currentMindNewsItems => _newsDemoMode
      ? _mindNewsPreviewItems
      : _newsArticles.map(_MindNewsPreview.fromArticle).toList(growable: false);

  Widget newsAnalyzerScreen() {
    final selected = _currentMindNewsItems.where(
      (item) => item.id == _selectedNewsId,
    ).firstOrNull ?? _currentMindNewsItems.firstOrNull;
    return _surface(
      _newsDetailOpen ? 'Article analysis' : 'News Analyzer',
      _newsDetailOpen ? 'Source, stored analysis and evidence' : 'News intelligence',
      [
        if (_newsDetailOpen && selected != null)
          _newsArticleDetail(selected)
        else ...[
        Row(children:[
          Expanded(child: _newsConnectionStrip()),
          if (!_newsDemoMode) IconButton(
            key: const ValueKey('mind-news-refresh'),
            tooltip: 'Refresh customer news',
            onPressed: _newsLoading || _newsLoadingMore
                ? null : () => _loadMindNews(reset: true),
            icon: const Icon(Icons.refresh_rounded, color: MindPalette.mint, size:20),
          ),
        ]),
        const SizedBox(height: 7),
        _newsSourceSummary(),
        const SizedBox(height: 7),
        _newsCompactMetrics(),
        const SizedBox(height: 8),
        Row(children:[for(final tab in _newsTabs) Expanded(child: InkWell(
          onTap:()=>_updateMind(()=>_newsTab=tab),
          child:Container(padding:const EdgeInsets.symmetric(vertical:9),
            decoration:BoxDecoration(border:Border(bottom:BorderSide(
              color:_newsTab==tab?MindPalette.mint:MindPalette.border,
              width:_newsTab==tab?2:1))),
            child:Text(tab,textAlign:TextAlign.center,style:TextStyle(
              color:_newsTab==tab?MindPalette.mint:MindPalette.muted,
              fontSize:12,fontWeight:FontWeight.w700)))))]),
        const SizedBox(height:8),
        if (_newsTab == 'Feed') _newsFeed()
        else if (_newsTab == 'Sources') _newsCoverage()
        else _newsActivity(),
        ],
      ],
    );
  }

  Widget _newsConnectionStrip() => Container(
    padding:const EdgeInsets.symmetric(horizontal:10,vertical:9),
    decoration:BoxDecoration(color:MindPalette.panel,borderRadius:BorderRadius.circular(11),border:Border.all(color:MindPalette.border)),
    child:Row(children:[Icon(Icons.circle,size:8,color:_newsError==null?MindPalette.mint:MindPalette.yellow),const SizedBox(width:8),
      Expanded(child:Text(_newsDemoMode?'Preview mode · backend not connected':_newsError==null?'Customer news feed · read only':'News service unavailable',style:const TextStyle(color:MindPalette.text,fontSize:11,fontWeight:FontWeight.w700))),
      Text(_newsLoading?'Updating':_newsLoaded?'${_newsArticles.length} loaded':'Waiting',style:const TextStyle(color:MindPalette.muted,fontSize:9))]),
  );

  Widget _newsSourceSummary() {
    const names=['NSE','Moneycontrol','Economic Times','SEBI'];
    String key(String value) => value.toLowerCase().replaceAll(RegExp(r'[^a-z]'), '');
    MarketNewsSource? sourceFor(String name) {
      final wanted = key(name);
      for (final source in _newsSources) {
        final candidate = key('${source.sourceId} ${source.publisher}');
        if (candidate.contains(wanted) || wanted.contains(key(source.sourceId))) return source;
      }
      return null;
    }
    Widget sourceCard(String name) {
      final source = sourceFor(name);
      final ok = source?.status.toUpperCase() == 'SUCCESS';
      return Expanded(child: Container(
        margin: EdgeInsets.only(right: name == names.last ? 0 : 6),
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
        decoration: BoxDecoration(color: MindPalette.panel,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: MindPalette.border)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(name, maxLines: 1, overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: MindPalette.muted, fontSize: 9,
              fontWeight: FontWeight.w600)),
          Text(source == null ? '—' : '${source.fetchedItems}',
            style: const TextStyle(color: MindPalette.text, fontSize: 16,
              fontWeight: FontWeight.w800)),
          Text(source == null ? 'health n/a' : ok ? 'fetched' : source.status.toLowerCase(),
            maxLines: 1, overflow: TextOverflow.ellipsis,
            style: TextStyle(color: source == null ? MindPalette.muted :
              ok ? MindPalette.mint : MindPalette.yellow, fontSize: 8)),
        ]),
      ));
    }
    return Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
      Row(children:[const Expanded(child:Text('LATEST SOURCE CHECK',style:TextStyle(color:MindPalette.muted,fontSize:10,fontWeight:FontWeight.w700))),
        Text(_newsSourceHealthError ?? (_newsSources.isEmpty?'Checking sources':'Backend health'),style:const TextStyle(color:MindPalette.muted,fontSize:9))]),
      const SizedBox(height:5),
      Row(children: [for (final name in names) sourceCard(name)]),
    ]);
  }

  Widget _newsCompactMetrics(){
    final analyzed=_newsArticles.where((a)=>a.analysisStatus=='ANALYZED' && a.stockImpact?.provider?.toUpperCase()=='GEMINI').length;
    final symbols=_newsArticles.expand((a)=>a.stockImpact?.impactedSymbols??const <String>[]).toSet().length;
    final cells=[('$_newsTotalItems','articles'),('$symbols','stocks linked · page'),('$analyzed','Gemini · page')];
    return Container(padding:const EdgeInsets.symmetric(vertical:8),decoration:BoxDecoration(color:MindPalette.panel,borderRadius:BorderRadius.circular(11),border:Border.all(color:MindPalette.border)),
      child:Row(children:[for(var i=0;i<cells.length;i++)Expanded(child:Container(padding:const EdgeInsets.symmetric(horizontal:10),decoration:BoxDecoration(border:i==0?null:const Border(left:BorderSide(color:MindPalette.border))),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Text(_newsLoading&&!_newsLoaded?'—':cells[i].$1,style:const TextStyle(color:MindPalette.text,fontSize:15,fontWeight:FontWeight.w800)),Text(cells[i].$2,style:const TextStyle(color:MindPalette.muted,fontSize:9))])))]));
  }

  Widget _newsUnavailable(String message) => MindPanel(child: Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Icon(Icons.cloud_off_outlined, color: MindPalette.yellow, size: 18),
      const SizedBox(width: 9),
      Expanded(child: Text(message, style: const TextStyle(
          color: MindPalette.yellow, fontSize: 11, height: 1.4))),
      TextButton(onPressed: () => _loadMindNews(reset: true),
          child: const Text('Retry')),
    ],
  ));

  Widget _newsPipelineRow(String number, String label, String status) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(children: [
      Container(
        width: 22, height: 22,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: Border.all(color: MindPalette.mint.withValues(alpha: .65)),
        ),
        child: Text(number, style: const TextStyle(
          color: MindPalette.mint, fontSize: 10, fontWeight: FontWeight.w700)),
      ),
      const SizedBox(width: 9),
      Expanded(child: Text(label, style: const TextStyle(
        color: MindPalette.text, fontSize: 12, fontWeight: FontWeight.w600))),
      Text(status, textAlign: TextAlign.right, style: const TextStyle(
        color: MindPalette.muted, fontSize: 10)),
    ]),
  );

  Widget _newsFeed() {
    final query = _newsQuery.toLowerCase().trim();
    final filtered = _currentMindNewsItems.where((item) {
      final matchesFilter = _newsFilter == 'All news' ||
          (_newsFilter == 'Stock-linked' && item.symbols.isNotEmpty) ||
          (_newsFilter == 'Analyzed' && item.status == 'Analyzed') ||
          (_newsFilter == 'Pending / failed' && item.status != 'Analyzed');
      final matchesSymbol = _newsSymbolFilter == null ||
          item.symbols.contains(_newsSymbolFilter);
      final matchesQuery = query.isEmpty ||
          '${item.title} ${item.summary} ${item.source} ${item.symbols.join(' ')}'
              .toLowerCase().contains(query);
      return matchesFilter && matchesSymbol && matchesQuery;
    }).toList();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      TextField(
        key: const ValueKey('mind-news-search'),
        controller: _mindNewsSearchController,
        onChanged: (value) => _updateMind(() => _newsQuery = value),
        onSubmitted: _applyMindNewsSymbolFilter,
        style: const TextStyle(color: MindPalette.text, fontSize: 12),
        decoration: InputDecoration(
          hintText: _newsDemoMode
              ? 'Search demo headlines or symbols'
              : 'Search loaded news · submit a symbol to filter server-side',
          hintStyle: const TextStyle(color: MindPalette.muted, fontSize: 12),
          prefixIcon: const Icon(Icons.search, color: MindPalette.muted),
          suffixIcon: IconButton(
            key: const ValueKey('mind-news-symbol-filter'),
            tooltip: 'Apply server-side stock symbol filter',
            onPressed: () => _applyMindNewsSymbolFilter(_newsQuery),
            icon: const Icon(Icons.arrow_forward, size: 17),
          ),
          filled: true, fillColor: MindPalette.panel,
          contentPadding: const EdgeInsets.symmetric(vertical: 10),
          enabledBorder: OutlineInputBorder(
            borderSide: const BorderSide(color: MindPalette.border),
            borderRadius: BorderRadius.circular(12)),
          focusedBorder: OutlineInputBorder(
            borderSide: const BorderSide(color: MindPalette.mint),
            borderRadius: BorderRadius.circular(12)),
        ),
      ),
      const SizedBox(height: 8),
      _chips(['All news', 'Stock-linked', 'Analyzed', 'Pending / failed'], _newsFilter,
          (value) => _updateMind(() => _newsFilter = value)),
      if (_newsSymbolFilter != null)
        Align(alignment: Alignment.centerLeft, child: InputChip(
          label: Text('Symbol: $_newsSymbolFilter'),
          onDeleted: () {
            _updateMind(() {
              _newsSymbolFilter = null;
              _newsQuery = '';
              _mindNewsSearchController.clear();
            });
            _loadMindNews(reset: true);
          },
          deleteIconColor: MindPalette.muted,
          backgroundColor: MindPalette.panel,
          labelStyle: const TextStyle(color: MindPalette.text, fontSize: 11),
        )),
      if (!_newsDemoMode && _newsLoading && _newsArticles.isEmpty)
        const Center(child: Padding(padding: EdgeInsets.all(18),
          child: CircularProgressIndicator(strokeWidth: 2))),
      if (!_newsDemoMode && _newsError != null) _newsUnavailable(_newsError!),
      _newsCardList(title: 'Newest first', items: filtered),
      if (filtered.isEmpty && (_newsDemoMode || (!_newsLoading && _newsError == null)))
        MindEmptyState(_newsDemoMode
            ? 'No demo headlines match this search or filter.'
            : 'No approved articles match this symbol or filter.'),
      if (!_newsDemoMode && !_newsLoading && _newsHasNext)
        OutlinedButton.icon(
          onPressed: _newsLoadingMore ? null : () => _loadMindNews(reset: false),
          icon: _newsLoadingMore
              ? const SizedBox(width: 14, height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.expand_more),
          label: Text(_newsLoadingMore ? 'Loading…' : 'Load more articles'),
          style: OutlinedButton.styleFrom(foregroundColor: MindPalette.mint),
        ),
      Padding(
        padding: EdgeInsets.only(top: 4),
        child: Text(
          _newsDemoMode
              ? 'Illustrative sample records only; no backend request is made in this mode.'
              : 'Customer API results only. Headlines are permission-filtered by the server; search text filters loaded items, while a valid symbol is sent to the server.',
          style: TextStyle(color: MindPalette.muted, fontSize: 10),
        ),
      ),
    ]);
  }

  void _applyMindNewsSymbolFilter(String raw) {
    final value = raw.trim().toUpperCase();
    if (value.isNotEmpty &&
        !RegExp(r'^[A-Z0-9&.-]{1,20}$').hasMatch(value)) {
      _notice('Enter a stock symbol, or use the text to search loaded articles.');
      return;
    }
    if (value == _newsSymbolFilter) return;
    _updateMind(() {
      _newsSymbolFilter = value.isEmpty ? null : value;
      _newsDetailOpen = false;
    });
    if (!_newsDemoMode) _loadMindNews(reset: true);
  }

  Widget _newsCardList({
    required String title,
    required List<_MindNewsPreview> items,
    bool showAll = false,
  }) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
    Padding(padding:const EdgeInsets.symmetric(vertical:7),child:Row(children:[
      const Icon(Icons.newspaper_outlined,color:MindPalette.mint,size:17),
      const SizedBox(width:7),Expanded(child:Text(title,style:const TextStyle(color:MindPalette.text,fontSize:15,fontWeight:FontWeight.w800))),
      if(showAll) TextButton(onPressed:()=>_updateMind(()=>_newsTab='Feed'),child:const Text('View all')),
    ])),
    for (final item in items)
      InkWell(
          key: ValueKey('mind-news-card-${item.id}'),
          onTap: () => _updateMind(() {
            _selectedNewsId = item.id;
            _newsTab = 'Feed';
            _newsDetailOpen = true;
          }),
          child: Container(padding:const EdgeInsets.symmetric(vertical:9),decoration:const BoxDecoration(border:Border(top:BorderSide(color:MindPalette.border))),child:Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(children: [
              const Icon(Icons.circle,size:7,color:MindPalette.mint),const SizedBox(width:6),
              Expanded(child: Text(item.source.toUpperCase(),style:const TextStyle(color:MindPalette.muted,fontSize:9,fontWeight:FontWeight.w700))),
              Text(item.time,style:const TextStyle(color:MindPalette.muted,fontSize:9)),
            ]),
            const SizedBox(height:5),
            Text(item.title, maxLines: 2, overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: MindPalette.text,
                    fontSize:12, fontWeight: FontWeight.w700)),
            if (item.article?.stockImpact != null) ...[
              const SizedBox(height: 6),
              _newsOutcomeSummary(item.article!.stockImpact!, compact: true),
            ],
            if(item.symbols.isNotEmpty||item.article?.stockImpact!=null)...[
              const SizedBox(height:6),
              Row(children:[
                for(final symbol in item.symbols.take(3)) Padding(padding:const EdgeInsets.only(right:5),child:MindTag(symbol,color:MindPalette.muted)),
                const Spacer(),
                Text(item.status=='Analyzed'?'ANALYZED':'PENDING',style:TextStyle(color:item.status=='Analyzed'?MindPalette.mint:MindPalette.yellow,fontSize:9,fontWeight:FontWeight.w700)),
              ]),
            ],
          ])),
      ),
  ]);

  /// Makes the stored model result visible without presenting it as a forecast.
  Widget _newsOutcomeSummary(MarketNewsImpact impact, {bool compact = false}) {
    final tone = switch (impact.sentiment) {
      'POSITIVE' => 'Positive tone',
      'NEGATIVE' => 'Negative tone',
      'NEUTRAL' => 'Neutral tone',
      'MIXED' => 'Mixed tone',
      _ => 'Unknown tone',
    };
    final uncertain = impact.ambiguity != false ||
        impact.contradiction != false ||
        impact.directionScore == null ||
        impact.materiality == null ||
        impact.confidence == null ||
        impact.confidence! < 0.5;
    final color = uncertain ? MindPalette.yellow : MindPalette.mint;
    final direction = impact.directionScore == null
        ? 'direction not scored'
        : 'direction ${impact.directionScore! >= 0 ? '+' : ''}${impact.directionScore!.toStringAsFixed(2)}';
    final confidence = impact.confidence == null
        ? 'model confidence unavailable'
        : 'model confidence ${(impact.confidence! * 100).round()}%';
    final materiality = impact.materiality == null
        ? 'materiality unavailable'
        : 'materiality ${(impact.materiality! * 100).round()}%';
    final horizon = (impact.horizon == null || impact.horizon == 'UNKNOWN')
        ? 'horizon unknown'
        : impact.horizon!.replaceAll('_', ' ').toLowerCase();
    final flag = impact.contradiction == true
        ? 'Conflicting evidence · excluded from directional context'
        : impact.ambiguity == true
            ? 'Ambiguous evidence · excluded from directional context'
            : impact.contradiction == null || impact.ambiguity == null
                ? 'Uncertainty flags unavailable · withheld from directional context'
                : impact.confidence == null || impact.confidence! < 0.5
                    ? 'Below 50% policy confidence · excluded from directional context'
                    : impact.directionScore == null || impact.materiality == null
                        ? 'Required direction/materiality missing · excluded from directional context'
                        : null;

    return Container(
      width: double.infinity,
      padding: EdgeInsets.all(compact ? 7 : 10),
      decoration: BoxDecoration(
        color: MindPalette.panel,
        borderRadius: BorderRadius.circular(9),
        border: Border.all(color: MindPalette.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(compact ? 'ARTICLE ANALYSIS' : 'ANALYSIS OUTCOME',
            style: TextStyle(color: color, fontSize: 9,
                fontWeight: FontWeight.w800, letterSpacing: .35)),
          const SizedBox(height: 3),
          Text('$tone · $direction',
            style: TextStyle(color: color, fontSize: compact ? 10 : 12,
                fontWeight: FontWeight.w700)),
          const SizedBox(height: 2),
          Text('$confidence · $materiality · $horizon',
            style: const TextStyle(color: MindPalette.muted,
                fontSize: 9, height: 1.35)),
          if (flag != null) ...[
            const SizedBox(height: 3),
            Text(flag, style: const TextStyle(color: MindPalette.yellow,
                fontSize: 9, fontWeight: FontWeight.w700)),
          ],
          if (!compact) ...[
            const SizedBox(height: 7),
            Text(
              'This is an article-level evidence classification, not an expected price move or buy/sell call. Eligible evidence may be attached to a research candidate for Mind history when the news-context feature is enabled. It does not currently change strategy scoring, replay/backtest results, risk, sizing, or orders.',
              style: const TextStyle(color: MindPalette.muted,
                  fontSize: 10, height: 1.4),
            ),
          ],
        ],
      ),
    );
  }

  Widget _newsArticleDetail(_MindNewsPreview item) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Align(alignment: Alignment.centerLeft, child: TextButton.icon(
        onPressed: () => _updateMind(() => _newsDetailOpen = false),
        icon: const Icon(Icons.arrow_back, size: 16),
        label: const Text('Back to feed'),
        style: TextButton.styleFrom(foregroundColor: MindPalette.mint),
      )),
      MindPanel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(children: [
            Expanded(child: Text('${item.source} · ${item.id}',
              style: const TextStyle(color: MindPalette.muted, fontSize: 10))),
            MindTag(item.status.toUpperCase(), color: item.status == 'Analyzed'
                ? MindPalette.mint : MindPalette.yellow),
          ]),
          const SizedBox(height: 9),
          Text(item.title, style: const TextStyle(color: MindPalette.text,
              fontSize: 18, height: 1.25, fontWeight: FontWeight.w800)),
          const SizedBox(height: 7),
          Text(item.article == null
              ? 'Published ${item.time} · illustrative sample'
              : 'Published ${_mindNewsTimestamp(item.article!.publishedAt)} · received ${_mindNewsTimestamp(item.article!.ingestedAt)}',
              style: const TextStyle(color: MindPalette.muted, fontSize: 10)),
          const SizedBox(height: 13),
          const MindSectionTitle(title: 'MintTick Mind analysis',
              icon: Icons.psychology_outlined),
          if (item.article?.stockImpact != null) ...[
            const SizedBox(height: 7),
            _newsOutcomeSummary(item.article!.stockImpact!),
          ],
          _newsDetailRow('Category', item.category),
          _newsDetailRow('Analysis provider', item.article?.stockImpact?.provider ?? 'Unavailable'),
          if (item.article?.stockImpact?.model != null)
            _newsDetailRow('Model', item.article!.stockImpact!.model!),
          _newsDetailRow('Tone', item.impact),
          _newsDetailRow('Confidence', item.article?.stockImpact?.confidence == null
              ? 'Unavailable' : '${item.confidence}%'),
          _newsDetailRow('Materiality', item.article?.stockImpact?.materiality == null
              ? 'Unavailable' : '${(item.article!.stockImpact!.materiality! * 100).round()}%'),
          _newsDetailRow('Direction score', item.article?.stockImpact?.directionScore == null
              ? 'Unavailable' : item.article!.stockImpact!.directionScore!.toStringAsFixed(2)),
          _newsDetailRow('Time horizon', item.article?.stockImpact?.horizon ?? 'Unavailable'),
          _newsDetailRow('Ambiguous / conflicting', item.article?.stockImpact == null
              ? 'Unavailable' : '${item.article!.stockImpact!.ambiguity == true ? 'Ambiguous' : 'No ambiguity flagged'} · ${item.article!.stockImpact!.contradiction == true ? 'Conflict flagged' : 'No conflict flagged'}'),
          _newsDetailRow('Symbols mentioned', '${item.symbols.length}'),
          const SizedBox(height: 9),
          Text(item.article?.excerpt ?? item.summary,
              style: const TextStyle(color: MindPalette.text,
                  fontSize: 12, height: 1.45)),
          if (item.article?.stockImpact != null) ...[
            const SizedBox(height: 10),
            const Text('Analysis evidence', style: TextStyle(color: MindPalette.muted,
                fontWeight: FontWeight.w700, fontSize: 11)),
            const SizedBox(height: 4),
            Text(item.article!.stockImpact!.evidence,
                style: const TextStyle(color: MindPalette.text, fontSize: 11, height: 1.4)),
            if (item.article!.stockImpact!.risks.isNotEmpty) ...[
              const SizedBox(height: 8),
              const Text('Risks and caveats', style: TextStyle(color: MindPalette.yellow,
                  fontWeight: FontWeight.w700, fontSize: 11)),
              for (final risk in item.article!.stockImpact!.risks)
                Padding(padding: const EdgeInsets.only(top: 4),
                  child: Text('• $risk', style: const TextStyle(color: MindPalette.muted,
                      fontSize: 11, height: 1.35))),
            ],
          ],
          if (item.article != null) ...[
            const SizedBox(height: 8),
            Align(alignment: Alignment.centerLeft, child: TextButton.icon(
              onPressed: () => _openMindNewsPublisher(item.article!.url),
              icon: const Icon(Icons.open_in_new, size: 15),
              label: const Text('Open publisher source'),
              style: TextButton.styleFrom(foregroundColor: MindPalette.mint),
            )),
          ],
        ],
      )),
      const SizedBox(height: 10),
      MindPanel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const MindSectionTitle(title: 'Stock mapping',
              icon: Icons.candlestick_chart_outlined),
          const Text('Symbols identified by the article model; instrument mapping is checked separately before Mind context use.',
              style: TextStyle(color: MindPalette.muted, fontSize: 10, height: 1.35)),
          for (final symbol in item.symbols)
            _newsDetailRow(symbol, '${item.impact} advisory context'),
          if (item.symbols.isEmpty)
            const Text('No stock symbols were returned by the article analysis.',
                style: TextStyle(color: MindPalette.muted, fontSize: 11)),
        ],
      )),
      const SizedBox(height: 10),
      MindPanel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const MindSectionTitle(title: 'Evidence & processing trace',
              icon: Icons.receipt_long_outlined),
          if (item.article == null) for (final row in const [
            ('18:42:16', 'Source item received and timestamped'),
            ('18:42:17', 'Canonical URL and duplicate check'),
            ('18:42:21', 'Model result stored with confidence'),
            ('18:42:22', 'Stock symbols checked'),
          ])
            _newsPipelineRow(row.$1, row.$2, 'SAMPLE'),
          if (item.article != null)
            _newsDetailRow('Analysis status', item.article!.analysisStatus),
          if (item.article?.stockImpact?.analyzedAt != null)
            _newsDetailRow('Analyzed at', _mindNewsTimestamp(item.article!.stockImpact!.analyzedAt!)),
          if (item.article != null)
            const Text('The customer articles API returns the current article and analysis projection. It does not expose the internal per-stage ingestion log.',
              style: TextStyle(color: MindPalette.muted, fontSize: 10, height: 1.35)),
        ],
      )),
      const SizedBox(height: 8),
      _newsBoundaryNote(),
    ],
  );

  Future<void> _openMindNewsPublisher(String rawUrl) async {
    final uri = Uri.tryParse(rawUrl);
    if (uri == null || uri.scheme != 'https' || uri.host.isEmpty ||
        uri.userInfo.isNotEmpty) {
      _notice('This publisher link is unavailable or not secure.');
      return;
    }
    try {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      _notice('Could not open the publisher source.');
    }
  }

  Widget _newsDetailRow(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Expanded(child: Text(label, style: const TextStyle(
          color: MindPalette.muted, fontSize: 11))),
      const SizedBox(width: 8),
      Flexible(child: Text(value, textAlign: TextAlign.right,
          style: const TextStyle(color: MindPalette.text, fontSize: 11,
              fontWeight: FontWeight.w600))),
    ]),
  );

  Widget _newsCoverage() {
    if (!_newsDemoMode) {
      return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children:[Expanded(child:Text('Source fetch health · ${_newsSources.length} publishers',style:const TextStyle(color:MindPalette.text,fontSize:12,fontWeight:FontWeight.w700))),const MindTag('BACKEND DATA')]),
          const SizedBox(height:6),
          if (_newsSourceHealthError != null) Text(_newsSourceHealthError!,style:const TextStyle(color:MindPalette.yellow,fontSize:10)),
          for(final source in _newsSources) Container(padding:const EdgeInsets.symmetric(vertical:8),decoration:const BoxDecoration(border:Border(bottom:BorderSide(color:MindPalette.border))),child:Row(children:[Icon(Icons.circle,size:7,color:source.status=='SUCCESS'?MindPalette.mint:MindPalette.yellow),const SizedBox(width:8),Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Text(source.publisher,style:const TextStyle(color:MindPalette.text,fontSize:11,fontWeight:FontWeight.w700)),Text(source.finishedAt==null?'Last run: never': 'Last run: ${_mindNewsTimestamp(source.finishedAt!)}',style:const TextStyle(color:MindPalette.muted,fontSize:9))])),Text('${source.status} · ${source.fetchedItems} fetched · ${source.insertedItems} new',style:const TextStyle(color:MindPalette.muted,fontSize:9))])),
          if(_newsSources.isEmpty && _newsSourceHealthError==null) const Padding(padding:EdgeInsets.symmetric(vertical:12),child:Text('Waiting for source health data.',style:TextStyle(color:MindPalette.muted,fontSize:11))),
          const SizedBox(height:8),
          const Text('A successful fetch confirms retrieval, not full market coverage or analysis. Analysis remains research context only and does not create or authorize orders.',style:TextStyle(color:MindPalette.yellow,fontSize:10,height:1.4)),
          if (_newsError != null) _newsUnavailable(_newsError!),
        ]);
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _metricGrid(const [
          ('Configured stock universe', '2,000+', 'illustrative target'),
          ('Stocks with news', '142', 'sample period'),
        ]),
        const SizedBox(height: 10),
        MindPanel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const MindSectionTitle(title: 'Sample source health',
                icon: Icons.rss_feed),
            for (final row in const [
              ('Economic Times', '672 demo items · 18:42', 'SAMPLE'),
              ('Moneycontrol', '612 demo items · 18:39', 'SAMPLE'),
              ('SEBI', '0 demo items · 17:15', 'SAMPLE'),
            ])
              _newsSourceRow(row.$1, row.$2, row.$3),
          ],
        )),
        const SizedBox(height: 10),
        MindPanel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const MindSectionTitle(title: 'Sample analysis queue',
                icon: Icons.pending_actions_outlined),
            _newsPipelineRow('18', 'Retrying provider response', 'sample'),
            _newsPipelineRow('12', 'Awaiting symbol review', 'sample'),
            _newsPipelineRow('13', 'Low confidence · withheld', 'sample'),
          ],
        )),
        const SizedBox(height: 8),
        MindPanel(highlight: true, child: const Text(
          'Coverage is limited to connected, permitted sources. No system can guarantee every article across the market.',
          style: TextStyle(color: MindPalette.yellow, fontSize: 11, height: 1.45),
        )),
      ],
    );
  }

  Widget _newsSourceRow(String source, String freshness, String status) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(children: [
          const Icon(Icons.circle, size: 8, color: MindPalette.mint),
          const SizedBox(width: 9),
          Expanded(child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(source, style: const TextStyle(color: MindPalette.text,
                  fontSize: 12, fontWeight: FontWeight.w700)),
              Text(freshness, style: const TextStyle(color: MindPalette.muted,
                  fontSize: 10)),
            ],
          )),
          MindTag(status),
        ]),
      );

  Widget _newsActivity() {
    if (!_newsDemoMode) {
      return Column(crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const MindTag('CUSTOMER API RECORDS · NOT A PIPELINE EVENT STREAM',
              color: MindPalette.yellow),
          const SizedBox(height: 10),
          if (_newsLoading && _newsArticles.isEmpty)
            const Center(child: Padding(padding: EdgeInsets.all(18),
              child: CircularProgressIndicator(strokeWidth: 2))),
          if (_newsError != null) _newsUnavailable(_newsError!),
          MindPanel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const MindSectionTitle(title: 'Recently returned article records',
                  icon: Icons.monitor_heart_outlined),
              for (final article in _newsArticles.take(8))
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Row(crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(width: 78, child: Text(
                        _mindNewsTimestamp(article.ingestedAt),
                        style: const TextStyle(color: MindPalette.muted, fontSize: 9))),
                      const SizedBox(width: 6),
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(article.analysisStatus.replaceAll('_', ' '),
                            style: const TextStyle(color: MindPalette.mint,
                                fontSize: 9, fontWeight: FontWeight.w700)),
                          const SizedBox(height: 2),
                          Text('${article.source} · ${article.title}',
                            style: const TextStyle(color: MindPalette.text, fontSize: 11)),
                        ],
                      )),
                    ],
                  ),
                ),
              if (_newsArticles.isEmpty && !_newsLoading && _newsError == null)
                const Text('No article records are currently available.',
                    style: TextStyle(color: MindPalette.muted, fontSize: 11)),
            ],
          )),
          const SizedBox(height: 8),
          MindPanel(highlight: true, child: const Text(
            'The customer endpoint exposes article publication/ingestion timestamps and analysis status. Internal fetch, policy, Gemini request and symbol-resolution events are not available to this customer session.',
            style: TextStyle(color: MindPalette.yellow, fontSize: 11, height: 1.45),
          )),
          _newsBoundaryNote(),
        ],
      );
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        MindTag('SIMULATED ACTIVITY', color: MindPalette.yellow),
        const SizedBox(height: 10),
        MindPanel(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const MindSectionTitle(title: 'Analysis activity',
                icon: Icons.monitor_heart_outlined),
            for (final row in const [
              ('18:42:16', 'SOURCE FETCH', 'Economic Times returned 12 items'),
              ('18:42:16', 'POLICY CHECK', 'Approved source policy · item retained'),
              ('18:42:17', 'DEDUPLICATION', 'Canonical URL checked · unique story'),
              ('18:42:21', 'GEMINI ANALYSIS', 'Completed · 2 entities · mixed impact'),
              ('18:42:22', 'SYMBOL CHECK', 'Two exact instruments verified'),
              ('18:42:24', 'MIND CONTEXT', 'Research evidence only · no trading effect'),
            ])
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(width: 50, child: Text(row.$1,
                      style: const TextStyle(color: MindPalette.muted, fontSize: 9))),
                    const SizedBox(width: 6),
                    Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(row.$2, style: const TextStyle(color: MindPalette.mint,
                            fontSize: 9, fontWeight: FontWeight.w700)),
                        const SizedBox(height: 2),
                        Text(row.$3, style: const TextStyle(color: MindPalette.text,
                            fontSize: 11)),
                      ],
                    )),
                  ],
                ),
              ),
          ],
        )),
        const SizedBox(height: 8),
        _newsBoundaryNote(),
      ],
    );
  }

  Widget _newsBoundaryNote() => MindPanel(highlight: true,
    child: const Text(
      'RESEARCH CONTEXT ONLY · Current Mind verified news is read-only evidence. It is not included in trading decisions and cannot authorize orders.',
      style: TextStyle(color: MindPalette.yellow, fontSize: 11, height: 1.45),
    ),
  );
}
