import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'mock_review_data.dart';

enum MockReviewScreen {
  home,
  markets,
  research,
  opportunity,
  portfolio,
  orders,
  more,
  broker,
  paperTrading,
  risk,
  backtests,
  notifications,
  settings,
}

class MockReviewApp extends StatefulWidget {
  const MockReviewApp({super.key});

  @override
  State<MockReviewApp> createState() => _MockReviewAppState();
}

class _MockReviewAppState extends State<MockReviewApp> {
  final MockReviewStore _store = MockReviewStore();
  MockReviewScreen _screen = MockReviewScreen.home;
  MockOpportunity? _selectedOpportunity;

  @override
  void dispose() {
    _store.dispose();
    super.dispose();
  }

  void _open(MockReviewScreen screen) => setState(() => _screen = screen);

  void _openOpportunity(MockOpportunity opportunity) {
    setState(() {
      _selectedOpportunity = opportunity;
      _screen = MockReviewScreen.opportunity;
    });
  }

  int get _navIndex => switch (_screen) {
        MockReviewScreen.home => 0,
        MockReviewScreen.markets => 1,
        MockReviewScreen.research || MockReviewScreen.opportunity => 2,
        MockReviewScreen.portfolio || MockReviewScreen.orders => 3,
        _ => 4,
      };

  bool get _showBottomNav => switch (_screen) {
        MockReviewScreen.home ||
        MockReviewScreen.markets ||
        MockReviewScreen.research ||
        MockReviewScreen.portfolio ||
        MockReviewScreen.orders ||
        MockReviewScreen.more => true,
        _ => false,
      };

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _store,
      builder: (context, _) {
        return Scaffold(
          backgroundColor: const Color(0xFF020817),
          body: SafeArea(child: _buildScreen()),
          bottomNavigationBar: _showBottomNav
              ? _ReviewBottomNav(
                  index: _navIndex,
                  onChanged: (index) => _open(switch (index) {
                    0 => MockReviewScreen.home,
                    1 => MockReviewScreen.markets,
                    2 => MockReviewScreen.research,
                    3 => MockReviewScreen.portfolio,
                    _ => MockReviewScreen.more,
                  }),
                )
              : null,
        );
      },
    );
  }

  Widget _buildScreen() => switch (_screen) {
        MockReviewScreen.home => _HomeReviewPage(store: _store, open: _open),
        MockReviewScreen.markets => _MarketsReviewPage(
            store: _store,
            onOpportunity: _openOpportunity,
          ),
        MockReviewScreen.research => _ResearchReviewPage(
            store: _store,
            onOpportunity: _openOpportunity,
          ),
        MockReviewScreen.opportunity => _OpportunityReviewPage(
            store: _store,
            opportunity: _selectedOpportunity ?? _store.opportunities.first,
            onBack: () => _open(MockReviewScreen.research),
            onPortfolio: () => _open(MockReviewScreen.portfolio),
          ),
        MockReviewScreen.portfolio => _PortfolioReviewPage(
            store: _store,
            onOrders: () => _open(MockReviewScreen.orders),
          ),
        MockReviewScreen.orders => _OrdersReviewPage(store: _store),
        MockReviewScreen.more => _MoreReviewPage(store: _store, open: _open),
        MockReviewScreen.broker => _BrokerReviewPage(
            store: _store,
            onBack: () => _open(MockReviewScreen.more),
          ),
        MockReviewScreen.paperTrading => _PaperTradingReviewPage(
            store: _store,
            onBack: () => _open(MockReviewScreen.more),
          ),
        MockReviewScreen.risk => _RiskReviewPage(
            store: _store,
            onBack: () => _open(MockReviewScreen.more),
          ),
        MockReviewScreen.backtests => _BacktestsReviewPage(
            store: _store,
            onBack: () => _open(MockReviewScreen.more),
          ),
        MockReviewScreen.notifications => _NotificationsReviewPage(
            store: _store,
            onBack: () => _open(MockReviewScreen.more),
          ),
        MockReviewScreen.settings => _SettingsReviewPage(
            store: _store,
            onBack: () => _open(MockReviewScreen.more),
          ),
      };
}

class _HomeReviewPage extends StatelessWidget {
  const _HomeReviewPage({required this.store, required this.open});

  final MockReviewStore store;
  final ValueChanged<MockReviewScreen> open;

  @override
  Widget build(BuildContext context) {
    final nifty = store.quotes.firstWhere((q) => q.symbol == 'NIFTY');
    final bank = store.quotes.firstWhere((q) => q.symbol == 'BANKNIFTY');
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const _AppMark(size: 38),
              const SizedBox(width: 10),
              const Expanded(
                child: Text('AiTradingSystem', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w900)),
              ),
              _CircleAction(
                icon: Icons.notifications_none_rounded,
                badge: store.notifications.where((item) => !item.read).length,
                onTap: () => open(MockReviewScreen.notifications),
              ),
            ],
          ),
          const SizedBox(height: 22),
          const Text('Hi, Satya 👋', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
          const SizedBox(height: 3),
          const Text('Ready to trade smarter?', style: TextStyle(color: _Ui.muted, fontSize: 13)),
          const SizedBox(height: 16),
          _GlowCard(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.auto_awesome_rounded, color: _Ui.cyan, size: 18),
                    const SizedBox(width: 8),
                    const Text('AI Market Pulse', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
                    const Spacer(),
                    Container(
                      width: 8,
                      height: 8,
                      decoration: const BoxDecoration(color: Color(0xFF60FFB1), shape: BoxShape.circle),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                const Text('NIFTY is up 0.68% with strong momentum.', style: TextStyle(color: _Ui.muted, height: 1.35)),
                const SizedBox(height: 12),
                SizedBox(height: 68, child: _Sparkline(values: nifty.sparkline, positive: true, glow: true)),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(child: _IndexCard(quote: nifty)),
              const SizedBox(width: 10),
              Expanded(child: _IndexCard(quote: bank)),
            ],
          ),
          const SizedBox(height: 14),
          GridView.count(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            crossAxisCount: 2,
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            childAspectRatio: 1.65,
            children: [
              _QuickAction(icon: Icons.auto_awesome_rounded, label: 'AI Opportunities', onTap: () => open(MockReviewScreen.research)),
              _QuickAction(icon: Icons.layers_rounded, label: 'Paper Trading', onTap: () => open(MockReviewScreen.paperTrading)),
              _QuickAction(icon: Icons.bar_chart_rounded, label: 'Markets', onTap: () => open(MockReviewScreen.markets)),
              _QuickAction(icon: Icons.account_balance_wallet_rounded, label: 'Portfolio', onTap: () => open(MockReviewScreen.portfolio)),
            ],
          ),
          const SizedBox(height: 18),
          _SectionHeader(title: 'Top movers', action: 'View all', onTap: () => open(MockReviewScreen.markets)),
          const SizedBox(height: 8),
          ...store.quotes.where((q) => !{'NIFTY', 'BANKNIFTY', 'SENSEX'}.contains(q.symbol)).take(3).map(
                (quote) => Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: _QuoteRow(quote: quote),
                ),
              ),
        ],
      ),
    );
  }
}

