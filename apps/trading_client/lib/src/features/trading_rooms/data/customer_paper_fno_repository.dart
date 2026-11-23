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

  /// Always-on, owner-scoped capability endpoint. It must be checked before
  /// loading conditional F&O routes or showing any simulated order ticket.
  Future<CustomerFnoCapability> capability(String roomId) async =>
      CustomerFnoCapability.decode(
        await api.getJson('$_path/$roomId/paper/fno/capability'));

  /// Read-only bookkeeping receipt. It never authorizes trading by itself.
  Future<CustomerFnoReceipt> reconciliation(String roomId) async =>
      CustomerFnoReceipt.decode(await api.getJson('$_path/$roomId/paper/fno/reconciliation'));

  /// Server-verified listed expiries. The list is never inferred on the client.
  Future<CustomerFnoExpiries> expiries(String roomId) async =>
      CustomerFnoExpiries.decode(await api.getJson('$_path/$roomId/paper/fno/expiries'));

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

  /// Independent synthetic-only training sandbox. Never NSE or Upstox quotes.
  Future<CustomerFnoSandboxState> sandboxState(String roomId) async =>
      CustomerFnoSandboxState.decode(await api.getJson(
        '$_path/$roomId/paper/fno/sandbox/state'));

  /// Unknown outcomes are checked via sandboxState, never blindly resubmitted.
  Future<CustomerFnoSandboxFill> sandboxSubmit({
    required String roomId,
    required String idempotencyKey,
    required String contractCode,
    required String side,
    required int lots,
  }) async {
    if (!const {'SIM_FNO_CE', 'SIM_FNO_PE'}.contains(contractCode) ||
        !const {'BUY', 'SELL'}.contains(side) ||
        lots < 1 || lots > 20 ||
        !RegExp(r'^[A-Za-z0-9_-]{8,64}$').hasMatch(idempotencyKey)) {
      throw const FormatException('Invalid synthetic practice order');
    }
    return CustomerFnoSandboxFill.decode(await api.postJson(
      '$_path/$roomId/paper/fno/sandbox/orders',
      headers: {'Idempotency-Key': idempotencyKey},
      body: {'contractCode': contractCode, 'side': side, 'lots': lots},
    ));
  }

  static String _ymd(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}

/// Configuration preflight, not proof of exchange quotes or trade permission.
/// LIVE execution can never be enabled by this PAPER response.
class CustomerFnoCapability {
  const CustomerFnoCapability({
    required this.status,
    required this.readOnlyConfigured,
    required this.paperConfigured,
    required this.blockerCodes,
  });

  final String status;
  final bool readOnlyConfigured;
  final bool paperConfigured;
  final List<String> blockerCodes;

  static const _statuses = {
    'ROOM_HORIZON_UNSUPPORTED', 'FNO_PAPER_DISABLED',
    'FNO_V3_FEED_DISABLED', 'DISPLAY_RIGHTS_NOT_VERIFIED',
    'PAPER_USE_RIGHTS_NOT_VERIFIED', 'SOURCE_CHECK_REQUIRED',
  };

  static CustomerFnoCapability decode(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Invalid F&O capability response');
    }
    final status = value['status'];
    final readOnly = value['readOnlyConfigured'];
    final paper = value['paperConfigured'];
    final live = value['liveExecutionAuthorized'];
    final rawCodes = value['blockerCodes'];
    if (status is! String || !_statuses.contains(status) ||
        readOnly is! bool || paper is! bool || live != false ||
        rawCodes is! List || rawCodes.any((x) => x is! String ||
            !_statuses.contains(x) || x == 'SOURCE_CHECK_REQUIRED') ||
        (paper && !readOnly) ||
        (status == 'SOURCE_CHECK_REQUIRED' && rawCodes.isNotEmpty) ||
        (status != 'SOURCE_CHECK_REQUIRED' &&
            (rawCodes.isEmpty || rawCodes.first != status)) ||
        (status == 'SOURCE_CHECK_REQUIRED' && (!readOnly || !paper))) {
      throw const FormatException('Untrustworthy F&O capability');
    }
    return CustomerFnoCapability(
      status: status,
      readOnlyConfigured: readOnly,
      paperConfigured: paper,
      blockerCodes: List.unmodifiable(rawCodes.cast<String>()),
    );
  }
}

