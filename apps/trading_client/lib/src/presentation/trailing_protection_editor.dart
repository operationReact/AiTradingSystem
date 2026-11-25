import 'package:flutter/material.dart';

/// Customer editor for optional persisted PAPER trailing protection.
///
/// There is intentionally no default percentage. The customer must opt in and
/// explicitly choose the trail distance; the backend remains authoritative for
/// the allowed operational range and for high-water/effective-stop state.
class TrailingProtectionEditor extends StatelessWidget {
  const TrailingProtectionEditor({
    required this.enabled,
    required this.distanceController,
    required this.onEnabledChanged,
    super.key,
  });

  final bool enabled;
  final TextEditingController distanceController;
  final ValueChanged<bool> onEnabledChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Container(
      key: const ValueKey('paper-trailing-protection-editor'),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        color: scheme.surfaceContainerHighest.withValues(alpha: 0.48),
        border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.7)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(13),
                  color: scheme.primary.withValues(alpha: 0.10),
                ),
                child: Icon(Icons.trending_up_rounded, color: scheme.primary),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Trailing protection',
                      style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      'Optional. You choose the trail distance — there is no preset.',
                      style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.35),
                    ),
                  ],
                ),
              ),
              Switch.adaptive(
                key: const ValueKey('paper-trailing-protection-toggle'),
                value: enabled,
                onChanged: onEnabledChanged,
              ),
            ],
          ),
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            child: enabled
                ? Padding(
                    key: const ValueKey('paper-trailing-protection-enabled'),
                    padding: const EdgeInsets.only(top: 14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        TextField(
                          key: const ValueKey('paper-trailing-distance-percent'),
                          controller: distanceController,
                          keyboardType: const TextInputType.numberWithOptions(decimal: true),
                          decoration: const InputDecoration(
                            labelText: 'Trail distance (%)',
                            hintText: 'Enter a percentage',
                            helperText: 'No value is selected for you. The server validates the permitted range.',
                            suffixText: '%',
                          ),
                        ),
                        const SizedBox(height: 12),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(14),
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(16),
                            color: scheme.primaryContainer.withValues(alpha: 0.32),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'How it protects the trade',
                                style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w900),
                              ),
                              const SizedBox(height: 5),
                              Text(
                                'After the PAPER fill, the app tracks the highest fresh market price. The protective stop follows at your chosen distance, can move up but never down, and never loosens your original stop loss.',
                                style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.45),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  )
                : const SizedBox.shrink(key: ValueKey('paper-trailing-protection-disabled')),
          ),
        ],
      ),
    );
  }
}