class _MarketsReviewPage extends StatefulWidget {
  const _MarketsReviewPage({required this.store, required this.onOpportunity});

  final MockReviewStore store;
  final ValueChanged<MockOpportunity> onOpportunity;

  @override
  State<_MarketsReviewPage> createState() => _MarketsReviewPageState();
}

class _MarketsReviewPageState extends State<_MarketsReviewPage> {
  final _search = TextEditingController();
  String _filter = 'Indices';

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  List<MockQuote> get _visible {
    final query = _search.text.trim().toUpperCase();
    Iterable<MockQuote> values = widget.store.quotes;
    if (_filter == 'Indices') {
      values = values.where((q) => {'NIFTY', 'BANKNIFTY', 'SENSEX'}.contains(q.symbol));
    } else if (_filter == 'Top Gainers') {
      values = values.where((q) => q.changePercent > 0).toList()..sort((a, b) => b.changePercent.compareTo(a.changePercent));
    } else if (_filter == 'Top Losers') {
      values = values.where((q) => q.changePercent < 0);
    } else if (_filter == 'Watchlist') {
      values = values.where((q) => widget.store.watchlist.contains(q.symbol));
    }
    if (query.isNotEmpty) {
      values = values.where((q) => q.symbol.contains(query) || q.name.toUpperCase().contains(query));
    }
    return values.toList(growable: false);
  }

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _PageTitle('Markets'),
          const SizedBox(height: 14),
          TextField(
            controller: _search,
            onChanged: (_) => setState(() {}),
            decoration: _Ui.input('Search stocks (e.g. RELIANCE, TCS)', prefix: Icons.search_rounded),
          ),
          const SizedBox(height: 12),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: ['Indices', 'Top Gainers', 'Top Losers', 'Watchlist']
                  .map((value) => Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: ChoiceChip(
                          label: Text(value),
                          selected: _filter == value,
                          onSelected: (_) => setState(() => _filter = value),
                        ),
                      ))
                  .toList(),
            ),
          ),
          const SizedBox(height: 16),
          if (_visible.isEmpty)
            const _EmptyCard(icon: Icons.search_off_rounded, title: 'No matching stocks', message: 'Try another search or filter.')
          else
            ..._visible.map(
              (quote) => Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _ReviewCard(
                  onTap: () {
                    final matches = widget.store.opportunities.where((item) => item.symbol == quote.symbol);
                    if (matches.isNotEmpty) {
                      widget.onOpportunity(matches.first);
                    } else {
                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${quote.symbol} added to market review.')));
                    }
                  },
                  child: _QuoteRow(quote: quote, showName: true),
                ),
              ),
            ),
          const SizedBox(height: 10),
          const _SectionHeader(title: 'Top movers'),
          const SizedBox(height: 8),
          ...widget.store.quotes.where((q) => !{'NIFTY', 'BANKNIFTY', 'SENSEX'}.contains(q.symbol)).take(4).map(
                (quote) => Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: _QuoteRow(quote: quote),
                ),
              ),
        ],
      ),
    );
  }
}

class _ResearchReviewPage extends StatefulWidget {
  const _ResearchReviewPage({required this.store, required this.onOpportunity});

  final MockReviewStore store;
  final ValueChanged<MockOpportunity> onOpportunity;

  @override
  State<_ResearchReviewPage> createState() => _ResearchReviewPageState();
}

class _ResearchReviewPageState extends State<_ResearchReviewPage> {
  String _timeframe = '5 minutes';
  String _stocks = '100';
  bool _scanned = true;

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _PageTitle('AI Research'),
          const SizedBox(height: 14),
          _GlowCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('AI Opportunity Scanner', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
                const SizedBox(height: 6),
                const Text('Let AI analyze the market and surface high-probability trade setups.', style: TextStyle(color: _Ui.muted, height: 1.4)),
                const SizedBox(height: 14),
                _SelectField(label: 'Timeframe', value: _timeframe, values: const ['5 minutes', '15 minutes', '30 minutes', '1 hour'], onChanged: (v) => setState(() => _timeframe = v)),
                const SizedBox(height: 10),
                _SelectField(label: 'Stocks to analyze', value: _stocks, values: const ['50', '100', '200'], onChanged: (v) => setState(() => _stocks = v)),
                const SizedBox(height: 14),
                _GradientButton(
                  icon: Icons.auto_awesome_rounded,
                  label: 'Find Opportunities',
                  onTap: () {
                    setState(() => _scanned = true);
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Mock AI scan completed: 12 opportunities found.')));
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),
          const _SectionHeader(title: 'Recent scans', action: 'View all'),
          const SizedBox(height: 8),
          _ReviewCard(
            child: const Column(
              children: [
                _ScanRow(title: '5m Scan · 100 stocks', subtitle: '12 opportunities · 2m ago'),
                Divider(height: 18),
                _ScanRow(title: '15m Scan · 200 stocks', subtitle: '18 opportunities · 10m ago'),
              ],
            ),
          ),
          if (_scanned) ...[
            const SizedBox(height: 18),
            const _SectionHeader(title: 'Top opportunities'),
            const SizedBox(height: 8),
            ...widget.store.opportunities.map(
              (opportunity) => Padding(
                padding: const EdgeInsets.only(bottom: 9),
                child: _OpportunityTile(opportunity: opportunity, onTap: () => widget.onOpportunity(opportunity)),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _OpportunityReviewPage extends StatelessWidget {
  const _OpportunityReviewPage({
    required this.store,
    required this.opportunity,
    required this.onBack,
    required this.onPortfolio,
  });

  final MockReviewStore store;
  final MockOpportunity opportunity;
  final VoidCallback onBack;
  final VoidCallback onPortfolio;

  @override
  Widget build(BuildContext context) {
    final watching = store.watchlist.contains(opportunity.symbol);
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _BackTitle(title: 'Opportunity Details', onBack: onBack, trailing: Icon(watching ? Icons.star_rounded : Icons.star_border_rounded, color: watching ? const Color(0xFFFFD85C) : _Ui.muted)),
          const SizedBox(height: 18),
          Row(
            children: [
              _SymbolAvatar(opportunity.symbol),
              const SizedBox(width: 10),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(opportunity.symbol, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)), Text(opportunity.company, style: const TextStyle(color: _Ui.muted, fontSize: 12))])),
            ],
          ),
          const SizedBox(height: 16),
          Row(children: [Text('₹${opportunity.price.toStringAsFixed(2)}', style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w900)), const SizedBox(width: 10), Text('+${opportunity.changePercent.toStringAsFixed(2)}%', style: const TextStyle(color: _Ui.green, fontWeight: FontWeight.w800))]),
          const SizedBox(height: 14),
          _ReviewCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [const Icon(Icons.auto_awesome_rounded, color: _Ui.cyan, size: 18), const SizedBox(width: 8), const Text('AI Rating', style: TextStyle(fontWeight: FontWeight.w800)), const Spacer(), Text(opportunity.rating, style: const TextStyle(color: _Ui.green, fontWeight: FontWeight.w900))]),
                const SizedBox(height: 12),
                Row(children: [const Text('Confidence', style: TextStyle(color: _Ui.muted)), const Spacer(), Text('${opportunity.confidence}%', style: const TextStyle(fontWeight: FontWeight.w900))]),
                const SizedBox(height: 7),
                ClipRRect(borderRadius: BorderRadius.circular(99), child: LinearProgressIndicator(value: opportunity.confidence / 100, minHeight: 6, backgroundColor: const Color(0xFF15223B), color: _Ui.cyan)),
              ],
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(child: _MetricCard(label: 'Setup', value: opportunity.setup)),
              const SizedBox(width: 8),
              Expanded(child: _MetricCard(label: 'Risk/Reward', value: opportunity.riskReward)),
              const SizedBox(width: 8),
              Expanded(child: _MetricCard(label: 'Timeframe', value: opportunity.timeframe)),
            ],
          ),
          const SizedBox(height: 10),
          _ReviewCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [const Row(children: [Icon(Icons.auto_awesome_rounded, color: _Ui.cyan, size: 16), SizedBox(width: 7), Text('AI Thesis', style: TextStyle(fontWeight: FontWeight.w900))]), const SizedBox(height: 8), Text(opportunity.thesis, style: const TextStyle(color: _Ui.muted, height: 1.4))]),
          ),
          const SizedBox(height: 10),
          Row(children: [Expanded(child: _PriceLevel(label: 'Entry', value: opportunity.entry, color: _Ui.cyan)), const SizedBox(width: 8), Expanded(child: _PriceLevel(label: 'Stop', value: opportunity.stop, color: _Ui.red)), const SizedBox(width: 8), Expanded(child: _PriceLevel(label: 'Target', value: opportunity.target, color: _Ui.green))]),
          const SizedBox(height: 16),
          _GradientButton(
            label: 'Add to Paper Trade',
            icon: Icons.play_arrow_rounded,
            onTap: () {
              store.executePaperTrade(opportunity);
              ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${opportunity.symbol} PAPER trade executed with mock data.')));
              onPortfolio();
            },
          ),
          const SizedBox(height: 10),
          OutlinedButton.icon(
            onPressed: () => store.toggleWatchlist(opportunity.symbol),
            icon: Icon(watching ? Icons.star_rounded : Icons.star_border_rounded),
            label: Text(watching ? 'Remove from Watchlist' : 'Add to Watchlist'),
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
          ),
        ],
      ),
    );
  }
}

