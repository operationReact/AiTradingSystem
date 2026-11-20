package in.aitradingsystem.mutualfund;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public NAVs and read-only evidence; never stores account ownership or orders. */
@Service
public class MfResearchStore {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private final JdbcClient jdbc;

    public MfResearchStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Snapshot(int schemeCode, String isin, String name, String category,
                           String risk, LocalDate navDate, BigDecimal nav, int observations,
                           String state) {}
    public record Alert(UUID id, int schemeCode, String name, String category,
                        String risk, LocalDate navDate, BigDecimal nav, String kind,
                        BigDecimal drawdownPercent, BigDecimal peakNav,
                        int illustrativeAmountInr, String explanation) {}
    public record Feed(String status, Instant checkedAt, LocalDate latestNavDate,
                       int rowsAccepted, String failureCode) {}
    public record Board(String source, String disclaimer, Feed feed,
                        List<Snapshot> watchlist, List<Alert> alerts) {}

    @Transactional
    public void accept(Map<Integer, AmfiDailyNavSource.DailyNav> verified) {
        if (verified.size() != MfResearchCatalogue.FUNDS.size()) {
            throw new IllegalArgumentException("AMFI_WATCHLIST_INCOMPLETE");
        }
        LocalDate newest = null;
        for (MfResearchCatalogue.Fund fund : MfResearchCatalogue.FUNDS) {
            AmfiDailyNavSource.DailyNav nav = verified.get(fund.schemeCode());
            if (nav == null || !nav.date().isAfter(LocalDate.now(INDIA).minusDays(8))) {
                throw new IllegalArgumentException("AMFI_NAV_NOT_CURRENT");
            }
            // Refuse same-day NAV revisions silently overwriting stored evidence.
            BigDecimal prior = jdbc.sql("""
                    SELECT nav FROM mf_research_nav
                    WHERE scheme_code=:code AND nav_date=:date
                    """).param("code", fund.schemeCode()).param("date", nav.date())
                .query(BigDecimal.class).optional().orElse(null);
            if (prior != null && prior.compareTo(nav.nav()) != 0) {
                throw new IllegalStateException("AMFI_NAV_REVISION_NEEDS_REVIEW");
            }
            jdbc.sql("""
                INSERT INTO mf_research_nav(scheme_code,nav_date,nav,fetched_at)
                VALUES (:code,:date,:nav,NOW()) ON CONFLICT (scheme_code,nav_date) DO NOTHING
                """).param("code", fund.schemeCode()).param("date", nav.date())
                .param("nav", nav.nav()).update();
            newest = newest == null || nav.date().isAfter(newest) ? nav.date() : newest;
            List<MfResearchSignalRules.Observation> history = history(fund.schemeCode());
            MfResearchSignalRules.Signal signal = MfResearchSignalRules.evaluate(fund, history);
            if (signal != null && history.get(history.size()-1).date().equals(nav.date())) {
                jdbc.sql("""
                    INSERT INTO mf_research_alert
                        (id,scheme_code,nav_date,kind,drawdown_bps,nav,peak_nav,illustrative_amount_inr)
                    VALUES (:id,:code,:date,:kind,:drawdown,:nav,:peak,:amount)
                    ON CONFLICT (scheme_code,nav_date,kind) DO NOTHING
                    """).param("id", UUID.randomUUID()).param("code", fund.schemeCode())
                    .param("date", nav.date()).param("kind", signal.kind())
                    .param("drawdown", signal.drawdownBps()).param("nav", nav.nav())
                    .param("peak", signal.peakNav())
                    .param("amount", fund.illustrativeAmountInr()).update();
            }
        }
        jdbc.sql("""
                UPDATE mf_research_sync SET status='VERIFIED',last_checked_at=NOW(),
                    latest_nav_date=:date, rows_accepted=:accepted, failure_code=NULL
                WHERE id=1
                """).param("date", newest).param("accepted", verified.size()).update();
    }

    public void failure(String code) {
        String safeCode = code != null && code.matches("[A-Z0-9_]{3,60}")
            ? code : "SOURCE_REFRESH_FAILED";
        jdbc.sql("""
                UPDATE mf_research_sync SET status='FAILED',last_checked_at=NOW(),
                    failure_code=:code WHERE id=1
                """).param("code", safeCode).update();
    }

