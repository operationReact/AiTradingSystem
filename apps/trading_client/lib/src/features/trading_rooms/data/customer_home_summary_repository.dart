import '../../../data/authenticated_api_client.dart';

/// Authenticated customer Home read model, never a trading execution contract.
abstract interface class CustomerHomeSummaryRepository {
  Future<CustomerHomeSummary> load(String mode);
}

class HttpCustomerHomeSummaryRepository
    implements CustomerHomeSummaryRepository {
  const HttpCustomerHomeSummaryRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<CustomerHomeSummary> load(String mode) async {
    if (mode != 'PAPER' && mode != 'LIVE') {
      throw const FormatException('Invalid workspace mode');
    }
    final raw = await api.getJson('/api/customer/home/v1/summary?mode=$mode');
    final summary = CustomerHomeSummary.decode(raw);
    if (summary.mode != mode) {
      throw const FormatException('Home response mode mismatch');
    }
    return summary;
  }
}

class CustomerHomeSummary {
  const CustomerHomeSummary({
    required this.mode,
    required this.generatedAt,
    required this.rooms,
    required this.brokers,
    required this.mind,
    required this.nextAction,
    required this.activity,
  });

  final String mode;
  final DateTime generatedAt;
  final HomeRooms rooms;
  final HomeBrokers brokers;
  final HomeMind mind;
  final HomeAction nextAction;
  final HomeActivity? activity;

  factory CustomerHomeSummary.decode(Object? raw) {
    final json = _map(raw);
    if (json['contractVersion'] != 'customer.home.v1' ||
        json['mode'] != 'PAPER' && json['mode'] != 'LIVE' ||
        json['liveExecutionEnabled'] != false) {
      throw const FormatException('Unverified Home contract');
    }
    return CustomerHomeSummary(
      mode: json['mode'] as String,
      generatedAt: _date(json['generatedAt']) ??
          (throw const FormatException('Missing summary timestamp')),
      rooms: HomeRooms.decode(json['rooms']),
      brokers: HomeBrokers.decode(json['brokers']),
      mind: HomeMind.decode(json['mind']),
      nextAction: HomeAction.decode(json['nextAction']),
      activity: json['activity'] == null
          ? null : HomeActivity.decode(json['activity']),
    );
  }
}

class HomeRoom {
  const HomeRoom({
    required this.id, required this.name, required this.focus,
    required this.status, required this.mode, required this.plannedBudgetInr,
  });

  final String id;
  final String name;
  final String focus;
  final String status;
  final String mode;
  final int plannedBudgetInr;

  factory HomeRoom.decode(Object? raw) {
    final json = _map(raw);
    return HomeRoom(
      id: _text(json['id']),
      name: _text(json['name']),
      focus: _text(json['focus']),
      status: _text(json['status']),
      mode: _text(json['mode']),
      plannedBudgetInr: _nonnegative(json['plannedBudgetInr']),
    );
  }
}

class HomeRooms {
  const HomeRooms({
    required this.count, required this.drafts, required this.funded,
    required this.plannedBudgetInr, required this.availablePaperCashPaise,
    required this.featured,
  });

  final int count;
  final int drafts;
  final int funded;
  final int plannedBudgetInr;
  /// Null = source could not reconcile. Zero = verified empty / no funded rooms.
  final int? availablePaperCashPaise;
  final List<HomeRoom> featured;

  factory HomeRooms.decode(Object? raw) {
    final json = _map(raw);
    final list = json['featured'];
    if (list is! List) {
      throw const FormatException('Missing Home room preview');
    }
    final count = _nonnegative(json['count']);
    final drafts = _nonnegative(json['drafts']);
    final funded = _nonnegative(json['funded']);
    if (drafts > count || funded > count || list.length > 2) {
      throw const FormatException('Inconsistent room totals');
    }
    return HomeRooms(
      count: count,
      drafts: drafts,
      funded: funded,
      plannedBudgetInr: _nonnegative(json['plannedBudgetInr']),
      availablePaperCashPaise: json['availablePaperCashPaise'] == null
          ? null : _nonnegative(json['availablePaperCashPaise']),
      featured: List.unmodifiable(list.map(HomeRoom.decode)),
    );
  }
}

class HomeBrokers {
  const HomeBrokers({
    required this.angelOne, required this.upstox,
    required this.connected, required this.complete,
  });

  final String angelOne;
  final String upstox;
  final int connected;
  final bool complete;