/// Bookkeeping consistency (walletReconciled, realized P&L) kept separate from
/// trading readiness (tradingApproved, rights, market data). Read-only.
class CustomerFnoReceipt {
  const CustomerFnoReceipt({
    required this.status,
    required this.checkedAt,
    required this.recordedFills,
    required this.openContracts,
    required this.computedRealizedPnlPaise,
    required this.recordedRealizedPnlPaise,
    required this.walletReconciled,
    required this.marketDataVerified,
    required this.providerRightsVerified,
    required this.tradingApproved,
    required this.blockerCodes,
  });

  final String status;
  final DateTime? checkedAt;
  final int recordedFills;
  final int openContracts;
  final int computedRealizedPnlPaise;
  final int recordedRealizedPnlPaise;
  final bool walletReconciled;
  final bool marketDataVerified;
  final bool providerRightsVerified;
  final bool tradingApproved;
  final List<String> blockerCodes;

  static CustomerFnoReceipt decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid F&O receipt');
    final status = value['status'];
    final fills = value['recordedFills'];
    final open = value['openContracts'];
    final computed = value['computedRealizedPnlPaise'];
    final recorded = value['recordedRealizedPnlPaise'];
    final wallet = value['walletReconciled'];
    final market = value['marketDataVerified'];
    final rights = value['providerRightsVerified'];
    final approved = value['tradingApproved'];
    final blockers = value['blockerCodes'];
    if (status is! String || status.isEmpty || status.length > 64 ||
        fills is! int || fills < 0 || open is! int || open < 0 ||
        computed is! int || recorded is! int ||
        wallet is! bool || market is! bool || rights is! bool || approved is! bool ||
        blockers is! List || blockers.any((b) => b is! String)) {
      throw const FormatException('Untrustworthy F&O receipt');
    }
    final checked = value['checkedAt'] == null
        ? null : DateTime.tryParse(value['checkedAt'].toString());
    return CustomerFnoReceipt(
      status: status,
      checkedAt: checked?.toUtc(),
      recordedFills: fills,
      openContracts: open,
      computedRealizedPnlPaise: computed,
      recordedRealizedPnlPaise: recorded,
      walletReconciled: wallet,
      marketDataVerified: market,
      providerRightsVerified: rights,
      tradingApproved: approved,
      blockerCodes: List.unmodifiable(blockers.cast<String>()),
    );
  }
}

/// Listed NIFTY option expiries. Only `VERIFIED` carries dates; anything else is blocked.
class CustomerFnoExpiries {
  const CustomerFnoExpiries({
    required this.status,
    required this.observedAt,
    required this.expiries,
  });

  final String status;
  final DateTime? observedAt;
  final List<DateTime> expiries;

  bool get verified =>
      status == 'VERIFIED' && observedAt != null && expiries.isNotEmpty;

  static final _date = RegExp(r'^\d{4}-\d{2}-\d{2}$');

