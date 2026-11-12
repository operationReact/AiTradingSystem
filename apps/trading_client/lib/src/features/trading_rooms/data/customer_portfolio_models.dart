/// Provider-aware, read-only Portfolio presentation models.
/// No broker holdings are labeled MintTick-managed without reconciled order IDs.
library;

class PortfolioInstrument {
  const PortfolioInstrument({
    required this.broker,
    required this.symbol,
    required this.quantity,
    required this.position,
    this.name,
    this.lastPrice,
    this.marketValue,
    this.averagePrice,
    this.pnl,
  });

  final String broker;
  final String symbol;
  final String? name;
  final num? quantity;
  final bool position;
  final num? lastPrice;
  final num? marketValue;
  final num? averagePrice;
  final num? pnl;

  /// No externally held quantity is assumed to be a MintTick execution.
  String get provenance => 'BROKER REPORTED';

  static List<PortfolioInstrument>? decode(
    Object? response, {
    required String broker,
    bool position = false,
  }) {
    final rows = portfolioRecords(response, position ? 'positions' : 'holdings');
    if (rows == null || rows.length > 500) return null;
    final decoded = <PortfolioInstrument>[];
    for (final value in rows) {
      // A non-empty but unrecognizable response must not become a verified
      // empty portfolio. Only an actual [] is a broker-confirmed zero.
      if (value is! Map) return null;
      final symbol = _string(_first(value, [
        'tradingsymbol', 'trading_symbol', 'tradingSymbol',
        'symbol', 'symbol_name', 'instrument_key',
      ]));
      if (symbol == null) return null;
      final quantity = _number(_first(value, [
        'quantity', 'netqty', 'net_qty', 'net_quantity', 'holding_quantity',
      ]));
      final ltp = _number(_first(value, [
        'ltp', 'last_price', 'lastPrice', 'last_traded_price',
      ]));
      final providedValue = _number(_first(value, [
        'holdingvalue', 'holding_value', 'current_value', 'currentValue',
        'market_value', 'marketValue',
      ]));
      final inferredValue = !position && providedValue == null &&
          ltp != null && ltp >= 0 && quantity != null && quantity >= 0
          ? ltp * quantity : null;
      decoded.add(PortfolioInstrument(
        broker: broker,
        symbol: symbol,
        name: _string(_first(value, ['company_name', 'companyName', 'name'])),
        quantity: quantity,
        position: position,
        lastPrice: ltp,
        marketValue: providedValue ?? inferredValue,
        averagePrice: _number(_first(value, [
          'averageprice', 'average_price', 'averagePrice', 'buy_avg', 'buyAvg',
        ])),
        pnl: _number(_first(value, [
          'pnl', 'unrealised', 'unrealised_pnl', 'unrealized_pnl', 'profit_and_loss',
        ])),
      ));
     }
    return List.unmodifiable(decoded);
  }

  static Object? _first(Map row, List<String> fields) {
    for (final field in fields) {
      if (row.containsKey(field) && row[field] != null) return row[field];
    }
    return null;
  }

  static String? _string(Object? value) {
    if (value is! String) return null;
    final text = value.trim();
    if (text.isEmpty) return null;
    return text.length > 70 ? text.substring(0, 70) : text;
  }

  static num? _number(Object? value) {
    if (value is num) return value.toDouble().isFinite ? value : null;
    if (value is String) {
      final number = num.tryParse(value.replaceAll(',', '').trim());
      return number?.toDouble().isFinite == true ? number : null;
    }
    return null;
  }
}

/// Null = provider did not return a recognizable list; [] = verified empty.
/// Some provider versions put the array under a second data node.
List<dynamic>? portfolioRecords(Object? raw, String section) {
  if (raw is List) return raw;
  if (raw is! Map) return null;
  // Never substitute orders or positions for missing holdings (or vice versa).
  for (final name in [section, 'rows', 'data']) {
    final nested = raw[name];
    if (nested is List) return nested;
    if (nested is Map && !identical(nested, raw)) {
      final found = portfolioRecords(nested, section);
      if (found != null) return found;
    }
  }
  return null;
}

/// Correct Indian grouping, without inventing unknown financial values.
String portfolioRupees(num? value) {
  if (value == null || !value.toDouble().isFinite) return '—';
  final fixed = value.abs().toStringAsFixed(2);
  final bits = fixed.split('.');
  final digits = bits.first;
  String integral;
  if (digits.length <= 3) {
    integral = digits;
  } else {
    final last = digits.substring(digits.length - 3);
    var remaining = digits.substring(0, digits.length - 3);
    final groups = <String>[];
    while (remaining.length > 2) {
      groups.insert(0, remaining.substring(remaining.length - 2));
      remaining = remaining.substring(0, remaining.length - 2);
    }
    if (remaining.isNotEmpty) groups.insert(0, remaining);
    integral = "${groups.join(',')},$last";
  }
  return "${value < 0 ? '-' : ''}₹$integral.${bits.last}";
}

num? portfolioAmount(Object? raw, {required String broker}) {
  if (raw is! Map) return null;
  final data = raw['data'] is Map ? raw['data'] as Map : raw;
  Object? value;
  if (broker == 'UPSTOX') {
    // Upstox V3 splits spendable cash from pledged margin. Never add pledge
    // collateral to cash or holdings value. V2 provides combined margin in
    // equity (commodity is a deprecated duplicate/zero segment).
    final trade = data['available_to_trade'];
    if (trade is Map) {
      final cash = trade['cash_available_to_trade'];
      if (cash is Map) value = cash['total'];
    } else {
      final equity = data['equity'];
      if (equity is Map) {
        value = equity['available_margin'] ?? equity['available_cash'];
      }
    }
  } else if (broker == 'ANGEL_ONE') {
    value = data['availablecash'] ?? data['availableCash'];
  }
  if (value is num && value.toDouble().isFinite) return value;
  if (value is String) {
    final parsed = num.tryParse(value.replaceAll(',', ''));
    if (parsed?.toDouble().isFinite == true) return parsed;
  }
  return null;
}

/// A holdings market value is usable only when every row has a valuation.
/// Empty [] is a verified zero; null denotes unavailable source data.
num? portfolioHoldingValue(List<PortfolioInstrument>? rows) {
  if (rows == null) return null;
  num sum = 0;
  for (final row in rows) {
    if (row.marketValue == null) return null;
    sum += row.marketValue!;
  }
  return sum;
}