class _PortfolioReviewPage extends StatefulWidget {
  const _PortfolioReviewPage({required this.store, required this.onOrders});
  final MockReviewStore store;
  final VoidCallback onOrders;

  @override
  State<_PortfolioReviewPage> createState() => _PortfolioReviewPageState();
}

class _PortfolioReviewPageState extends State<_PortfolioReviewPage> {
  String _tab = 'Overall';

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [const Expanded(child: _PageTitle('Portfolio')), TextButton(onPressed: widget.onOrders, child: const Text('Orders'))]),
          const SizedBox(height: 12),
          _Segmented(values: const ['Overall', 'Positions', 'Holdings'], selected: _tab, onChanged: (value) => setState(() => _tab = value)),
          const SizedBox(height: 12),
          if (_tab == 'Overall') ...[
            _GlowCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [const Text('PAPER Portfolio', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w900)), const Spacer(), _Pill(text: 'PAPER MODE', color: _Ui.magenta)]),
                  const SizedBox(height: 16),
                  _MoneyLine(label: 'Virtual Capital', value: widget.store.virtualCapital),
                  const SizedBox(height: 10),
                  _MoneyLine(label: 'Current Value', value: widget.store.currentValue, strong: true),
                  const SizedBox(height: 10),
                  Text('P&L  +₹${widget.store.totalPnl.toStringAsFixed(0)} (+5.84%)', style: const TextStyle(color: _Ui.green, fontWeight: FontWeight.w900)),
                  const SizedBox(height: 12),
                  SizedBox(height: 110, child: _Sparkline(values: const [18, 26, 24, 34, 43, 38, 51, 64, 78], positive: true, glow: true)),
                ],
              ),
            ),
            const SizedBox(height: 10),
            Row(children: [Expanded(child: _MetricCard(label: 'Open Positions', value: '${widget.store.positions.length}')), const SizedBox(width: 8), const Expanded(child: _MetricCard(label: 'Win Rate', value: '75%')), const SizedBox(width: 8), Expanded(child: _MetricCard(label: 'Today P&L', value: '+₹${widget.store.todayPnl.toStringAsFixed(0)}', valueColor: _Ui.green))]),
            const SizedBox(height: 16),
            const _SectionHeader(title: 'Positions'),
            const SizedBox(height: 8),
          ],
          if (_tab == 'Holdings') const _InfoBanner(text: 'Review-mode holdings use the same mock positions so you can validate populated layouts before broker integration.'),
          if (widget.store.positions.isEmpty)
            const _EmptyCard(icon: Icons.account_balance_wallet_outlined, title: 'No PAPER positions', message: 'Add a mock PAPER trade from AI Research to populate this view.')
          else
            ...widget.store.positions.map((position) => Padding(padding: const EdgeInsets.only(bottom: 8), child: _PositionTile(position: position))),
        ],
      ),
    );
  }
}

class _OrdersReviewPage extends StatefulWidget {
  const _OrdersReviewPage({required this.store});
  final MockReviewStore store;

  @override
  State<_OrdersReviewPage> createState() => _OrdersReviewPageState();
}

class _OrdersReviewPageState extends State<_OrdersReviewPage> {
  String _filter = 'All';

  List<MockOrder> get _orders => widget.store.orders.where((order) {
        if (_filter == 'All') return true;
        if (_filter == 'Open') return order.status == 'Open';
        return order.status == _filter;
      }).toList(growable: false);

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _PageTitle('Orders'),
          const SizedBox(height: 12),
          _Segmented(values: const ['All', 'Open', 'Completed', 'Cancelled'], selected: _filter, onChanged: (value) => setState(() => _filter = value)),
          const SizedBox(height: 14),
          if (_orders.isEmpty)
            const _EmptyCard(icon: Icons.receipt_long_outlined, title: 'No orders in this filter', message: 'Your review-mode PAPER orders will appear here.')
          else
            ..._orders.map((order) => Padding(padding: const EdgeInsets.only(bottom: 9), child: _OrderTile(order: order))),
        ],
      ),
    );
  }
}

class _MoreReviewPage extends StatelessWidget {
  const _MoreReviewPage({required this.store, required this.open});

