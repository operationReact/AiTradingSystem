import 'package:flutter/foundation.dart';

@immutable
class MockQuote {
  const MockQuote({
    required this.symbol,
    required this.name,
    required this.price,
    required this.changePercent,
    required this.sparkline,
  });

  final String symbol;
  final String name;
  final double price;
  final double changePercent;
  final List<double> sparkline;
}

@immutable
class MockOpportunity {
  const MockOpportunity({
    required this.symbol,
    required this.company,
    required this.price,
    required this.changePercent,
    required this.rating,
    required this.confidence,
    required this.setup,
    required this.riskReward,
    required this.timeframe,
    required this.thesis,
    required this.entry,
    required this.stop,
    required this.target,
  });

  final String symbol;
  final String company;
  final double price;
  final double changePercent;
  final String rating;
  final int confidence;
  final String setup;
  final String riskReward;
  final String timeframe;
  final String thesis;
  final double entry;
  final double stop;
  final double target;
}

@immutable
class MockOrder {
  const MockOrder({
    required this.symbol,
    required this.side,
    required this.quantity,
    required this.averagePrice,
    required this.status,
    required this.time,
  });

  final String symbol;
  final String side;
  final int quantity;
  final double averagePrice;
  final String status;
  final String time;
}

@immutable
class MockPosition {
  const MockPosition({
    required this.symbol,
    required this.quantity,
    required this.averagePrice,
    required this.lastPrice,
  });

  final String symbol;
  final int quantity;
  final double averagePrice;
  final double lastPrice;

  double get marketValue => quantity * lastPrice;
  double get pnl => quantity * (lastPrice - averagePrice);
  double get pnlPercent => averagePrice == 0 ? 0 : (lastPrice - averagePrice) / averagePrice * 100;
}

@immutable
class MockNotificationItem {
  const MockNotificationItem({
    required this.title,
    required this.message,
    required this.category,
    required this.time,
    this.read = false,
  });

  final String title;
  final String message;
  final String category;
  final String time;
  final bool read;

  MockNotificationItem copyWith({bool? read}) => MockNotificationItem(
        title: title,
        message: message,
        category: category,
        time: time,
        read: read ?? this.read,
      );
}

@immutable
class MockBacktest {
  const MockBacktest({
    required this.name,
    required this.universe,
    required this.period,
    required this.returnPercent,
  });

  final String name;
  final String universe;
  final String period;
  final double returnPercent;
}

class MockReviewStore extends ChangeNotifier {
  MockReviewStore();

  final List<MockQuote> quotes = const [
    MockQuote(symbol: 'NIFTY', name: 'NIFTY 50', price: 22543.90, changePercent: 0.68, sparkline: [40, 42, 41, 46, 49, 48, 54, 58, 61]),
    MockQuote(symbol: 'BANKNIFTY', name: 'NIFTY Bank', price: 48912.30, changePercent: 0.79, sparkline: [34, 37, 39, 38, 44, 49, 47, 52, 57]),
    MockQuote(symbol: 'SENSEX', name: 'BSE Sensex', price: 74301.24, changePercent: 0.42, sparkline: [31, 32, 36, 34, 39, 41, 46, 44, 49]),
    MockQuote(symbol: 'RELIANCE', name: 'Reliance Industries Ltd.', price: 2945.60, changePercent: 1.32, sparkline: [22, 25, 27, 26, 31, 34, 38, 42, 47]),
    MockQuote(symbol: 'TCS', name: 'Tata Consultancy Services', price: 3876.20, changePercent: 0.96, sparkline: [26, 27, 31, 33, 32, 37, 40, 44, 46]),
    MockQuote(symbol: 'HDFCBANK', name: 'HDFC Bank Ltd.', price: 1642.30, changePercent: -0.34, sparkline: [48, 46, 45, 43, 44, 40, 39, 37, 38]),
    MockQuote(symbol: 'INFY', name: 'Infosys Ltd.', price: 1548.40, changePercent: 0.51, sparkline: [31, 33, 34, 37, 36, 39, 43, 42, 46]),
  ];

  final List<MockOpportunity> opportunities = const [
    MockOpportunity(
      symbol: 'RELIANCE',
      company: 'Reliance Industries Ltd.',
      price: 2945.60,
      changePercent: 1.32,
      rating: 'Strong Buy',
      confidence: 87,
      setup: 'Breakout',
      riskReward: '1 : 2.8',
      timeframe: '15 min',
      thesis: 'Price broke above key resistance with strong volume. Momentum remains constructive while the breakout zone holds.',
      entry: 2948.00,
      stop: 2918.00,
      target: 3032.00,
    ),
    MockOpportunity(
      symbol: 'TCS',
      company: 'Tata Consultancy Services',
      price: 3876.20,
      changePercent: 0.96,
      rating: 'Buy',
      confidence: 81,
      setup: 'Momentum',
      riskReward: '1 : 2.4',
      timeframe: '15 min',
      thesis: 'Momentum expanded after consolidation and price is holding above intraday VWAP with improving breadth.',
      entry: 3878.00,
      stop: 3848.00,
      target: 3950.00,
    ),
    MockOpportunity(
      symbol: 'INFY',
      company: 'Infosys Ltd.',
      price: 1548.40,
      changePercent: 0.51,
      rating: 'Watch',
      confidence: 74,
      setup: 'Trend continuation',
      riskReward: '1 : 2.1',
      timeframe: '30 min',
      thesis: 'Higher lows are intact and the stock is approaching a clean continuation trigger above the recent range.',
      entry: 1552.00,
      stop: 1536.00,
      target: 1586.00,
    ),
  ];

