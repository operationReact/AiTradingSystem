import 'package:flutter/material.dart';

/// Standalone, backend-free LIVE trading review flow.
///
/// This file is intentionally isolated from production broker/order services.
/// It exists only to let product reviewers exercise the full LIVE UI with
/// deterministic local mock data. No broker API or real-money order is called.
class MockLiveReviewHost extends StatefulWidget {
  const MockLiveReviewHost({super.key, required this.paperApp});

  final Widget paperApp;

  @override
  State<MockLiveReviewHost> createState() => _MockLiveReviewHostState();
}

class _MockLiveReviewHostState extends State<MockLiveReviewHost> {
  bool _livePreview = false;

  @override
  Widget build(BuildContext context) {
    if (_livePreview) {
      return MockLiveTradingFlow(
        onExitToPaper: () => setState(() => _livePreview = false),
      );
    }

    return Stack(
      children: [
        widget.paperApp,
        Positioned(
          right: 14,
          bottom: 82,
          child: SafeArea(
            child: Material(
              color: Colors.transparent,
              child: InkWell(
                key: const Key('open-live-preview'),
                onTap: () => setState(() => _livePreview = true),
                borderRadius: BorderRadius.circular(999),
                child: Ink(
                  padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 9),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      colors: [Color(0xFFFF3C78), Color(0xFF8B4DFF)],
                    ),
                    borderRadius: BorderRadius.circular(999),
                    boxShadow: const [
                      BoxShadow(color: Color(0x553C0B49), blurRadius: 18),
                    ],
                  ),
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.bolt_rounded, size: 16, color: Colors.white),
                      SizedBox(width: 5),
                      Text(
                        'LIVE Preview',
                        style: TextStyle(fontSize: 11, fontWeight: FontWeight.w900),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

enum MockLiveScreen {
  activation,
  funds,
  ticket,
  review,
  execution,
  position,
  emergency,
  session,
}

class MockLiveTradingStore extends ChangeNotifier {
  void refresh() => notifyListeners();

  bool acknowledgementAccepted = false;
  bool liveEnabled = false;
  bool brokerConnected = true;
  bool sessionActive = true;
  bool newLiveOrdersBlocked = false;
  bool cancelPendingOnKill = true;
  bool exitPositionsOnKill = false;
  bool stopLossEnabled = true;
  bool targetEnabled = true;

  String side = 'BUY';
  String orderType = 'LIMIT';
  int quantity = 10;
  double orderPrice = 2948;
  double stopLoss = 2918;
  double target = 3032;
  double lastPrice = 2956.40;
  String orderStatus = 'Submitted';

  double availableCash = 485000;
  double usedMargin = 125000;
  double availableMargin = 360000;
  double holdingsValue = 642380;
  double unrealizedPnl = 3840;

  final List<MockLiveHolding> holdings = const [
    MockLiveHolding(symbol: 'RELIANCE', quantity: 40, value: 118256, pnl: 2840),
    MockLiveHolding(symbol: 'TCS', quantity: 25, value: 96905, pnl: 1960),
    MockLiveHolding(symbol: 'HDFCBANK', quantity: 55, value: 90327, pnl: -620),
  ];

  double get estimatedValue => quantity * orderPrice;
  double get estimatedCharges => estimatedValue * 0.00042 + 24.50;
  double get fundsAfterOrder => availableMargin - estimatedValue * 0.20 - estimatedCharges;
  double get positionPnl => quantity * (lastPrice - orderPrice);
  double get positionPnlPercent => orderPrice == 0 ? 0 : (lastPrice - orderPrice) / orderPrice * 100;

  void toggleAcknowledgement(bool value) {
    acknowledgementAccepted = value;
    notifyListeners();
  }

  void enableLive() {
    if (!acknowledgementAccepted || !brokerConnected || !sessionActive) return;
    liveEnabled = true;
    notifyListeners();
  }

  void setSide(String value) {
    side = value;
    notifyListeners();
  }

  void setOrderType(String value) {
    orderType = value;
    notifyListeners();
  }

  void changeQuantity(int delta) {
    quantity = (quantity + delta).clamp(1, 500);
    notifyListeners();
  }

  void setStatus(String status) {
    orderStatus = status;
    notifyListeners();
  }

  void simulatePriceMove() {
    lastPrice = lastPrice < 2978 ? lastPrice + 12.40 : 2956.40;
    notifyListeners();
  }

  void expireSession() {
    sessionActive = false;
    liveEnabled = false;
    notifyListeners();
  }

  void reconnectSession() {
    brokerConnected = true;
    sessionActive = true;
    notifyListeners();
  }

  void activateKillSwitch() {
    newLiveOrdersBlocked = true;
    liveEnabled = false;
    if (exitPositionsOnKill) unrealizedPnl = 0;
    notifyListeners();
  }

  void resetKillSwitch() {
    newLiveOrdersBlocked = false;
    notifyListeners();
  }
}

class MockLiveHolding {
  const MockLiveHolding({
    required this.symbol,
    required this.quantity,
    required this.value,
    required this.pnl,
  });

  final String symbol;
  final int quantity;
  final double value;
  final double pnl;
}

class MockLiveTradingFlow extends StatefulWidget {
  const MockLiveTradingFlow({super.key, required this.onExitToPaper});

  final VoidCallback onExitToPaper;

  @override
  State<MockLiveTradingFlow> createState() => _MockLiveTradingFlowState();
}

class _MockLiveTradingFlowState extends State<MockLiveTradingFlow> {
  final MockLiveTradingStore _store = MockLiveTradingStore();
  MockLiveScreen _screen = MockLiveScreen.activation;

  @override
  void dispose() {
    _store.dispose();
    super.dispose();
  }

  void _open(MockLiveScreen screen) => setState(() => _screen = screen);

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _store,
      builder: (context, _) {
        return Scaffold(
          key: const Key('mock-live-flow'),
          backgroundColor: _LiveUi.bg,
          body: SafeArea(
            child: switch (_screen) {
              MockLiveScreen.activation => _LiveActivationPage(
                  store: _store,
                  onPaper: widget.onExitToPaper,
                  onContinue: () => _open(MockLiveScreen.funds),
                  onSession: () => _open(MockLiveScreen.session),
                ),
              MockLiveScreen.funds => _LiveFundsPage(
                  store: _store,
                  onBack: () => _open(MockLiveScreen.activation),
                  onTrade: () => _open(MockLiveScreen.ticket),
                  onEmergency: () => _open(MockLiveScreen.emergency),
                  onSession: () => _open(MockLiveScreen.session),
                ),
              MockLiveScreen.ticket => _LiveTicketPage(
                  store: _store,
                  onBack: () => _open(MockLiveScreen.funds),
                  onContinue: () => _open(MockLiveScreen.review),
                ),
              MockLiveScreen.review => _LiveOrderReviewPage(
                  store: _store,
                  onBack: () => _open(MockLiveScreen.ticket),
                  onExecute: () {
                    _store.setStatus('Submitted');
                    _open(MockLiveScreen.execution);
                  },
                ),
              MockLiveScreen.execution => _LiveExecutionPage(
                  store: _store,
                  onBack: () => _open(MockLiveScreen.review),
                  onPosition: () => _open(MockLiveScreen.position),
                  onSession: () => _open(MockLiveScreen.session),
                ),
              MockLiveScreen.position => _LivePositionPage(
                  store: _store,
                  onBack: () => _open(MockLiveScreen.execution),
                  onEmergency: () => _open(MockLiveScreen.emergency),
                ),
              MockLiveScreen.emergency => _LiveEmergencyPage(
                  store: _store,
                  onBack: () => _open(MockLiveScreen.funds),
                ),
              MockLiveScreen.session => _LiveSessionPage(
                  store: _store,
                  onBack: () => _open(MockLiveScreen.funds),
                ),
            },
          ),
        );
      },
    );
  }
}

class _LiveActivationPage extends StatelessWidget {
  const _LiveActivationPage({
    required this.store,
    required this.onPaper,
    required this.onContinue,
    required this.onSession,
  });

  final MockLiveTradingStore store;
  final VoidCallback onPaper;
  final VoidCallback onContinue;
  final VoidCallback onSession;

  @override
  Widget build(BuildContext context) {
    return _LiveScroll(
      children: [
        _LiveHeader(title: 'LIVE Trading', onBack: onPaper, badge: 'REAL MONEY UI'),
        const SizedBox(height: 16),
        _LiveHero(
          icon: Icons.bolt_rounded,
          title: 'Activate LIVE Trading',
          subtitle: 'Review the real-money experience safely with local mock broker data. No live order is sent from this build.',
        ),
        const SizedBox(height: 14),
        _LiveCard(
          child: Column(
            children: [
              _StatusRow(label: 'Angel One', value: store.brokerConnected ? 'Connected' : 'Disconnected', good: store.brokerConnected),
              const Divider(height: 22),
              _StatusRow(label: 'Broker session', value: store.sessionActive ? 'Active' : 'Expired', good: store.sessionActive),
              const Divider(height: 22),
              const _StatusRow(label: 'Risk engine', value: 'Ready', good: true),
              const Divider(height: 22),
              const _StatusRow(label: 'Trading mode', value: 'PAPER remains default', good: true),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _LiveCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Before enabling LIVE', style: _LiveUi.cardTitle),
              const SizedBox(height: 12),
              const _CheckLine(text: 'Orders can use real broker funds when backend LIVE integration is enabled.'),
              const _CheckLine(text: 'Every LIVE order must pass the risk engine before submission.'),
              const _CheckLine(text: 'Emergency controls can block new trades and cancel/exit positions.'),
              const SizedBox(height: 8),
              CheckboxListTile(
                key: const Key('live-acknowledgement'),
                value: store.acknowledgementAccepted,
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                title: const Text('I understand this is the LIVE trading path.'),
                onChanged: (value) => store.toggleAcknowledgement(value ?? false),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        _LivePrimaryButton(
          key: const Key('enable-live-button'),
          label: store.liveEnabled ? 'LIVE Trading Enabled' : 'Enable LIVE Trading',
          icon: store.liveEnabled ? Icons.check_circle_rounded : Icons.lock_open_rounded,
          enabled: (store.acknowledgementAccepted && store.sessionActive && store.brokerConnected) || store.liveEnabled,
          onTap: () {
            if (!store.liveEnabled) store.enableLive();
            onContinue();
          },
        ),
        const SizedBox(height: 10),
        _LiveOutlineButton(label: 'Broker Session & Re-authentication', icon: Icons.key_rounded, onTap: onSession),
        const SizedBox(height: 10),
        TextButton(onPressed: onPaper, child: const Text('Return to PAPER review')),
      ],
    );
  }
}

class _LiveFundsPage extends StatelessWidget {
  const _LiveFundsPage({
    required this.store,
    required this.onBack,
    required this.onTrade,
    required this.onEmergency,
    required this.onSession,
  });

  final MockLiveTradingStore store;
  final VoidCallback onBack;
  final VoidCallback onTrade;
  final VoidCallback onEmergency;
  final VoidCallback onSession;

  @override
  Widget build(BuildContext context) {
    return _LiveScroll(
      children: [
        _LiveHeader(title: 'Broker & Funds', onBack: onBack, badge: 'LIVE MOCK'),
        const SizedBox(height: 14),
        _LiveCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const CircleAvatar(backgroundColor: Color(0xFF14355C), child: Icon(Icons.account_balance_rounded, color: _LiveUi.cyan)),
                  const SizedBox(width: 10),
                  const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('Angel One', style: _LiveUi.cardTitle), Text('Account •••• 4821', style: _LiveUi.muted)])),
                  _LiveBadge(text: store.sessionActive ? 'SYNCED' : 'SESSION EXPIRED', good: store.sessionActive),
                ],
              ),
              const SizedBox(height: 14),
              _MoneyRow(label: 'Available cash', value: store.availableCash),
              _MoneyRow(label: 'Used margin', value: store.usedMargin),
              _MoneyRow(label: 'Available margin', value: store.availableMargin, highlight: true),
              _MoneyRow(label: 'Holdings value', value: store.holdingsValue),
              const SizedBox(height: 6),
              const Text('Last synced just now · Mock broker snapshot', style: _LiveUi.muted),
            ],
          ),
        ),
        const SizedBox(height: 14),
        const Text('Holdings', style: _LiveUi.sectionTitle),
        const SizedBox(height: 8),
        ...store.holdings.map((holding) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: _LiveCard(
                child: Row(
                  children: [
                    Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(holding.symbol, style: _LiveUi.cardTitle), const SizedBox(height: 3), Text('${holding.quantity} qty · ₹${_LiveUi.money(holding.value)}', style: _LiveUi.muted)])),
                    Text('${holding.pnl >= 0 ? '+' : ''}₹${_LiveUi.money(holding.pnl)}', style: TextStyle(fontWeight: FontWeight.w900, color: holding.pnl >= 0 ? _LiveUi.green : _LiveUi.red)),
                  ],
                ),
              ),
            )),
        const SizedBox(height: 8),
        _LivePrimaryButton(key: const Key('open-live-ticket'), label: 'Place LIVE Trade', icon: Icons.bolt_rounded, enabled: store.liveEnabled && store.sessionActive && !store.newLiveOrdersBlocked, onTap: onTrade),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: _LiveOutlineButton(label: 'Session', icon: Icons.key_rounded, onTap: onSession)),
            const SizedBox(width: 8),
            Expanded(child: _LiveOutlineButton(label: 'Emergency', icon: Icons.warning_amber_rounded, danger: true, onTap: onEmergency)),
          ],
        ),
      ],
    );
  }
}