  final MockReviewStore store;
  final ValueChanged<MockReviewScreen> open;

  @override
  Widget build(BuildContext context) {
    final items = <(IconData, String, String, MockReviewScreen)>[
      (Icons.link_rounded, 'Broker Connect', store.brokerConnected ? 'Angel One connected' : 'Connect Angel One', MockReviewScreen.broker),
      (Icons.layers_rounded, 'Paper Trading', 'Practice with virtual money', MockReviewScreen.paperTrading),
      (Icons.shield_outlined, 'Risk Controls', 'Limits, stop loss, exposure', MockReviewScreen.risk),
      (Icons.science_outlined, 'Backtests', 'Test and validate strategies', MockReviewScreen.backtests),
      (Icons.receipt_long_outlined, 'Orders', 'Review PAPER orders', MockReviewScreen.orders),
      (Icons.notifications_none_rounded, 'Notifications', 'Alerts and updates', MockReviewScreen.notifications),
      (Icons.tune_rounded, 'Settings', 'Security and preferences', MockReviewScreen.settings),
    ];
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _PageTitle('More'),
          const SizedBox(height: 14),
          _ReviewCard(
            child: const Row(
              children: [
                CircleAvatar(radius: 22, backgroundColor: Color(0xFF8B4DFF), child: Text('S', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w900))),
                SizedBox(width: 12),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('Satya', style: TextStyle(fontWeight: FontWeight.w900, fontSize: 16)), SizedBox(height: 2), Text('satya@aitrading.demo', style: TextStyle(color: _Ui.muted, fontSize: 12))])),
                Icon(Icons.chevron_right_rounded),
              ],
            ),
          ),
          const SizedBox(height: 12),
          ...items.map((item) => Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _MenuTile(icon: item.$1, title: item.$2, subtitle: item.$3, onTap: () => open(item.$4)),
              )),
        ],
      ),
    );
  }
}

class _BrokerReviewPage extends StatelessWidget {
  const _BrokerReviewPage({required this.store, required this.onBack});
  final MockReviewStore store;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _BackTitle(title: 'Connect Broker', onBack: onBack),
          const SizedBox(height: 20),
          _GlowCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [const _AngelOneMark(), const SizedBox(width: 12), const Text('Angel One', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w900)), const Spacer(), if (store.brokerConnected) const _Pill(text: 'CONNECTED', color: _Ui.green)]),
                const SizedBox(height: 14),
                Text(store.brokerConnected ? 'Mock broker connection is active for UI review.' : 'Connect your Angel One account to review the future live-trading experience.', style: const TextStyle(color: _Ui.muted, height: 1.4)),
                const SizedBox(height: 16),
                const _CheckLine('Secure & read-only account access'),
                const _CheckLine('Place and manage orders'),
                const _CheckLine('View holdings and positions'),
                const _CheckLine('Credentials are never stored in the app'),
                const SizedBox(height: 18),
                if (!store.brokerConnected)
                  _GradientButton(label: 'Connect Angel One', icon: Icons.link_rounded, onTap: store.connectBroker)
                else
                  OutlinedButton.icon(onPressed: store.disconnectBroker, icon: const Icon(Icons.link_off_rounded), label: const Text('Disconnect review connection'), style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48))),
              ],
            ),
          ),
          const SizedBox(height: 12),
          const _InfoBanner(text: 'Review mode only: no Angel One API call is made and no credential is collected.'),
        ],
      ),
    );
  }
}

class _PaperTradingReviewPage extends StatelessWidget {
  const _PaperTradingReviewPage({required this.store, required this.onBack});
  final MockReviewStore store;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _BackTitle(title: 'Paper Trading', onBack: onBack),
          const SizedBox(height: 18),
          _ToggleCard(title: 'PAPER Mode', subtitle: 'Practice trading with virtual money.', value: store.paperMode, onChanged: (value) { store.paperMode = value; store.notifyListeners(); }),
          const SizedBox(height: 10),
          _ReviewCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [const Expanded(child: Text('Virtual Capital', style: TextStyle(color: _Ui.muted))), IconButton(onPressed: () => _editCapital(context), icon: const Icon(Icons.edit_outlined, size: 18, color: _Ui.cyan))]),
              Text('₹${store.virtualCapital.toStringAsFixed(0)}', style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w900)),
            ]),
          ),
          const SizedBox(height: 10),
          Row(children: [Expanded(child: _MetricCard(label: 'Today P&L', value: '+₹${store.todayPnl.toStringAsFixed(0)}', valueColor: _Ui.green)), const SizedBox(width: 8), Expanded(child: _MetricCard(label: 'Total P&L', value: '+₹${store.totalPnl.toStringAsFixed(0)}', valueColor: _Ui.green))]),
          const SizedBox(height: 16),
          OutlinedButton.icon(onPressed: () => _confirmReset(context), icon: const Icon(Icons.refresh_rounded), label: const Text('Reset Portfolio'), style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48))),
          const SizedBox(height: 12),
          const Center(child: Text('No real money involved. Trade and test strategies risk-free.', textAlign: TextAlign.center, style: TextStyle(color: _Ui.muted, fontSize: 12))),
        ],
      ),
    );
  }

  Future<void> _editCapital(BuildContext context) async {
    final controller = TextEditingController(text: store.virtualCapital.toStringAsFixed(0));
    final value = await showDialog<double>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Virtual Capital'),
        content: TextField(controller: controller, keyboardType: TextInputType.number, decoration: const InputDecoration(prefixText: '₹ ')),
        actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')), FilledButton(onPressed: () => Navigator.pop(context, double.tryParse(controller.text)), child: const Text('Save'))],
      ),
    );
    controller.dispose();
    if (value != null && value >= 10000) store.setVirtualCapital(value);
  }

  Future<void> _confirmReset(BuildContext context) async {
    final reset = await showDialog<bool>(context: context, builder: (context) => AlertDialog(title: const Text('Reset PAPER portfolio?'), content: const Text('This clears mock positions and orders for this review session.'), actions: [TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')), FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Reset'))]));
    if (reset == true) store.resetPortfolio();
  }
}

