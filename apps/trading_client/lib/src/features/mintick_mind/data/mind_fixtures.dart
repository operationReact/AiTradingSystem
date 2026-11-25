/// View-only fixture data ported from the user-approved standalone HTML.
//// This does not represent installed or profitable trading strategies.
class MindStrategy {
  const MindStrategy({required this.id, required this.name, required this.family, required this.modes, required this.status});
  final String id;
  final String name;
  final String family;
  final List<String> modes;
  final String status;
}
class MindModel {
  const MindModel(this.name, this.family, this.role, this.status, this.metric, this.description);
  final String name;
  final String family;
  final String role;
  final String status;
  final String metric;
  final String description;
}
class MindCompany {
  const MindCompany(this.symbol, this.name, this.stage, this.change);
  final String symbol;
  final String name;
  final String stage;
  final String change;
}
class MindFixtures {
  static const strategyFamilies = <String>[
    'Trend Following',
    'Momentum',
    'Breakout',
    'Mean Reversion',
    'Price Action & Candlesticks',
    'Volume & Liquidity',
    'Volatility',
    'Statistical Arbitrage',
    'Event Driven',
    'Fundamental & Multibagger',
    'Factor & Portfolio',
    'Market Regime & Macro',
    'Market Microstructure',
    'AI & Machine Learning',
    'Futures',
    'Options — Directional',
    'Options — Volatility',
    'Options — Hedging',
    'Short Selling',
    'Execution & Risk',
  ];
  static const _catalog = <(String, String, String)>[
    ('Trend Following', 'Intraday Swing Delivery', 'SMA Cross|EMA Cross|Supertrend|ADX Trend Strength|MACD Trend|Ichimoku Cloud|Parabolic SAR|Donchian Trend|Multi-Timeframe Trend Alignment|20 EMA Pullback|Anchored VWAP Trend|Higher High Higher Low'),
    ('Momentum', 'Intraday Swing Delivery', 'RSI Momentum|Relative Strength Rotation|ROC Acceleration|MACD Signal Crossover|52-Week High Momentum|Stochastic Momentum|Momentum Divergence|Momentum Ignition|Trend Continuation|TSI Momentum|Cross-Sectional Momentum'),
    ('Breakout', 'Intraday Swing Delivery', 'Opening Range Breakout|Donchian Channel Breakout|52-Week High Breakout|Volatility Breakout|Consolidation Breakout|Darvas Box|Flag Breakout|Triangle Breakout|Cup and Handle|Pivot Breakout|Range Expansion Breakout|Multi-Day High Breakout'),
    ('Mean Reversion', 'Intraday Swing', 'RSI Oversold Rebound|RSI Overbought Fade|Bollinger Band Reversion|VWAP Reclaim|Z-Score Reversion|Keltner Mean Reversion|Gap Fill Reversion|Overnight Reversal|Short-Term Oversold|Distance-from-MA Reversion|Reversion after Panic'),
    ('Price Action & Candlesticks', 'Intraday Swing Delivery', 'Support Resistance Bounce|Support Resistance Break|Inside Bar|Engulfing Pattern|Hammer Reversal|Shooting Star|Morning Star|Evening Star|Doji Context|Double Top Bottom|Head and Shoulders|Ascending Triangle|Descending Triangle|Market Structure Break|Fair Value Gap Research|Fibonacci Pullback'),
    ('Volume & Liquidity', 'Intraday Swing', 'Volume Spike Confirmation|On-Balance Volume Trend|Accumulation Distribution|Chaikin Money Flow|Money Flow Index|Volume Profile Reversion|VWAP Band Breakout|Anchored VWAP Reclaim|Unusual Volume Detection|Liquidity Sweep Research|Delivery Volume Breakout'),
    ('Volatility', 'Intraday Swing', 'ATR Breakout|ATR Trailing Stop|Bollinger Squeeze|Keltner Squeeze|Volatility Contraction Pattern|Historical Volatility Regime|Volatility Expansion|Range Compression|Volatility Targeting|Intraday Range Forecast'),
    ('Statistical Arbitrage', 'Swing Research', 'Pairs Mean Reversion|Cointegration Spread|Sector Relative Value|Beta-Neutral Spread|Correlation Dislocation|Cross-Sectional Z-Score|Residual Momentum|Statistical Basket Rebalance|Factor Neutral Reversion'),
    ('Event Driven', 'Intraday Swing Delivery', 'Earnings Surprise|Post Earnings Drift|Results Gap Continuation|Guidance Revision|Corporate Action Adjustment|Buyback Announcement|Dividend Event|Index Rebalance|Sector Policy Event|Management Change Event|Order Win Inflection|Pledge Disclosure Watch'),
    ('Fundamental & Multibagger', 'Delivery Long-Term Research', 'Quality Compounders|Earnings Acceleration|ROCE Improvement|Free Cash Flow Growth|Debt Deleveraging|GARP Growth at Reasonable Price|Undervalued Cash Flows|Operating Leverage Inflection|Capacity Expansion Inflection|Margin Expansion|Smallcap Business Inflection|Promoter Governance Screening|Capital Allocation Quality|Industry Consolidation|Fundamental Momentum|Financial Turnaround|High ROIC Reinvestment'),
    ('Factor & Portfolio', 'Delivery Long-Term Research', 'Quality Factor|Value Factor|Low Volatility Factor|Momentum Factor|Size Factor|Multi-Factor Rank|Sector Rotation|Equal Weight Rebalance|Risk Parity|Volatility Target Portfolio|Minimum Variance|Cash Buffer Rotation|Core Satellite Allocation'),
    ('Market Regime & Macro', 'Intraday Swing Delivery', 'Trend Range Classifier|High Volatility Regime|Low Liquidity Avoidance|Market Breadth Shift|Sector Relative Strength|Risk-On Risk-Off|Global Market Spillover|Rate Sensitivity|Oil Price Sensitivity|FX Sensitivity|Event Volatility Gate'),
    ('Market Microstructure', 'Intraday Research', 'Bid Ask Spread Filter|Order Book Imbalance|Liquidity Depth Gate|Microprice Signal Research|Trade Flow Imbalance|Market Impact Estimator|Session Opening Liquidity|Intraday Seasonality|Quote Staleness Gate'),
    ('AI & Machine Learning', 'Research Swing Delivery', 'Tabular Alpha Classification|Time-Series Forecast|Cross-Sectional ML Ranker|Static Ensemble|Adaptive Ensemble|Market Regime HMM|Isolation Forest Anomaly Gate|Financial NLP Context|Event Intelligence Ranker|Calibrated Meta Decision|Uncertainty Abstention|Contextual Bandit Research|Reinforcement Learning Research|Feature Drift Gate|Model Disagreement Gate'),
    ('Futures', 'F&O Research', 'Futures Basis Carry|Futures Calendar Spread|Rollover Signal|Open Interest Trend|Price OI Divergence|Cash Futures Arbitrage Research|Futures Momentum|Futures Mean Reversion|Futures Hedge Overlay|Contract Liquidity Gate|Expiry Basis Reversion'),
    ('Options — Directional', 'F&O Research', 'Long Call|Long Put|Bull Call Debit Spread|Bear Put Debit Spread|Bull Put Credit Spread|Bear Call Credit Spread|Protective Put|Covered Call|Collar|Call Ratio Spread Research|Put Ratio Spread Research'),
    ('Options — Volatility', 'F&O Research', 'Long Straddle|Long Strangle|Short Straddle Research|Short Strangle Research|Iron Condor|Iron Butterfly|Long Butterfly|Calendar Spread|Diagonal Spread|IV Rank Mean Reversion|Volatility Crush Research|Gamma Scalping Research'),
    ('Options — Hedging', 'F&O Research', 'Delta Hedge|Beta Hedge with Options|Tail Risk Put Hedge|Put Spread Collar|Defined Risk Spread Roll|Expiry Risk Neutralization|Portfolio Protective Overlay|Greeks Exposure Rebalance|Skew-Based Hedge'),
    ('Short Selling', 'Intraday Research', 'Intraday Trend Short|Bearish Breakdown|Failed Breakout Short|VWAP Rejection Short|Bearish Momentum Short|Gap Down Continuation|Overbought Short Reversion|Borrow-Eligible Positional Short Research'),
    ('Execution & Risk', 'Intraday Swing Delivery', 'Stop Loss Discipline|Trailing Exit|Time Based Exit|Exposure Cap|Portfolio Correlation Gate|Position Sizing Volatility|Drawdown Kill Switch|Gap Risk Filter|Pretrade Liquidity Check|Dynamic Participation Limit|Order Slicing Research|Broker Reconciliation Gate'),
  ];
  static final List<MindStrategy> strategies = _buildStrategies();
  static List<MindStrategy> _buildStrategies() {
    final items = <MindStrategy>[];
    for (final (family, modes, names) in _catalog) {
      for (final name in names.split('|')) {
        final n = items.length + 1;
        final research = modes.contains('Research') || modes.contains('F&O');
        items.add(MindStrategy(
          id: 'STR-${n.toString().padLeft(3, '0')}',
          name: name,
          family: family,
          modes: modes.split(' '),
          status: research ? 'Research' : (n % 11 == 0 ? 'Paused' : n % 7 == 0 ? 'Watch' : 'Paper'),
        ));
      }
    }
    return List.unmodifiable(items);
  }
  static const models = <MindModel>[
    MindModel('LightGBM', 'Structured Alpha', 'Champion', 'Research', 'AUC 0.81', 'Calibrated classification'),
    MindModel('CatBoost', 'Structured Alpha', 'Challenger', 'Research', 'AUC 0.79', 'Tabular classification'),
    MindModel('XGBoost', 'Structured Alpha', 'Benchmark', 'Research', 'AUC 0.77', 'Research benchmark'),
    MindModel('PyTorch MLP', 'Neural Baseline', 'Benchmark', 'Research', 'AUC 0.73', 'Neural research baseline'),
    MindModel('Original Patch Transformer', 'Time-Series', 'Challenger', 'Research', 'MAE 0.031', 'Causal temporal model'),
    MindModel('TFT-inspired Model', 'Time-Series', 'Challenger', 'Research', 'MAE 0.034', 'Temporal challenger'),
    MindModel('TimeMixer-style Model', 'Time-Series', 'Challenger', 'Research', 'MAE 0.032', 'Temporal research model'),
    MindModel('Chronos-2 Adapter', 'Time-Series', 'Candidate', 'Adapter', 'Not verified', 'Checkpoint not verified'),
    MindModel('Regime HMM', 'Regime', 'Advisory', 'Research', 'F1 0.76', 'Regime classifier'),
    MindModel('Isolation Forest', 'Anomaly', 'Advisory', 'Research', 'F1 0.68', 'Anomaly research'),
    MindModel('Gemini Context', 'News NLP', 'Advisory', 'Integration', 'Not verified', 'Context summarization'),
    MindModel('Financial NLP Challenger', 'News NLP', 'Candidate', 'License review', 'Not verified', 'Commercial use pending'),
    MindModel('Static Ensemble', 'Decision Fusion', 'Baseline', 'Research', 'Sharpe 1.19', 'Static fusion baseline'),
    MindModel('Adaptive Ensemble', 'Decision Fusion', 'Challenger', 'Research', 'Sharpe 1.23', 'Adaptive research'),
    MindModel('Meta Decision Learner', 'Decision Fusion', 'Candidate', 'Planned', 'Not verified', 'Planned arbitration'),
    MindModel('Decision-1 Research', 'Decision Fusion', 'Candidate', 'Planned', 'Not verified', 'Experimental arbitration'),
    MindModel('Quantile Forecaster', 'Time-Series', 'Candidate', 'Research', 'Coverage 0.78', 'Prediction intervals'),
    MindModel('PPO / FinRL-X', 'Reinforcement Learning', 'Candidate', 'Advanced R&D', 'Not verified', 'No execution authority'),
  ];
  static const companies = <MindCompany>[
    MindCompany('TITAN','Titan Company Ltd.','Thesis Active','+12.6% (1M)'),
    MindCompany('CDSL','Central Depository Services','Deep Review','+8.4% (1M)'),
    MindCompany('IREDA','Indian Renewable Energy Development Agency','Screening','+6.1% (1M)'),
  ];
}