class _LiveTicketPage extends StatelessWidget {
  const _LiveTicketPage({required this.store, required this.onBack, required this.onContinue});

  final MockLiveTradingStore store;
  final VoidCallback onBack;
  final VoidCallback onContinue;

  @override
  Widget build(BuildContext context) {
    return _LiveScroll(
      children: [
        _LiveHeader(title: 'LIVE Trade Ticket', onBack: onBack, badge: 'LIVE'),
        const SizedBox(height: 14),
        _LiveCard(
          child: Row(
            children: [
              const CircleAvatar(backgroundColor: Color(0xFF2B1A51), child: Text('R', style: TextStyle(fontWeight: FontWeight.w900))),
              const SizedBox(width: 10),
              const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('RELIANCE', style: _LiveUi.cardTitle), Text('NSE · Reliance Industries Ltd.', style: _LiveUi.muted)])),
              Column(crossAxisAlignment: CrossAxisAlignment.end, children: [Text('₹${_LiveUi.money(store.lastPrice)}', style: _LiveUi.cardTitle), const Text('+1.46%', style: TextStyle(color: _LiveUi.green, fontSize: 12, fontWeight: FontWeight.w800))]),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _SegmentedChoice(values: const ['BUY', 'SELL'], selected: store.side, onSelected: store.setSide),
        const SizedBox(height: 12),
        _LiveCard(
          child: Column(
            children: [
              _ChoiceRow(label: 'Order type', values: const ['MARKET', 'LIMIT'], selected: store.orderType, onSelected: store.setOrderType),
              const Divider(height: 24),
              Row(
                children: [
                  const Expanded(child: Text('Quantity', style: _LiveUi.muted)),
                  _RoundIcon(icon: Icons.remove_rounded, onTap: () => store.changeQuantity(-1)),
                  SizedBox(width: 56, child: Text('${store.quantity}', textAlign: TextAlign.center, style: _LiveUi.cardTitle)),
                  _RoundIcon(icon: Icons.add_rounded, onTap: () => store.changeQuantity(1)),
                ],
              ),
              const Divider(height: 24),
              _ValueLine(label: 'Price', value: '₹${_LiveUi.money(store.orderPrice)}'),
              _ValueLine(label: 'Stop loss', value: store.stopLossEnabled ? '₹${_LiveUi.money(store.stopLoss)}' : 'Off'),
              _ValueLine(label: 'Target', value: store.targetEnabled ? '₹${_LiveUi.money(store.target)}' : 'Off'),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _LiveCard(
          child: Column(children: [
            _ValueLine(label: 'Order value', value: '₹${_LiveUi.money(store.estimatedValue)}'),
            _ValueLine(label: 'Est. charges', value: '₹${_LiveUi.money(store.estimatedCharges)}'),
            _ValueLine(label: 'Available margin', value: '₹${_LiveUi.money(store.availableMargin)}', highlight: true),
          ]),
        ),
        const SizedBox(height: 14),
        _LivePrimaryButton(key: const Key('review-live-order'), label: 'Review LIVE Order', icon: Icons.arrow_forward_rounded, enabled: store.liveEnabled && store.sessionActive && !store.newLiveOrdersBlocked, onTap: onContinue),
      ],
    );
  }
}

class _LiveOrderReviewPage extends StatelessWidget {
  const _LiveOrderReviewPage({required this.store, required this.onBack, required this.onExecute});

  final MockLiveTradingStore store;
  final VoidCallback onBack;
  final VoidCallback onExecute;

  @override
  Widget build(BuildContext context) {
    final riskPct = store.estimatedValue == 0 ? 0 : (store.orderPrice - store.stopLoss).abs() * store.quantity / store.availableCash * 100;
    return _LiveScroll(
      children: [
        _LiveHeader(title: 'LIVE Order Review', onBack: onBack, badge: 'FINAL CHECK'),
        const SizedBox(height: 14),
        _LiveHero(icon: Icons.verified_user_rounded, title: '${store.side} RELIANCE · ${store.quantity} qty', subtitle: 'Review funds, charges and risk before the mock LIVE submission.'),
        const SizedBox(height: 12),
        _LiveCard(
          child: Column(children: [
            _ValueLine(label: 'Product', value: 'CNC / Delivery'),
            _ValueLine(label: 'Order type', value: store.orderType),
            _ValueLine(label: 'Price', value: '₹${_LiveUi.money(store.orderPrice)}'),
            _ValueLine(label: 'Order value', value: '₹${_LiveUi.money(store.estimatedValue)}'),
            _ValueLine(label: 'Est. broker/exchange charges', value: '₹${_LiveUi.money(store.estimatedCharges)}'),
            _ValueLine(label: 'Margin after trade', value: '₹${_LiveUi.money(store.fundsAfterOrder)}', highlight: true),
          ]),
        ),
        const SizedBox(height: 12),
        _LiveCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Risk Engine', style: _LiveUi.cardTitle),
            const SizedBox(height: 10),
            const _RiskPass(label: 'Position size', value: 'Within configured limit'),
            const _RiskPass(label: 'Daily loss limit', value: '₹10,000 remaining'),
            const _RiskPass(label: 'Sector exposure', value: 'Oil & Gas 16% < 25%'),
            _RiskPass(label: 'Risk to stop', value: '${riskPct.toStringAsFixed(2)}% of available cash'),
          ]),
        ),
        const SizedBox(height: 10),
        const _WarningBox(text: 'This button is a UI mock only. No live broker request is made by this APK.'),
        const SizedBox(height: 14),
        _LivePrimaryButton(key: const Key('place-live-order'), label: 'PLACE LIVE ORDER', icon: Icons.bolt_rounded, enabled: store.liveEnabled && store.sessionActive, onTap: onExecute, danger: true),
      ],
    );
  }
}

