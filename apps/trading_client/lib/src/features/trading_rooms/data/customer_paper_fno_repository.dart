import '../../../data/authenticated_api_client.dart';

/// PAPER-only NIFTY long-option contract. Every value is decoded strictly:
/// anything missing or out of contract throws, so nothing is invented.
/// Server routes: .../paper/fno/{market,orders,positions}.
class CustomerPaperFnoRepository {
  const CustomerPaperFnoRepository(this.api);

  static const _path = '/api/customer/trading-rooms';
  static final _instrument = RegExp(r'^NSE_FO\|[A-Za-z0-9._-]{1,64}$');

  final AuthenticatedApiClient api;

  Future<CustomerFnoMarket> market(String roomId, DateTime expiry) async {
    final raw = await api.getJson(
      '$_path/$roomId/paper/fno/market',
      query: {'expiry': _ymd(expiry)},
    );
    return CustomerFnoMarket.decode(raw);
  }

  Future<List<CustomerFnoFill>> orders(String roomId) async {
    final raw = await api.getJson('$_path/$roomId/paper/fno/orders');
    if (raw is! List) throw const FormatException('Invalid F&O orders');
    return raw.map(CustomerFnoFill.decode).toList(growable: false);
  }

  Future<List<CustomerFnoPosition>> positions(String roomId) async {
    final raw = await api.getJson('$_path/$roomId/paper/fno/positions');
    if (raw is! List) throw const FormatException('Invalid F&O positions');
    return raw.map(CustomerFnoPosition.decode).toList(growable: false);
  }

  /// BUY opens a long CE/PE; SELL closes an existing long. Server validates
  /// risk, cash, session, quote freshness and idempotency.
  Future<CustomerFnoFill> submit({
    required String roomId,
    required String idempotencyKey,
    required String instrumentKey,
    required DateTime expiry,
    required String side,
    required int lots,
    required num limitPrice,
  }) async {
    if (side != 'BUY' && side != 'SELL') {
      throw const FormatException('Side must be BUY or SELL');
    }
    if (!_instrument.hasMatch(instrumentKey)) {
      throw const FormatException('Invalid F&O instrument');
    }
    if (lots < 1 || lots > 20) throw const FormatException('Lots must be 1–20');
    if (!idempotencyKey.trim().isNotEmpty || idempotencyKey.length > 128) {
      throw const FormatException('Invalid idempotency key');
    }
    final raw = await api.postJson(
      '$_path/$roomId/paper/fno/orders',
      headers: {'Idempotency-Key': idempotencyKey},
      body: {
        'instrumentKey': instrumentKey,
        'expiry': _ymd(expiry),
        'side': side,
        'lots': lots,
        'limitPrice': limitPrice,
      },
    );
    return CustomerFnoFill.decode(raw);
  }

  static String _ymd(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}

class CustomerFnoLeg {
  const CustomerFnoLeg({
    required this.instrumentKey,
    required this.tradingSymbol,
    required this.side,
    required this.lotSize,
    required this.tickSize,
    required this.bid,
    required this.ask,
    required this.exchangeAt,
    required this.observedAt,
    required this.quoteFresh,
    required this.paperEligible,
  });

  final String instrumentKey;
  final String tradingSymbol;
  final String side;
  final int lotSize;
  final num tickSize;
  final num? bid;
  final num? ask;
  final DateTime? exchangeAt;
  final DateTime? observedAt;
  final bool quoteFresh;
  final bool paperEligible;

