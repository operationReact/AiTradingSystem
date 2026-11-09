package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.ExpectedCandleSlot;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Forward-only multi-stock research cohort registration and data-coverage
 * status. A human ADMIN must explicitly initiate a new protocol cohort.
 * No outcome-dependent replacement, backfill, trading or auto-enrollment.
 */
@Service
public class MindProspectiveCohortService {
    private static final int MAX_DAYS_TO_NEXT_SESSION = 14;
    private static final int MAX_DAYS_TO_TEN_SESSIONS = 40;
    private static final int READ_LIMIT = 20_001;
    private static final int TARGET_SESSIONS = 10;
    private static final BigDecimal MIN_MEMBER_COVERAGE = new BigDecimal("95.00");
    private static final Duration FETCH_GRACE = Duration.ofMinutes(15);

    private final MindProspectiveCohortRepository repository;
    private final MarketSessionCalendar calendar;
    private final Clock clock;

    public record MemberState(int slot, String exchange, String symbol,
                              int validCandles, int expectedCandles,
                              BigDecimal coveragePercent, String state) {}

    public record Status(String contractVersion, Instant generatedAt,
                         String sourceStatus, String cohortCode,
                         String strategyCode, String selectionMethod,
                         UUID catalogueVersion, Instant registeredAt,
                         Instant observationStartsAt, Instant maturityAt,
                         int targetSessions, int completedSessions,
                         int memberCount, int membersWithSufficientCoverage,
                         List<MemberState> members,
                         List<String> blockers, String decisionPosture,
                         boolean outcomeValidated, boolean paperOrderSubmitted,
                         boolean executionEnabled) {}