class _LiveExecutionPage extends StatelessWidget {
  const _LiveExecutionPage({required this.store, required this.onBack, required this.onPosition, required this.onSession});

  final MockLiveTradingStore store;
  final VoidCallback onBack;
  final VoidCallback onPosition;
  final VoidCallback onSession;

  @override
  Widget build(BuildContext context) {
    final filled = store.orderStatus == 'Filled';
    final rejected = store.orderStatus == 'Rejected';
    return _LiveScroll(
      children: [
        _LiveHeader(title: 'LIVE Order Status', onBack: onBack, badge: 'BROKER MOCK'),
        const SizedBox(height: 14),
        _LiveHero(
          icon: rejected ? Icons.cancel_rounded : filled ? Icons.check_circle_rounded : Icons.sync_rounded,
          title: rejected ? 'Order Rejected' : filled ? 'Order Filled' : store.orderStatus,
          subtitle: rejected ? 'Mock rejection: RMS margin/risk response.' : filled ? 'Mock broker execution completed at ₹${_LiveUi.money(store.orderPrice)}.' : 'Mock broker order is progressing through the exchange lifecycle.',
          positive: !rejected,
        ),
        const SizedBox(height: 12),
        _LiveCard(
          child: Column(children: [
            const _ValueLine(label: 'Broker order ID', value: 'A1-261101-009842'),
            const _ValueLine(label: 'Exchange order ID', value: '11012600043920'),
            _ValueLine(label: 'Symbol', value: 'RELIANCE · ${store.side}'),
            _ValueLine(label: 'Quantity', value: '${store.quantity}'),
            _ValueLine(label: 'Average price', value: filled ? '₹${_LiveUi.money(store.orderPrice)}' : '—'),
          ]),
        ),
        const SizedBox(height: 12),
        const Text('Preview execution states', style: _LiveUi.sectionTitle),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: ['Submitted', 'Pending', 'Partially Filled', 'Filled', 'Rejected']
              .map((status) => ChoiceChip(label: Text(status), selected: store.orderStatus == status, onSelected: (_) => store.setStatus(status)))
              .toList(),
        ),
        const SizedBox(height: 14),
        if (filled) _LivePrimaryButton(key: const Key('view-live-position'), label: 'View LIVE Position', icon: Icons.show_chart_rounded, enabled: true, onTap: onPosition),
        if (rejected) _LiveOutlineButton(label: 'Review Broker Session', icon: Icons.key_rounded, onTap: onSession),
      ],
    );
  }
}

