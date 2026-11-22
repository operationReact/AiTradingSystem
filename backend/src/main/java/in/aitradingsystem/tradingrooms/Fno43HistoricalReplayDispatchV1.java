package in.aitradingsystem.tradingrooms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact 43/43 typed Java-evaluator dispatcher for source-supplied historical
 * as-of windows. 43/43 here is code ROUTING coverage, never 43/43 independently
 * verified market data, after-cost backtests or broker trading.
 *
 * <p>Each call invokes the same deterministic strategy evaluator used in the
 * research services, using that evaluator's concrete nested input types.
 * InputEvidence labels match the actual capability manifest's per-strategy
 * requirements, one-for-one. This class never fetches, edits, stores or exports
 * market data. It has no controller/endpoint, SSM, PAPER or LIVE order path.
 *
 * <p>Assertions of rights and source timestamps alone do NOT independently
 * prove original market-data custody; all outputs are explicitly research-only.
 */
public final class Fno43HistoricalReplayDispatchV1 {
    public static final String CONTRACT = "minttick.fno43.typed-historical-replay-dispatch.v1";
    private static final int LIMIT = 64;
    private Fno43HistoricalReplayDispatchV1() {}

    public record CaseSpec(String strategyId, String javaEvaluator,
                           int evaluatorArgumentCount, List<String> requiredInputs) {}
    /**
     * A per-manifest-requirement external input custody CLAIM. A source
     * adapter must independently validate the original immutable receipt.
     * A true boolean is not an independent source authorization certificate.
     */
    public record InputEvidence(
        String manifestRequirement, Instant originalFirstKnownAt,
        Instant originalCapturedAt, Instant originalRightsVerifiedAt,
        String sourceSha256, String rightsReceiptSha256,
        boolean researchUseVerified) {}
    /**
     * Data contains original evaluator-specific typed Java arguments in exact
     * order, excluding the last Instant asOf argument. Never log arguments:
     * they can contain genuine broker positions, quotes or other private data.
     */
    public record Window(Instant asOf, List<?> typedArguments,
                         List<InputEvidence> inputEvidence) {}
    public record Row(Instant asOf, String evaluatorStatus, String reasonCode,
                      String evidenceHash) {}
    public record Report(
        String contractVersion, String strategyId, String state, String reasonCode,
        int windowsEvaluated, int researchCandidates, int noSignals,
        int evaluatorBlocks, String runSha256,
        boolean independentHistoricalE2E, boolean paperLedgerE2E,
        boolean liveBrokerE2E, boolean brokerOrdersPlaced, List<Row> rows) {}

    private record Eval(String status, String reason) {}