  static CustomerFnoExpiries decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid F&O expiries');
    final status = value['status'];
    final underlying = value['underlyingKey'];
    final raw = value['expiries'];
    if (status is! String || status.isEmpty || status.length > 64 ||
        underlying is! String || raw is! List) {
      throw const FormatException('Untrustworthy F&O expiries');
    }
    final observedAt = value['observedAt'] == null
        ? null : DateTime.tryParse(value['observedAt'].toString());
    if (value['observedAt'] != null && observedAt == null) {
      throw const FormatException('Invalid expiry timestamp');
    }
    final dates = <DateTime>[];
    for (final item in raw) {
      if (item is! String || !_date.hasMatch(item)) {
        throw const FormatException('Invalid expiry date');
      }
      final parsed = DateTime.tryParse(item);
      // Reject rolled-over dates such as 2026-02-31 instead of coercing them.
      if (parsed == null || parsed.year != int.parse(item.substring(0, 4)) ||
          parsed.month != int.parse(item.substring(5, 7)) ||
          parsed.day != int.parse(item.substring(8, 10))) {
        throw const FormatException('Invalid expiry date');
      }
      dates.add(parsed);
    }
    // A blocked or empty listing must never carry dates to the UI.
    if (status != 'VERIFIED' && dates.isNotEmpty) {
      throw const FormatException('Expiries returned without a verified listing');
    }
    final unique = dates.toSet().toList()..sort();
    return CustomerFnoExpiries(
      status: status,
      observedAt: observedAt?.toUtc(),
      expiries: List.unmodifiable(unique),
    );
  }
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
    this.ltp, this.previousClose, this.openInterest,
    this.volume, this.bidQuantity, this.askQuantity, this.lastTradedQuantity,
    this.iv, this.delta, this.theta, this.gamma, this.vega,
  });

  /// Broker V3 LTPC, open-interest and Greeks. May be absent; REST
  /// option-chain values must never silently fill these fields.
  final num? ltp;
  final num? previousClose;
  final int? openInterest;
  /// These are V3 exchange-time observations only, never V2 reference data.
  /// Volume != OI != last trade quantity != top bid/ask quantity.
  final int? volume, bidQuantity, askQuantity, lastTradedQuantity;
  final num? iv, delta, theta, gamma, vega;

  /// Only populated by a fresh exchange-timed authorised V3 observation.
  bool get priceChangeVerified => quoteFresh &&
      ltp != null && previousClose != null && previousClose! > 0;
  num? get priceChangePct => priceChangeVerified
      ? ((ltp! - previousClose!) * 100 / previousClose!) : null;

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
    final ltp = value['ltp'];
    final previousClose = value['previousClose'];
    final openInterest = value['openInterest'];
    final volume = value['volume'];
    final bidQuantity = value['bidQuantity'];
    final askQuantity = value['askQuantity'];
    final lastTradedQuantity = value['lastTradedQuantity'];
    final iv = value['iv'];
    final delta = value['delta'];
    final theta = value['theta'];
    final gamma = value['gamma'];
    final vega = value['vega'];
    for (final metric in [ltp, previousClose, iv, delta, theta, gamma, vega]) {
      if (metric != null && (metric is! num || !metric.isFinite)) {
        throw const FormatException('Invalid V3 option metric');
      }
    }
    for (final count in [
      openInterest, volume, bidQuantity, askQuantity, lastTradedQuantity,
    ]) {
      if (count != null && (count is! int || count < 0)) {
        throw const FormatException('Invalid V3 option count');
      }
    }
    if ((ltp != null && ltp <= 0) ||
        (previousClose != null && previousClose <= 0) ||
        (iv != null && iv < 0)) {
      throw const FormatException('Invalid V3 option metric range');
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
      ltp: ltp as num?, previousClose: previousClose as num?,
      openInterest: openInterest as int?,
      volume: volume as int?,
      bidQuantity: bidQuantity as int?,
      askQuantity: askQuantity as int?,
      lastTradedQuantity: lastTradedQuantity as int?,
      iv: iv as num?, delta: delta as num?, theta: theta as num?,
      gamma: gamma as num?, vega: vega as num?,
    );
  }
}

class CustomerFnoStrike {
  const CustomerFnoStrike({required this.strikePrice, this.call, this.put});
  final num strikePrice;
  final CustomerFnoLeg? call;
  final CustomerFnoLeg? put;
}

/// Upstox V2 option-chain values, fetched at market.snapshotAt.
/// They are read-only observations, not exchange-timed or order-eligible V3 ticks.
class CustomerFnoChainSnapshotIndicator {
  const CustomerFnoChainSnapshotIndicator({
    this.ltp, this.bid, this.ask, this.openInterest,
    this.volume, this.previousOpenInterest,
    this.iv, this.delta, this.theta, this.gamma, this.vega,
  });

  final num? ltp, bid, ask, iv, delta, theta, gamma, vega;
  /// Read-only Upstox V2 chain counts (HTTP observation time, not a live tick).
  final int? openInterest, volume, previousOpenInterest;
  int? get openInterestChange =>
      openInterest != null && previousOpenInterest != null
          ? openInterest! - previousOpenInterest! : null;