class _LivePositionPage extends StatelessWidget {
  const _LivePositionPage({required this.store, required this.onBack, required this.onEmergency});

  final MockLiveTradingStore store;
  final VoidCallback onBack;
  final VoidCallback onEmergency;

  @override
  Widget build(BuildContext context) {
    final pnl = store.positionPnl;
    return _LiveScroll(
      children: [
        _LiveHeader(title: 'LIVE Position', onBack: onBack, badge: 'LIVE'),
        const SizedBox(height: 14),
        _LiveCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('RELIANCE', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
            const Text('NSE · CNC · Mock position', style: _LiveUi.muted),
            const SizedBox(height: 14),
            Text('${pnl >= 0 ? '+' : ''}₹${_LiveUi.money(pnl)}', style: TextStyle(fontSize: 28, fontWeight: FontWeight.w900, color: pnl >= 0 ? _LiveUi.green : _LiveUi.red)),
            Text('${store.positionPnlPercent >= 0 ? '+' : ''}${store.positionPnlPercent.toStringAsFixed(2)}% unrealized', style: _LiveUi.muted),
            const SizedBox(height: 14),
            _ValueLine(label: 'Quantity', value: '${store.quantity}'),
            _ValueLine(label: 'Average entry', value: '₹${_LiveUi.money(store.orderPrice)}'),
            _ValueLine(label: 'Current price', value: '₹${_LiveUi.money(store.lastPrice)}'),
            _ValueLine(label: 'Margin used', value: '₹${_LiveUi.money(store.estimatedValue * 0.20)}'),
          ]),
        ),
        const SizedBox(height: 12),
        _LiveCard(
          child: Column(children: [
            SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Stop Loss'), subtitle: Text('₹${_LiveUi.money(store.stopLoss)}', style: _LiveUi.muted), value: store.stopLossEnabled, onChanged: (v) { store.stopLossEnabled = v; store.refresh(); }),
            SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Target'), subtitle: Text('₹${_LiveUi.money(store.target)}', style: _LiveUi.muted), value: store.targetEnabled, onChanged: (v) { store.targetEnabled = v; store.refresh(); }),
          ]),
        ),
        const SizedBox(height: 12),
        _LiveOutlineButton(label: 'Simulate Price Move', icon: Icons.auto_graph_rounded, onTap: store.simulatePriceMove),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(child: _LiveOutlineButton(label: 'Partial Exit', icon: Icons.call_split_rounded, onTap: () => ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Mock partial exit staged.'))))),
          const SizedBox(width: 8),
          Expanded(child: _LiveOutlineButton(label: 'Exit Position', icon: Icons.logout_rounded, danger: true, onTap: () => ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Mock full exit confirmed.'))))),
        ]),
        const SizedBox(height: 10),
        _LiveOutlineButton(label: 'Emergency / Kill Switch', icon: Icons.warning_amber_rounded, danger: true, onTap: onEmergency),
      ],
    );
  }
}

