enum HealthState { healthy, degraded, unknown, unavailable, idle, halted, unsafe }

HealthState parseHealthState(Object? raw) {
  final normalized = raw?.toString().toUpperCase();
  return switch (normalized) {
    'HEALTHY' => HealthState.healthy,
    'DEGRADED' => HealthState.degraded,
    'UNAVAILABLE' => HealthState.unavailable,
    'IDLE' => HealthState.idle,
    'HALTED' => HealthState.halted,
    'UNSAFE' => HealthState.unsafe,
    _ => HealthState.unknown,
  };
}

class HealthComponent {
  const HealthComponent({
    required this.component,
    required this.state,
    required this.asOf,
    required this.ageSeconds,
    required this.reasonCode,
  });

  factory HealthComponent.fromJson(Map<String, dynamic> json) => HealthComponent(
        component: json['component']?.toString() ?? 'unknown',
        state: parseHealthState(json['state']),
        asOf: DateTime.tryParse(json['asOf']?.toString() ?? ''),
        ageSeconds: switch (json['ageSeconds']) {
          int value => value,
          num value => value.toInt(),
          _ => null,
        },
        reasonCode: json['reasonCode']?.toString() ?? 'UNKNOWN',
      );

  final String component;
  final HealthState state;
  final DateTime? asOf;
  final int? ageSeconds;
  final String reasonCode;
}

class HealthSnapshot {
  const HealthSnapshot({
    required this.checkedAt,
    required this.state,
    required this.executionMode,
    required this.liveAvailable,
    required this.emergencyStopActive,
    required this.components,
  });

  factory HealthSnapshot.fromJson(Map<String, dynamic> json) {
    final rawComponents = json['components'];
    return HealthSnapshot(
      checkedAt: DateTime.tryParse(json['checkedAt']?.toString() ?? ''),
      state: parseHealthState(json['state']),
      executionMode: json['executionMode']?.toString() ?? 'UNKNOWN',
      liveAvailable: json['liveAvailable'] == true,
      emergencyStopActive: json['emergencyStopActive'] is bool ? json['emergencyStopActive'] as bool : null,
      components: rawComponents is List
          ? rawComponents.whereType<Map>().map((value) => HealthComponent.fromJson(Map<String, dynamic>.from(value))).toList(growable: false)
          : const <HealthComponent>[],
    );
  }

  final DateTime? checkedAt;
  final HealthState state;
  final String executionMode;
  final bool liveAvailable;
  final bool? emergencyStopActive;
  final List<HealthComponent> components;
}
