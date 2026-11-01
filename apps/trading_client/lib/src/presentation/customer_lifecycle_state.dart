import 'package:flutter/material.dart';

/// Customer-facing PAPER lifecycle language.
///
/// Raw backend status codes remain available for diagnostics but must not be
/// rendered as the primary product state. Unknown values fail safely to a
/// neutral state instead of implying execution.
enum CustomerLifecycleKind {
  watching,
  riskApproved,
  rejectedByRisk,
  positionOpened,
  positionClosed,
  openPosition,
  cancelled,
  researchSupported,
  needsReview,
}

class CustomerLifecycleState {
  const CustomerLifecycleState(this.kind, this.label, this.description);

  final CustomerLifecycleKind kind;
  final String label;
  final String description;

  static const watching = CustomerLifecycleState(
    CustomerLifecycleKind.watching,
    'Watching',
    'The setup is being tracked. No PAPER position has been opened.',
  );
  static const riskApproved = CustomerLifecycleState(
    CustomerLifecycleKind.riskApproved,
    'Risk approved',
    'The Risk Engine approved this PAPER order.',
  );
  static const rejectedByRisk = CustomerLifecycleState(
    CustomerLifecycleKind.rejectedByRisk,
    'Rejected by risk',
    'The Risk Engine blocked this PAPER order.',
  );
  static const positionOpened = CustomerLifecycleState(
    CustomerLifecycleKind.positionOpened,
    'Position opened',
    'The PAPER buy was filled and is now being monitored.',
  );
  static const positionClosed = CustomerLifecycleState(
    CustomerLifecycleKind.positionClosed,
    'Position closed',
    'The PAPER sell was filled and the result is recorded.',
  );
  static const openPosition = CustomerLifecycleState(
    CustomerLifecycleKind.openPosition,
    'Open position',
    'This PAPER position is active and being monitored.',
  );
  static const cancelled = CustomerLifecycleState(
    CustomerLifecycleKind.cancelled,
    'Cancelled',
    'This PAPER order will not execute.',
  );
  static const researchSupported = CustomerLifecycleState(
    CustomerLifecycleKind.researchSupported,
    'Research supported',
    'Historical evidence supports reviewing this setup. This is not execution approval.',
  );
  static const needsReview = CustomerLifecycleState(
    CustomerLifecycleKind.needsReview,
    'Needs review',
    'Check the latest details before taking any action.',
  );

  static CustomerLifecycleState forOrder({required String status, required String side}) {
    switch (status.trim().toUpperCase()) {
      case 'NEW':
      case 'RECEIVED':
      case 'PENDING':
        return watching;
      case 'RISK_APPROVED':
      case 'APPROVED':
        return riskApproved;
      case 'REJECTED':
      case 'RISK_REJECTED':
        return rejectedByRisk;
      case 'FILLED':
        return side.trim().toUpperCase() == 'SELL' ? positionClosed : positionOpened;
      case 'CANCELLED':
      case 'CANCELED':
        return cancelled;
      default:
        return needsReview;
    }
  }

  static CustomerLifecycleState forEvaluation(String decisionLabel) {
    switch (decisionLabel.trim().toUpperCase()) {
      case 'RESEARCH_SUPPORTED':
      case 'SUPPORTED':
        return researchSupported;
      case 'REJECTED':
      case 'RISK_REJECTED':
        return rejectedByRisk;
      default:
        return needsReview;
    }
  }
}

class CustomerLifecycleBadge extends StatelessWidget {
  const CustomerLifecycleBadge({required this.state, super.key});

  final CustomerLifecycleState state;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (Color background, Color foreground, IconData icon) = switch (state.kind) {
      CustomerLifecycleKind.rejectedByRisk => (scheme.errorContainer, scheme.onErrorContainer, Icons.shield_outlined),
      CustomerLifecycleKind.positionOpened || CustomerLifecycleKind.openPosition =>
        (scheme.primaryContainer, scheme.onPrimaryContainer, Icons.monitor_heart_outlined),
      CustomerLifecycleKind.positionClosed =>
        (scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.task_alt_rounded),
      CustomerLifecycleKind.riskApproved || CustomerLifecycleKind.researchSupported =>
        (scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.verified_user_outlined),
      CustomerLifecycleKind.cancelled =>
        (scheme.surfaceContainerHighest, scheme.onSurfaceVariant, Icons.cancel_outlined),
      CustomerLifecycleKind.watching || CustomerLifecycleKind.needsReview =>
        (scheme.surfaceContainerHighest, scheme.onSurfaceVariant, Icons.visibility_outlined),
    };

    return Tooltip(
      message: state.description,
      child: Semantics(
        label: '${state.label}. ${state.description}',
        child: Chip(
          avatar: Icon(icon, size: 16, color: foreground),
          label: Text(state.label),
          backgroundColor: background,
          labelStyle: TextStyle(color: foreground, fontWeight: FontWeight.w700),
          side: BorderSide.none,
          visualDensity: VisualDensity.compact,
        ),
      ),
    );
  }
}