class _RiskReviewPage extends StatelessWidget {
  const _RiskReviewPage({required this.store, required this.onBack});
  final MockReviewStore store;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _BackTitle(title: 'Risk Controls', onBack: onBack),
          const SizedBox(height: 18),
          _ReviewCard(child: Column(children: [
            _SelectField(label: 'Per Trade Risk', value: store.perTradeRisk, values: const ['0.5% of capital', '1.0% of capital', '1.5% of capital', '2.0% of capital'], onChanged: (value) { store.perTradeRisk = value; store.notifyListeners(); }),
            const SizedBox(height: 12),
            _SelectField(label: 'Daily Loss Limit', value: store.dailyLossLimit, values: const ['2.0% of capital', '3.0% of capital', '5.0% of capital'], onChanged: (value) { store.dailyLossLimit = value; store.notifyListeners(); }),
            const SizedBox(height: 12),
            _SelectField(label: 'Max Open Positions', value: '${store.maxOpenPositions}', values: const ['3', '5', '8', '10'], onChanged: (value) { store.maxOpenPositions = int.parse(value); store.notifyListeners(); }),
          ])),
          const SizedBox(height: 10),
          _ToggleCard(title: 'Stop Loss (SL)', subtitle: 'Apply configured stop protection.', value: store.stopLossEnabled, onChanged: (value) { store.stopLossEnabled = value; store.notifyListeners(); }),
          const SizedBox(height: 8),
          _ToggleCard(title: 'Take Profit (TP)', subtitle: 'Use profit targets on mock orders.', value: store.takeProfitEnabled, onChanged: (value) { store.takeProfitEnabled = value; store.notifyListeners(); }),
          const SizedBox(height: 8),
          _ToggleCard(title: 'Auto Square-off (EOD)', subtitle: 'Close intraday mock positions at end of day.', value: store.autoSquareOff, onChanged: (value) { store.autoSquareOff = value; store.notifyListeners(); }),
        ],
      ),
    );
  }
}

class _BacktestsReviewPage extends StatelessWidget {
  const _BacktestsReviewPage({required this.store, required this.onBack});
  final MockReviewStore store;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _BackTitle(title: 'Backtests', onBack: onBack),
          const SizedBox(height: 10),
          const Text('Test your strategy on historical data', style: TextStyle(color: _Ui.muted)),
          const SizedBox(height: 16),
          _GradientButton(label: 'Run Backtest', icon: Icons.play_arrow_rounded, onTap: () { store.addBacktest(); ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Mock backtest completed successfully.'))); }),
          const SizedBox(height: 20),
          const _SectionHeader(title: 'Recent backtests'),
          const SizedBox(height: 8),
          ...store.backtests.map((result) => Padding(padding: const EdgeInsets.only(bottom: 8), child: _ReviewCard(child: Row(children: [Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(result.name, style: const TextStyle(fontWeight: FontWeight.w900)), const SizedBox(height: 4), Text('${result.universe} · ${result.period}', style: const TextStyle(color: _Ui.muted, fontSize: 12))])), Text('+${result.returnPercent.toStringAsFixed(1)}%', style: const TextStyle(color: _Ui.green, fontWeight: FontWeight.w900))])))),
        ],
      ),
    );
  }
}

class _NotificationsReviewPage extends StatefulWidget {
  const _NotificationsReviewPage({required this.store, required this.onBack});
  final MockReviewStore store;
  final VoidCallback onBack;

  @override
  State<_NotificationsReviewPage> createState() => _NotificationsReviewPageState();
}

class _NotificationsReviewPageState extends State<_NotificationsReviewPage> {
  String _filter = 'All';

  @override
  Widget build(BuildContext context) {
    final visible = widget.store.notifications.where((item) => _filter == 'All' || item.category == _filter).toList();
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _BackTitle(title: 'Notifications', onBack: widget.onBack, trailing: TextButton(onPressed: widget.store.markAllNotificationsRead, child: const Text('Mark all as read'))),
          const SizedBox(height: 12),
          _Segmented(values: const ['All', 'Trades', 'Alerts', 'System'], selected: _filter, onChanged: (value) => setState(() => _filter = value)),
          const SizedBox(height: 14),
          ...visible.map((item) => Padding(padding: const EdgeInsets.only(bottom: 8), child: _NotificationTile(item: item))),
        ],
      ),
    );
  }
}

class _SettingsReviewPage extends StatelessWidget {
  const _SettingsReviewPage({required this.store, required this.onBack});
  final MockReviewStore store;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _BackTitle(title: 'Settings', onBack: onBack),
          const SizedBox(height: 18),
          const _SectionHeader(title: 'App & Security'),
          const SizedBox(height: 8),
          _ReviewCard(child: Column(children: [
            _SettingsSwitch(icon: Icons.fingerprint_rounded, title: 'Biometric Sign-in', value: store.biometricSignIn, onChanged: (value) { store.biometricSignIn = value; store.notifyListeners(); }),
            const Divider(height: 1),
            _SettingsRow(icon: Icons.lock_reset_rounded, title: 'Change Password', onTap: () => _message(context, 'Password change UI will use the same secure auth style.')),
            const Divider(height: 1),
            _SettingsSwitch(icon: Icons.lock_outline_rounded, title: 'App Lock', value: store.appLock, onChanged: (value) { store.appLock = value; store.notifyListeners(); }),
          ])),
          const SizedBox(height: 18),
          const _SectionHeader(title: 'Preferences'),
          const SizedBox(height: 8),
          _ReviewCard(child: Column(children: [
            _SettingsRow(icon: Icons.dark_mode_outlined, title: 'Theme', trailing: store.theme, onTap: () => _chooseTheme(context)),
            const Divider(height: 1),
            _SettingsSwitch(icon: Icons.notifications_active_outlined, title: 'Market Hours Alerts', value: store.marketHoursAlerts, onChanged: (value) { store.marketHoursAlerts = value; store.notifyListeners(); }),
            const Divider(height: 1),
            _SettingsRow(icon: Icons.language_rounded, title: 'Language', trailing: store.language, onTap: () => _chooseLanguage(context)),
          ])),
        ],
      ),
    );
  }

  void _message(BuildContext context, String message) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));

  Future<void> _chooseTheme(BuildContext context) async {
    final result = await showModalBottomSheet<String>(context: context, builder: (context) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: ['Dark (Default)', 'Neon High Contrast'].map((value) => ListTile(title: Text(value), trailing: store.theme == value ? const Icon(Icons.check_rounded, color: _Ui.cyan) : null, onTap: () => Navigator.pop(context, value))).toList())));
    if (result != null) { store.theme = result; store.notifyListeners(); }
  }

  Future<void> _chooseLanguage(BuildContext context) async {
    final result = await showModalBottomSheet<String>(context: context, builder: (context) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: ['English', 'हिन्दी', 'తెలుగు'].map((value) => ListTile(title: Text(value), trailing: store.language == value ? const Icon(Icons.check_rounded, color: _Ui.cyan) : null, onTap: () => Navigator.pop(context, value))).toList())));
    if (result != null) { store.language = result; store.notifyListeners(); }
  }
}