  factory HomeBrokers.decode(Object? raw) {
    final json = _map(raw);
    const valid = {'CONNECTED', 'NOT_CONNECTED', 'UNAVAILABLE'};
    final angel = _text(json['angelOne']);
    final upstox = _text(json['upstox']);
    final count = _nonnegative(json['connected']);
    if (!valid.contains(angel) || !valid.contains(upstox) ||
        json['complete'] is! bool || count > 2 ||
        count != (angel == 'CONNECTED' ? 1 : 0) +
            (upstox == 'CONNECTED' ? 1 : 0)) {
      throw const FormatException('Unverified broker connection statuses');
    }
    return HomeBrokers(
      angelOne: angel, upstox: upstox, connected: count,
      complete: json['complete'] as bool,
    );
  }
}

class HomeEvidence {
  const HomeEvidence(this.status, this.asOf, this.source);

  final String status;
  final DateTime? asOf;
  final String? source;

  factory HomeEvidence.decode(Object? raw) {
    final json = _map(raw);
    final status = _text(json['status']);
    const statuses = {
      'UNAVAILABLE', 'STORED', 'STALE', 'RECORDED',
      'PARTIAL', 'FAILED',
    };
    if (!statuses.contains(status) ||
        json['asOf'] != null && _date(json['asOf']) == null) {
      throw const FormatException('Unknown research status');
    }
    if (status == 'UNAVAILABLE' &&
        (json['source'] != null || json['asOf'] != null)) {
      throw const FormatException('Unavailable evidence cannot have a source');
    }
    return HomeEvidence(
      status,
      _date(json['asOf']),
      json['source'] is String ? json['source'] as String : null,
    );
  }
}

class HomeMind {
  const HomeMind({
    required this.market, required this.research,
  });

  final HomeEvidence market;
  final HomeEvidence research;

  factory HomeMind.decode(Object? raw) {
    final json = _map(raw);
    if (json['executionEnabled'] != false) {
      throw const FormatException('Unexpected execution permission');
    }
    return HomeMind(
      market: HomeEvidence.decode(json['market']),
      research: HomeEvidence.decode(json['research']),
    );
  }
}

class HomeAction {
  const HomeAction({
    required this.code, required this.destination, this.roomId,
  });

  final String code;
  final String destination;
  final String? roomId;

  factory HomeAction.decode(Object? raw) {
    final json = _map(raw);
    const codes = {
      'CREATE_PAPER_ROOM', 'COMPLETE_PAPER_ROOM', 'REVIEW_PAPER_ROOM',
      'VERIFY_BROKER', 'CONNECT_BROKER', 'PLAN_LIVE_ROOM',
      'REVIEW_LIVE_ROOM',
    };
    const destinations = {'CREATE_ROOM', 'ROOM', 'BROKERS'};
    final code = _text(json['code']);
    final destination = _text(json['destination']);
    if (!codes.contains(code) || !destinations.contains(destination)) {
      throw const FormatException('Unknown Home next action');
    }
    return HomeAction(
      code: code, destination: destination,
      roomId: json['roomId'] == null ? null : _text(json['roomId']),
    );
  }
}

class HomeActivity {
  const HomeActivity({
    required this.kind, required this.description,
    required this.occurredAt, required this.roomId,
  });

  final String kind;
  final String description;
  final DateTime occurredAt;
  final String? roomId;

  factory HomeActivity.decode(Object? raw) {
    final json = _map(raw);
    final kind = _text(json['kind']);
    if (kind != 'ROOM_PLAN_SAVED' && kind != 'PAPER_CASH_EVENT') {
      throw const FormatException('Unsupported verified Home activity');
    }
    return HomeActivity(
      kind: kind, description: _text(json['description']),
      occurredAt: _date(json['occurredAt']) ??
          (throw const FormatException('Missing activity timestamp')),
      roomId: json['roomId'] == null ? null : _text(json['roomId']),
    );
  }
}

Map<String, dynamic> _map(Object? value) {
  if (value is! Map) {
    throw const FormatException('Invalid Home summary object');
  }
  return value.map((k, v) {
    if (k is! String) throw const FormatException('Invalid summary key');
    return MapEntry(k, v);
  });
}

String _text(Object? value) {
  if (value is! String || value.trim().isEmpty) {
    throw const FormatException('Missing verified Home field');
  }
  return value.trim();
}

int _nonnegative(Object? value) {
  if (value is! int || value < 0) {
    throw const FormatException('Invalid Home number');
  }
  return value;
}

DateTime? _date(Object? value) {
  if (value == null) return null;
  if (value is! String) return null;
  return DateTime.tryParse(value)?.toUtc();
}