class _LiveEmergencyPage extends StatelessWidget {
  const _LiveEmergencyPage({required this.store, required this.onBack});

  final MockLiveTradingStore store;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return _LiveScroll(
      children: [
        _LiveHeader(title: 'Emergency Controls', onBack: onBack, badge: 'SAFETY'),
        const SizedBox(height: 14),
        _LiveHero(icon: Icons.shield_rounded, title: store.newLiveOrdersBlocked ? 'LIVE Trading Locked' : 'Kill Switch', subtitle: 'A dedicated safety surface for blocking new LIVE trades and handling outstanding exposure.', positive: !store.newLiveOrdersBlocked),
        const SizedBox(height: 12),
        _LiveCard(
          child: Column(children: [
            SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Cancel pending LIVE orders'), value: store.cancelPendingOnKill, onChanged: (v) { store.cancelPendingOnKill = v; store.refresh(); }),
            SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Exit all open LIVE positions'), subtitle: const Text('Mock UI only', style: _LiveUi.muted), value: store.exitPositionsOnKill, onChanged: (v) { store.exitPositionsOnKill = v; store.refresh(); }),
          ]),
        ),
        const SizedBox(height: 12),
        const _WarningBox(text: 'In production this action must require strong confirmation and backend acknowledgement before showing success.'),
        const SizedBox(height: 14),
        if (!store.newLiveOrdersBlocked)
          _LivePrimaryButton(key: const Key('activate-kill-switch'), label: 'ACTIVATE KILL SWITCH', icon: Icons.block_rounded, enabled: true, onTap: store.activateKillSwitch, danger: true)
        else
          _LivePrimaryButton(label: 'Reset Mock Lock', icon: Icons.restart_alt_rounded, enabled: true, onTap: store.resetKillSwitch),
      ],
    );
  }
}