    public MindProspectiveCohortService(MindProspectiveCohortRepository repository,
                                        MarketSessionCalendar calendar, Clock clock) {
        this.repository = repository;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** Explicit administrative registration; a GET never mutates cohort state. */
    public Status register() {
        Instant now = clock.instant();
        Instant start = nextSessionOpen(now);
        repository.register(UUID.randomUUID(), now, start);
        return inspect();
    }

    public Status inspect() {
        Instant now = clock.instant();
        try {
            var saved = repository.current();
            if (saved.isEmpty()) return unavailable(now, "COHORT_NOT_REGISTERED");
            var cohort = saved.get();
            if (!validCohort(cohort, now)) {
                return unavailable(now, "COHORT_MEMBERSHIP_INVALID");
            }
            List<SessionDay> sessions = expectedSessions(cohort.observationStartsAt());
            Instant maturity = sessions.getLast().close();
            int completed = 0;
            Set<Instant> eligible = new HashSet<>();
            for (var session : sessions) {
                if (!session.close().isAfter(now)) {
                    completed++;
                    eligible.addAll(session.slots());
                }
            }

            Map<Integer, Set<Instant>> counted = new HashMap<>();
            for (var member : cohort.members()) counted.put(member.slot(), new HashSet<>());
            if (!eligible.isEmpty()) {
                // 20 instruments × 10 regular sessions × 75 bars = 15,000
                // observations maximum under normal exchange hours.
                var observed = repository.observations(
                    cohort.id(), cohort.observationStartsAt(), maturity, READ_LIMIT);
                if (observed.size() >= READ_LIMIT) {
                    return unavailable(now, "OBSERVATION_QUERY_LIMIT_REACHED");
                }
                for (var record : observed) {
                    if (validObservation(record, now, cohort.registeredAt(), eligible)) {
                        counted.get(record.slot()).add(record.candleTime());
                    }
                }
            }
            int expected = eligible.size();
            int sufficient = 0;
            List<MemberState> members = new ArrayList<>();
            for (var member : cohort.members()) {
                int count = counted.get(member.slot()).size();
                BigDecimal coverage = expected == 0
                    ? BigDecimal.ZERO.setScale(2)
                    : BigDecimal.valueOf(count).multiply(new BigDecimal("100"))
                        .divide(BigDecimal.valueOf(expected), 2, RoundingMode.DOWN);
                boolean good = completed == TARGET_SESSIONS &&
                    coverage.compareTo(MIN_MEMBER_COVERAGE) >= 0;
                if (good) sufficient++;
                members.add(new MemberState(member.slot(), member.exchange(),
                    member.tradingSymbol(), count, expected, coverage,
                    completed < TARGET_SESSIONS ? "OBSERVATION_PENDING" :
                        good ? "SOURCE_COVERAGE_RECORDED" : "INSUFFICIENT_SOURCE_COVERAGE"));
            }
            String state = now.isBefore(cohort.observationStartsAt())
                ? "AWAITING_FUTURE_SESSION"
                : completed < TARGET_SESSIONS ? "COLLECTING"
                : sufficient == cohort.members().size()
                    ? "SOURCE_COVERAGE_READY_RESEARCH_ONLY" : "INSUFFICIENT_COVERAGE";
            List<String> blockers = new ArrayList<>(safetyBlockers());
            if (completed < TARGET_SESSIONS) blockers.add("PROSPECTIVE_SESSIONS_NOT_COMPLETE");
            if (sufficient < cohort.members().size())
                blockers.add("ALL_MEMBERS_SOURCE_COVERAGE_NOT_VERIFIED");
            return new Status("mind.prospective-cohort.v1", now, state,
                cohort.cohortCode(), cohort.strategyCode(),
                MindProspectiveCohortRepository.SELECTION, cohort.catalogueVersion(),
                cohort.registeredAt(), cohort.observationStartsAt(), maturity,
                TARGET_SESSIONS, completed, cohort.members().size(), sufficient,
                List.copyOf(members), List.copyOf(blockers),
                "ABSTAIN", false, false, false);
        } catch (RuntimeException failure) {
            // Failed DB, holiday calendar, or source check: no fake cohort.
            return unavailable(now, "PROSPECTIVE_EVIDENCE_UNAVAILABLE");
        }
    }

    private Instant nextSessionOpen(Instant now) {
        LocalDate date = now.atZone(IndianCashMarketCalendar.ZONE)
            .toLocalDate().plusDays(1);
        for (int offset = 0; offset < MAX_DAYS_TO_NEXT_SESSION; offset++) {
            var session = calendar.session(date.plusDays(offset));
            if (session.tradingDay() && session.open().toInstant().isAfter(now)) {
                return session.open().toInstant();
            }
        }
        throw new IllegalStateException("FUTURE_CASH_SESSION_UNAVAILABLE");
    }

    private record SessionDay(Instant close, Set<Instant> slots) {}

    private List<SessionDay> expectedSessions(Instant observationStart) {
        LocalDate date = observationStart.atZone(IndianCashMarketCalendar.ZONE)
            .toLocalDate();
        List<SessionDay> days = new ArrayList<>();
        for (int offset = 0; offset < MAX_DAYS_TO_TEN_SESSIONS &&
                days.size() < TARGET_SESSIONS; offset++) {
            LocalDate candidate = date.plusDays(offset);
            var session = calendar.session(candidate);
            if (!session.tradingDay()) continue;
            var slots = calendar.expectedSlots(candidate, HistoricalCandleInterval.FIVE_MINUTE);
            Set<Instant> timestamps = new HashSet<>();
            for (ExpectedCandleSlot slot : slots) {
                Instant t = slot.timestamp();
                if (t == null || t.isBefore(session.open().toInstant()) ||
                    !t.isBefore(session.close().toInstant()) ||
                    !timestamps.add(t)) {
                    throw new IllegalStateException("UNVERIFIED_CASH_SESSION_SLOTS");
                }
            }
            if (timestamps.isEmpty()) {
                throw new IllegalStateException("EMPTY_CASH_SESSION_SLOTS");
            }
            if (days.isEmpty() &&
                !session.open().toInstant().equals(observationStart)) {
                throw new IllegalStateException("COHORT_START_NOT_SESSION_OPEN");
            }
            days.add(new SessionDay(session.close().toInstant(), Set.copyOf(timestamps)));
        }
        if (days.size() != TARGET_SESSIONS) {
            throw new IllegalStateException("INSUFFICIENT_FUTURE_SESSIONS");
        }
        return List.copyOf(days);
    }

    private static boolean validCohort(
            MindProspectiveCohortRepository.Cohort c, Instant now) {
        if (c.members().size() != MindProspectiveCohortRepository.SIZE ||
            c.targetSessions() != TARGET_SESSIONS ||
            !MindProspectiveCohortRepository.CODE.equals(c.cohortCode()) ||
            !MindProspectiveCohortRepository.PROTOCOL.equals(c.protocolVersion()) ||
            !"MA_RECLAIM_V1".equals(c.strategyCode()) ||
            c.catalogueVersion() == null || c.catalogueActivatedAt() == null ||
            c.registeredAt() == null || c.observationStartsAt() == null ||
            c.registeredAt().isAfter(now) ||
            c.catalogueActivatedAt().isAfter(c.registeredAt()) ||
            !c.observationStartsAt().isAfter(c.registeredAt())) return false;
        Set<Long> ids = new HashSet<>();
        Set<String> symbols = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (var m : c.members()) {
            if (m.instrumentId() < 1 || m.slot() < 1 ||
                m.slot() > MindProspectiveCohortRepository.SIZE ||
                !slots.add(m.slot()) || !ids.add(m.instrumentId()) ||
                !c.catalogueVersion().equals(m.catalogueVersion()) ||
                !"NSE".equals(m.exchange()) ||
                m.tradingSymbol() == null ||
                !m.tradingSymbol().matches("[A-Z0-9._&-]+-EQ") ||
                m.tradingSymbol().contains("TEST") ||
                !symbols.add(m.tradingSymbol()) ||
                m.symbolToken() == null || m.symbolToken().isBlank()) return false;
        }
        return true;
    }

    private static boolean validObservation(
            MindProspectiveCohortRepository.Observation o, Instant now,
            Instant registered, Set<Instant> expected) {
        if (o == null || o.candleTime() == null || o.fetchedAt() == null ||
            o.slot() < 1 || o.slot() > MindProspectiveCohortRepository.SIZE ||
            !expected.contains(o.candleTime()) ||
            o.fetchedAt().isBefore(registered) ||
            o.fetchedAt().isBefore(o.candleTime().plus(Duration.ofMinutes(5))) ||
            o.fetchedAt().isAfter(o.candleTime().plus(FETCH_GRACE)) ||
            o.fetchedAt().isAfter(now) ||
            o.source() == null || o.source().isBlank() ||
            o.source().matches("(?i).*(TEST|FIXTURE|MOCK|DEMO|SYNTHETIC).*") ||
            o.volume() < 1 || o.open() == null || o.high() == null ||
            o.low() == null || o.close() == null ||
            o.open().signum() <= 0 || o.high().signum() <= 0 ||
            o.low().signum() <= 0 || o.close().signum() <= 0 ||
            o.high().compareTo(o.low()) < 0 ||
            o.high().compareTo(o.open()) < 0 ||
            o.high().compareTo(o.close()) < 0 ||
            o.low().compareTo(o.open()) > 0 ||
            o.low().compareTo(o.close()) > 0) return false;
        return true;
    }

    private static List<String> safetyBlockers() {
        return List.of("PROSPECTIVE_RETURNS_NOT_EVALUATED",
            "LIQUIDITY_AND_SURVIVORSHIP_NOT_INDEPENDENTLY_VERIFIED",
            "SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_ATTESTED",
            "BROKER_STATUTORY_COST_MODEL_NOT_COMPLETE",
            "ACCOUNT_RISK_NOT_EVALUATED",
            "PAPER_SHADOW_ENROLLMENT_REQUIRED",
            "LIVE_EXECUTION_DISABLED");
    }

    private static Status unavailable(Instant now, String code) {
        List<String> blockers = new ArrayList<>(safetyBlockers());
        blockers.add(code);
        return new Status("mind.prospective-cohort.v1", now,
            "COHORT_NOT_REGISTERED".equals(code) ? "UNREGISTERED" : "UNAVAILABLE",
            null, "MA_RECLAIM_V1",
            MindProspectiveCohortRepository.SELECTION, null,
            null, null, null, TARGET_SESSIONS, 0, 0, 0,
            List.of(), List.copyOf(blockers),
            "ABSTAIN", false, false, false);
    }
}