class _ReviewBottomNav extends StatelessWidget {
  const _ReviewBottomNav({required this.index, required this.onChanged});
  final int index;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    const items = <(IconData, String)>[(Icons.home_filled, 'Home'), (Icons.candlestick_chart_rounded, 'Markets'), (Icons.auto_awesome_rounded, 'AI'), (Icons.account_balance_wallet_rounded, 'Portfolio'), (Icons.grid_view_rounded, 'More')];
    return Container(
      decoration: const BoxDecoration(color: Color(0xFF061022), border: Border(top: BorderSide(color: Color(0xFF14243B)))),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 62,
          child: Row(
            children: List.generate(items.length, (i) {
              final active = i == index;
              return Expanded(
                child: InkWell(
                  onTap: () => onChanged(i),
                  child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [Icon(items[i].$1, size: 20, color: active ? _Ui.cyan : _Ui.muted), const SizedBox(height: 3), Text(items[i].$2, style: TextStyle(fontSize: 10, fontWeight: active ? FontWeight.w900 : FontWeight.w600, color: active ? _Ui.cyan : _Ui.muted))]),
                ),
              );
            }),
          ),
        ),
      ),
    );
  }
}

class _PageScroll extends StatelessWidget {
  const _PageScroll({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 18, 16, 24),
        child: Center(child: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 620), child: child)),
      );
}

class _ReviewCard extends StatelessWidget {
  const _ReviewCard({required this.child, this.onTap, this.padding = const EdgeInsets.all(14)});
  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => Material(
        color: const Color(0xFF08162A),
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(16),
          child: Container(
            padding: padding,
            decoration: BoxDecoration(borderRadius: BorderRadius.circular(16), border: Border.all(color: const Color(0xFF183050))),
            child: child,
          ),
        ),
      );
}

class _GlowCard extends StatelessWidget {
  const _GlowCard({required this.child, this.padding = const EdgeInsets.all(16)});
  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => Container(
        padding: padding,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(18),
          gradient: const LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(0xFF241349), Color(0xFF0A2446), Color(0xFF07162A)]),
          border: Border.all(color: const Color(0xFF553E87)),
          boxShadow: const [BoxShadow(color: Color(0x331DE7FF), blurRadius: 24, offset: Offset(0, 8))],
        ),
        child: child,
      );
}

class _AppMark extends StatelessWidget {
  const _AppMark({this.size = 40});
  final double size;
  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(size * .28), gradient: const LinearGradient(colors: [_Ui.cyan, _Ui.violet, _Ui.magenta])),
        child: Icon(Icons.query_stats_rounded, color: Colors.white, size: size * .55),
      );
}

class _CircleAction extends StatelessWidget {
  const _CircleAction({required this.icon, required this.onTap, this.badge = 0});
  final IconData icon;
  final VoidCallback onTap;
  final int badge;
  @override
  Widget build(BuildContext context) => Stack(children: [IconButton(onPressed: onTap, icon: Icon(icon, color: Colors.white)), if (badge > 0) Positioned(right: 5, top: 5, child: Container(width: 7, height: 7, decoration: const BoxDecoration(color: _Ui.magenta, shape: BoxShape.circle)))]);
}

class _PageTitle extends StatelessWidget {
  const _PageTitle(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Text(text, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w900, letterSpacing: -.4));
}

class _BackTitle extends StatelessWidget {
  const _BackTitle({required this.title, required this.onBack, this.trailing});
  final String title;
  final VoidCallback onBack;
  final Widget? trailing;
  @override
  Widget build(BuildContext context) => Row(children: [IconButton(onPressed: onBack, padding: EdgeInsets.zero, alignment: Alignment.centerLeft, icon: const Icon(Icons.arrow_back_rounded)), Expanded(child: Text(title, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900))), if (trailing != null) trailing!]);
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title, this.action, this.onTap});
  final String title;
  final String? action;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Row(children: [Expanded(child: Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w900))), if (action != null) TextButton(onPressed: onTap, child: Text(action!, style: const TextStyle(color: _Ui.cyan, fontSize: 12)))]);
}

class _IndexCard extends StatelessWidget {
  const _IndexCard({required this.quote});
  final MockQuote quote;
  @override
  Widget build(BuildContext context) => _ReviewCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(quote.symbol, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 12)), const SizedBox(height: 4), Text(_Ui.money(quote.price), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)), const SizedBox(height: 3), Text(_Ui.percent(quote.changePercent), style: TextStyle(color: quote.changePercent >= 0 ? _Ui.green : _Ui.red, fontSize: 12, fontWeight: FontWeight.w800))]));
}

class _QuickAction extends StatelessWidget {
  const _QuickAction({required this.icon, required this.label, required this.onTap});
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => _ReviewCard(onTap: onTap, child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [Icon(icon, color: _Ui.cyan, size: 25), const SizedBox(height: 8), Text(label, textAlign: TextAlign.center, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800))]));
}

class _QuoteRow extends StatelessWidget {
  const _QuoteRow({required this.quote, this.showName = false});
  final MockQuote quote;
  final bool showName;
  @override
  Widget build(BuildContext context) => Row(children: [_SymbolAvatar(quote.symbol), const SizedBox(width: 10), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(quote.symbol, style: const TextStyle(fontWeight: FontWeight.w900)), if (showName) Text(quote.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _Ui.muted, fontSize: 11))])), SizedBox(width: 72, height: 30, child: _Sparkline(values: quote.sparkline, positive: quote.changePercent >= 0)), const SizedBox(width: 10), Column(crossAxisAlignment: CrossAxisAlignment.end, children: [Text(_Ui.money(quote.price), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 12)), Text(_Ui.percent(quote.changePercent), style: TextStyle(color: quote.changePercent >= 0 ? _Ui.green : _Ui.red, fontWeight: FontWeight.w800, fontSize: 11))])]);
}

class _SymbolAvatar extends StatelessWidget {
  const _SymbolAvatar(this.symbol);
  final String symbol;
  @override
  Widget build(BuildContext context) {
    final letter = symbol.isEmpty ? '?' : symbol[0];
    return CircleAvatar(radius: 17, backgroundColor: const Color(0xFF18294A), child: Text(letter, style: const TextStyle(color: _Ui.cyan, fontWeight: FontWeight.w900)));
  }
}

class _Sparkline extends StatelessWidget {
  const _Sparkline({required this.values, required this.positive, this.glow = false});
  final List<double> values;
  final bool positive;
  final bool glow;
  @override
  Widget build(BuildContext context) => CustomPaint(painter: _SparkPainter(values, positive ? _Ui.green : _Ui.red, glow));
}

class _SparkPainter extends CustomPainter {
  _SparkPainter(this.values, this.color, this.glow);
  final List<double> values;
  final Color color;
  final bool glow;
  @override
  void paint(Canvas canvas, Size size) {
    if (values.length < 2) return;
    final minValue = values.reduce(math.min);
    final maxValue = values.reduce(math.max);
    final span = math.max(1.0, maxValue - minValue);
    final path = Path();
    for (var i = 0; i < values.length; i++) {
      final x = size.width * i / (values.length - 1);
      final y = size.height - ((values[i] - minValue) / span * (size.height * .8)) - size.height * .1;
      if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
    }
    if (glow) canvas.drawPath(path, Paint()..style = PaintingStyle.stroke..strokeWidth = 8..color = color.withValues(alpha: .16)..maskFilter = const MaskFilter.blur(BlurStyle.normal, 8));
    canvas.drawPath(path, Paint()..style = PaintingStyle.stroke..strokeWidth = glow ? 2.3 : 1.7..strokeCap = StrokeCap.round..strokeJoin = StrokeJoin.round..color = color);
  }
  @override
  bool shouldRepaint(covariant _SparkPainter oldDelegate) => oldDelegate.values != values || oldDelegate.color != color;
}

