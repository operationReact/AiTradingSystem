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
        risk is! String || status != 'DRAFT') {
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
    );
  }
}
