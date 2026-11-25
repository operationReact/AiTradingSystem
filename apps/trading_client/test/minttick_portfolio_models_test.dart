import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_portfolio_models.dart';

void main() {
  test('Angel One and Upstox holdings retain source and never infer MintTick execution', () {
    final angel = PortfolioInstrument.decode([
      {'tradingsymbol': 'RELIANCE-EQ', 'quantity': '2', 'ltp': '1500.50'},
    ], broker: 'ANGEL_ONE')!;
    final upstox = PortfolioInstrument.decode({
      'data': {'holdings': [
        {'trading_symbol': 'SBIN', 'quantity': 3, 'last_price': 750},
      ]}
    }, broker: 'UPSTOX')!;
    expect(angel.single.provenance, 'BROKER REPORTED');
    expect(upstox.single.provenance, 'BROKER REPORTED');
    expect(portfolioHoldingValue(angel), 3001);
    expect(portfolioHoldingValue(upstox), 2250);
    expect(portfolioRupees(5251), '₹5,251.00');
  });

  test('unavailable source is not misreported as zero', () {
    expect(PortfolioInstrument.decode(null, broker: 'ANGEL_ONE'), isNull);
    expect(PortfolioInstrument.decode({'unknown': true}, broker: 'UPSTOX'), isNull);
    expect(PortfolioInstrument.decode([], broker: 'UPSTOX'), isEmpty);
    expect(portfolioHoldingValue(null), isNull);
    expect(portfolioHoldingValue([]), 0);
    expect(portfolioRupees(null), '—');
    expect(PortfolioInstrument.decode([
      {'non_symbol_field': 'abc'},
    ], broker: 'UPSTOX'), isNull);
    expect(PortfolioInstrument.decode([
      {'trading_symbol': 'SBIN'}, {'other': 1},
    ], broker: 'UPSTOX'), isNull);
    expect(PortfolioInstrument.decode({
      'positions': [{'trading_symbol': 'NIFTY'}]
    }, broker: 'UPSTOX'), isNull);
  });

  test('invalid metrics and incomplete valuations fail closed', () {
    final holding = PortfolioInstrument.decode([
      {'trading_symbol': 'INFY', 'quantity': 'not numeric',
       'last_price': 'not numeric'},
    ], broker: 'UPSTOX');
    expect(holding, isNotNull);
    expect(holding!.single.marketValue, isNull);
    expect(portfolioHoldingValue(holding), isNull);
    expect(portfolioAmount({'equity': {'available_margin': '12345.67'}},
      broker: 'UPSTOX'), 12345.67);
    expect(portfolioAmount({'data': {'available_to_trade': {
      'total': 9000, 'cash_available_to_trade': {'total': 7000},
      'pledge_available_to_trade': {'total': 2000},
    }}}, broker: 'UPSTOX'), 7000);
    expect(portfolioAmount({'data': {'equity': {'available_margin': 0},
      'commodity': {'available_margin': 2000}}},
      broker: 'UPSTOX'), 0);
    expect(portfolioAmount({'available_to_trade': {'total': 9000}},
      broker: 'UPSTOX'), isNull);
    expect(portfolioAmount({'availablecash': '1,234.50'},
      broker: 'ANGEL_ONE'), 1234.50);
  });

  test('positions are not added to holdings valuations', () {
    final positions = PortfolioInstrument.decode([
      {'tradingsymbol': 'NIFTY', 'netqty': '-50', 'ltp': '130'},
    ], broker: 'ANGEL_ONE', position: true)!;
    expect(positions.single.position, isTrue);
    expect(positions.single.quantity, -50);
    expect(positions.single.marketValue, isNull);
  });
}