    private static final List<CaseSpec> CATALOG = List.of(
        new CaseSpec("strategy.STR-170", "FnoFuturesBasisCarryV1", 5,
            List.of(
                "NSE_EQ spot and matching NSE_FO futures exchange-time bid/ask",
                "Verified contract, expiry and lot size",
                "Financing, dividend, borrowing and all-in execution cost assumptions")),
        new CaseSpec("strategy.STR-171", "FnoFuturesCalendarSpreadV1", 6,
            List.of(
                "NSE_FO near/far contracts on the same underlying with distinct expiries and matching lot sizes",
                "Research-rights verified exchange-time bid/ask for both futures legs",
                "Funding, dividend and two-leg round-trip cost assumptions")),
        new CaseSpec("strategy.STR-172", "FnoFuturesRolloverSignalV1", 5,
            List.of(
                "Source-verified near and next NSE_FO futures contract IDs, expiries and identical underlying/lot size",
                "Three exchange-session-complete, provider-observed near/far OI and daily volume snapshots with as-of timestamps",
                "Closing bid/ask for both futures, source-use rights, and explicit estimated execution friction thresholds")),
        new CaseSpec("strategy.STR-173", "FnoFuturesOpenInterestTrendV1", 4,
            List.of(
                "Exact source-listed NSE_FO futures contract identity, expiry, lot size, and research-use provenance",
                "Five consecutive calendar-verified, completed futures sessions with finalized OI and volume in consistent underlying units",
                "As-of exchange close and first-observed OI publication timestamps plus explicit trend/participation thresholds")),
        new CaseSpec("strategy.STR-174", "FnoFuturesPriceOiDivergenceV1", 4,
            List.of(
                "Source-verified same NSE_FO futures contract across five completed trading sessions",
                "Rights-verified finalized futures close price and open-interest snapshots with original as-of publication times",
                "Session-normalized OI and traded volume plus explicit divergence and source-quality thresholds")),
        new CaseSpec("strategy.STR-175", "FnoCashFuturesArbitrageResearchV1", 7,
            List.of(
                "STR-170 approved single-stock NSE_EQ spot and matching NSE_FO futures basis-carry research decision",
                "Same-provider, source-verified exchange-timed spot ASK / futures BID depth for complete lot",
                "Broker-verified full cash equity purchase, futures margin, stress variation margin and delivery settlement buffer",
                "Independently verified incremental fees, slippage, dividends and corporate action uncertainty, with non-duplicated cost model")),
        new CaseSpec("strategy.STR-176", "FnoFuturesMomentumV1", 4,
            List.of(
                "Provider-verified same NSE_FO futures contract, listed-from date, expiry and lot size",
                "Seven session-complete NSE futures OHLCV bars and final OI in consistent underlying units, with separate original as-of publication timestamps",
                "Separately verified internal research rights for OHLCV and OI, exchange session provenance and explicit momentum, volume and cost thresholds")),
        new CaseSpec("strategy.STR-177", "FnoFuturesMeanReversionV1", 4,
            List.of(
                "Same source-listed NSE_FO futures contract, listing/expiry/lot and ten completed trading-session OHLCV closes",
                "Separately observed and rights-verified finalized OHLCV/OI timestamps, source provenance, normalized volume and OI",
                "Eight-session baseline volatility, ninth-session extreme, tenth-session partial recovery, liquidity and explicit after-cost research thresholds")),
        new CaseSpec("strategy.STR-178", "FnoFuturesHedgeOverlayV1", 6,
            List.of(
                "Reconciled portfolio index-beta INR exposure including existing futures/options hedges and verified beta model, timestamped within five minutes",
                "Source-verified NSE index-futures contract expiry and same-index identity, market session, fresh bid/ask, open interest and order-book depth",
                "Broker-verified cash/margin/variation stress reserves plus explicit stress index move, basis divergence, round-trip cost and room-level hedge limits",
                "Separately verified market, contract discovery and portfolio-data internal research rights")),
        new CaseSpec("strategy.STR-179", "FnoContractLiquidityGateV1", 4,
            List.of(
                "Provider-verified NSE_FO future or option instrument key, underlying, expiry, option strike/side and lot size",
                "Three original source-observed, chronologically ordered same-provider Level-1 bid/ask/depth and exchange timestamps, verified exchange session",
                "Original as-of OI and cumulative session-volume first-observed timestamps and consistent underlying-unit normalization",
                "Independently verified research-use rights for quote/OI/volume/contract discovery and explicit depth, spread, impact, OI/volume and freshness thresholds")),
        new CaseSpec("strategy.STR-180", "FnoExpiryBasisReversionV1", 4,
            List.of(
                "Source-verified matching listed NSE_EQ stock and NSE_FO single-stock futures contract, listing, expiry, lot size and deliverability",
                "Five completed, chronological, same-provider NSE exchange-session close spot/future bid/ask pairs and independently verified finalized quote publication as-of times",
                "Explicit financing/dividend rates, confirmed dividends/corporate actions and physical-delivery/Indian statutory cost review",
                "Point-in-time signed carry-adjusted basis residuals, consecutive narrowing, residual costs and strict research thresholds")),
        new CaseSpec("strategy.STR-181", "FnoLongCallV1", 6,
            List.of(
                "Verified NIFTY 50 closed daily 35+ session source candles with original first-known timestamps and bullish EMA8/EMA21 crossover",
                "Rights-verified matching source-listed NSE_FO CE strike, expiry, lot, price tick and canonical NIFTY underlying",
                "Same-provider time-synchronized NSE underlying and CE bid/ask, option depth and OI at valid exchange session timestamps",
                "Explicit after-tax INR-per-lot costs, premium-at-risk budget and hypothetical at-expiry payoff scenario")),
        new CaseSpec("strategy.STR-182", "FnoLongPutV1", 6,
            List.of(
                "35+ genuine completed NIFTY 50 daily closes, source first-known timestamp and a new EMA8-below-EMA21 bearish crossover",
                "Verified listed NSE_FO NIFTY PE with strike, lot, expiry and tick provenance",
                "Same-provider synchronized fresh underlying and PE ask/bid, verified depth/OI and research-use rights",
                "Explicit downside-at-expiry hypothetical scenario, bid/ask/slippage, Indian transaction fees and premium-at-risk cap")),
        new CaseSpec("strategy.STR-183", "FnoBullCallDebitSpreadV1", 8,
            List.of(
                "Source-verified completed NIFTY 50 daily bars and a new bullish EMA8/EMA21 crossover",
                "Two source-verified NSE_FO NIFTY CE contracts: buy lower strike, sell higher, identical expiry/lot",
                "Fresh synchronized 3-leg underlying/call bid-ask exchange timestamps, real OI/depth and independent research rights",
                "Premium debit, costs and taxes, risk-capital allocation, bounded expiry payoff and after-cost reward/risk hurdle")),
        new CaseSpec("strategy.STR-184", "FnoBearPutDebitSpreadV1", 8,
            List.of(
                "Verified completed NIFTY 50 daily EMA8/EMA21 crossover below (bearish) with original source timestamps",
                "Two matched NSE_FO NIFTY PE options: long higher strike, short lower strike, same expiry, lot and underlying",
                "Real synchronized same-provider index and both put bid/ask, OI/depth, spread and research-use permissions",
                "Round-trip fees, bid/ask slippage, estimated debit/maximum modeled expiry payoff and bounded research capital")),
        new CaseSpec("strategy.STR-185", "FnoBullPutCreditSpreadV1", 8,
            List.of(
                "Verified 35+ completed NIFTY 50 index daily candles, original publication timestamps and new EMA8/EMA21 bullish crossover",
                "Two matched real NSE_FO PE option contracts: short higher strike, long lower strike, identical expiry, lot and underlying",
                "Same-provider fresh synchronized NIFTY and both PE bid/ask, true exchange timestamps, OI/depth/spread and independent research data rights",
                "After-tax and after-slippage net credit, max-loss capital budget and realistic cost-adjusted maximum reward/risk")),
        new CaseSpec("strategy.STR-186", "FnoBearCallCreditSpreadV1", 8,
            List.of(
                "35+ true completed NSE NIFTY index closes with source first-known timestamps and new bearish EMA8/EMA21 crossover",
                "Two verified matching NSE_FO CE instruments: sell lower-strike, buy higher-strike, same lot/expiry",
                "Same-provider 3-leg time-synced real quotes, positive bid/ask depth and OI, strike/option/source research rights",
                "Net credit after bid/ask/slippage and modeled fees, capped hypothetical expiry max loss and risk capital limit")),
        new CaseSpec("strategy.STR-187", "FnoProtectivePutV1", 7,
            List.of(
                "Verified one settled physically deliverable equity lot in broker-reconciled actual holdings, matching one source-listed NSE_FO PE contract and expiry",
                "35+ true completed source-first-known NSE equity candles and an observed 20-session drawdown risk trigger",
                "Rights-verified time-aligned same-provider held equity and genuine PE bid/ask, strike/lot/delivery mapping, OI/depth and original source timestamps",
                "Nonzero estimated Indian put premium/fees, explicit downside hypothetical expiry comparison and premium-at-risk budget")),
        new CaseSpec("strategy.STR-188", "FnoCoveredCallV1", 7,
            List.of(
                "Source-confirmed settled and deliverable one-lot owned NSE equity shares with broker holdings provenance",
                "One corresponding verified genuine listed NSE_FO CE option matching shares/lot/expiry and CE delivery rights",
                "35+ verified completed NSE equity closes, original first-known timestamps and observed owned-position gain screening trigger",
                "Licensed time-aligned true equity/CE quotes, bid depth, OI, slippage and nonzero Indian fees; full held-stock notional risk cap")),
        new CaseSpec("strategy.STR-189", "FnoEquityCollarV1", 9,
            List.of(
                "Genuine settled/reconciled one-lot NSE_EQ broker-owned shares, and source-listed physically matching NSE_FO lower-strike PE/higher-strike CE contracts with same expiry/lot",
                "35+ verified completed NSE equity sessions with source-first-known publication and latest close versus 20-prior-session drawdown trigger",
                "Licensed synchronous true bid/ask equity, put and call snapshots from same provider, OI/depth and valid quote age/skew, contract rights and market calendar",
                "Explicit Indian tax/fees and leg slippage, theoretical expiry floor/cap, full held-stock notional budget and maximum net-debit budget")),
        new CaseSpec("strategy.STR-190", "FnoCallRatioSpreadV1", 8,
            List.of(
                "At least 35 verified completed original-observation NIFTY daily closes with independently checked bullish EMA8/EMA21 crossover",
                "True source-listed NSE_FO same-expiry/lot NIFTY CE contracts, buy 1 lower strike and sell 2 higher strike; short bid depth enough for two contracts",
                "Same-provider synchronised original-exchange-time index and both CE quotes, rights, valid bid/ask OI/depth and as-of availability",
                "Three-leg after-cost net debit/credit, explicit upside stress scenario and documented unbounded upside loss with research non-execution")),
        new CaseSpec("strategy.STR-191", "FnoPutRatioSpreadV1", 8,
            List.of(
                "35+ as-of original first-known genuinely completed NIFTY index daily closes and newly formed bearish EMA8/EMA21 crossover",
                "Two genuine source-listed same-expiry NIFTY PE instruments: buy one higher-strike long put; sell two lower-strike short puts, adequate depth for two shorts",
                "Rights-verified synchronized index and PE bid/ask, exchange timestamps, open interest and depth; authentic NSE expiry/lot/session mapping",
                "True theoretical maximum expiry loss computed from S=0 and S>=higher strike, nonzero three-leg Indian modeled fees and short-put margin review")),
        new CaseSpec("strategy.STR-192", "FnoLongStraddleV1", 8,
            List.of(
                "35+ verified completed source-first-known genuine NIFTY index daily candles and independently licensed index market data",
                "True source-listed matched CE and PE same NIFTY strike/expiry/lot with authentic quoted ask and ask-depth, actual OI, and instrument metadata",
                "Licensed same-provider NIFTY spot, CE and PE synchronized as-of quotes and independently source-derived annualized IV percentages for both options",
                "Explicit two-leg modeled slippage/India statutory fees, full premium-at-risk research budget and transparent IV-one-sigma-versus-cost heuristic")),
        new CaseSpec("strategy.STR-193", "FnoLongStrangleV1", 8,
            List.of(
                "35+ verified completed NIFTY daily closes with source-original first-known publication timestamps",
                "Two licensed source-listed options: buy one OTM NIFTY CE above spot, buy one OTM PE below spot, matching genuine expiry and lot",
                "Same-provider synchronous underlying/CE/PE true bid/ask, OI, ask-depth and separate source-derived annualized implied volatility percentages for both options",
                "Nonzero two-leg estimated Indian fees, transaction slippage, full premium risk budget and illustrative IV-one-sigma movement versus OTM strike gaps/breakevens")),
        new CaseSpec("strategy.STR-194", "FnoShortStraddleV1", 8,
            List.of(
                "35+ genuine verified completed original-first-known NIFTY daily index closes with licensed index research rights",
                "Two genuine NSE_FO CE and PE sold at identical strike/expiry/lot/underlying from the licensed option chain, verified instrument metadata",
                "Original-as-of synchronized true NIFTY/CE/PE quotes with authentic bid liquidity, OI and traceable separately licensed IV percent for each option",
                "After-cost net short premium, explicitly modeled Indian transaction fees, illustrative volatility move and explicit unbounded theoretical upside loss flag")),
        new CaseSpec("strategy.STR-195", "FnoShortStrangleV1", 8,
            List.of(
                "35+ genuine source-verified completed NIFTY daily closes and original source-first-known observation metadata",
                "True licensed market option chain: short one OTM high-strike CE plus one OTM low-strike PE, valid NSE lot and identical expiry",
                "Rights-verified contemporaneous underlying/CE/PE bid/ask and source annualized IV values, bid-side depth and OI, original as-of timestamps",
                "After-cost option credit, theoretical lower/upper breakevens, finite zero-index put loss and UNLIMITED short-call upside risk")),
        new CaseSpec("strategy.STR-196", "FnoIronCondorV1", 12,
            List.of(
                "35+ source-completed genuine NIFTY daily closes with source-first-known timestamps and verified research permissions",
                "Four independently source-listed NIFTY options, one long lower PE, short higher PE, short lower CE, long higher CE, identical expiry and lot, five synchronized original exchange quotes",
                "Genuine bid liquidity/OI for both sold options, ask liquidity/OI for protective bought options, true annualized licensed IV for each four contracts",
                "After-fees and slippage net credit, loss modeled using wider of asymmetric wings, profit/loss research thresholds and transparent IV move heuristic")),
        new CaseSpec("strategy.STR-197", "FnoIronButterflyV1", 12,
            List.of(
                "35+ genuine first-known completed NIFTY index bars, licensed index research permissions and canonical instrument expiry/lot",
                "Four source-listed options: buy lower PE, sell SAME CENTRAL STRIKE PE and CE, buy higher CE, all one expiry and lot",
                "Five synchronized genuine exchange-time spot/option quotes with true buy ask/sell bid depth, OI, independent annualized source IV per each option",
                "After-fee full credit, worst-width defined-wing theoretical expiry loss and modeled IV move versus protective wing research hurdles")),
        new CaseSpec("strategy.STR-198", "FnoLongButterflyV1", 10,
            List.of(
                "35+ genuine source-first-known completed historical NIFTY daily candles and licensed index research rights",
                "Three separately source-listed, source-verified CALL options: buy low CE, sell TWO matching center CE lots, buy upper CE, symmetric strike spacing and same expiry/official lot",
                "True contemporaneous same-provider NIFTY plus three CE bid/ask, original as-of exchange and first-known timestamps, authentic ask depth at long legs, TWO lots bid depth at center, open interest and annualized option IV",
                "Actual modeled 4-contract India transaction fees, slippage, full after-cost debit at risk, theoretical symmetric butterfly expiry breakevens and research risk/reward/IV-move hurdles")),
        new CaseSpec("strategy.STR-199", "FnoCalendarSpreadV1", 8,
            List.of(
                "35+ genuine completed NIFTY index daily bars with original first-known timestamps and licensed index research rights",
                "Independently source-verified same-strike same-type NIFTY CE/PE near-expiry short option and far-expiry long option, official lot size and distinct authenticated expiration dates",
                "Synchronized genuine original-as-of NIFTY/near-short/far-long quotes with separately sourced annualized IV per expiry, authentic near bid depth and far ask depth, actual OI and exchange-time freshness",
                "After-cost two-leg calendar net debit, original source IV term-structure ratio, quoted premium coverage and illustrative near-expiry IV one-sigma movement, NO assumed same-expiry payoff")),
        new CaseSpec("strategy.STR-200", "FnoDiagonalSpreadV1", 8,
            List.of(
                "35+ original first-known source-completed NIFTY daily closes and independent licensed index research rights",
                "Two separately source-listed NIFTY CE or PE instruments of SAME OPTION TYPE and LOT, but DIFFERENT authenticated strikes and ordered expiries: short OTM near leg, long ITM far leg",
                "Same-provider original-as-of NIFTY index, near-short and far-long exchange quotes with original annualized source IV for BOTH expiries, real bid liquidity for sold leg and ask depth for bought leg",
                "After-fee net debit, original near/far IV term-structure, option premium ratio and near-expiry illustrative IV movement research metrics; no synthetic expiry maximum loss or breakeven")),
        new CaseSpec("strategy.STR-201", "FnoIvRankMeanReversionV1", 5,
            List.of(
                "200+ authentic source-original first-known NIFTY completed daily OHLC index candles aligned session-by-session to independent 30-calendar-day constant-tenor ATM source IV history",
                "Licensed original listed NIFTY option-chain derived 30-day constant-tenor source IV normalized in annualized percentages, with original interpolation, provider, temporal and research rights",
                "Fresh independently source-verified current NIFTY normalized 30-day ATM IV quote with authentic exchange/first-known times and matching complete historical source",
                "Historical 30-day IV rank, non-degenerate prior IV min/max, independent recent high/low and observed absolute vol-point pullback or rebound with no forward lookahead")),
        new CaseSpec("strategy.STR-202", "FnoVolatilityCrushV1", 6,
            List.of(
                "Independently verified official NIFTY-sensitive calendar event with original schedule first-known, actual event start/end and licensed event-calendar research rights",
                "35+ authentic completed source-verified NIFTY daily OHLC index candles with original 15:30 IST exchange close and first-known before the scheduled event",
                "Two genuine source-verified same-provider NIFTY spot and normalized 30-calendar-day constant-tenor ATM implied-volatility snapshots, both original-first-known and correctly anchored before and after event",
                "Independent chain/IV/constant-tenor/interpolation source rights, measured post-event annualized IV point decline, relative decline bps and actual spot move bps with no pre-event hindsight")),
        new CaseSpec("strategy.STR-203", "FnoGammaScalpingV1", 7,
            List.of(
                "35+ source-verified completed NIFTY index closes with original exchange and first-known timestamps, separately licensed index/options/Greeks/futures/history research rights",
                "Two independently verified same-strike/expiry/official lot NIFTY CE and PE and a separately official source-listed genuine NIFTY futures contract with valid expiry, tick and potentially distinct futures lot",
                "Three or more synchronized original-first-known same-provider intraday NIFTY/index-futures/CE/PE samples with original option delta/gamma/theta per unit, annualized IV, authentic bid/ask/OI and actual futures bid/ask depth",
                "Retrospective observed-gamma-capture approximation, historical theta drag, integer-lot futures delta hedge turnover and after-spread/slippage/fees hypothetical hedge cost with NO claimed actual fills")),
        new CaseSpec("strategy.STR-204", "FnoDeltaHedgeV1", 7,
            List.of(
                "35+ source-proven completed first-known NIFTY daily closes and six separately verified research rights: broker positions, NIFTY index, authentic NSE option chain, original delta Greeks, listed futures, completed history",
                "One or more actually broker-confirmed distinct NSE_FO CE/PE positions with real signed option lots, official instrument lot/tick/expiry and original broker first-known evidence, plus verified current futures lots",
                "Synchronized original-first-known same-provider NIFTY index spot, real option bid/ask/delta/open interest and genuine listed NIFTY futures bid/ask/depth observations",
                "Pure retrospective signed portfolio delta aggregation and integer-lot hypothetical futures hedge improvement with authentic side-of-book liquidity, modeled spread/slippage/fees and residual-delta risk limits")),
        new CaseSpec("strategy.STR-205", "FnoBetaHedgeOptionsV1", 6,
            List.of(
                "Six independent genuine research rights: authenticated broker long-equity holdings, original-first-known NIFTY daily history, as-of individual equity prices/history, licensed real option chain, original exchange Greeks and NSE-listed option master",
                "65+ matched, original source-timestamped fully completed daily NIFTY plus every held NSE-equity closes, same provider/session and actual original first-known times; sufficient authentic index variance",
                "Currently broker-confirmed positive equity shares, genuine synchronized underlying prices and real listed NIFTY long put or call option with original per-underlying-unit delta, annualized IV, bid/ask, OI, ask depth and valid official lot/expiry",
                "Current-weight historical portfolio beta with no lookahead; hypothetical signed beta INR exposure, local long-option delta hedge in integer lots and true ask-side premium/slippage/fee liquidity and budget constraints")),
        new CaseSpec("strategy.STR-206", "FnoTailRiskPutHedgeV1", 6,
            List.of(
                "Six independently attested rights: verified long-cash equity broker holdings, completed NIFTY original history, each held-equity original history, genuine exchange-listed options, original option delta/IV and official instrument master",
                "65+ chronological source-verified completed daily NIFTY index and every currently held equity close with original first-known and matched provider/session provenance, proving the worst originally observed NIFTY negative daily session and coincident equity returns",
                "Source-listed out-of-money NIFTY long put with true lot/strike/expiry/tick, genuine same-provider bid/ask, original put delta and annualized IV, ask depth/open interest, authentic current NIFTY and each holding price",
                "Explicit hindsight-only fixed-current-holdings tail-session replay, hypothetical put intrinsic at expiry if worst observed daily shock persists, full estimated ask/slippage/fee debit, integer-lot purchase and loss-reduction/premium/depth policy checks")),
        new CaseSpec("strategy.STR-207", "FnoPutSpreadCollarV1", 9,
            List.of(
                "Six independently verifiable rights: broker-confirmed existing long NIFTY futures, source original index completed closes, official listed option chain, original per-leg delta and IV, genuine listed futures bid/ask and official NSE instrument master",
                "35+ genuine original-first-known completed NIFTY index daily closes and synchronized original first-known same-provider current NIFTY index, futures and three independently listed option bid/ask/Greeks/OI/order-book quotes",
                "Verified existing positive long NIFTY futures lots and original broker average entry price; exact same underlying/expiry/official lot as bought higher OTM put, sold lower OTM put and sold higher OTM call",
                "Entire actual buy-ask/sell-bid quote depth, modeled directional slippage and nonzero India fees, exact 1:-1:-1 structures per verified long future lot, honest conditional common-expiry payoff at zero/lower put/higher put/short call strike with exposed extreme downside")),
        new CaseSpec("strategy.STR-208", "FnoDefinedRiskSpreadRollV1", 9,
            List.of(
                "Six independently verified research permissions: actual broker-confirmed matched existing NIFTY put debit vertical, original as-of NIFTY index and 35+ completed closes, authentic listed option quotes, original put delta/IV Greeks and official NSE option instrument master",
                "Exactly equal verified long-high-PE and short-low-PE original broker lots, correct matched existing expiry, broker positive original spread debit and original broker first-known position evidence",
                "Separately listed and sourced later-expiry higher-strike long-PE and lower-strike short-PE, matching original official NIFTY lot, source-original first-known per-leg bid/ask/negative delta/IV/open interest and order-book depth for all four exact bid/ask close/open sides",
                "Deterministic source-observed historical downside regime, hypothetical 4-leg close-and-open signed cash flow with buy/sell slippage and positive fees, new vertical finite theoretical expiry maximum loss/gain, signed roll debit and explicit research cost budget")),
        new CaseSpec("strategy.STR-209", "FnoExpiryRiskNeutralizationV1", 6,
            List.of(
                "Six original-source research rights: verified broker-complete same-expiry signed NIFTY options positions, licensed 35+ completed NIFTY index closes, real original option chain, authentic original delta/gamma/IV, official NSE instrument master and independently source-verified official last-tradable expiry cutoff",
                "Complete broker-attested as-of NIFTY one-expiry CE/PE position book and original first-known signed lots with source-listed unique NSE_FO keys, actual official lot/strike/tick/expiry and no missing or duplicate option quotes",
                "Synchronized originally known same-provider NIFTY spot and each listed option BID/ASK/original delta/gamma/annualized IV/OI/side-specific bid/ask depth adequate to hypothetically close all broker-held lots",
                "Official source original last trading timestamp bounded by a close-window policy, theoretical full expiry-book bid-sale or ask-buy hypothetical debit with slippage/fees and configured positive debit cap, net original delta/gamma, and honest bounded expiry intrinsic study scenarios with open-ended short-call flag")),
        new CaseSpec("strategy.STR-210", "FnoPortfolioProtectiveOverlayV1", 6,
            List.of(
                "Six independently authenticated research rights: broker-verified current long NSE cash equity holdings, original-first-known NIFTY index and each holding's historically matched equity daily closes, genuine listed NIFTY option chain, authentic original put delta/IV and official NSE option instrument master",
                "65+ completed historical original-first-known sessions, each with genuine NIFTY index and every held NSE cash-equity close on identical provider/date/exchange close; source-consistent verified current positive brokerage shares and synchronized equity marks",
                "Two distinct source-listed OTM NIFTY puts with original real bid/ask/negative delta/annualized IV/open interest/ASK-side depth, same official expiry/lot size, actual tick and listing source and original first-known timestamps",
                "Three distinct largest historically observed matched NIFTY down days with corresponding actual same-session held-stock returns at today's broker-held long-stock weights; hypothetical index shock persistence to option expiry, conditional put intrinsic only, ask-side friction/premium budget/whole-depth constraints, and explicitly unmodeled index-stock basis risk")),
        new CaseSpec("strategy.STR-211", "FnoGreeksExposureRebalanceV1", 6,
            List.of(
                "Six source rights: genuine verified complete broker-held NIFTY option book, licensed original completed 35+ NIFTY daily closes, genuine source-listed options chain, source-original option delta/gamma, source-original vega/theta/IV and official NSE NIFTY F&O instrument master",
                "Broker-verifiable original-first-known complete signed lot options positions and original synchronized market snapshot plus exact matching source-original quote key set for every held and candidate option; genuine listed lot/strike/expiry/tick and broker attestation",
                "Two authentic source-listed OTM NIFTY option buy-only research candidates, one PUT and one CALL with same listed expiry and official lot; original bid/ask, per-unit delta/gamma/vega/theta with documented Greek units, IV/OI and genuine ASK-side depth",
                "Historical sourced observed index movement gate; true ASK plus buy-slippage and nonzero fees; integer buy lots constrained by actual depth, premium, absolute residual delta/gamma/vega, maximum negative theta and minimum original absolute delta improvement, with no delta sign reversal")),
        new CaseSpec("strategy.STR-212", "FnoSkewBasedHedgeV1", 9,
            List.of(
                "Six independently evidenced source rights: broker-confirmed original as-of long listed NIFTY future holdings/average entry basis, genuine 35+ original completed NIFTY daily closes, real listed NIFTY options chain, genuine source-original put/call delta and annualized IV, current official NIFTY futures bid/ask and official option/futures instrument master",
                "Official identical underlying/expiry/lot sourced NIFTY future, long higher OTM NIFTY PE and short lower PE plus independent OTM reference CE; true source-listed strikes and dates, synchronously known real bid/ask, original signed delta/IV/OI and depth for all owned lots on correct buy-ASK/sell-BID sides",
                "Delta-matched independently verified source-original higher bought put IV versus comparable absolute-delta OTM reference call IV, explicit IV-percent-point put skew research threshold, and strict source-original same-provider first-known timestamps before evaluation",
                "Worst originally observed completed NIFTY index daily loss replayed as explicit fictional persistent common-expiry stress; genuine ASK/BID slippage, positive one-leg and two-leg fees, signed premium saving and budget gate, conditional put spread protection and severe uncapped downside below sold lower put against actually long NIFTY future"))
    );
    public static List<CaseSpec> catalog() { return CATALOG; }

