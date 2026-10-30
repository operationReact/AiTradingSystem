import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/execution_repository.dart';

enum ExecutionWorkspaceTab { orders, portfolio, approvals, risk }

class ExecutionWorkspacePage extends StatefulWidget {
  const ExecutionWorkspacePage({
    required this.repository,
    required this.initialTab,
    super.key,
  });

  final ExecutionRepository repository;
  final ExecutionWorkspaceTab initialTab;

  @override
  State<ExecutionWorkspacePage> createState() => _ExecutionWorkspacePageState();
}

class _ExecutionWorkspacePageState extends State<ExecutionWorkspacePage> with SingleTickerProviderStateMixin {
  late final TabController _tabs;
  final _instrumentId = TextEditingController();
  final _symbol = TextEditingController();
  final _exchange = TextEditingController(text: 'NSE');
  final _entry = TextEditingController();
  final _stop = TextEditingController();
  final _target = TextEditingController();
  final _strategy = TextEditingController(text: 'operator-review');
  final _haltReason = TextEditingController(text: 'Operator safety halt');

  PortfolioSnapshot? _portfolio;
  List<PaperOrder> _orders = const [];
  List<ApprovalItem> _approvals = const [];
  PositionSizeSuggestion? _suggestion;
  String? _error;
  bool _loading = false;
  bool _acting = false;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 4, vsync: this, initialIndex: widget.initialTab.index);
    _refresh();
  }

  @override
  void dispose() {
    _tabs.dispose();
    _instrumentId.dispose();
    _symbol.dispose();
    _exchange.dispose();
    _entry.dispose();
    _stop.dispose();
    _target.dispose();
    _strategy.dispose();
    _haltReason.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait([
        widget.repository.portfolio(),
        widget.repository.orders(),
        widget.repository.approvals(),
      ]);
      if (!mounted) return;
      setState(() {
        _portfolio = results[0] as PortfolioSnapshot;
        _orders = results[1] as List<PaperOrder>;
        _approvals = results[2] as List<ApprovalItem>;
      });
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } catch (_) {
      if (!mounted) return;
      setState(() => _error = 'EXECUTION_READ_FAILED');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _calculateSize() async {
    final instrumentId = int.tryParse(_instrumentId.text.trim());
    final entry = num.tryParse(_entry.text.trim());
    final stop = num.tryParse(_stop.text.trim());
    final target = num.tryParse(_target.text.trim());
    if (instrumentId == null || entry == null || stop == null || target == null || _symbol.text.trim().isEmpty) {
      setState(() => _error = 'INVALID_POSITION_SIZE_REQUEST');
      return;
    }
    setState(() {
      _acting = true;
      _error = null;
    });
    try {
      final suggestion = await widget.repository.positionSize(
        instrumentId: instrumentId,
        exchange: _exchange.text.trim(),
        symbol: _symbol.text.trim().toUpperCase(),
        entryPrice: entry,
        stopLoss: stop,
        targetPrice: target,
        strategy: _strategy.text.trim(),
      );
      if (!mounted) return;
      setState(() => _suggestion = suggestion);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } finally {
      if (mounted) setState(() => _acting = false);
    }
  }

  Future<void> _placePaperOrder() async {
    final suggestion = _suggestion;
    final entry = num.tryParse(_entry.text.trim());
    final stop = num.tryParse(_stop.text.trim());
    final target = num.tryParse(_target.text.trim());
    if (suggestion == null || suggestion.quantity < 1 || entry == null || stop == null || target == null) {
      setState(() => _error = 'POSITION_SIZE_REQUIRED');
      return;
    }
    setState(() {
      _acting = true;
      _error = null;
    });
    try {
      final key = 'flutter-${DateTime.now().microsecondsSinceEpoch}';
      await widget.repository.createOrder(
        idempotencyKey: key,
        symbol: _symbol.text.trim().toUpperCase(),
        side: 'BUY',
        quantity: suggestion.quantity,
        referencePrice: entry,
        stopLoss: stop,
        target: target,
      );
      await _refresh();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } finally {
      if (mounted) setState(() => _acting = false);
    }
  }

  Future<void> _decide(ApprovalItem approval, String decision) async {
    setState(() {
      _acting = true;
      _error = null;
    });
    try {
      await widget.repository.decideApproval(
        approval: approval,
        decision: decision,
        actor: 'flutter-operator',
        reason: decision == 'approve' ? 'Operator approved after review' : 'Operator rejected after review',
      );
      await _refresh();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } finally {
      if (mounted) setState(() => _acting = false);
    }
  }

  Future<void> _setHalt(bool halted) async {
    setState(() {
      _acting = true;
      _error = null;
    });
    try {
      await widget.repository.setEmergencyStop(
        halted: halted,
        reason: halted ? _haltReason.text.trim() : 'Operator resumed trading',
      );
      await _refresh();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.code);
    } finally {
      if (mounted) setState(() => _acting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('PAPER execution & risk'),
        bottom: TabBar(
          controller: _tabs,
          isScrollable: true,
          tabs: const [
            Tab(text: 'Orders'),
            Tab(text: 'Portfolio'),
            Tab(text: 'Approvals'),
            Tab(text: 'Risk'),
          ],
        ),
        actions: [
          IconButton(onPressed: _loading ? null : _refresh, tooltip: 'Refresh', icon: const Icon(Icons.refresh)),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            if (_error != null)
              MaterialBanner(
                content: Text('Authoritative execution state unavailable: $_error'),
                actions: [TextButton(onPressed: () => setState(() => _error = null), child: const Text('Dismiss'))],
              ),
            Expanded(
              child: TabBarView(
                controller: _tabs,
                children: [
                  _ordersTab(),
                  _portfolioTab(),
                  _approvalsTab(),
                  _riskTab(),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _ordersTab() => ListView(
        key: const ValueKey('orders-screen'),
        padding: const EdgeInsets.all(20),
        children: [
          const _Heading('Position sizing → PAPER order'),
          const SizedBox(height: 8),
          const Text('Position size is advisory and server-owned. Order placement re-evaluates fresh risk state before PAPER execution.'),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  _field(_instrumentId, 'Instrument ID', 'order-instrument-id', numeric: true),
                  _field(_exchange, 'Exchange', 'order-exchange'),
                  _field(_symbol, 'Symbol', 'order-symbol'),
                  _field(_entry, 'Entry', 'order-entry', numeric: true),
                  _field(_stop, 'Stop loss', 'order-stop', numeric: true),
                  _field(_target, 'Target', 'order-target', numeric: true),
                  _field(_strategy, 'Strategy', 'order-strategy'),
                  FilledButton(
                    key: const ValueKey('calculate-position-size'),
                    onPressed: _acting ? null : _calculateSize,
                    child: const Text('Calculate position size'),
                  ),
                ],
              ),
            ),
          ),
          if (_suggestion != null) ...[
            const SizedBox(height: 12),
            Card(
              key: const ValueKey('position-size-suggestion'),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Wrap(
                  spacing: 20,
                  runSpacing: 8,
                  children: [
                    _Metric('Quantity', '${_suggestion!.quantity}'),
                    _Metric('Risk budget', '₹${_suggestion!.riskBudgetAmount}'),
                    _Metric('Estimated risk', '₹${_suggestion!.estimatedRisk}'),
                    _Metric('Limiting rule', _suggestion!.limitingRule),
                    FilledButton.tonal(
                      key: const ValueKey('place-paper-order'),
                      onPressed: _acting || (_portfolio?.newOrdersHalted ?? true) ? null : _placePaperOrder,
                      child: const Text('Place PAPER order'),
                    ),
                  ],
                ),
              ),
            ),
          ],
          const SizedBox(height: 18),
          const _Heading('Order history'),
          const SizedBox(height: 8),
          if (_loading && _orders.isEmpty)
            const Center(child: CircularProgressIndicator())
          else if (_orders.isEmpty)
            const Card(child: Padding(padding: EdgeInsets.all(20), child: Text('No PAPER orders are available.')))
          else
            ..._orders.map((order) => Card(
                  key: ValueKey('paper-order-${order.id}'),
                  child: ListTile(
                    title: Text('${order.side} ${order.symbol} × ${order.quantity}'),
                    subtitle: Text('${order.status}${order.rejectionReason == null ? '' : ' • ${order.rejectionReason}'}'),
                    trailing: Text(order.averageFillPrice == null ? '—' : '₹${order.averageFillPrice}'),
                  ),
                )),
        ],
      );

  Widget _portfolioTab() {
    final p = _portfolio;
    return ListView(
      key: const ValueKey('portfolio-screen'),
      padding: const EdgeInsets.all(20),
      children: [
        const _Heading('Authoritative PAPER portfolio'),
        const SizedBox(height: 12),
        if (_loading && p == null)
          const Center(child: CircularProgressIndicator())
        else if (p == null)
          const Card(child: Padding(padding: EdgeInsets.all(20), child: Text('Portfolio evidence is unavailable.')))
        else
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Wrap(
                spacing: 24,
                runSpacing: 16,
                children: [
                  _Metric('Mode', p.mode),
                  _Metric('Cash', '₹${p.cash}'),
                  _Metric('Invested', '₹${p.invested}'),
                  _Metric('Market value', '₹${p.marketValue}'),
                  _Metric('Equity', '₹${p.equity}'),
                  _Metric('Realized P&L', '₹${p.realizedPnl}'),
                  _Metric('Unrealized P&L', '₹${p.unrealizedPnl}'),
                  _Metric('Exposure', '${p.exposurePercent}%'),
                  _Metric('Open positions', '${p.openPositions}'),
                  _Metric('Cash reconciled', p.cashReconciled ? 'YES' : 'NO'),
                ],
              ),
            ),
          ),
      ],
    );
  }

  Widget _approvalsTab() => ListView(
        key: const ValueKey('approvals-screen'),
        padding: const EdgeInsets.all(20),
        children: [
          const _Heading('Approval queue'),
          const SizedBox(height: 8),
          const Text('Approval decisions send the server-provided row version to prevent stale operator actions.'),
          const SizedBox(height: 12),
          if (_loading && _approvals.isEmpty)
            const Center(child: CircularProgressIndicator())
          else if (_approvals.isEmpty)
            const Card(child: Padding(padding: EdgeInsets.all(20), child: Text('No approval requests are available.')))
          else
            ..._approvals.map((approval) => Card(
                  key: ValueKey('approval-${approval.id}'),
                  child: ListTile(
                    title: Text('${approval.state} • Order ${approval.orderId}'),
                    subtitle: Text('Version ${approval.rowVersion}${approval.decisionReason == null ? '' : ' • ${approval.decisionReason}'}'),
                    trailing: approval.state == 'PENDING'
                        ? Wrap(
                            spacing: 6,
                            children: [
                              TextButton(onPressed: _acting ? null : () => _decide(approval, 'reject'), child: const Text('Reject')),
                              FilledButton(onPressed: _acting ? null : () => _decide(approval, 'approve'), child: const Text('Approve')),
                            ],
                          )
                        : null,
                  ),
                )),
        ],
      );

  Widget _riskTab() {
    final halted = _portfolio?.newOrdersHalted;
    return ListView(
      key: const ValueKey('risk-screen'),
      padding: const EdgeInsets.all(20),
      children: [
        const _Heading('Risk & emergency stop'),
        const SizedBox(height: 8),
        Text(halted == null ? 'Emergency-stop state UNKNOWN' : halted ? 'NEW ORDERS HALTED' : 'New orders enabled'),
        const SizedBox(height: 14),
        TextField(
          key: const ValueKey('emergency-stop-reason'),
          controller: _haltReason,
          decoration: const InputDecoration(labelText: 'Operator reason'),
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 10,
          children: [
            FilledButton.icon(
              key: const ValueKey('halt-new-orders'),
              onPressed: _acting || halted == true ? null : () => _setHalt(true),
              icon: const Icon(Icons.stop_circle_outlined),
              label: const Text('Halt new orders'),
            ),
            OutlinedButton.icon(
              key: const ValueKey('resume-new-orders'),
              onPressed: _acting || halted != true ? null : () => _setHalt(false),
              icon: const Icon(Icons.play_circle_outline),
              label: const Text('Resume'),
            ),
          ],
        ),
        const SizedBox(height: 16),
        const Text('The PAPER order action is disabled client-side while halted, and the backend remains authoritative for enforcement.'),
      ],
    );
  }

  Widget _field(TextEditingController controller, String label, String key, {bool numeric = false}) => SizedBox(
        width: 180,
        child: TextField(
          key: ValueKey(key),
          controller: controller,
          keyboardType: numeric ? const TextInputType.numberWithOptions(decimal: true) : TextInputType.text,
          decoration: InputDecoration(labelText: label),
        ),
      );
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Semantics(header: true, child: Text(text, style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)));
}

class _Metric extends StatelessWidget {
  const _Metric(this.label, this.value);
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => Semantics(label: '$label: $value', child: SizedBox(width: 170, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label), const SizedBox(height: 3), Text(value, style: const TextStyle(fontWeight: FontWeight.w700))])));
}