class _SelectField extends StatelessWidget {
  const _SelectField({required this.label, required this.value, required this.values, required this.onChanged});
  final String label;
  final String value;
  final List<String> values;
  final ValueChanged<String> onChanged;
  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(value: value, decoration: _Ui.input(label), dropdownColor: const Color(0xFF0B1930), items: values.map((v) => DropdownMenuItem(value: v, child: Text(v))).toList(), onChanged: (v) { if (v != null) onChanged(v); });
}

class _GradientButton extends StatelessWidget {
  const _GradientButton({required this.label, required this.onTap, this.icon});
  final String label;
  final VoidCallback onTap;
  final IconData? icon;
  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(13), gradient: const LinearGradient(colors: [_Ui.cyan, Color(0xFF5DE1FF), _Ui.violet, _Ui.magenta])),
        child: Material(color: Colors.transparent, child: InkWell(onTap: onTap, borderRadius: BorderRadius.circular(13), child: SizedBox(height: 50, width: double.infinity, child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [if (icon != null) ...[Icon(icon, color: const Color(0xFF03111F), size: 20), const SizedBox(width: 8)], Text(label, style: const TextStyle(color: Color(0xFF03111F), fontWeight: FontWeight.w900))]))),
      );
}

class _ScanRow extends StatelessWidget {
  const _ScanRow({required this.title, required this.subtitle});
  final String title;
  final String subtitle;
  @override
  Widget build(BuildContext context) => Row(children: [const Icon(Icons.check_circle_outline_rounded, color: _Ui.cyan, size: 20), const SizedBox(width: 10), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(title, style: const TextStyle(fontWeight: FontWeight.w800)), Text(subtitle, style: const TextStyle(color: _Ui.muted, fontSize: 11))])), const Icon(Icons.check_rounded, color: _Ui.green, size: 18)]);
}

class _OpportunityTile extends StatelessWidget {
  const _OpportunityTile({required this.opportunity, required this.onTap});
  final MockOpportunity opportunity;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => _ReviewCard(onTap: onTap, child: Row(children: [_SymbolAvatar(opportunity.symbol), const SizedBox(width: 10), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(opportunity.symbol, style: const TextStyle(fontWeight: FontWeight.w900)), const SizedBox(height: 3), Text('${opportunity.setup} · ${opportunity.timeframe}', style: const TextStyle(color: _Ui.muted, fontSize: 11))])), Column(crossAxisAlignment: CrossAxisAlignment.end, children: [Text(opportunity.rating, style: const TextStyle(color: _Ui.green, fontWeight: FontWeight.w900, fontSize: 12)), Text('${opportunity.confidence}% confidence', style: const TextStyle(color: _Ui.muted, fontSize: 10))]), const SizedBox(width: 4), const Icon(Icons.chevron_right_rounded, color: _Ui.muted)]));
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({required this.label, required this.value, this.valueColor});
  final String label;
  final String value;
  final Color? valueColor;
  @override
  Widget build(BuildContext context) => _ReviewCard(padding: const EdgeInsets.all(10), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _Ui.muted, fontSize: 9)), const SizedBox(height: 4), Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: valueColor ?? Colors.white, fontWeight: FontWeight.w900, fontSize: 12))]));
}

class _PriceLevel extends StatelessWidget {
  const _PriceLevel({required this.label, required this.value, required this.color});
  final String label;
  final double value;
  final Color color;
  @override
  Widget build(BuildContext context) => _ReviewCard(padding: const EdgeInsets.all(10), child: Column(children: [Text(label, style: const TextStyle(color: _Ui.muted, fontSize: 10)), const SizedBox(height: 4), Text('₹${value.toStringAsFixed(0)}', style: TextStyle(color: color, fontWeight: FontWeight.w900, fontSize: 12))]));
}

class _MoneyLine extends StatelessWidget {
  const _MoneyLine({required this.label, required this.value, this.strong = false});
  final String label;
  final double value;
  final bool strong;
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label, style: const TextStyle(color: _Ui.muted, fontSize: 11)), const SizedBox(height: 3), Text('₹${value.toStringAsFixed(0)}', style: TextStyle(fontWeight: FontWeight.w900, fontSize: strong ? 25 : 20))]);
}

class _PositionTile extends StatelessWidget {
  const _PositionTile({required this.position});
  final MockPosition position;
  @override
  Widget build(BuildContext context) => _ReviewCard(child: Row(children: [_SymbolAvatar(position.symbol), const SizedBox(width: 10), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(position.symbol, style: const TextStyle(fontWeight: FontWeight.w900)), Text('${position.quantity} qty · Avg ₹${position.averagePrice.toStringAsFixed(2)}', style: const TextStyle(color: _Ui.muted, fontSize: 11))])), Column(crossAxisAlignment: CrossAxisAlignment.end, children: [Text('₹${position.marketValue.toStringAsFixed(0)}', style: const TextStyle(fontWeight: FontWeight.w900)), Text('${position.pnl >= 0 ? '+' : ''}₹${position.pnl.toStringAsFixed(0)} (${position.pnlPercent.toStringAsFixed(1)}%)', style: TextStyle(color: position.pnl >= 0 ? _Ui.green : _Ui.red, fontSize: 11, fontWeight: FontWeight.w800))])]));
}

class _OrderTile extends StatelessWidget {
  const _OrderTile({required this.order});
  final MockOrder order;
  @override
  Widget build(BuildContext context) {
    final statusColor = order.status == 'Completed' ? _Ui.green : order.status == 'Cancelled' ? _Ui.red : _Ui.cyan;
    return _ReviewCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Row(children: [Text(order.symbol, style: const TextStyle(fontWeight: FontWeight.w900)), const Spacer(), _Pill(text: order.status, color: statusColor)]), const SizedBox(height: 5), Text('${order.side} · PAPER', style: TextStyle(color: order.side == 'BUY' ? _Ui.cyan : _Ui.magenta, fontSize: 11, fontWeight: FontWeight.w800)), const SizedBox(height: 5), Row(children: [Expanded(child: Text('Qty: ${order.quantity} · Avg: ₹${order.averagePrice.toStringAsFixed(2)}', style: const TextStyle(color: _Ui.muted, fontSize: 11))), Text(order.time, style: const TextStyle(color: _Ui.muted, fontSize: 10))])]));
  }
}