    public static Report replay(String strategyId, List<Window> windows, Instant verificationAt) {
        CaseSpec spec = CATALOG.stream()
            .filter(c -> c.strategyId().equals(strategyId)).findFirst().orElse(null);
        if (spec == null) return denied(strategyId, "UNDECLARED_STRATEGY_ID");
        if (verificationAt == null || windows == null
                || windows.isEmpty() || windows.size() > LIMIT)
            return denied(strategyId, "REPLAY_SCOPE_INVALID");
        Instant previous = null;
        int candidates = 0, quiet = 0, blocked = 0;
        List<Row> decisions = new ArrayList<>();
        for (Window window : windows) {
            if (window == null || window.asOf() == null
                    || window.asOf().isAfter(verificationAt)
                    || (previous != null && !window.asOf().isAfter(previous))
                    || window.typedArguments() == null
                    || window.typedArguments().size() != spec.evaluatorArgumentCount()
                    || window.inputEvidence() == null
                    || window.inputEvidence().size() != spec.requiredInputs().size())
                return denied(strategyId, "AS_OF_OR_TYPED_ARGUMENT_SCOPE_INVALID");
            for (int i = 0; i < spec.requiredInputs().size(); i++) {
                InputEvidence proof = window.inputEvidence().get(i);
                if (proof == null
                        || !spec.requiredInputs().get(i).equals(proof.manifestRequirement())
                        || !proof.researchUseVerified()
                        || proof.originalFirstKnownAt() == null
                        || proof.originalCapturedAt() == null
                        || proof.originalRightsVerifiedAt() == null
                        || proof.originalFirstKnownAt().isAfter(window.asOf())
                        || proof.originalCapturedAt().isAfter(window.asOf())
                        || proof.originalRightsVerifiedAt().isAfter(window.asOf())
                        || !validDigest(proof.sourceSha256())
                        || !validDigest(proof.rightsReceiptSha256()))
                    return denied(strategyId, "MISSING_OR_FUTURE_KNOWN_INPUT_CUSTODY");
            }
            Eval result;
            try {
                result = invokeTypedEvaluator(strategyId,
                    window.typedArguments(), window.asOf());
            } catch (RuntimeException sourceInvalid) {
                // Do not log a source exception; it may contain a broker
                // position, source quote, database field or credential.
                return denied(strategyId, "TYPED_INPUT_OR_EVALUATOR_FAILED_CLOSED");
            }
            if (result == null || result.status() == null || result.reason() == null)
                return denied(strategyId, "EVALUATOR_RESULT_INVALID");
            switch (result.status()) {
                case "RESEARCH_CANDIDATE" -> candidates++;
                case "NO_SIGNAL" -> quiet++;
                default -> {
                    if (!result.status().startsWith("BLOCKED"))
                        return denied(strategyId, "UNRECOGNIZED_RESEARCH_STATUS");
                    blocked++;
                }
            }
            decisions.add(new Row(window.asOf(), result.status(), result.reason(),
                hashInputs(strategyId, window.inputEvidence())));
            previous = window.asOf();
        }
        List<Row> rows = List.copyOf(decisions);
        return new Report(CONTRACT, strategyId,
            blocked > 0 ? "TYPED_EVALUATORS_RAN_WITH_BLOCKED_INPUTS"
                        : "TYPED_EVALUATORS_RAN_RESEARCH_ONLY",
            "INDEPENDENT_ORIGINAL_SOURCE_AND_AFTER_COST_OOS_NOT_PROVEN",
            rows.size(), candidates, quiet, blocked,
            hashOutputs(strategyId, rows), false, false, false, false, rows);
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
    private static String hashInputs(String id, List<InputEvidence> proofs) {
        StringBuilder out = new StringBuilder(id);
        for (InputEvidence p : proofs) {
            out.append('|').append(p.manifestRequirement()).append('|')
                .append(p.originalFirstKnownAt()).append('|')
                .append(p.originalCapturedAt()).append('|')
                .append(p.originalRightsVerifiedAt()).append('|')
                .append(p.sourceSha256()).append('|')
                .append(p.rightsReceiptSha256());
        }
        return digest(out.toString());
    }
    private static String hashOutputs(String id, List<Row> rows) {
        StringBuilder out = new StringBuilder(id);
        for (Row r : rows) out.append('|').append(r.asOf())
            .append('|').append(r.evaluatorStatus())
            .append('|').append(r.reasonCode())
            .append('|').append(r.evidenceHash());
        return digest(out.toString());
    }
    private static String digest(String s) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                hash.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("HASH_ALGORITHM_UNAVAILABLE", impossible);
        }
    }
    private static Report denied(String id, String reason) {
        return new Report(CONTRACT, id, "BLOCKED_NOT_HISTORICAL_E2E", reason,
            0, 0, 0, 0, null, false, false, false, false, List.of());
    }

    /**
     * Every switch case is an actual strongly referenced Java evaluator call.
     * Generic List casts use erased Java types; evaluators still validate
     * individual source records and this dispatcher catches malformed casts.
     * Neither a successful dispatch nor a synthetic test counts as market E2E.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Eval invokeTypedEvaluator(String id, List<?> args, Instant asOf) {
        return switch (id) {
            case "strategy.STR-170" -> {
                var decision = FnoFuturesBasisCarryV1.evaluate((FnoFuturesBasisCarryV1.Contract) args.get(0), (FnoFuturesBasisCarryV1.Quote) args.get(1), (FnoFuturesBasisCarryV1.Quote) args.get(2), (FnoFuturesBasisCarryV1.SourceRights) args.get(3), (FnoFuturesBasisCarryV1.Assumptions) args.get(4), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-171" -> {
                var decision = FnoFuturesCalendarSpreadV1.evaluate((FnoFuturesCalendarSpreadV1.Contract) args.get(0), (FnoFuturesCalendarSpreadV1.Contract) args.get(1), (FnoFuturesCalendarSpreadV1.Quote) args.get(2), (FnoFuturesCalendarSpreadV1.Quote) args.get(3), (FnoFuturesCalendarSpreadV1.SourceRights) args.get(4), (FnoFuturesCalendarSpreadV1.Assumptions) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-172" -> {
                var decision = FnoFuturesRolloverSignalV1.evaluate((FnoFuturesRolloverSignalV1.Contract) args.get(0), (FnoFuturesRolloverSignalV1.Contract) args.get(1), (List) args.get(2), (FnoFuturesRolloverSignalV1.SourceRights) args.get(3), (FnoFuturesRolloverSignalV1.Thresholds) args.get(4), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-173" -> {
                var decision = FnoFuturesOpenInterestTrendV1.evaluate((FnoFuturesOpenInterestTrendV1.Contract) args.get(0), (List) args.get(1), (FnoFuturesOpenInterestTrendV1.SourceRights) args.get(2), (FnoFuturesOpenInterestTrendV1.Thresholds) args.get(3), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-174" -> {
                var decision = FnoFuturesPriceOiDivergenceV1.evaluate((FnoFuturesPriceOiDivergenceV1.Contract) args.get(0), (List) args.get(1), (FnoFuturesPriceOiDivergenceV1.SourceRights) args.get(2), (FnoFuturesPriceOiDivergenceV1.Thresholds) args.get(3), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-175" -> {
                var decision = FnoCashFuturesArbitrageResearchV1.evaluate((FnoFuturesBasisCarryV1.Contract) args.get(0), (FnoCashFuturesArbitrageResearchV1.Leg) args.get(1), (FnoCashFuturesArbitrageResearchV1.Leg) args.get(2), (FnoFuturesBasisCarryV1.SourceRights) args.get(3), (FnoFuturesBasisCarryV1.Assumptions) args.get(4), (FnoCashFuturesArbitrageResearchV1.Capital) args.get(5), (FnoCashFuturesArbitrageResearchV1.AdditionalCosts) args.get(6), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-176" -> {
                var decision = FnoFuturesMomentumV1.evaluate((FnoFuturesMomentumV1.Contract) args.get(0), (List) args.get(1), (FnoFuturesMomentumV1.SourceRights) args.get(2), (FnoFuturesMomentumV1.Thresholds) args.get(3), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-177" -> {
                var decision = FnoFuturesMeanReversionV1.evaluate((FnoFuturesMeanReversionV1.Contract) args.get(0), (List) args.get(1), (FnoFuturesMeanReversionV1.SourceRights) args.get(2), (FnoFuturesMeanReversionV1.Thresholds) args.get(3), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-178" -> {
                var decision = FnoFuturesHedgeOverlayV1.evaluate((FnoFuturesHedgeOverlayV1.Contract) args.get(0), (FnoFuturesHedgeOverlayV1.Portfolio) args.get(1), (FnoFuturesHedgeOverlayV1.MarketQuote) args.get(2), (FnoFuturesHedgeOverlayV1.SourceRights) args.get(3), (FnoFuturesHedgeOverlayV1.Funding) args.get(4), (FnoFuturesHedgeOverlayV1.Stress) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-179" -> {
                var decision = FnoContractLiquidityGateV1.evaluate((FnoContractLiquidityGateV1.Contract) args.get(0), (List) args.get(1), (FnoContractLiquidityGateV1.SourceRights) args.get(2), (FnoContractLiquidityGateV1.Thresholds) args.get(3), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-180" -> {
                var decision = FnoExpiryBasisReversionV1.evaluate((FnoExpiryBasisReversionV1.Contract) args.get(0), (List) args.get(1), (FnoExpiryBasisReversionV1.SourceRights) args.get(2), (FnoExpiryBasisReversionV1.Assumptions) args.get(3), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-181" -> {
                var decision = FnoLongCallV1.evaluate((FnoLongCallV1.Contract) args.get(0), (List) args.get(1), (FnoLongCallV1.UnderlyingQuote) args.get(2), (FnoLongCallV1.CallQuote) args.get(3), (FnoLongCallV1.SourceRights) args.get(4), (FnoLongCallV1.Assumptions) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-182" -> {
                var decision = FnoLongPutV1.evaluate((FnoLongPutV1.Contract) args.get(0), (List) args.get(1), (FnoLongPutV1.UnderlyingQuote) args.get(2), (FnoLongPutV1.PutQuote) args.get(3), (FnoLongPutV1.SourceRights) args.get(4), (FnoLongPutV1.Assumptions) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-183" -> {
                var decision = FnoBullCallDebitSpreadV1.evaluate((FnoBullCallDebitSpreadV1.Contract) args.get(0), (FnoBullCallDebitSpreadV1.Contract) args.get(1), (List) args.get(2), (FnoBullCallDebitSpreadV1.UnderlyingQuote) args.get(3), (FnoBullCallDebitSpreadV1.OptionQuote) args.get(4), (FnoBullCallDebitSpreadV1.OptionQuote) args.get(5), (FnoBullCallDebitSpreadV1.SourceRights) args.get(6), (FnoBullCallDebitSpreadV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-184" -> {
                var decision = FnoBearPutDebitSpreadV1.evaluate((FnoBearPutDebitSpreadV1.Contract) args.get(0), (FnoBearPutDebitSpreadV1.Contract) args.get(1), (List) args.get(2), (FnoBearPutDebitSpreadV1.UnderlyingQuote) args.get(3), (FnoBearPutDebitSpreadV1.OptionQuote) args.get(4), (FnoBearPutDebitSpreadV1.OptionQuote) args.get(5), (FnoBearPutDebitSpreadV1.SourceRights) args.get(6), (FnoBearPutDebitSpreadV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-185" -> {
                var decision = FnoBullPutCreditSpreadV1.evaluate((FnoBullPutCreditSpreadV1.Contract) args.get(0), (FnoBullPutCreditSpreadV1.Contract) args.get(1), (List) args.get(2), (FnoBullPutCreditSpreadV1.UnderlyingQuote) args.get(3), (FnoBullPutCreditSpreadV1.OptionQuote) args.get(4), (FnoBullPutCreditSpreadV1.OptionQuote) args.get(5), (FnoBullPutCreditSpreadV1.SourceRights) args.get(6), (FnoBullPutCreditSpreadV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-186" -> {
                var decision = FnoBearCallCreditSpreadV1.evaluate((FnoBearCallCreditSpreadV1.Contract) args.get(0), (FnoBearCallCreditSpreadV1.Contract) args.get(1), (List) args.get(2), (FnoBearCallCreditSpreadV1.UnderlyingQuote) args.get(3), (FnoBearCallCreditSpreadV1.OptionQuote) args.get(4), (FnoBearCallCreditSpreadV1.OptionQuote) args.get(5), (FnoBearCallCreditSpreadV1.SourceRights) args.get(6), (FnoBearCallCreditSpreadV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-187" -> {
                var decision = FnoProtectivePutV1.evaluate((FnoProtectivePutV1.PutContract) args.get(0), (FnoProtectivePutV1.Holding) args.get(1), (List) args.get(2), (FnoProtectivePutV1.EquityQuote) args.get(3), (FnoProtectivePutV1.PutQuote) args.get(4), (FnoProtectivePutV1.SourceRights) args.get(5), (FnoProtectivePutV1.Assumptions) args.get(6), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-188" -> {
                var decision = FnoCoveredCallV1.evaluate((FnoCoveredCallV1.CallContract) args.get(0), (FnoCoveredCallV1.Holding) args.get(1), (List) args.get(2), (FnoCoveredCallV1.EquityQuote) args.get(3), (FnoCoveredCallV1.CallQuote) args.get(4), (FnoCoveredCallV1.SourceRights) args.get(5), (FnoCoveredCallV1.Assumptions) args.get(6), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-189" -> {
                var decision = FnoEquityCollarV1.evaluate((FnoEquityCollarV1.Contract) args.get(0), (FnoEquityCollarV1.Contract) args.get(1), (FnoEquityCollarV1.Holding) args.get(2), (List) args.get(3), (FnoEquityCollarV1.Quote) args.get(4), (FnoEquityCollarV1.Quote) args.get(5), (FnoEquityCollarV1.Quote) args.get(6), (FnoEquityCollarV1.Rights) args.get(7), (FnoEquityCollarV1.Assumptions) args.get(8), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-190" -> {
                var decision = FnoCallRatioSpreadV1.evaluate((FnoCallRatioSpreadV1.Contract) args.get(0), (FnoCallRatioSpreadV1.Contract) args.get(1), (List) args.get(2), (FnoCallRatioSpreadV1.Quote) args.get(3), (FnoCallRatioSpreadV1.Quote) args.get(4), (FnoCallRatioSpreadV1.Quote) args.get(5), (FnoCallRatioSpreadV1.Rights) args.get(6), (FnoCallRatioSpreadV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-191" -> {
                var decision = FnoPutRatioSpreadV1.evaluate((FnoPutRatioSpreadV1.Contract) args.get(0), (FnoPutRatioSpreadV1.Contract) args.get(1), (List) args.get(2), (FnoPutRatioSpreadV1.Quote) args.get(3), (FnoPutRatioSpreadV1.Quote) args.get(4), (FnoPutRatioSpreadV1.Quote) args.get(5), (FnoPutRatioSpreadV1.Rights) args.get(6), (FnoPutRatioSpreadV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-192" -> {
                var decision = FnoLongStraddleV1.evaluate((FnoLongStraddleV1.Contract) args.get(0), (FnoLongStraddleV1.Contract) args.get(1), (List) args.get(2), (FnoLongStraddleV1.Quote) args.get(3), (FnoLongStraddleV1.Quote) args.get(4), (FnoLongStraddleV1.Quote) args.get(5), (FnoLongStraddleV1.Rights) args.get(6), (FnoLongStraddleV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-193" -> {
                var decision = FnoLongStrangleV1.evaluate((FnoLongStrangleV1.Contract) args.get(0), (FnoLongStrangleV1.Contract) args.get(1), (List) args.get(2), (FnoLongStrangleV1.Quote) args.get(3), (FnoLongStrangleV1.Quote) args.get(4), (FnoLongStrangleV1.Quote) args.get(5), (FnoLongStrangleV1.Rights) args.get(6), (FnoLongStrangleV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-194" -> {
                var decision = FnoShortStraddleV1.evaluate((FnoShortStraddleV1.Contract) args.get(0), (FnoShortStraddleV1.Contract) args.get(1), (List) args.get(2), (FnoShortStraddleV1.Quote) args.get(3), (FnoShortStraddleV1.Quote) args.get(4), (FnoShortStraddleV1.Quote) args.get(5), (FnoShortStraddleV1.Rights) args.get(6), (FnoShortStraddleV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-195" -> {
                var decision = FnoShortStrangleV1.evaluate((FnoShortStrangleV1.Contract) args.get(0), (FnoShortStrangleV1.Contract) args.get(1), (List) args.get(2), (FnoShortStrangleV1.Quote) args.get(3), (FnoShortStrangleV1.Quote) args.get(4), (FnoShortStrangleV1.Quote) args.get(5), (FnoShortStrangleV1.Rights) args.get(6), (FnoShortStrangleV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-196" -> {
                var decision = FnoIronCondorV1.evaluate((FnoIronCondorV1.Contract) args.get(0), (FnoIronCondorV1.Contract) args.get(1), (FnoIronCondorV1.Contract) args.get(2), (FnoIronCondorV1.Contract) args.get(3), (List) args.get(4), (FnoIronCondorV1.Quote) args.get(5), (FnoIronCondorV1.Quote) args.get(6), (FnoIronCondorV1.Quote) args.get(7), (FnoIronCondorV1.Quote) args.get(8), (FnoIronCondorV1.Quote) args.get(9), (FnoIronCondorV1.Rights) args.get(10), (FnoIronCondorV1.Assumptions) args.get(11), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-197" -> {
                var decision = FnoIronButterflyV1.evaluate((FnoIronButterflyV1.Contract) args.get(0), (FnoIronButterflyV1.Contract) args.get(1), (FnoIronButterflyV1.Contract) args.get(2), (FnoIronButterflyV1.Contract) args.get(3), (List) args.get(4), (FnoIronButterflyV1.Quote) args.get(5), (FnoIronButterflyV1.Quote) args.get(6), (FnoIronButterflyV1.Quote) args.get(7), (FnoIronButterflyV1.Quote) args.get(8), (FnoIronButterflyV1.Quote) args.get(9), (FnoIronButterflyV1.Rights) args.get(10), (FnoIronButterflyV1.Assumptions) args.get(11), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-198" -> {
                var decision = FnoLongButterflyV1.evaluate((FnoLongButterflyV1.Contract) args.get(0), (FnoLongButterflyV1.Contract) args.get(1), (FnoLongButterflyV1.Contract) args.get(2), (List) args.get(3), (FnoLongButterflyV1.Quote) args.get(4), (FnoLongButterflyV1.Quote) args.get(5), (FnoLongButterflyV1.Quote) args.get(6), (FnoLongButterflyV1.Quote) args.get(7), (FnoLongButterflyV1.Rights) args.get(8), (FnoLongButterflyV1.Assumptions) args.get(9), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-199" -> {
                var decision = FnoCalendarSpreadV1.evaluate((FnoCalendarSpreadV1.Contract) args.get(0), (FnoCalendarSpreadV1.Contract) args.get(1), (List) args.get(2), (FnoCalendarSpreadV1.Quote) args.get(3), (FnoCalendarSpreadV1.Quote) args.get(4), (FnoCalendarSpreadV1.Quote) args.get(5), (FnoCalendarSpreadV1.Rights) args.get(6), (FnoCalendarSpreadV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-200" -> {
                var decision = FnoDiagonalSpreadV1.evaluate((FnoDiagonalSpreadV1.Contract) args.get(0), (FnoDiagonalSpreadV1.Contract) args.get(1), (List) args.get(2), (FnoDiagonalSpreadV1.Quote) args.get(3), (FnoDiagonalSpreadV1.Quote) args.get(4), (FnoDiagonalSpreadV1.Quote) args.get(5), (FnoDiagonalSpreadV1.Rights) args.get(6), (FnoDiagonalSpreadV1.Assumptions) args.get(7), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-201" -> {
                var decision = FnoIvRankMeanReversionV1.evaluate((List) args.get(0), (List) args.get(1), (FnoIvRankMeanReversionV1.CurrentIv) args.get(2), (FnoIvRankMeanReversionV1.Rights) args.get(3), (FnoIvRankMeanReversionV1.Assumptions) args.get(4), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-202" -> {
                var decision = FnoVolatilityCrushV1.evaluate((FnoVolatilityCrushV1.Event) args.get(0), (List) args.get(1), (FnoVolatilityCrushV1.Observation) args.get(2), (FnoVolatilityCrushV1.Observation) args.get(3), (FnoVolatilityCrushV1.Rights) args.get(4), (FnoVolatilityCrushV1.Assumptions) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-203" -> {
                var decision = FnoGammaScalpingV1.evaluate((FnoGammaScalpingV1.OptionContract) args.get(0), (FnoGammaScalpingV1.OptionContract) args.get(1), (FnoGammaScalpingV1.FutureContract) args.get(2), (List) args.get(3), (List) args.get(4), (FnoGammaScalpingV1.Rights) args.get(5), (FnoGammaScalpingV1.Assumptions) args.get(6), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-204" -> {
                var decision = FnoDeltaHedgeV1.evaluate((List) args.get(0), (FnoDeltaHedgeV1.FutureInstrument) args.get(1), (FnoDeltaHedgeV1.FuturesPosition) args.get(2), (FnoDeltaHedgeV1.Snapshot) args.get(3), (List) args.get(4), (FnoDeltaHedgeV1.Rights) args.get(5), (FnoDeltaHedgeV1.Policy) args.get(6), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-205" -> {
                var decision = FnoBetaHedgeOptionsV1.evaluate((List) args.get(0), (FnoBetaHedgeOptionsV1.OptionContract) args.get(1), (FnoBetaHedgeOptionsV1.Snapshot) args.get(2), (List) args.get(3), (FnoBetaHedgeOptionsV1.Rights) args.get(4), (FnoBetaHedgeOptionsV1.Policy) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-206" -> {
                var decision = FnoTailRiskPutHedgeV1.evaluate((List) args.get(0), (FnoTailRiskPutHedgeV1.PutContract) args.get(1), (FnoTailRiskPutHedgeV1.Snapshot) args.get(2), (List) args.get(3), (FnoTailRiskPutHedgeV1.Rights) args.get(4), (FnoTailRiskPutHedgeV1.Policy) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-207" -> {
                var decision = FnoPutSpreadCollarV1.evaluate((FnoPutSpreadCollarV1.BrokerFuturePosition) args.get(0), (FnoPutSpreadCollarV1.FutureContract) args.get(1), (FnoPutSpreadCollarV1.OptionContract) args.get(2), (FnoPutSpreadCollarV1.OptionContract) args.get(3), (FnoPutSpreadCollarV1.OptionContract) args.get(4), (FnoPutSpreadCollarV1.Snapshot) args.get(5), (List) args.get(6), (FnoPutSpreadCollarV1.Rights) args.get(7), (FnoPutSpreadCollarV1.Policy) args.get(8), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-208" -> {
                var decision = FnoDefinedRiskSpreadRollV1.evaluate((FnoDefinedRiskSpreadRollV1.BrokerSpread) args.get(0), (FnoDefinedRiskSpreadRollV1.Contract) args.get(1), (FnoDefinedRiskSpreadRollV1.Contract) args.get(2), (FnoDefinedRiskSpreadRollV1.Contract) args.get(3), (FnoDefinedRiskSpreadRollV1.Contract) args.get(4), (FnoDefinedRiskSpreadRollV1.Snapshot) args.get(5), (List) args.get(6), (FnoDefinedRiskSpreadRollV1.Rights) args.get(7), (FnoDefinedRiskSpreadRollV1.Policy) args.get(8), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-209" -> {
                var decision = FnoExpiryRiskNeutralizationV1.evaluate((FnoExpiryRiskNeutralizationV1.BrokerBook) args.get(0), (FnoExpiryRiskNeutralizationV1.Snapshot) args.get(1), (FnoExpiryRiskNeutralizationV1.ExpiryEvidence) args.get(2), (List) args.get(3), (FnoExpiryRiskNeutralizationV1.Rights) args.get(4), (FnoExpiryRiskNeutralizationV1.Policy) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-210" -> {
                var decision = FnoPortfolioProtectiveOverlayV1.evaluate((List) args.get(0), (List) args.get(1), (FnoPortfolioProtectiveOverlayV1.Snapshot) args.get(2), (List) args.get(3), (FnoPortfolioProtectiveOverlayV1.Rights) args.get(4), (FnoPortfolioProtectiveOverlayV1.Policy) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-211" -> {
                var decision = FnoGreeksExposureRebalanceV1.evaluate((FnoGreeksExposureRebalanceV1.BrokerBook) args.get(0), (List) args.get(1), (FnoGreeksExposureRebalanceV1.Snapshot) args.get(2), (List) args.get(3), (FnoGreeksExposureRebalanceV1.Rights) args.get(4), (FnoGreeksExposureRebalanceV1.Policy) args.get(5), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            case "strategy.STR-212" -> {
                var decision = FnoSkewBasedHedgeV1.evaluate((FnoSkewBasedHedgeV1.BrokerLongFuture) args.get(0), (FnoSkewBasedHedgeV1.FutureContract) args.get(1), (FnoSkewBasedHedgeV1.OptionContract) args.get(2), (FnoSkewBasedHedgeV1.OptionContract) args.get(3), (FnoSkewBasedHedgeV1.OptionContract) args.get(4), (FnoSkewBasedHedgeV1.Snapshot) args.get(5), (List) args.get(6), (FnoSkewBasedHedgeV1.Rights) args.get(7), (FnoSkewBasedHedgeV1.Policy) args.get(8), asOf);
                yield new Eval(decision.status().name(), decision.reasonCode());
            }
            default -> null;
        };
    }
}