class _LiveSessionPage extends StatelessWidget {
  const _LiveSessionPage({required this.store, required this.onBack});

  final MockLiveTradingStore store;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return _LiveScroll(
      children: [
        _LiveHeader(title: 'Broker Session', onBack: onBack, badge: 'ANGEL ONE'),
        const SizedBox(height: 14),
        _LiveHero(icon: store.sessionActive ? Icons.verified_rounded : Icons.lock_clock_rounded, title: store.sessionActive ? 'Session Active' : 'Session Expired', subtitle: store.sessionActive ? 'Mock Angel One authorization is valid for the UI review.' : 'LIVE order placement is unavailable until the broker session is re-authorized.', positive: store.sessionActive),
        const SizedBox(height: 12),
        _LiveCard(
          child: const Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Permissions', style: _LiveUi.cardTitle),
            SizedBox(height: 10),
            _CheckLine(text: 'Read funds, holdings and positions'),
            _CheckLine(text: 'Place and manage authorized orders'),
            _CheckLine(text: 'No broker credentials stored in the app'),
            _CheckLine(text: 'Session token must be refreshed when expired'),
          ]),
        ),
        const SizedBox(height: 12),
        if (store.sessionActive)
          _LiveOutlineButton(key: const Key('expire-live-session'), label: 'Simulate Session Expiry', icon: Icons.timer_off_rounded, onTap: store.expireSession)
        else
          _LivePrimaryButton(key: const Key('reconnect-live-session'), label: 'Reconnect Angel One', icon: Icons.link_rounded, enabled: true, onTap: store.reconnectSession),
        const SizedBox(height: 10),
        const Text('Production behavior will use the real broker authorization/session flow. This review build changes local mock state only.', style: _LiveUi.muted),
      ],
    );
  }
}