class _Segmented extends StatelessWidget {
  const _Segmented({required this.values, required this.selected, required this.onChanged});
  final List<String> values;
  final String selected;
  final ValueChanged<String> onChanged;
  @override
  Widget build(BuildContext context) => SingleChildScrollView(scrollDirection: Axis.horizontal, child: Row(children: values.map((value) => Padding(padding: const EdgeInsets.only(right: 7), child: ChoiceChip(label: Text(value), selected: selected == value, onSelected: (_) => onChanged(value)))).toList()));
}

class _MenuTile extends StatelessWidget {
  const _MenuTile({required this.icon, required this.title, required this.subtitle, required this.onTap});
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => _ReviewCard(onTap: onTap, child: Row(children: [Container(width: 42, height: 42, decoration: BoxDecoration(color: _Ui.cyan.withValues(alpha: .10), borderRadius: BorderRadius.circular(13)), child: Icon(icon, color: _Ui.cyan, size: 21)), const SizedBox(width: 12), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(title, style: const TextStyle(fontWeight: FontWeight.w900)), const SizedBox(height: 3), Text(subtitle, style: const TextStyle(color: _Ui.muted, fontSize: 11))])), const Icon(Icons.chevron_right_rounded, color: _Ui.muted)]));
}

class _AngelOneMark extends StatelessWidget {
  const _AngelOneMark();
  @override
  Widget build(BuildContext context) => Container(width: 44, height: 44, decoration: BoxDecoration(shape: BoxShape.circle, gradient: const LinearGradient(colors: [_Ui.green, _Ui.cyan, _Ui.violet]), border: Border.all(color: Colors.white24)), child: const Icon(Icons.change_history_rounded, color: Colors.white));
}

class _CheckLine extends StatelessWidget {
  const _CheckLine(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Padding(padding: const EdgeInsets.only(bottom: 9), child: Row(children: [const Icon(Icons.check_circle_rounded, color: _Ui.green, size: 18), const SizedBox(width: 8), Expanded(child: Text(text, style: const TextStyle(fontSize: 12)))]));
}

class _ToggleCard extends StatelessWidget {
  const _ToggleCard({required this.title, required this.subtitle, required this.value, required this.onChanged});
  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;
  @override
  Widget build(BuildContext context) => _ReviewCard(child: Row(children: [Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(title, style: const TextStyle(fontWeight: FontWeight.w900)), const SizedBox(height: 3), Text(subtitle, style: const TextStyle(color: _Ui.muted, fontSize: 11))])), Switch(value: value, onChanged: onChanged)]));
}

class _NotificationTile extends StatelessWidget {
  const _NotificationTile({required this.item});
  final MockNotificationItem item;
  @override
  Widget build(BuildContext context) => _ReviewCard(child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Container(width: 38, height: 38, decoration: BoxDecoration(color: (item.read ? _Ui.muted : _Ui.cyan).withValues(alpha: .10), borderRadius: BorderRadius.circular(12)), child: Icon(Icons.notifications_none_rounded, color: item.read ? _Ui.muted : _Ui.cyan, size: 19)), const SizedBox(width: 10), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Row(children: [Expanded(child: Text(item.title, style: TextStyle(fontWeight: item.read ? FontWeight.w600 : FontWeight.w900))), Text(item.time, style: const TextStyle(color: _Ui.muted, fontSize: 10))]), const SizedBox(height: 4), Text(item.message, style: const TextStyle(color: _Ui.muted, fontSize: 11))]))]));
}

class _SettingsSwitch extends StatelessWidget {
  const _SettingsSwitch({required this.icon, required this.title, required this.value, required this.onChanged});
  final IconData icon;
  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;
  @override
  Widget build(BuildContext context) => Row(children: [Icon(icon, color: _Ui.muted, size: 19), const SizedBox(width: 10), Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.w700))), Switch(value: value, onChanged: onChanged)]);
}

class _SettingsRow extends StatelessWidget {
  const _SettingsRow({required this.icon, required this.title, required this.onTap, this.trailing});
  final IconData icon;
  final String title;
  final VoidCallback onTap;
  final String? trailing;
  @override
  Widget build(BuildContext context) => InkWell(onTap: onTap, child: Padding(padding: const EdgeInsets.symmetric(vertical: 13), child: Row(children: [Icon(icon, color: _Ui.muted, size: 19), const SizedBox(width: 10), Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.w700))), if (trailing != null) Text(trailing!, style: const TextStyle(color: _Ui.muted, fontSize: 11)), const SizedBox(width: 5), const Icon(Icons.chevron_right_rounded, color: _Ui.muted, size: 18)])));
}

class _Pill extends StatelessWidget {
  const _Pill({required this.text, required this.color});
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4), decoration: BoxDecoration(color: color.withValues(alpha: .13), borderRadius: BorderRadius.circular(99), border: Border.all(color: color.withValues(alpha: .28))), child: Text(text, style: TextStyle(color: color, fontSize: 9, fontWeight: FontWeight.w900)));
}

class _InfoBanner extends StatelessWidget {
  const _InfoBanner({required this.text});
  final String text;
  @override
  Widget build(BuildContext context) => Container(width: double.infinity, padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: _Ui.cyan.withValues(alpha: .07), borderRadius: BorderRadius.circular(14), border: Border.all(color: _Ui.cyan.withValues(alpha: .20))), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [const Icon(Icons.info_outline_rounded, color: _Ui.cyan, size: 18), const SizedBox(width: 8), Expanded(child: Text(text, style: const TextStyle(color: _Ui.muted, fontSize: 11, height: 1.35)))]));
}

class _EmptyCard extends StatelessWidget {
  const _EmptyCard({required this.icon, required this.title, required this.message});
  final IconData icon;
  final String title;
  final String message;
  @override
  Widget build(BuildContext context) => _ReviewCard(child: Center(child: Padding(padding: const EdgeInsets.symmetric(vertical: 20), child: Column(children: [Icon(icon, color: _Ui.muted, size: 34), const SizedBox(height: 10), Text(title, style: const TextStyle(fontWeight: FontWeight.w900)), const SizedBox(height: 5), Text(message, textAlign: TextAlign.center, style: const TextStyle(color: _Ui.muted, fontSize: 11))]))));
}

class _Ui {
  static const cyan = Color(0xFF13DFF3);
  static const violet = Color(0xFF7148FF);
  static const magenta = Color(0xFFFF36D1);
  static const green = Color(0xFF29F39A);
  static const red = Color(0xFFFF5B6E);
  static const muted = Color(0xFF8DA4C7);

  static InputDecoration input(String label, {IconData? prefix}) => InputDecoration(
        labelText: label,
        prefixIcon: prefix == null ? null : Icon(prefix, color: muted, size: 19),
        filled: true,
        fillColor: const Color(0xFF071529),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: Color(0xFF183050))),
        enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: Color(0xFF183050))),
        focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: cyan)),
      );

  static String money(double value) => value >= 10000 ? value.toStringAsFixed(2) : '₹${value.toStringAsFixed(2)}';
  static String percent(double value) => '${value >= 0 ? '+' : ''}${value.toStringAsFixed(2)}%';
}