  static CustomerFnoLeg decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid F&O leg');
    final key = value['instrumentKey'];
    final symbol = value['tradingSymbol'];
    final side = value['side'];
    final lot = value['lotSize'];
    final tick = value['tickSize'];
    final fresh = value['quoteFresh'];
    final eligible = value['paperEligible'];
    if (key is! String || !CustomerPaperFnoRepository._instrument.hasMatch(key) ||
        symbol is! String || symbol.isEmpty || symbol.length > 64 ||
        (side != 'CE' && side != 'PE') ||
        lot is! int || lot < 1 || lot > 10000 ||
        tick is! num || tick <= 0 ||
        fresh is! bool || eligible is! bool) {
      throw const FormatException('Untrustworthy F&O leg');
    }
    final bid = value['bid'];
    final ask = value['ask'];
    if ((bid != null && bid is! num) || (ask != null && ask is! num)) {
      throw const FormatException('Invalid F&O quote');
    }
    // A quote is only usable when fresh and paper eligible; the server says so.
    return CustomerFnoLeg(
      instrumentKey: key,
      tradingSymbol: symbol,
      side: side as String,
      lotSize: lot,
      tickSize: tick,
      bid: bid as num?,
      ask: ask as num?,
      exchangeAt: _optionalTime(value['exchangeAt']),
      observedAt: _optionalTime(value['observedAt']),
      quoteFresh: fresh,
      paperEligible: eligible,
    );
  }
}

class CustomerFnoStrike {
  const CustomerFnoStrike({required this.strikePrice, this.call, this.put});
  final num strikePrice;
  final CustomerFnoLeg? call;
  final CustomerFnoLeg? put;
}

class CustomerFnoMarket {
  const CustomerFnoMarket({
    required this.underlyingKey,
    required this.expiry,
    required this.status,
    required this.snapshotAt,
    required this.displayRightsVerified,
    required this.liveOrderEnabled,
    required this.strikes,
  });

  final String underlyingKey;
  final DateTime expiry;
  /// MARKET_CLOSED, FRESH_QUOTES or NO_FRESH_QUOTES, as the server reports.
  final String status;
  final DateTime? snapshotAt;
  final bool displayRightsVerified;
  final bool liveOrderEnabled;
  final List<CustomerFnoStrike> strikes;

  bool get hasFreshQuotes => status == 'FRESH_QUOTES';

  static CustomerFnoMarket decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid F&O market');
    final underlying = value['underlyingKey'];
    final expiry = DateTime.tryParse(value['expiry']?.toString() ?? '');
    final status = value['status'];
    final display = value['displayRightsVerified'];
    final live = value['liveOrderEnabled'];
    final strikesRaw = value['strikes'];
    if (underlying is! String || expiry == null ||
        !const {'MARKET_CLOSED', 'FRESH_QUOTES', 'NO_FRESH_QUOTES'}.contains(status) ||
        display is! bool || strikesRaw is! List) {
      throw const FormatException('Untrustworthy F&O market');
    }
    // Live orders are never accepted from this PAPER contract.
    if (live == true) throw const FormatException('Unexpected LIVE F&O enablement');
    final strikes = strikesRaw.map((raw) {
      if (raw is! Map<String, dynamic>) throw const FormatException('Invalid strike');
      final price = raw['strikePrice'];
      if (price is! num || price <= 0) throw const FormatException('Invalid strike price');
      final call = raw['call'];
      final put = raw['put'];
      return CustomerFnoStrike(
        strikePrice: price,
        call: call == null ? null : CustomerFnoLeg.decode(call),
        put: put == null ? null : CustomerFnoLeg.decode(put),
      );
    }).toList(growable: false);
    return CustomerFnoMarket(
      underlyingKey: underlying,
      expiry: expiry,
      status: status as String,
      snapshotAt: _optionalTime(value['snapshotAt']),
      displayRightsVerified: display,
      liveOrderEnabled: live == true,
      strikes: strikes,
    );
  }
}

class CustomerFnoFill {
  const CustomerFnoFill({
    required this.id,
    required this.instrumentKey,
    required this.optionType,
    required this.expiry,
    required this.side,
    required this.quantity,
    required this.lotSize,
    required this.fillPrice,
    required this.notionalPaise,
    required this.estimatedFeesPaise,
    required this.realizedPnlDeltaPaise,
    required this.cashAfterPaise,
    required this.createdAt,
  });

