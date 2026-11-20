import '../../../data/authenticated_api_client.dart';
import '../../trading_rooms/data/customer_room_repository.dart';

class FnoPaperRoom {
  const FnoPaperRoom(this.id,this.name);
  final String id,name;
}

class FnoPaperAutomationState {
  const FnoPaperAutomationState(this.enabled,this.lastStatus,this.lastReason,
      this.lastRunAt,this.nextRunAt);
  final bool enabled;
  final String? lastStatus,lastReason;
  final DateTime? lastRunAt,nextRunAt;
  factory FnoPaperAutomationState.parse(Object? raw) {
    if(raw is! Map<String,dynamic> || raw['liveMoney']!=false ||
        raw['enabled'] is! bool ||
        (raw['lastStatus']!=null && raw['lastStatus'] is! String) ||
        (raw['lastReason']!=null && raw['lastReason'] is! String)) {
      throw const FormatException('Unsafe PAPER automation state');
    }
    DateTime? time(Object? v) => v is String ? DateTime.tryParse(v) : null;
    if((raw['lastRunAt']!=null && time(raw['lastRunAt'])==null) ||
        (raw['nextRunAt']!=null && time(raw['nextRunAt'])==null)) {
      throw const FormatException('Invalid PAPER timestamp');
    }
    return FnoPaperAutomationState(raw['enabled'] as bool,
      raw['lastStatus'] as String?,raw['lastReason'] as String?,
      time(raw['lastRunAt']),time(raw['nextRunAt']));
  }
}

class FnoPaperCycleEvent {
  const FnoPaperCycleEvent(this.at,this.status,this.reason,this.action,
      this.instrument,this.fillId,this.realizedPnlPaise,this.cashPaise);
  final DateTime at;
  final String status,reason,action;
  final String? instrument,fillId;
  final int? realizedPnlPaise,cashPaise;
  factory FnoPaperCycleEvent.parse(Object? raw) {
    if(raw is! Map<String,dynamic> || raw['liveMoney']!=false ||
        raw['status'] is! String || raw['reasonCode'] is! String ||
        !const {'NONE','ENTER','EXIT'}.contains(raw['action']) ||
        raw['evaluatedAt'] is! String ||
        (raw['fillId']!=null && raw['fillId'] is! String) ||
        (raw['realizedPnlDeltaPaise']!=null &&
            raw['realizedPnlDeltaPaise'] is! int)) {
      throw const FormatException('Unrecognized PAPER F&O cycle event');
    }
    final at=DateTime.tryParse(raw['evaluatedAt'] as String);
    if(at==null)throw const FormatException('PAPER event has no time');
    if(raw['action']!='NONE' && raw['fillId']==null &&
        (raw['status'] as String).startsWith('FILLED')) {
      throw const FormatException('PAPER fill without audit identity');
    }
    return FnoPaperCycleEvent(at,raw['status'] as String,
        raw['reasonCode'] as String,raw['action'] as String,
        raw['instrumentKey'] as String?,raw['fillId'] as String?,
        raw['realizedPnlDeltaPaise'] as int?,raw['cashAfterPaise'] as int?);
  }
}

abstract interface class MindFnoAutoPaperRepository {
  Future<List<FnoPaperRoom>> rooms();
  Future<FnoPaperAutomationState> state(String roomId);
  Future<FnoPaperAutomationState> setEnabled(String roomId,bool enabled);
  Future<List<FnoPaperCycleEvent>> history(String roomId);
}

class HttpMindFnoAutoPaperRepository implements MindFnoAutoPaperRepository {
  const HttpMindFnoAutoPaperRepository(this.api);
  final AuthenticatedApiClient api;
  static String path(String id) {
    if(!RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(id)) {
      throw const FormatException('Invalid F&O PAPER room');
    }
    return '/api/customer/trading-rooms/$id/mind/fno/paper-automation';
  }

  @override
  Future<List<FnoPaperRoom>> rooms() async => [
    for (final room in await HttpCustomerRoomRepository(api).list())
      if(room.mode=='PAPER' && room.status=='PAPER_FUNDED' &&
          room.marketScope=='FNO' &&
          const ['Intraday','Swing'].contains(room.focus))
        FnoPaperRoom(room.id,room.name),
  ];

  @override
  Future<FnoPaperAutomationState> state(String roomId) async =>
    FnoPaperAutomationState.parse(await api.getJson(path(roomId)));

  @override
  Future<FnoPaperAutomationState> setEnabled(String roomId,bool enabled) async =>
    FnoPaperAutomationState.parse(await api.postJson(
        '${path(roomId)}/control',body:{'enabled':enabled}));

  @override
  Future<List<FnoPaperCycleEvent>> history(String roomId) async {
    final response=await api.getJson('${path(roomId)}/history');
    if(response is! List)throw const FormatException('Invalid F&O PAPER history');
    return [for(final item in response)FnoPaperCycleEvent.parse(item)];
  }
}
