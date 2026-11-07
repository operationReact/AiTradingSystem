import '../../../data/authenticated_api_client.dart';
import '../domain/trading_room_draft.dart';

/// Authenticated, customer-owned Trading Room plans.
/// No order-placement, broker authorization, or account balances are implied.
class HttpCustomerRoomRepository implements RoomDraftPersistence {
  HttpCustomerRoomRepository(this.api);
  final AuthenticatedApiClient api;

  static const _path = '/api/customer/trading-rooms';

  @override
  Future<List<TradingRoomDraft>> list() async {
    final json = await api.getJson(_path);
    if (json is! List) throw const FormatException('Invalid room list');
    return json.map(_decode).toList(growable: false);
  }

  @override
  Future<TradingRoomDraft> create(TradingRoomDraft draft) async {
    final data = await api.postJson(_path, body: {
      'name': draft.name,
      'focus': _focusToWire(draft.focus),
      'plannedBudgetInr': draft.paperBudget,
      'riskPreference': draft.riskPreference.toUpperCase(),
      'mode': draft.mode,
    });
    return _decode(data);
  }

  /// Only PAPER rooms can initialize virtual funding; LIVE is never touched.
  Future<CustomerPaperWallet> activatePaper(String roomId) async =>
      CustomerPaperWallet.decode(await api.postJson(
        '$_path/$roomId/paper/activate',
      ));

  Future<CustomerPaperWallet> paperAccount(String roomId) async =>
      CustomerPaperWallet.decode(await api.getJson(
        '$_path/$roomId/paper/account',
      ));

  Future<List<CustomerPaperCashEvent>> paperLedger(String roomId) async {
    final response = await api.getJson('$_path/$roomId/paper/ledger');
    if (response is! List) throw const FormatException('Invalid Paper ledger');
    return response.map(CustomerPaperCashEvent.decode).toList(growable: false);
  }

  static String _focusToWire(String focus) => switch (focus) {
    'Intraday' => 'INTRADAY',
    'Swing' => 'SWING',
    'Long-term' => 'LONG_TERM',
    'Help me choose' => 'HELP_ME_CHOOSE',
    _ => throw const FormatException('Unknown room focus'),
  };

  static TradingRoomDraft _decode(Object? data) {
    if (data is! Map<String, dynamic>) throw const FormatException('Invalid room');
    final id = data['id'];
    final name = data['name'];
    final mode = data['mode'];
    final focus = data['focus'];
    final budget = data['plannedBudgetInr'];
    final risk = data['riskPreference'];
    final status = data['status'];
    if (id is! String || id.isEmpty || name is! String || name.isEmpty ||
        !(mode == 'PAPER' || mode == 'LIVE') ||
        budget is! int || budget < 1000 ||
        risk is! String ||
        !(status == 'DRAFT' ||
          (mode == 'PAPER' && status == 'PAPER_FUNDED'))) {
      throw const FormatException('Unrecognized room data');
    }
    return TradingRoomDraft(
      id: id, name: name,
      focus: switch (focus) {
        'INTRADAY' => 'Intraday',
        'SWING' => 'Swing',
        'LONG_TERM' => 'Long-term',
        'HELP_ME_CHOOSE' => 'Help me choose',
        _ => throw const FormatException('Unrecognized room focus'),
      },
      paperBudget: budget,
      riskPreference: switch (risk) {
        'CAUTIOUS' => 'Cautious',
        'BALANCED' => 'Balanced',
        'AGGRESSIVE' => 'Aggressive',
        _ => throw const FormatException('Unrecognized risk preference'),
      },
      mode: mode,
      status: status as String,
    );
  }
}

class CustomerPaperWallet {
  const CustomerPaperWallet({
    required this.roomId,
    required this.roomStatus,
    required this.initialCashPaise,
    required this.availableCashPaise,
    required this.reservedCashPaise,
    required this.realizedPnlPaise,
    required this.halted,
    required this.executionEnabled,
    required this.updatedAt,
  });

  final String roomId;
  final String roomStatus;
  final int initialCashPaise;
  final int availableCashPaise;
  final int reservedCashPaise;
  final int realizedPnlPaise;
  final bool halted;
  final bool executionEnabled;
  final DateTime updatedAt;

  static CustomerPaperWallet decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['roomId'] is! String ||
        raw['mode'] != 'PAPER' ||
        raw['roomStatus'] != 'PAPER_FUNDED' ||
        raw['initialCashPaise'] is! int ||
        raw['availableCashPaise'] is! int ||
        raw['reservedCashPaise'] is! int ||
        raw['realizedPnlPaise'] is! int ||
        raw['halted'] is! bool ||
        raw['executionEnabled'] is! bool ||
        raw['updatedAt'] is! String) {
      throw const FormatException('Unrecognized Paper account');
    }
    final updated = DateTime.tryParse(raw['updatedAt'] as String);
    if (updated == null ||
        (raw['availableCashPaise'] as int) < 0 ||
        (raw['initialCashPaise'] as int) < 100000) {
      throw const FormatException('Invalid Paper account values');
    }
    return CustomerPaperWallet(
      roomId: raw['roomId'] as String,
      roomStatus: raw['roomStatus'] as String,
      initialCashPaise: raw['initialCashPaise'] as int,
      availableCashPaise: raw['availableCashPaise'] as int,
      reservedCashPaise: raw['reservedCashPaise'] as int,
      realizedPnlPaise: raw['realizedPnlPaise'] as int,
      halted: raw['halted'] as bool,
      executionEnabled: raw['executionEnabled'] as bool,
      updatedAt: updated,
    );
  }
}

class CustomerPaperCashEvent {
  const CustomerPaperCashEvent({
    required this.id,
    required this.eventKind,
    required this.cashDeltaPaise,
    required this.cashAfterPaise,
    required this.createdAt,
  });
  final String id;
  final String eventKind;
  final int cashDeltaPaise;
  final int cashAfterPaise;
  final DateTime createdAt;

  static CustomerPaperCashEvent decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['id'] is! String ||
        raw['eventKind'] != 'INITIAL_PAPER_ALLOCATION' ||
        raw['cashDeltaPaise'] is! int ||
        raw['cashAfterPaise'] is! int ||
        raw['createdAt'] is! String) {
      throw const FormatException('Unrecognized Paper cash event');
    }
    final time = DateTime.tryParse(raw['createdAt'] as String);
    if (time == null) throw const FormatException('Invalid cash event time');
    return CustomerPaperCashEvent(
      id: raw['id'] as String,
      eventKind: raw['eventKind'] as String,
      cashDeltaPaise: raw['cashDeltaPaise'] as int,
      cashAfterPaise: raw['cashAfterPaise'] as int,
      createdAt: time,
    );
  }
}
