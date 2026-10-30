import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/risk_approval_repository.dart';

class RiskApprovalsPage extends StatefulWidget {
  const RiskApprovalsPage({required this.repository, super.key});

  final RiskApprovalRepository repository;

  @override
  State<RiskApprovalsPage> createState() => _RiskApprovalsPageState();
}

class _RiskApprovalsPageState extends State<RiskApprovalsPage> {
  final _haltReason = TextEditingController();
  RiskApprovalSnapshot? _snapshot;
  ApiFailure? _failure;
  String? _localError;
  bool _loading = false;
  bool _mutating = false;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _haltReason.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _failure = null;
      _localError = null;
    });
    try {
      final snapshot = await widget.repository.fetch();
      if (!mounted) return;
      setState(() => _snapshot = snapshot);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _failure = failure);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _setEmergencyStop(bool halted) async {
    if (_mutating) return;
    final reason = _haltReason.text.trim();
    if (halted && reason.length < 3) {
      setState(() => _localError = 'A halt reason of at least 3 characters is required.');
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(halted ? 'Halt new PAPER orders?' : 'Resume PAPER orders?'),
        content: Text(
          halted
              ? 'This requests the backend emergency stop. Existing state remains authoritative until the server confirms the mutation.'
              : 'This requests the backend to clear the emergency stop. Risk policy and readiness checks still apply.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(
            key: ValueKey(halted ? 'confirm-risk-halt' : 'confirm-risk-resume'),
            onPressed: () => Navigator.pop(context, true),
            child: Text(halted ? 'Halt new orders' : 'Resume'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() {
      _mutating = true;
      _failure = null;
      _localError = null;
    });
    try {
      final config = await widget.repository.setEmergencyStop(halted: halted, reason: reason);
      if (!mounted) return;
      final previous = _snapshot;
      if (previous != null) {
        setState(() => _snapshot = RiskApprovalSnapshot(
              config: config,
              decisions: previous.decisions,
              approvals: previous.approvals,
            ));
      }
      await _refresh();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _failure = failure);
    } finally {
      if (mounted) setState(() => _mutating = false);
    }
  }

  Future<void> _decide(ApprovalView approval, String decision) async {
    if (_mutating || !approval.isPending) return;
    final actorController = TextEditingController();
    final reasonController = TextEditingController();
    try {
      final submitted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('${_decisionLabel(decision)} approval'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Order ${approval.orderId}\nPolicy v${approval.policyVersion} • row v${approval.rowVersion}'),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('approval-actor'),
                controller: actorController,
                decoration: const InputDecoration(labelText: 'Actor'),
                autofocus: true,
              ),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('approval-reason'),
                controller: reasonController,
                decoration: const InputDecoration(labelText: 'Reason (optional)'),
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
            FilledButton(
              key: const ValueKey('confirm-approval-decision'),
              onPressed: () => Navigator.pop(context, true),
              child: Text(_decisionLabel(decision)),
            ),
          ],
        ),
      );
      if (submitted != true || !mounted) return;
      final actor = actorController.text.trim();
      if (actor.isEmpty) {
        setState(() => _localError = 'Approval actor is required.');
        return;
      }

      setState(() {
        _mutating = true;
        _failure = null;
        _localError = null;
      });
      await widget.repository.decideApproval(
        approval: approval,
        decision: decision,
        actor: actor,
        reason: reasonController.text.trim().isEmpty ? null : reasonController.text.trim(),
      );
      if (!mounted) return;
      await _refresh();
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _failure = failure);
    } finally {
      actorController.dispose();
      reasonController.dispose();
      if (mounted) setState(() => _mutating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    return Scaffold(
      appBar: AppBar(title: const Text('Risk & approvals')),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refresh,
          child: ListView(
            key: const ValueKey('risk-approvals-list'),
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
            children: [
              Semantics(header: true, child: Text('Backend risk authority', style: Theme.of(context).textTheme.headlineSmall)),
              const SizedBox(height: 8),
              const Text('Risk verdicts, policy version, emergency stop and approval lifecycle are read from or mutated through the Spring backend. UI appearance never changes execution authority.'),
              if (_localError != null) ...[
                const SizedBox(height: 12),
                Card(
                  key: const ValueKey('risk-local-error'),
                  child: Padding(padding: const EdgeInsets.all(16), child: Text(_localError!)),
                ),
              ],
              if (_failure != null) ...[
                const SizedBox(height: 12),
                _errorCard(_failure!, stale: snapshot != null),
              ],
              const SizedBox(height: 16),
              if (_loading && snapshot == null)
                const Center(key: ValueKey('risk-loading'), child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()))
              else if (snapshot != null) ...[
                _configCard(snapshot.config),
                const SizedBox(height: 24),
                Semantics(header: true, child: Text('Pending approvals', style: Theme.of(context).textTheme.titleLarge)),
                const SizedBox(height: 8),
                if (snapshot.approvals.where((item) => item.isPending).isEmpty)
                  const Card(
                    key: ValueKey('approvals-empty'),
                    child: Padding(padding: EdgeInsets.all(16), child: Text('No pending approvals are reported by the backend.')),
                  )
                else
                  ...snapshot.approvals.where((item) => item.isPending).map(_approvalCard),
                const SizedBox(height: 24),
                Semantics(header: true, child: Text('Recent risk decisions', style: Theme.of(context).textTheme.titleLarge)),
                const SizedBox(height: 8),
                if (snapshot.decisions.isEmpty)
                  const Card(
                    key: ValueKey('risk-decisions-empty'),
                    child: Padding(padding: EdgeInsets.all(16), child: Text('No persisted risk decisions are reported by the backend.')),
                  )
                else
                  ...snapshot.decisions.map(_decisionCard),
              ] else if (_failure == null)
                const Card(child: Padding(padding: EdgeInsets.all(16), child: Text('No authoritative risk evidence is available.'))),
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const ValueKey('risk-refresh'),
        onPressed: _loading || _mutating ? null : _refresh,
        icon: const Icon(Icons.refresh),
        label: const Text('Refresh'),
      ),
    );
  }

  Widget _configCard(RiskConfig config) => Card(
        key: const ValueKey('risk-config'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  Chip(label: Text('Mode ${config.tradingMode}')),
                  Chip(label: Text(config.liveTradingEnabled ? 'LIVE ENABLED' : 'LIVE DISABLED')),
                  Chip(label: Text(config.newOrdersHalted ? 'NEW ORDERS HALTED' : 'NEW ORDERS ACTIVE')),
                  Chip(label: Text('Policy ${config.policyVersion}')),
                ],
              ),
              const SizedBox(height: 12),
              Text('Risk/trade: ${config.riskPerTradePercent ?? 'UNKNOWN'}%'),
              Text('Max daily drawdown: ${config.maxDailyDrawdownPercent ?? 'UNKNOWN'}%'),
              Text('Max open positions: ${config.maxOpenPositions}'),
              Text('Signal max age: ${config.signalMaxAgeMinutes} minutes'),
              const SizedBox(height: 16),
              TextField(
                key: const ValueKey('emergency-stop-reason'),
                controller: _haltReason,
                enabled: !_mutating,
                decoration: const InputDecoration(labelText: 'Emergency-stop reason', helperText: 'Required when halting new orders.'),
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.tonalIcon(
                    key: const ValueKey('risk-halt-orders'),
                    onPressed: _mutating || config.newOrdersHalted ? null : () => _setEmergencyStop(true),
                    icon: const Icon(Icons.pause_circle_outline),
                    label: const Text('Halt new PAPER orders'),
                  ),
                  OutlinedButton.icon(
                    key: const ValueKey('risk-resume-orders'),
                    onPressed: _mutating || !config.newOrdersHalted ? null : () => _setEmergencyStop(false),
                    icon: const Icon(Icons.play_circle_outline),
                    label: const Text('Request resume'),
                  ),
                ],
              ),
            ],
          ),
        ),
      );

  Widget _approvalCard(ApprovalView approval) => Card(
        key: ValueKey('approval-${approval.id}'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Order ${approval.orderId}', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text('State ${approval.state} • policy v${approval.policyVersion} • row v${approval.rowVersion}'),
              Text('Expires ${approval.expiresAt?.toIso8601String() ?? 'UNKNOWN'}'),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton(
                    key: ValueKey('approval-approve-${approval.id}'),
                    onPressed: _mutating ? null : () => _decide(approval, 'APPROVED'),
                    child: const Text('Approve'),
                  ),
                  OutlinedButton(
                    key: ValueKey('approval-reject-${approval.id}'),
                    onPressed: _mutating ? null : () => _decide(approval, 'REJECTED'),
                    child: const Text('Reject'),
                  ),
                  TextButton(
                    key: ValueKey('approval-cancel-${approval.id}'),
                    onPressed: _mutating ? null : () => _decide(approval, 'CANCELLED'),
                    child: const Text('Cancel approval'),
                  ),
                ],
              ),
            ],
          ),
        ),
      );

  Widget _decisionCard(RiskDecisionSummaryView decision) => Card(
        key: ValueKey('risk-decision-${decision.id}'),
        child: ListTile(
          title: Text('${decision.decision} • order ${decision.orderId}'),
          subtitle: Text(
            'Policy ${decision.policyVersion} • failed rules ${decision.failedRuleCount}\n'
            '${decision.reasonCodes.isEmpty ? 'No reason codes' : decision.reasonCodes.join(', ')} • ${decision.evaluatedAt?.toIso8601String() ?? 'UNKNOWN'}',
          ),
          isThreeLine: true,
        ),
      );

  Widget _errorCard(ApiFailure failure, {required bool stale}) => Card(
        key: ValueKey(stale ? 'risk-stale' : 'risk-error'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text(
            stale
                ? 'Refresh/action failed (${failure.code}). Existing values are last-known evidence and must not be treated as current.'
                : 'Risk evidence unavailable: ${failure.code}${failure.correlationId == null ? '' : ' • correlation ${failure.correlationId}'}',
          ),
        ),
      );

  static String _decisionLabel(String decision) => switch (decision) {
        'APPROVED' => 'Approve',
        'REJECTED' => 'Reject',
        'CANCELLED' => 'Cancel',
        _ => decision,
      };
}
