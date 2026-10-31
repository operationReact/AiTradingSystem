import 'package:flutter/material.dart';

import '../data/scenario_evidence_repository.dart';
import 'scenario_evidence_page.dart';

class ScenarioLookupPage extends StatefulWidget {
  const ScenarioLookupPage({required this.repository, super.key});

  final ScenarioEvidenceRepository repository;

  @override
  State<ScenarioLookupPage> createState() => _ScenarioLookupPageState();
}

class _ScenarioLookupPageState extends State<ScenarioLookupPage> {
  final _controller = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _open() {
    final id = _controller.text.trim();
    if (!RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(id)) {
      setState(() => _error = 'Enter the persisted scenario-run UUID from research evaluation evidence.');
      return;
    }
    setState(() => _error = null);
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ScenarioEvidencePage(
          repository: widget.repository,
          scenarioRunId: id,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Scenario evidence lookup')),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
            children: [
              Semantics(
                header: true,
                child: Text(
                  'Open a persisted scenario run',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'Use the scenarioRunId recorded by a research evaluation. This path only reads persisted evidence; it never starts a new simulation.',
              ),
              const SizedBox(height: 16),
              TextField(
                key: const ValueKey('scenario-run-id'),
                controller: _controller,
                decoration: InputDecoration(
                  labelText: 'Scenario run UUID',
                  errorText: _error,
                  border: const OutlineInputBorder(),
                ),
                onSubmitted: (_) => _open(),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                key: const ValueKey('open-scenario-evidence'),
                onPressed: _open,
                icon: const Icon(Icons.analytics_outlined),
                label: const Text('Open persisted evidence'),
              ),
            ],
          ),
        ),
      );
}
