/// Deterministic, synthetic Indian-market examples for the Mintick Mind agent UI.
/// None of these are quotes, verified evidence, recommendations, or executable signals.
class MindAgentVote {
  const MindAgentVote(this.id, this.role, this.position, this.weight, this.score,
      this.reason, this.source, this.challenge);
  final String id, role, position, reason, source, challenge;
  final int weight, score;
}

class MindAgentEvidence {
  const MindAgentEvidence(this.id, this.claim, this.symbol, this.source,
      this.knownAt, this.usability, this.category);
  final String id, claim, symbol, source, knownAt, usability, category;
}

class MindAgentIncident {
  const MindAgentIncident(this.id, this.symbol, this.kind, this.severity,
      this.decision, this.stage, this.cause, this.fix, this.timeline);
  final String id, symbol, kind, severity, decision, stage, cause, fix;
  final List<String> timeline;
}

abstract final class MindAgentFixtures {
  static const horizons = <String>[
    'Intraday', 'Swing', 'Positional', 'Delivery', 'Long-Term',
    'Cash Short', 'Futures', 'Options'
  ];
  static const councilSymbols = <String>[
    'CDSL','TITAN','HDFCBANK','RELIANCE','INFY','POLYCAB','NTPC'
  ];
  static const votes = <MindAgentVote>[
    MindAgentVote('bull','Bull researcher','BUY CANDIDATE',26,78,
      'Relative strength improving; volume impulse and sector leadership.',
      'Synthetic NSE-style candles and synthetic sector features',
      'Breakout and volume may represent the same evidence family.'),
    MindAgentVote('bear','Bear researcher','WAIT',22,46,
      'Price extension and weak after-cost risk/reward.',
      'Synthetic price distribution and risk budget',
      'Slippage and tail events are not yet measured.'),
    MindAgentVote('fund','Fundamental researcher','RESEARCH',22,66,
      'Business quality merits review; valuation and filings are incomplete.',
      'Unverified illustrative accounting fields',
      'Official, timestamped filings are required.'),
    MindAgentVote('flow','Regime & institutional flow','WAIT',17,58,
      'Regime is selective and institutional participation is uncertain.',
      'Synthetic breadth and placeholder flow data',
      'Official institutional flow is not connected.'),
    MindAgentVote('critic','Skeptical auditor','ABSTAIN CHECK',13,53,
      'Model dependency overlap and freshness must be checked.',
      'Synthetic provenance and correlation graph',
      'The deterministic capital authority may still veto.'),
  ];
  static const evidence = <MindAgentEvidence>[
    MindAgentEvidence('EVD-001','Price trend','RELIANCE',
      'Illustrative candle stream','Mock 09:22:05','Synthetic','Market evidence'),
    MindAgentEvidence('EVD-002','Volume participation','CDSL',
      'Illustrative volume bars','Mock 09:22:02','Synthetic','Market evidence'),
    MindAgentEvidence('EVD-003','Corporate filing','POLYCAB',
      'Official NSE/BSE source required','Not verified','Unavailable','Company evidence'),
    MindAgentEvidence('EVD-004','Institutional net flow','NIFTY50',
      'Official exchange/depository source required','Not verified','Unavailable','Institutional'),
    MindAgentEvidence('EVD-005','Regime classifier','NIFTY50',
      'Versioned feature window','Mock 09:22:05','Synthetic','Model output'),
    MindAgentEvidence('EVD-006','Broker eligibility','NTPC',
      'Signed-in broker permission required','Not connected','Unavailable','Capital authority'),
    MindAgentEvidence('EVD-007','Forward outcome label','CDSL',
      'Outcome available after decision horizon','Mock +5 days','Synthetic','Learning'),
  ];
  static const incidents = <MindAgentIncident>[
    MindAgentIncident('INC-102','CDSL','False breakout','High','DEC-2402',
      'Signal validation',
      'Volume and correlated momentum features were double-counted as independent evidence.',
      'Deduplicate correlated inputs, require freshness and enforce a pre-decision volume gate.',
      ['09:18 — Breakout candidate generated',
       '09:18 — Two momentum researchers supported the entry',
       '09:19 — Mock stale volume confirmation bypassed',
       '09:44 — Breakout invalidated; synthetic adverse excursion -1.6%']),
    MindAgentIncident('INC-103','NTPC','Short-sale abstention','Low','DEC-2406',
      'Eligibility gate',
      'Intraday short-sale eligibility and broker permission were not verified.',
      'Keep abstention. Verify actual instrument/product permission before reconsideration.',
      ['11:14 — Bearish trend identified',
       '11:15 — Intraday short proposal reached eligibility gate',
       '11:15 — Broker permission unconfirmed',
       '11:15 — ABSTAIN; no order submitted']),
    MindAgentIncident('INC-104','POLYCAB','Thesis drift','Medium','DEC-2408',
      'Long-term valuation',
      'Quality remained promising but margin-of-safety and governance checks failed.',
      'Separate business quality from entry price; verify filings and downside cases.',
      ['12:00 — Quality thesis rescored',
       '12:03 — Valuation uncertainty increased',
       '12:04 — Governance source marked unavailable',
       '12:05 — THESIS REVIEW, not an entry']),
    MindAgentIncident('INC-105','TCS','Regime mismatch','Medium','DEC-2409',
      'Regime router',
      'A short-term impulse conflicted with weak breadth and a higher-timeframe regime.',
      'Apply horizon-specific routing, model disagreement and out-of-sample calibration.',
      ['10:22 — Momentum researcher voted bullish',
       '10:23 — Breadth and regime researchers disagreed',
       '10:23 — Agreement-weighted confidence decreased',
       '10:24 — WAIT; no action']),
  ];
  static const regimeWeights = <String,List<int>>{
    'Trending Up':[42,20,26,12],
    'Range Bound':[12,33,28,27],
    'Trending Down':[6,26,24,44],
    'High Volatility':[10,20,25,45],
    'Data Uncertain':[0,0,0,0],
  };
  static const flowRows = <(String,String,String,String)>[
    ('FII net','-₹1,420 Cr','Unverified example','Outflows may pressure risk appetite'),
    ('DII net','+₹1,810 Cr','Unverified example','Domestic absorption is possible'),
    ('NIFTY breadth','43% positive','Synthetic','Weak participation'),
    ('Banking breadth','57% positive','Synthetic','Selective strength'),
  ];
  static const calibrationBuckets = <(String,int,int,int)>[
    ('40–50%',45,43,28),('50–60%',55,47,37),
    ('60–70%',65,56,31),('70–80%',75,61,22),('80–90%',85,66,9),
  ];
}