  static CustomerFnoChainSnapshotIndicator decode(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid Upstox option-chain snapshot');
    }
    num? metric(String key, {bool positive = false, bool nonnegative = false}) {
      final v = raw[key];
      if (v == null) return null;
      if (v is! num || !v.isFinite ||
          (positive && v <= 0) || (nonnegative && v < 0)) {
        throw const FormatException('Invalid read-only option-chain value');
      }
      return v;
    }
    final oi = raw['openInterest'];
    final volume = raw['volume'];
    final previousOi = raw['previousOpenInterest'];
    for (final count in [oi, volume, previousOi]) {
      if (count != null && (count is! int || count < 0)) {
        throw const FormatException('Invalid option-chain count');
      }
    }
    return CustomerFnoChainSnapshotIndicator(
      ltp: metric('ltp', positive: true),
      bid: metric('bid', positive: true),
      ask: metric('ask', positive: true),
      openInterest: oi as int?,
      volume: volume as int?,
      previousOpenInterest: previousOi as int?,
      iv: metric('iv', nonnegative: true),
      delta: metric('delta'), theta: metric('theta'),
      gamma: metric('gamma'), vega: metric('vega'),
    );
  }
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
    this.underlyingReferenceSpot,
    this.chainSnapshotIndicators = const {},
  });

  final String underlyingKey;
  final DateTime expiry;
  /// MARKET_CLOSED, FRESH_QUOTES or NO_FRESH_QUOTES, as the server reports.
  final String status;
  final DateTime? snapshotAt;
  final bool displayRightsVerified;
  final bool liveOrderEnabled;
  final List<CustomerFnoStrike> strikes;
  /// REST chain reference spot used only for contract ordering, never live LTP.
  final num? underlyingReferenceSpot;
  /// Separate read-only V2 chain snapshots; never part of V3 quote freshness.
  final Map<String, CustomerFnoChainSnapshotIndicator> chainSnapshotIndicators;

  bool get hasFreshQuotes => status == 'FRESH_QUOTES';

  static CustomerFnoMarket decode(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('Invalid F&O market');
    final underlying = value['underlyingKey'];
    final expiry = DateTime.tryParse(value['expiry']?.toString() ?? '');
    final status = value['status'];
    final display = value['displayRightsVerified'];
    final live = value['liveOrderEnabled'];
    final strikesRaw = value['strikes'];
    final referenceSpot = value['underlyingReferenceSpot'];
    if (referenceSpot != null && (referenceSpot is! num ||
        !referenceSpot.isFinite || referenceSpot <= 0)) {
      throw const FormatException('Invalid F&O reference spot');
    }
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
    final indicatorsRaw = value['chainSnapshotIndicators'];
    if (indicatorsRaw != null && indicatorsRaw is! Map<String, dynamic>) {
      throw const FormatException('Invalid option-chain snapshot map');
    }
    final validKeys = <String>{
      for (final strike in strikes)
        if (strike.call != null) strike.call!.instrumentKey,
      for (final strike in strikes)
        if (strike.put != null) strike.put!.instrumentKey,
    };
    final snapshots = <String, CustomerFnoChainSnapshotIndicator>{};
    if (indicatorsRaw != null) {
      if (indicatorsRaw.length > 512) {
        throw const FormatException('Oversized option-chain snapshot map');
      }
      for (final entry in indicatorsRaw.entries) {
        if (!validKeys.contains(entry.key)) {
          throw const FormatException('Unlisted option-chain snapshot key');
        }
        snapshots[entry.key] =
            CustomerFnoChainSnapshotIndicator.decode(entry.value);
      }
    }
    return CustomerFnoMarket(
      underlyingKey: underlying,
      expiry: expiry,
      status: status as String,
      snapshotAt: _optionalTime(value['snapshotAt']),
      displayRightsVerified: display,
      liveOrderEnabled: live == true,
      strikes: strikes,
      underlyingReferenceSpot: referenceSpot as num?,
      chainSnapshotIndicators: Map.unmodifiable(snapshots),
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

/// This is *only* a synthetic learning market, never a quotation of real NSE
/// F&O instruments. The backend must certify zero exchange data and orders.
class CustomerFnoSandboxContract {
  const CustomerFnoSandboxContract({
    required this.code, required this.label, required this.optionType,
    required this.lotSize, required this.buyPrice, required this.sellPrice,
  });
  final String code;
  final String label;
  final String optionType;
  final int lotSize;
  final num buyPrice;
  final num sellPrice;

  static CustomerFnoSandboxContract decode(Object? raw) {
    if (raw is! Map<String, dynamic>) throw const FormatException('Invalid practice contract');
    final code = raw['code'];
    final label = raw['label'];
    final type = raw['optionType'];
    final lot = raw['lotSize'];
    final buy = raw['buyPrice'];
    final sell = raw['sellPrice'];
    if (!const {'SIM_FNO_CE', 'SIM_FNO_PE'}.contains(code) ||
        !const {'CE', 'PE'}.contains(type) ||
        label is! String || !label.contains('Synthetic') ||
        lot != 10 || buy is! num || sell is! num ||
        !buy.isFinite || !sell.isFinite || buy <= 0 || sell <= 0 ||
        sell > buy) {
      throw const FormatException('Untrustworthy practice contract');
    }
    return CustomerFnoSandboxContract(
      code: code as String, label: label, optionType: type as String,
      lotSize: lot as int, buyPrice: buy, sellPrice: sell);
  }
}

class CustomerFnoSandboxPosition {
  const CustomerFnoSandboxPosition(this.contractCode, this.quantity, this.costBasisPaise);
  final String contractCode;
  final int quantity;
  final int costBasisPaise;

  static CustomerFnoSandboxPosition decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        !const {'SIM_FNO_CE', 'SIM_FNO_PE'}.contains(raw['contractCode']) ||
        raw['quantity'] is! int || (raw['quantity'] as int) <= 0 ||
        raw['costBasisPaise'] is! int || (raw['costBasisPaise'] as int) < 0) {
      throw const FormatException('Invalid synthetic position');
    }
    return CustomerFnoSandboxPosition(
      raw['contractCode'] as String, raw['quantity'] as int,
      raw['costBasisPaise'] as int);
  }
}

