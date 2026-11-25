package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.CandidateFingerprint;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
import in.aitradingsystem.scanner.ScanContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Strictly prospective scanner receipt capture for an immutable 20-stock
 * cohort. An explicit ADMIN action runs this at the current completed
 * five-minute close. Late runs cannot generate retrospective signals.
 * Signal records cannot authorize accounts, PAPER or LIVE broker orders.
 *
 * A future scheduled collector may invoke capture only after separate
 * operational review; no implicit scheduler or GET side effect is added.
 */
@Service
public class MindProspectiveSignalService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final Duration MAX_CAPTURE_DELAY = Duration.ofMinutes(10);
    private static final Duration MAX_SOURCE_DELAY = Duration.ofMinutes(15);
    private static final int REQUIRED_BARS = 21;
    private static final String STRATEGY_CODE = "MA_RECLAIM_V1";
    private static final int TEN_SESSIONS = 10;

    private final MindProspectiveCohortRepository cohorts;
    private final MindProspectiveSignalRepository receipts;
    private final MarketDataRepository market;
    private final MarketSessionCalendar calendar;
    private final MovingAverageReclaimStrategy strategy;
    private final Clock clock;

    public record Capture(Instant capturedAt, Instant signalCandleAt,
                          int memberCount, int written, int candidateSignals,
                          int sourceBlocked, int alreadyRecorded,
                          String posture, boolean executionEnabled) {}

    public record MemberCount(int slot, String symbol,
                              int recordedWindows, int researchCandidates) {}

    public record Status(String contractVersion, Instant generatedAt,
                         String status, Instant registeredAt, Instant observationStartsAt,
                         int memberCount, int recordedWindows, int researchCandidates,
                         List<MemberCount> members, List<String> blockers,
                         String decisionPosture, boolean outcomeValidated,
                         boolean paperOrderSubmitted, boolean executionEnabled) {}

    public MindProspectiveSignalService(
            MindProspectiveCohortRepository cohorts,
            MindProspectiveSignalRepository receipts,
            MarketDataRepository market,
            MarketSessionCalendar calendar,
            MovingAverageReclaimStrategy strategy,
            Clock clock) {
        this.cohorts = cohorts;
        this.receipts = receipts;
        this.market = market;
        this.calendar = calendar;
        this.strategy = strategy;
        this.clock = clock;
    }

    public Capture capture() {
        Instant now = clock.instant();
        var cohort = cohorts.current()
                .orElseThrow(() -> new IllegalStateException("COHORT_NOT_REGISTERED"));
        if (cohort.registeredAt() == null || cohort.observationStartsAt() == null ||
            !cohort.registeredAt().isBefore(cohort.observationStartsAt()) ||
            cohort.registeredAt().isAfter(now) ||
            cohort.members().size() != MindProspectiveCohortRepository.SIZE ||
            !STRATEGY_CODE.equals(cohort.strategyCode())) {
            throw new IllegalStateException("COHORT_NOT_VALID");
        }
        LocalDate date = now.atZone(IST).toLocalDate();
        if (!isWithinFirstTenSessions(cohort.observationStartsAt(), date)) {
            throw new IllegalStateException("OUTSIDE_PROSPECTIVE_SESSION_RANGE");
        }
        var session = calendar.session(date);
        if (!session.tradingDay() || now.isBefore(session.open().toInstant()) ||
            now.isBefore(cohort.observationStartsAt())) {
            throw new IllegalStateException("CASH_MARKET_NOT_READY");
        }
        var slots = calendar.expectedSlots(date, HistoricalCandleInterval.FIVE_MINUTE);
        int selected = -1;
        for (int i = 0; i < slots.size(); i++) {
            var close = slots.get(i).timestamp().plus(BAR);
            if (!now.isBefore(close) && now.isBefore(close.plus(MAX_CAPTURE_DELAY))) {
                selected = i;
            }
        }
        if (selected < REQUIRED_BARS - 1) {
            throw new IllegalStateException("NO_ELIGIBLE_COMPLETED_SIGNAL_WINDOW");
        }
        Instant signalAt = slots.get(selected).timestamp();
        // Do not permit after-hours or future-session reconstruction.
        if (signalAt.isBefore(session.open().toInstant()) ||
            !signalAt.isBefore(session.close().toInstant()) ||
            now.isAfter(session.close().toInstant().plus(MAX_CAPTURE_DELAY))) {
            throw new IllegalStateException("INVALID_SIGNAL_SESSION");
        }

        int written = 0, signals = 0, missing = 0, duplicates = 0;
        for (var member : cohort.members()) {
            if (member.slot() < 1 ||
                member.slot() > MindProspectiveCohortRepository.SIZE ||
                member.instrumentId() < 1 ||
                !"NSE".equals(member.exchange()) ||
                !cohort.catalogueVersion().equals(member.catalogueVersion())) {
                throw new IllegalStateException("IMMUTABLE_MEMBER_INVALID");
            }
            List<MarketCandle> bars = market.recentCandlesAsOf(
                member.instrumentId(), "FIVE_MINUTE", signalAt, REQUIRED_BARS);
            if (!validInputs(cohort, member, bars, slots, selected, now)) {
                missing++;
                continue;
            }
            // The existing strategy is reused unmodified. Output stays
            // research-only even when the candidate fingerprint reproduces.
            var candidate = strategy.evaluate(new ScanContext(
                    member.instrumentId(), member.tradingSymbol(),
                    member.exchange(), "FIVE_MINUTE", now, bars)).orElse(null);
            var frozen = MindSourceSnapshot.fromCandles(
                bars, member.instrumentId(), member.exchange(),
                member.tradingSymbol(), "FIVE_MINUTE", signalAt, now);
            String fingerprint = candidate == null ? null : CandidateFingerprint.of(
                candidate.instrumentId(), candidate.interval(), candidate.strategy(),
                strategy.descriptor().version(), candidate.candleTime(),
                candidate.referencePrice(), candidate.suggestedStop(),
                candidate.suggestedTarget());
            boolean added = receipts.record(cohort.id(), member.slot(), signalAt,
                    now, frozen, candidate, fingerprint);
            if (added) {
                written++;
                if (candidate != null) signals++;
            } else {
                duplicates++;
            }
        }
        return new Capture(now, signalAt, cohort.members().size(),
                written, signals, missing, duplicates, "ABSTAIN", false);
    }

    public Status inspect() {
        Instant now = clock.instant();
        try {
            var saved = cohorts.current();
            if (saved.isEmpty()) return unavailable(now, "COHORT_NOT_REGISTERED");
            var cohort = saved.get();
            if (cohort.members().size() != MindProspectiveCohortRepository.SIZE ||
                !STRATEGY_CODE.equals(cohort.strategyCode()) ||
                cohort.registeredAt() == null || cohort.observationStartsAt() == null ||
                !cohort.observationStartsAt().isAfter(cohort.registeredAt())) {
                return unavailable(now, "COHORT_INVALID");
            }
            var counts = new HashMap<Integer, MindProspectiveSignalRepository.ReceiptCount>();
            for (var c : receipts.counts(cohort.id())) counts.put(c.memberSlot(), c);
            var members = new ArrayList<MemberCount>();
            int observations = 0, signals = 0;
            for (var member : cohort.members()) {
                var count = counts.get(member.slot());
                int recorded = count == null ? 0 : count.observations();
                int candidate = count == null ? 0 : count.signals();
                if (recorded < 0 || candidate < 0 || candidate > recorded) {
                    return unavailable(now, "RECEIPT_COUNTS_INVALID");
                }
                observations += recorded;
                signals += candidate;
                members.add(new MemberCount(member.slot(),
                    member.tradingSymbol(), recorded, candidate));
            }
            return new Status("mind.prospective-signals.v1", now,
                observations == 0 ? "AWAITING_RECEIPTS" : "RESEARCH_RECEIPTS_RECORDED",
                cohort.registeredAt(), cohort.observationStartsAt(),
                members.size(), observations, signals, List.copyOf(members),
                blockers("PROSPECTIVE_OUTCOMES_NOT_RECONCILED"), "ABSTAIN",
                false, false, false);
        } catch (RuntimeException ex) {
            return unavailable(now, "SIGNAL_LEDGER_UNAVAILABLE");
        }
    }

    private boolean isWithinFirstTenSessions(Instant start, LocalDate current) {
        LocalDate first = start.atZone(IST).toLocalDate();
        int count = 0;
        for (LocalDate date = first; !date.isAfter(current); date = date.plusDays(1)) {
            if (date.isAfter(first.plusDays(40))) return false;
            if (calendar.session(date).tradingDay()) {
                count++;
                if (date.equals(current)) return count <= TEN_SESSIONS;
            }
        }
        return false;
    }

    private static boolean validInputs(
            MindProspectiveCohortRepository.Cohort cohort,
            MindProspectiveCohortRepository.Member member,
            List<MarketCandle> bars,
            List<in.aitradingsystem.marketdata.ExpectedCandleSlot> expected,
            int endIndex, Instant now) {
        if (bars == null || bars.size() != REQUIRED_BARS ||
            endIndex < REQUIRED_BARS - 1) return false;
        String source = bars.getFirst().source();
        if (source == null || source.isBlank() ||
            source.matches("(?i).*(TEST|FIXTURE|MOCK|DEMO|SYNTHETIC).*")) return false;
        for (int i = 0; i < REQUIRED_BARS; i++) {
            MarketCandle bar = bars.get(i);
            Instant timestamp = expected.get(endIndex - REQUIRED_BARS + 1 + i).timestamp();
            if (bar == null || bar.candleTime() == null ||
                !bar.candleTime().equals(timestamp) ||
                bar.instrumentId() != member.instrumentId() ||
                !member.exchange().equals(bar.exchange()) ||
                !member.tradingSymbol().equals(bar.tradingSymbol()) ||
                !member.symbolToken().equals(bar.symbolToken()) ||
                !cohort.catalogueVersion().equals(bar.catalogueVersion()) ||
                !"FIVE_MINUTE".equals(bar.interval()) ||
                !source.equals(bar.source()) ||
                bar.fetchedAt() == null ||
                bar.fetchedAt().isBefore(timestamp.plus(BAR)) ||
                bar.fetchedAt().isAfter(timestamp.plus(MAX_SOURCE_DELAY)) ||
                bar.fetchedAt().isAfter(now) ||
                bar.fetchedAt().isBefore(cohort.registeredAt()) ||
                bar.open() == null || bar.high() == null ||
                bar.low() == null || bar.close() == null ||
                bar.open().signum() <= 0 ||
                bar.high().compareTo(bar.open()) < 0 ||
                bar.high().compareTo(bar.close()) < 0 ||
                bar.low().compareTo(bar.open()) > 0 ||
                bar.low().compareTo(bar.close()) > 0 ||
                bar.volume() <= 0) return false;
        }
        return true;
    }

    private static List<String> blockers(String reason) {
        return List.of(reason,
                "ORIGINAL_PROVIDER_KNOWN_AT_NOT_INDEPENDENTLY_ATTESTED",
                "BROKER_STATUTORY_COST_MODEL_NOT_COMPLETE",
                "ACCOUNT_RISK_NOT_EVALUATED",
                "PAPER_SHADOW_ENROLLMENT_REQUIRED",
                "LIVE_EXECUTION_DISABLED");
    }

    private static Status unavailable(Instant now, String reason) {
        return new Status("mind.prospective-signals.v1", now,
                "COHORT_NOT_REGISTERED".equals(reason) ? "UNREGISTERED" : "UNAVAILABLE",
                null, null, 0, 0, 0, List.of(), blockers(reason),
                "ABSTAIN", false, false, false);
    }
}
