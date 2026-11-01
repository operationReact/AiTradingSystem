import 'package:flutter/material.dart';

import '../../../core/session/operator_session.dart';
import '../../../data/authenticated_api_client.dart';
import '../../../data/health_repository.dart';
import '../../overview/presentation/overview_page.dart' as existing;
import '../data/paper_runtime_repository.dart';
import 'paper_runtime_card.dart';

/// Customer Home wrapper that keeps the existing portfolio/AI experience while
/// making trading mode, automation state, Risk Engine and Emergency Stop visible
/// before the user scrolls into the rest of the dashboard.
class OverviewPage extends StatelessWidget {
  const OverviewPage({
    required this.session,
    required this.dashboardRepository,
    super.key,
  });

  final OperatorSession session;
  final DashboardRepository dashboardRepository;

  @override
  Widget build(BuildContext context) {
    final runtimeRepository = HttpPaperRuntimeRepository(AuthenticatedApiClient(session: session));
    return ColoredBox(
      color: Theme.of(context).scaffoldBackgroundColor,
      child: Column(
        children: [
          SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
              child: PaperRuntimeCard(repository: runtimeRepository),
            ),
          ),
          const SizedBox(height: 4),
          Expanded(
            child: existing.OverviewPage(
              session: session,
              dashboardRepository: dashboardRepository,
            ),
          ),
        ],
      ),
    );
  }
}