class _LiveScroll extends StatelessWidget {
  const _LiveScroll({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 28),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
    );
  }
}

class _LiveHeader extends StatelessWidget {
  const _LiveHeader({required this.title, required this.onBack, required this.badge});

  final String title;
  final VoidCallback onBack;
  final String badge;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        IconButton(onPressed: onBack, icon: const Icon(Icons.arrow_back_rounded)),
        Expanded(child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900))),
        const SizedBox(width: 8),
        _LiveBadge(text: badge, good: false),
      ],
    );
  }
}

class _LiveHero extends StatelessWidget {
  const _LiveHero({required this.icon, required this.title, required this.subtitle, this.positive = true});

  final IconData icon;
  final String title;
  final String subtitle;
  final bool positive;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: LinearGradient(colors: positive ? const [Color(0xFF081E32), Color(0xFF17113C)] : const [Color(0xFF341322), Color(0xFF17113C)]),
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: positive ? const Color(0xFF155D78) : const Color(0xFF80344E)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(icon, size: 34, color: positive ? _LiveUi.cyan : _LiveUi.red),
        const SizedBox(height: 12),
        Text(title, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
        const SizedBox(height: 5),
        Text(subtitle, style: _LiveUi.muted.copyWith(height: 1.4)),
      ]),
    );
  }
}

class _LiveCard extends StatelessWidget {
  const _LiveCard({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: _LiveUi.card,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: const BorderSide(color: _LiveUi.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(15),
        child: child,
      ),
    );
  }
}

class _LivePrimaryButton extends StatelessWidget {
  const _LivePrimaryButton({super.key, required this.label, required this.icon, required this.enabled, required this.onTap, this.danger = false});

  final String label;
  final IconData icon;
  final bool enabled;
  final VoidCallback onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: enabled ? 1 : 0.45,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: enabled ? onTap : null,
          borderRadius: BorderRadius.circular(16),
          child: Ink(
            height: 52,
            decoration: BoxDecoration(
              gradient: danger ? const LinearGradient(colors: [Color(0xFFFF3C78), Color(0xFF8B4DFF)]) : const LinearGradient(colors: [Color(0xFF12DBE8), Color(0xFF5775FF)]),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [Icon(icon, color: Colors.white), const SizedBox(width: 8), Text(label, style: const TextStyle(fontWeight: FontWeight.w900, color: Colors.white))]),
          ),
        ),
      ),
    );
  }
}

class _LiveOutlineButton extends StatelessWidget {
  const _LiveOutlineButton({super.key, required this.label, required this.icon, required this.onTap, this.danger = false});

  final String label;
  final IconData icon;
  final VoidCallback onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: onTap,
      icon: Icon(icon, size: 18),
      label: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis),
      style: OutlinedButton.styleFrom(
        foregroundColor: danger ? _LiveUi.red : _LiveUi.cyan,
        side: BorderSide(color: danger ? const Color(0xFF80344E) : const Color(0xFF175C75)),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 14),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(15)),
      ),
    );
  }
}

class _LiveBadge extends StatelessWidget {
  const _LiveBadge({required this.text, required this.good});

  final String text;
  final bool good;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(color: good ? const Color(0xFF083424) : const Color(0xFF35133F), borderRadius: BorderRadius.circular(999), border: Border.all(color: good ? const Color(0xFF176843) : const Color(0xFF713081))),
      child: Text(text, style: TextStyle(fontSize: 9, fontWeight: FontWeight.w900, color: good ? _LiveUi.green : const Color(0xFFF08CFF))),
    );
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({required this.label, required this.value, required this.good});

  final String label;
  final String value;
  final bool good;

  @override
  Widget build(BuildContext context) {
    return Row(children: [Expanded(child: Text(label, style: _LiveUi.muted)), Icon(good ? Icons.check_circle_rounded : Icons.error_rounded, size: 16, color: good ? _LiveUi.green : _LiveUi.red), const SizedBox(width: 6), Flexible(child: Text(value, textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w800)))]);
  }
}

