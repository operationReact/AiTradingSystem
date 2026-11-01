from pathlib import Path

path = Path('lib/src/presentation/review/mock_transparency_journey.dart')
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f'missing patch anchor: {label}')
    text = text.replace(old, new, 1)


replace_once(
    "  executionQuality,\n  learningRecord,\n",
    "  executionQuality,\n  improvementAnalysis,\n  learningRecord,\n",
    'audit enum',
)

replace_once(
    "        _AuditStep.executionQuality => 'Execution Quality',\n        _AuditStep.learningRecord => 'Learning Record',\n",
    "        _AuditStep.executionQuality => 'Execution Quality',\n        _AuditStep.improvementAnalysis => 'What Could Improve?',\n        _AuditStep.learningRecord => 'Learning Record',\n",
    'title switch',
)

replace_once(
    "        _AuditStep.executionQuality => 'Separate idea quality from broker/execution quality',\n        _AuditStep.learningRecord => 'What is stored, evaluated and never auto-promoted',\n",
    "        _AuditStep.executionQuality => 'Separate idea quality from broker/execution quality',\n        _AuditStep.improvementAnalysis => 'Hindsight analysis without pretending the future was knowable',\n        _AuditStep.learningRecord => 'What is stored, evaluated and never auto-promoted',\n",
    'subtitle switch',
)

replace_once(
    "        _AuditStep.executionQuality => _executionQuality(),\n        _AuditStep.learningRecord => _learningRecord(),\n",
    "        _AuditStep.executionQuality => _executionQuality(),\n        _AuditStep.improvementAnalysis => _improvementAnalysis(),\n        _AuditStep.learningRecord => _learningRecord(),\n",
    'screen switch',
)

method_anchor = "  Widget _learningRecord() {\n"
method = r'''  Widget _improvementAnalysis() {
    final losing = _showLossScenario;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _AuditGlow(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(
                children: [
                  Icon(Icons.tune_rounded, color: _AuditC.cyan),
                  SizedBox(width: 9),
                  Expanded(child: Text('Post-trade improvement analysis', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900))),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                losing
                    ? 'The review asks which filters, sizing or execution choices might have reduced the loss — without claiming those changes were obvious before entry.'
                    : 'The review checks whether the same profit could have been achieved with better risk efficiency — without rewriting the original decision using hindsight.',
                style: const TextStyle(color: _AuditC.muted, height: 1.45),
              ),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _AuditCard(
          child: Column(
            children: [
              _ImprovementRow(
                title: losing ? 'Wait for stronger confirmation' : 'Entry timing',
                observation: losing ? 'A second volume confirmation could have filtered this failed breakout.' : 'The original entry was already inside the planned zone.',
                verdict: losing ? 'RESEARCH' : 'NO CHANGE',
                tone: losing ? _AuditC.amber : _AuditC.green,
              ),
              const Divider(height: 18),
              _ImprovementRow(
                title: 'Position sizing',
                observation: losing ? 'A lower size would reduce absolute loss but would not make the thesis correct.' : 'Risk stayed inside the configured budget; increasing size is not justified by one profitable result.',
                verdict: 'KEEP RISK RULE',
                tone: _AuditC.cyan,
              ),
              const Divider(height: 18),
              _ImprovementRow(
                title: 'Stop placement',
                observation: losing ? 'A materially tighter stop would have reduced this loss but may increase false exits across similar setups.' : 'A tighter stop would have risked premature exit before the favorable move.',
                verdict: 'BACKTEST FIRST',
                tone: _AuditC.amber,
              ),
              const Divider(height: 18),
              _ImprovementRow(
                title: 'Execution quality',
                observation: 'Lower latency/slippage would improve realized P&L independently of model accuracy.',
                verdict: 'OPTIMIZE',
                tone: _AuditC.green,
              ),
              const Divider(height: 18),
              _ImprovementRow(
                title: 'Model confidence',
                observation: losing ? 'One loss does not justify lowering the model threshold; calibration must be evaluated over a large out-of-sample set.' : 'One profit does not justify raising confidence or loosening thresholds.',
                verdict: 'BATCH EVALUATE',
                tone: _AuditC.violet,
              ),
            ],
          ),
        ),
        const SizedBox(height: 10),
        const _AuditNotice('Improvement ideas are hypotheses for replay/backtest and batch evaluation. The app must never present hindsight optimization as something the system definitely should have known before the trade.'),
      ],
    );
  }

'''
replace_once(method_anchor, method + method_anchor, 'improvement method')

replace_once(
    "        _AuditStep.executionQuality => 'Execution Quality',\n        _AuditStep.learningRecord => 'Learning Record',\n",
    "        _AuditStep.executionQuality => 'Execution Quality',\n        _AuditStep.improvementAnalysis => 'What Could Improve?',\n        _AuditStep.learningRecord => 'Learning Record',\n",
    'step title switch',
)

helper_anchor = "class _LearningRow extends StatelessWidget {\n"
helper = r'''class _ImprovementRow extends StatelessWidget {
  const _ImprovementRow({required this.title, required this.observation, required this.verdict, required this.tone});

  final String title;
  final String observation;
  final String verdict;
  final Color tone;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.w800))),
              const SizedBox(width: 8),
              Flexible(child: Text(verdict, textAlign: TextAlign.right, style: TextStyle(color: tone, fontSize: 10, fontWeight: FontWeight.w900))),
            ],
          ),
          const SizedBox(height: 5),
          Text(observation, style: const TextStyle(color: _AuditC.muted, fontSize: 11, height: 1.4)),
        ],
      );
}

'''
replace_once(helper_anchor, helper + helper_anchor, 'improvement row helper')

path.write_text(text)