class CustomerFnoSandboxFill {
  const CustomerFnoSandboxFill({
    required this.id, required this.contractCode, required this.side,
    required this.lots, required this.quantity, required this.fillPrice,
    required this.cashAfterPaise, required this.realizedPnlDeltaPaise,
  });
  final String id;
  final String contractCode;
  final String side;
  final int lots;
  final int quantity;
  final num fillPrice;
  final int cashAfterPaise;
  final int realizedPnlDeltaPaise;

  static CustomerFnoSandboxFill decode(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['id'] is! String ||
        !const {'SIM_FNO_CE', 'SIM_FNO_PE'}.contains(raw['contractCode']) ||
        !const {'BUY', 'SELL'}.contains(raw['side']) ||
        raw['lots'] is! int || raw['lots'] < 1 || raw['lots'] > 20 ||
        raw['quantity'] is! int || raw['quantity'] != raw['lots'] * 10 ||
        raw['fillPrice'] is! num || raw['fillPrice'] <= 0 ||
        raw['cashAfterPaise'] is! int || raw['cashAfterPaise'] < 0 ||
        raw['realizedPnlDeltaPaise'] is! int) {
      throw const FormatException('Invalid synthetic fill');
    }
    return CustomerFnoSandboxFill(
      id: raw['id'] as String,
      contractCode: raw['contractCode'] as String,
      side: raw['side'] as String,
      lots: raw['lots'] as int,
      quantity: raw['quantity'] as int,
      fillPrice: raw['fillPrice'] as num,
      cashAfterPaise: raw['cashAfterPaise'] as int,
      realizedPnlDeltaPaise: raw['realizedPnlDeltaPaise'] as int);
  }
}

class CustomerFnoSandboxState {
  const CustomerFnoSandboxState({
    required this.initialCashPaise, required this.availableCashPaise,
    required this.realizedPnlPaise, required this.ledgerReconciled,
    required this.recordedFills, required this.contracts, required this.positions,
    required this.orders,
  });
  final int initialCashPaise;
  final int availableCashPaise;
  final int realizedPnlPaise;
  final bool ledgerReconciled;
  final int recordedFills;
  final List<CustomerFnoSandboxContract> contracts;
  final List<CustomerFnoSandboxPosition> positions;
  final List<CustomerFnoSandboxFill> orders;

  static CustomerFnoSandboxState decode(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['mode'] != 'SYNTHETIC_PRACTICE' ||
        raw['synthetic'] != true || raw['exchangeDataUsed'] != false ||
        raw['brokerOrdersEnabled'] != false ||
        raw['initialCashPaise'] is! int || raw['initialCashPaise'] <= 0 ||
        raw['availableCashPaise'] is! int || raw['availableCashPaise'] < 0 ||
        raw['realizedPnlPaise'] is! int ||
        raw['ledgerReconciled'] is! bool ||
        raw['recordedFills'] is! int || raw['recordedFills'] < 0 ||
        raw['contracts'] is! List || raw['positions'] is! List ||
        raw['orders'] is! List) {
      throw const FormatException('Untrustworthy synthetic sandbox response');
    }
    final contracts = (raw['contracts'] as List)
        .map(CustomerFnoSandboxContract.decode).toList(growable: false);
    final positions = (raw['positions'] as List)
        .map(CustomerFnoSandboxPosition.decode).toList(growable: false);
    final orders = (raw['orders'] as List)
        .map(CustomerFnoSandboxFill.decode).toList(growable: false);
    if (contracts.length != 2 ||
        contracts.map((c) => c.code).toSet().length != 2) {
      throw const FormatException('Unexpected synthetic contract list');
    }
    return CustomerFnoSandboxState(
      initialCashPaise: raw['initialCashPaise'] as int,
      availableCashPaise: raw['availableCashPaise'] as int,
      realizedPnlPaise: raw['realizedPnlPaise'] as int,
      ledgerReconciled: raw['ledgerReconciled'] as bool,
      recordedFills: raw['recordedFills'] as int,
      contracts: List.unmodifiable(contracts),
      positions: List.unmodifiable(positions),
      orders: List.unmodifiable(orders),
    );
  }
}