class _CheckLine extends StatelessWidget {
  const _CheckLine({required this.text});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(padding: const EdgeInsets.only(bottom: 9), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [const Icon(Icons.check_circle_outline_rounded, size: 17, color: _LiveUi.green), const SizedBox(width: 8), Expanded(child: Text(text, style: _LiveUi.muted.copyWith(height: 1.35)))]));
}

class _MoneyRow extends StatelessWidget {
  const _MoneyRow({required this.label, required this.value, this.highlight = false});
  final String label;
  final double value;
  final bool highlight;
  @override
  Widget build(BuildContext context) => _ValueLine(label: label, value: '₹${_LiveUi.money(value)}', highlight: highlight);
}

class _ValueLine extends StatelessWidget {
  const _ValueLine({required this.label, required this.value, this.highlight = false});
  final String label;
  final String value;
  final bool highlight;
  @override
  Widget build(BuildContext context) => Padding(padding: const EdgeInsets.symmetric(vertical: 6), child: Row(children: [Expanded(child: Text(label, style: _LiveUi.muted)), const SizedBox(width: 10), Flexible(child: Text(value, textAlign: TextAlign.right, style: TextStyle(fontWeight: FontWeight.w900, color: highlight ? _LiveUi.cyan : Colors.white)))]));
}

class _RiskPass extends StatelessWidget {
  const _RiskPass({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => Padding(padding: const EdgeInsets.only(bottom: 9), child: Row(children: [const Icon(Icons.check_circle_rounded, size: 17, color: _LiveUi.green), const SizedBox(width: 8), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label, style: const TextStyle(fontWeight: FontWeight.w800)), Text(value, style: _LiveUi.muted)]))]));
}

class _WarningBox extends StatelessWidget {
  const _WarningBox({required this.text});
  final String text;
  @override
  Widget build(BuildContext context) => Container(padding: const EdgeInsets.all(13), decoration: BoxDecoration(color: const Color(0xFF2F1820), borderRadius: BorderRadius.circular(14), border: Border.all(color: const Color(0xFF713344))), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [const Icon(Icons.warning_amber_rounded, color: Color(0xFFFFB35B), size: 20), const SizedBox(width: 8), Expanded(child: Text(text, style: const TextStyle(color: Color(0xFFFFD8B0), height: 1.35)))]));
}

class _SegmentedChoice extends StatelessWidget {
  const _SegmentedChoice({required this.values, required this.selected, required this.onSelected});
  final List<String> values;
  final String selected;
  final ValueChanged<String> onSelected;
  @override
  Widget build(BuildContext context) => Row(children: values.map((value) => Expanded(child: Padding(padding: EdgeInsets.only(right: value == values.last ? 0 : 8), child: ChoiceChip(label: SizedBox(width: double.infinity, child: Text(value, textAlign: TextAlign.center)), selected: selected == value, onSelected: (_) => onSelected(value))))).toList());
}

class _ChoiceRow extends StatelessWidget {
  const _ChoiceRow({required this.label, required this.values, required this.selected, required this.onSelected});
  final String label;
  final List<String> values;
  final String selected;
  final ValueChanged<String> onSelected;
  @override
  Widget build(BuildContext context) => Row(children: [Expanded(child: Text(label, style: _LiveUi.muted)), ...values.map((value) => Padding(padding: const EdgeInsets.only(left: 6), child: ChoiceChip(label: Text(value), selected: selected == value, onSelected: (_) => onSelected(value))))]);
}

class _RoundIcon extends StatelessWidget {
  const _RoundIcon({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => IconButton(onPressed: onTap, icon: Icon(icon, size: 18), style: IconButton.styleFrom(backgroundColor: const Color(0xFF11233A), minimumSize: const Size(36, 36)));
}

class _LiveUi {
  static const bg = Color(0xFF020817);
  static const card = Color(0xFF08162A);
  static const border = Color(0xFF17304D);
  static const muted = TextStyle(color: Color(0xFF8FA4BD), fontSize: 12, height: 1.35);
  static const cardTitle = TextStyle(fontSize: 15, fontWeight: FontWeight.w900);
  static const sectionTitle = TextStyle(fontSize: 16, fontWeight: FontWeight.w900);
  static const cyan = Color(0xFF18E4F2);
  static const green = Color(0xFF4DF5A4);
  static const red = Color(0xFFFF557D);

  static String money(double value) {
    final negative = value < 0;
    final raw = value.abs().toStringAsFixed(2);
    final parts = raw.split('.');
    final digits = parts[0];
    final buffer = StringBuffer();
    for (var i = 0; i < digits.length; i++) {
      if (i > 0 && (digits.length - i) % 3 == 0) buffer.write(',');
      buffer.write(digits[i]);
    }
    return '${negative ? '-' : ''}${buffer.toString()}.${parts[1]}';
  }
}