    public Board board() {
        Feed feed = jdbc.sql("""
                SELECT status,last_checked_at,latest_nav_date,rows_accepted,failure_code
                FROM mf_research_sync WHERE id=1
                """).query((rs,n)-> new Feed(
                    rs.getString("status"),
                    rs.getTimestamp("last_checked_at") == null ? null : rs.getTimestamp("last_checked_at").toInstant(),
                    rs.getDate("latest_nav_date") == null ? null : rs.getDate("latest_nav_date").toLocalDate(),
                    rs.getInt("rows_accepted"), rs.getString("failure_code"))).single();
        LocalDate today = LocalDate.now(INDIA);
        List<Snapshot> watchlist = new ArrayList<>();
        for (MfResearchCatalogue.Fund fund : MfResearchCatalogue.FUNDS) {
            List<MfResearchSignalRules.Observation> data = history(fund.schemeCode());
            MfResearchSignalRules.Observation latest = data.isEmpty() ? null : data.get(data.size()-1);
            String state = latest == null ? "NO_NAV" : latest.date().isBefore(today.minusDays(7))
                ? "STALE" : data.size() < 22 ? "WARMING_UP" : "MONITORING";
            watchlist.add(new Snapshot(fund.schemeCode(),fund.isin(),fund.name(),
                fund.category(),fund.risk(),latest == null ? null : latest.date(),
                latest == null ? null : latest.nav(),data.size(),state));
        }
        List<Alert> alerts = jdbc.sql("""
                SELECT id,scheme_code,nav_date,nav,kind,drawdown_bps,
                       peak_nav,illustrative_amount_inr FROM mf_research_alert
                WHERE nav_date >= :fromDate
                ORDER BY nav_date DESC,created_at DESC LIMIT 30
                """).param("fromDate", today.minusDays(14))
            .query((rs,n) -> {
                int code = rs.getInt("scheme_code");
                MfResearchCatalogue.Fund fund = MfResearchCatalogue.BY_CODE.get(code);
                String kind = rs.getString("kind");
                BigDecimal percent = BigDecimal.valueOf(rs.getInt("drawdown_bps"),2);
                return new Alert(rs.getObject("id",UUID.class),code,fund.name(),
                    fund.category(),fund.risk(),rs.getDate("nav_date").toLocalDate(),
                    rs.getBigDecimal("nav"),kind,percent,rs.getBigDecimal("peak_nav"),
                    rs.getInt("illustrative_amount_inr"),
                    "NAV is " + percent.toPlainString()
                    + "% below its prior 20-observation peak. Review only; "
                    + "no guarantee of recovery or favorable entry.");
            }).list();
        return new Board("AMFI_NAVALL", "Research alerts, not personalized investment advice. "
            + "Amounts are illustrative, not buy instructions. NAV is not a live price. "
            + "Verify risk, suitability, expense ratio and exit load before investing.",
            feed, List.copyOf(watchlist), List.copyOf(alerts));
    }

    public record Backfill(LocalDate nextFromDate, String status,
                           LocalDate lastWindowEnd, int acceptedRows, String failureCode) {}

    public Backfill backfill() {
        return jdbc.sql("""
                SELECT next_from_date,status,last_window_end,accepted_rows,failure_code
                FROM mf_research_history_cursor WHERE id=1
                """).query((rs,row)->new Backfill(
                    rs.getDate("next_from_date").toLocalDate(),
                    rs.getString("status"),
                    rs.getDate("last_window_end")==null ? null : rs.getDate("last_window_end").toLocalDate(),
                    rs.getInt("accepted_rows"),rs.getString("failure_code"))).single();
    }

    @Transactional
    public Backfill acceptHistory(List<AmfiHistoricalNavSource.Nav> items,
                                  LocalDate from, LocalDate through) {
        if (!backfill().nextFromDate().equals(from) || through.isBefore(from)
            || through.isAfter(from.plusDays(6))
            || items == null || items.size() < MfResearchCatalogue.FUNDS.size()) {
            throw new IllegalArgumentException("AMFI_HISTORY_WINDOW_CONFLICT");
        }
        for (AmfiHistoricalNavSource.Nav point : items) {
            if (MfResearchCatalogue.BY_CODE.get(point.schemeCode()) == null
                || point.navDate().isBefore(from) || point.navDate().isAfter(through)
                || point.nav().signum() <= 0) {
                throw new IllegalArgumentException("AMFI_HISTORY_UNTRUSTED_NAV");
            }
            BigDecimal earlier = jdbc.sql("""
                    SELECT nav FROM mf_research_nav
                    WHERE scheme_code=:code AND nav_date=:date
                    """).param("code",point.schemeCode())
                .param("date",point.navDate()).query(BigDecimal.class).optional().orElse(null);
            if (earlier != null && earlier.compareTo(point.nav()) != 0)
                throw new IllegalArgumentException("AMFI_HISTORY_NAV_REVISION");
            jdbc.sql("""
                    INSERT INTO mf_research_nav(scheme_code,nav_date,nav,source)
                    VALUES (:code,:date,:nav,'AMFI')
                    ON CONFLICT (scheme_code,nav_date) DO NOTHING
                    """).param("code",point.schemeCode())
                .param("date",point.navDate()).param("nav",point.nav()).update();
        }
        int updated = jdbc.sql("""
                UPDATE mf_research_history_cursor
                SET next_from_date=:next,status='RUNNING',last_window_end=:through,
                    accepted_rows=accepted_rows+:count,last_imported_at=NOW(),
                    failure_code=NULL
                WHERE id=1 AND next_from_date=:from
                """).param("next",through.plusDays(1)).param("through",through)
            .param("count",items.size()).param("from",from).update();
        if (updated != 1) throw new IllegalStateException("AMFI_HISTORY_CURSOR_RACE");
        return backfill();
    }

    public void backfillFailed(String cause) {
        String code = cause != null && cause.matches("[A-Z0-9_]{3,60}")
            ? cause : "AMFI_HISTORY_REFRESH_FAILED";
        jdbc.sql("""
                UPDATE mf_research_history_cursor SET status='FAILED',
                    failure_code=:code WHERE id=1
                """).param("code",code).update();
    }

    public Backfill backfillComplete() {
        jdbc.sql("""
                UPDATE mf_research_history_cursor SET status='COMPLETE',
                    failure_code=NULL WHERE id=1
                """).update();
        return backfill();
    }

    private List<MfResearchSignalRules.Observation> history(int code) {
        List<MfResearchSignalRules.Observation> newest = jdbc.sql("""
                SELECT nav_date,nav FROM mf_research_nav
                WHERE scheme_code=:code ORDER BY nav_date DESC LIMIT 40
                """).param("code",code).query((rs,n)->
                    new MfResearchSignalRules.Observation(rs.getDate("nav_date").toLocalDate(),
                        rs.getBigDecimal("nav"))).list();
        return newest.reversed();
    }
}