  final Set<String> watchlist = {'RELIANCE', 'TCS'};

  final List<MockOrder> orders = [
    const MockOrder(symbol: 'RELIANCE', side: 'BUY', quantity: 10, averagePrice: 2930.00, status: 'Completed', time: '10:24 AM'),
    const MockOrder(symbol: 'TCS', side: 'SELL', quantity: 5, averagePrice: 3882.00, status: 'Completed', time: '09:58 AM'),
    const MockOrder(symbol: 'INFY', side: 'BUY', quantity: 10, averagePrice: 1542.00, status: 'Cancelled', time: '09:40 AM'),
    const MockOrder(symbol: 'HDFCBANK', side: 'BUY', quantity: 10, averagePrice: 1646.00, status: 'Completed', time: '09:32 AM'),
  ];

  final List<MockPosition> positions = [
    const MockPosition(symbol: 'RELIANCE', quantity: 10, averagePrice: 2930.00, lastPrice: 2945.60),
    const MockPosition(symbol: 'TCS', quantity: 8, averagePrice: 3838.00, lastPrice: 3876.20),
    const MockPosition(symbol: 'INFY', quantity: 12, averagePrice: 1530.00, lastPrice: 1548.40),
    const MockPosition(symbol: 'HDFCBANK', quantity: 10, averagePrice: 1651.00, lastPrice: 1642.30),
  ];

  List<MockNotificationItem> notifications = const [
    MockNotificationItem(title: 'AI Opportunity Found', message: 'RELIANCE · Strong Buy (87%)', category: 'Alerts', time: '2m ago'),
    MockNotificationItem(title: 'Paper Trade Executed', message: 'Bought 10 RELIANCE @ ₹2,930.00', category: 'Trades', time: '15m ago'),
    MockNotificationItem(title: 'Target Hit', message: 'INFY target of ₹1,580 reached', category: 'Trades', time: '32m ago'),
    MockNotificationItem(title: 'Daily Summary', message: 'P&L: +₹12,340 (+1.23%)', category: 'System', time: '1h ago'),
  ];

  final List<MockBacktest> backtests = [
    const MockBacktest(name: 'Breakout Strategy', universe: 'NIFTY', period: '6 months', returnPercent: 14.2),
    const MockBacktest(name: 'Momentum V2', universe: 'Top 100', period: '1 year', returnPercent: 21.7),
    const MockBacktest(name: 'Mean Reversion', universe: 'NIFTY', period: '6 months', returnPercent: 6.3),
  ];

  bool brokerConnected = false;
  bool paperMode = true;
  bool biometricSignIn = true;
  bool appLock = true;
  bool marketHoursAlerts = true;
  bool stopLossEnabled = true;
  bool takeProfitEnabled = true;
  bool autoSquareOff = true;
  double virtualCapital = 1000000;
  double currentValue = 1058420;
  double todayPnl = 12340;
  double totalPnl = 58420;
  String perTradeRisk = '1.0% of capital';
  String dailyLossLimit = '3.0% of capital';
  int maxOpenPositions = 5;
  String theme = 'Dark (Default)';
  String language = 'English';

  double get investedValue => positions.fold(0, (sum, item) => sum + item.quantity * item.averagePrice);
  double get marketValue => positions.fold(0, (sum, item) => sum + item.marketValue);
  double get unrealizedPnl => positions.fold(0, (sum, item) => sum + item.pnl);

  void toggleWatchlist(String symbol) {
    if (!watchlist.remove(symbol)) watchlist.add(symbol);
    notifyListeners();
  }

  void connectBroker() {
    brokerConnected = true;
    notifications = [
      const MockNotificationItem(title: 'Angel One Connected', message: 'Broker review connection is ready.', category: 'System', time: 'now'),
      ...notifications,
    ];
    notifyListeners();
  }

  void disconnectBroker() {
    brokerConnected = false;
    notifyListeners();
  }

  void executePaperTrade(MockOpportunity opportunity) {
    orders.insert(
      0,
      MockOrder(
        symbol: opportunity.symbol,
        side: 'BUY',
        quantity: 10,
        averagePrice: opportunity.entry,
        status: 'Completed',
        time: 'now',
      ),
    );
    positions.add(
      MockPosition(
        symbol: opportunity.symbol,
        quantity: 10,
        averagePrice: opportunity.entry,
        lastPrice: opportunity.price,
      ),
    );
    todayPnl += 620;
    totalPnl += 620;
    currentValue += 620;
    notifications = [
      MockNotificationItem(
        title: 'Paper Trade Executed',
        message: 'Bought 10 ${opportunity.symbol} @ ₹${opportunity.entry.toStringAsFixed(2)}',
        category: 'Trades',
        time: 'now',
      ),
      ...notifications,
    ];
    notifyListeners();
  }

  void resetPortfolio() {
    positions.clear();
    orders.clear();
    currentValue = virtualCapital;
    todayPnl = 0;
    totalPnl = 0;
    notifyListeners();
  }

  void setVirtualCapital(double value) {
    virtualCapital = value;
    currentValue = value + totalPnl;
    notifyListeners();
  }

  void addBacktest() {
    backtests.insert(
      0,
      const MockBacktest(name: 'AI Breakout Scan', universe: 'Top 100', period: '3 months', returnPercent: 18.6),
    );
    notifyListeners();
  }

  void markAllNotificationsRead() {
    notifications = notifications.map((item) => item.copyWith(read: true)).toList(growable: false);
    notifyListeners();
  }
}
