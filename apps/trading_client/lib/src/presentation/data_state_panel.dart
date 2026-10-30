import 'package:flutter/material.dart';

enum DataStateKind {
  loading,
  empty,
  error,
  stale,
  degraded,
  unknown,
}

class DataStatePanel extends StatelessWidget {
  const DataStatePanel({
    required this.kind,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
    super.key,
  });

  final DataStateKind kind;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  String get _semanticState => switch (kind) {
        DataStateKind.loading => 'LOADING',
        DataStateKind.empty => 'EMPTY',
        DataStateKind.error => 'ERROR',
        DataStateKind.stale => 'STALE',
        DataStateKind.degraded => 'DEGRADED',
        DataStateKind.unknown => 'UNKNOWN',
      };

  IconData get _icon => switch (kind) {
        DataStateKind.loading => Icons.sync_rounded,
        DataStateKind.empty => Icons.inbox_outlined,
        DataStateKind.error => Icons.error_outline_rounded,
        DataStateKind.stale => Icons.history_toggle_off_rounded,
        DataStateKind.degraded => Icons.warning_amber_rounded,
        DataStateKind.unknown => Icons.help_outline_rounded,
      };

  bool get _requiresAttention => switch (kind) {
        DataStateKind.error ||
        DataStateKind.stale ||
        DataStateKind.degraded ||
        DataStateKind.unknown => true,
        DataStateKind.loading || DataStateKind.empty => false,
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final foreground = _requiresAttention ? scheme.onErrorContainer : scheme.onSurfaceVariant;
    final background = _requiresAttention ? scheme.errorContainer : scheme.surfaceContainerHighest;

    return Semantics(
      container: true,
      label: '$_semanticState: $title. $message',
      child: Container(
        key: ValueKey('data-state-${kind.name}'),
        width: double.infinity,
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: _requiresAttention
                ? scheme.error.withValues(alpha: 0.35)
                : scheme.outlineVariant.withValues(alpha: 0.65),
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (kind == DataStateKind.loading)
              const SizedBox(
                width: 22,
                height: 22,
                child: CircularProgressIndicator(strokeWidth: 2.2),
              )
            else
              Icon(_icon, color: foreground),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: theme.textTheme.titleMedium?.copyWith(
                      color: foreground,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 5),
                  Text(
                    message,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: foreground,
                      height: 1.45,
                    ),
                  ),
                  if (actionLabel != null && onAction != null) ...[
                    const SizedBox(height: 12),
                    OutlinedButton(
                      onPressed: onAction,
                      child: Text(actionLabel!),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