  final String id;
  final String instrumentKey;
  final String optionType;
  final DateTime expiry;
  final String side;
  final int quantity;
  final int lotSize;
  final num fillPrice;
  final int notionalPaise;
  final int estimatedFeesPaise;
  final int realizedPnlDeltaPaise;
  final int cashAfterPaise;
  final DateTime? createdAt;

  static CustomerFnoFill decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid F&O fill');
    final id = value['id'];
    final key = value['instrumentKey'];
    final type = value['optionType'];
    final expiry = DateTime.tryParse(value['expiry']?.toString() ?? '');
    final side = value['side'];
    final qty = value['quantity'];
    final lot = value['lotSize'];
    final price = value['fillPrice'];
    final notional = value['notionalPaise'];
    final fees = value['estimatedFeesPaise'];
    final pnl = value['realizedPnlDeltaPaise'];
    final cash = value['cashAfterPaise'];
    if (id is! String || key is! String ||
        !CustomerPaperFnoRepository._instrument.hasMatch(key) ||
        (type != 'CE' && type != 'PE') || expiry == null ||
        (side != 'BUY' && side != 'SELL') ||
        qty is! int || qty < 1 || lot is! int || lot < 1 ||
        price is! num || price <= 0 ||
        notional is! int || fees is! int || pnl is! int || cash is! int) {
      throw const FormatException('Untrustworthy F&O fill');
    }
    return CustomerFnoFill(
      id: id,
      instrumentKey: key,
      optionType: type as String,
      expiry: expiry,
      side: side as String,
      quantity: qty,
      lotSize: lot,
      fillPrice: price,
      notionalPaise: notional,
      estimatedFeesPaise: fees,
      realizedPnlDeltaPaise: pnl,
      cashAfterPaise: cash,
      createdAt: _optionalTime(value['createdAt']),
    );
  }
}

class CustomerFnoPosition {
  const CustomerFnoPosition({
    required this.instrumentKey,
    required this.optionType,
    required this.expiry,
    required this.lotSize,
    required this.quantity,
    required this.costBasisPaise,
    required this.indicativeUnrealizedPnlPaise,
    required this.priceFresh,
  });

  final String instrumentKey;
  final String optionType;
  final DateTime expiry;
  final int lotSize;
  final int quantity;
  final int costBasisPaise;
  /// Null when no fresh valuation exists. Never shown as zero.
  final int? indicativeUnrealizedPnlPaise;
  final bool priceFresh;

  static CustomerFnoPosition decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid F&O position');
    final key = value['instrumentKey'];
    final type = value['optionType'];
    final expiry = DateTime.tryParse(value['expiry']?.toString() ?? '');
    final lot = value['lotSize'];
    final qty = value['quantity'];
    final basis = value['costBasisPaise'];
    final pnl = value['indicativeUnrealizedPnlPaise'];
    final fresh = value['priceFresh'];
    if (key is! String || !CustomerPaperFnoRepository._instrument.hasMatch(key) ||
        (type != 'CE' && type != 'PE') || expiry == null ||
        lot is! int || lot < 1 || qty is! int || qty < 0 ||
        basis is! int || (pnl != null && pnl is! int) || fresh is! bool) {
      throw const FormatException('Untrustworthy F&O position');
    }
    return CustomerFnoPosition(
      instrumentKey: key,
      optionType: type as String,
      expiry: expiry,
      lotSize: lot,
      quantity: qty,
      costBasisPaise: basis,
      indicativeUnrealizedPnlPaise: pnl as int?,
      priceFresh: fresh,
    );
  }
}

DateTime? _optionalTime(Object? value) {
  if (value == null) return null;
  final parsed = DateTime.tryParse(value.toString());
  if (parsed == null) throw const FormatException('Invalid timestamp');
  return parsed.toUtc();
}
