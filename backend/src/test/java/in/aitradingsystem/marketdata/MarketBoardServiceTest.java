package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarketBoardServiceTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 11, 12);
    /** 16:30 IST: the cash session has closed. */
    private static final Instant AFTER_CLOSE = Instant.parse("2026-11-13T11:00:00Z");
    private static final Instant TODAY_TICK = Instant.parse("2026-11-13T09:59:00Z");
    private static final Instant YESTERDAY_TICK = Instant.parse("2026-11-12T09:59:00Z");

    private final MarketBoardRepository repository = mock(MarketBoardRepository.class);
    private final AppAngelOneMarketFeedService feed = mock(AppAngelOneMarketFeedService.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final MarketDetailSnapshotStore store = mock(MarketDetailSnapshotStore.class);

    private MarketBoardService service(Instant now) {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.session(TODAY)).thenReturn(new MarketSession(TODAY,
            ZonedDateTime.of(TODAY, LocalTime.of(9, 15), IST),
            ZonedDateTime.of(TODAY, LocalTime.of(15, 30), IST), true));
        doAnswer(call -> {
            Instant at = call.getArgument(0);
            LocalTime time = at.atZone(IST).toLocalTime();
            return !time.isBefore(LocalTime.of(9, 15)) && time.isBefore(LocalTime.of(15, 30));
        }).when(calendar).isInsideCashSession(any());
        return new MarketBoardService(repository, feed, calendar, store, Clock.fixed(now, ZoneOffset.UTC), true, 90);
    }

    private static MarketBoardRepository.StoredQuote quote(long id, String symbol, String industry,
            Set<String> universes, String ltp, String prev, long volume, String high52, String low52,
            Instant at) {
        BigDecimal price = new BigDecimal(ltp);
        BigDecimal previous = new BigDecimal(prev);
        return new MarketBoardRepository.StoredQuote(id, symbol, symbol.replace("-EQ", " Ltd."), industry,
            universes, price, previous, price.max(previous), price.min(previous), previous, volume,
            high52 == null ? null : new BigDecimal(high52), low52 == null ? null : new BigDecimal(low52), at);
    }

    private void givenMarket() {
        when(repository.universes()).thenReturn(List.of(
            new MarketBoardRepository.UniverseState("NIFTY50", 50, YESTERDAY),
            new MarketBoardRepository.UniverseState("NIFTY100", 100, YESTERDAY),
            new MarketBoardRepository.UniverseState("MIDCAP150", 150, YESTERDAY)));
        when(repository.activeNseListings()).thenReturn(2751L);
        when(repository.storedQuotes()).thenReturn(List.of(
            quote(1, "LARGEUP-EQ", "Financial Services", Set.of("NIFTY100", "NIFTY50"),
                "110", "100", 1_000_000, "111", "80", TODAY_TICK),
            quote(2, "LARGEDN-EQ", "Financial Services", Set.of("NIFTY100"),
                "95", "100", 5_000_000, "150", "94", TODAY_TICK),
            quote(3, "MIDUP-EQ", "Information Technology", Set.of("MIDCAP150"),
                "102", "100", 10, "200", "50", TODAY_TICK),
            quote(4, "UNLISTED-EQ", null, Set.of(), "100", "100", 0, null, null, TODAY_TICK),
            // An older session's quote must not be mixed into today's movers.
            quote(5, "OLDMOVER-EQ", "Healthcare", Set.of("MIDCAP150"), "150", "100", 99, "151", "90",
                YESTERDAY_TICK)));
    }

    @Test
    void summaryCoversLatestSessionOnlyAndReportsUningestedSegments() {
        givenMarket();
        var summary = service(AFTER_CLOSE).summary();

        assertThat(summary.market().state()).isEqualTo("CLOSED");
        assertThat(summary.freshness().state()).isEqualTo("LAST_SESSION");
        assertThat(summary.freshness().sessionDate()).isEqualTo(TODAY);
        assertThat(summary.freshness().excludedOlderSession()).isEqualTo(1);
        assertThat(summary.listed()).isEqualTo(2751);

        var all = summary.segments().stream().filter(s -> s.segment().equals("ALL")).findFirst().orElseThrow();
        assertThat(all.quoted()).isEqualTo(4);
        assertThat(all.breadth()).isEqualTo(new MarketBoardService.Breadth(2, 1, 1, 4));

        var large = summary.segments().stream().filter(s -> s.segment().equals("LARGE")).findFirst().orElseThrow();
        assertThat(large.available()).isTrue();
        assertThat(large.quoted()).isEqualTo(2);
        assertThat(large.avgChangePct()).isEqualByComparingTo("2.50");

        var micro = summary.segments().stream().filter(s -> s.segment().equals("MICRO")).findFirst().orElseThrow();
        assertThat(micro.available()).isFalse();
        assertThat(micro.quoted()).isZero();

        // Financial Services averages (+10% and -5%) / 2 = +2.50%, ahead of IT at +2.00%.
        assertThat(summary.sectors()).extracting(MarketBoardService.SectorSummary::sector)
            .containsExactly("Financial Services", "Information Technology");
        assertThat(summary.sectors().get(0).leader()).isEqualTo("LARGEUP-EQ");
        assertThat(summary.sectors().get(0).avgChangePct()).isEqualByComparingTo("2.50");
    }

    @Test
    void boardRanksMoversActivityAndFiftyTwoWeekExtremes() {
        givenMarket();
        var board = service(AFTER_CLOSE);

        assertThat(board.board(MarketBoardService.Segment.ALL, MarketBoardService.Sort.GAINERS, null, 10).rows())
            .extracting(MarketBoardService.Row::tradingSymbol).containsExactly("LARGEUP-EQ", "MIDUP-EQ");
        assertThat(board.board(MarketBoardService.Segment.ALL, MarketBoardService.Sort.LOSERS, null, 10).rows())
            .extracting(MarketBoardService.Row::tradingSymbol).containsExactly("LARGEDN-EQ");
        assertThat(board.board(MarketBoardService.Segment.ALL, MarketBoardService.Sort.ACTIVE, null, 1).rows())
            .extracting(MarketBoardService.Row::tradingSymbol).containsExactly("LARGEDN-EQ");
        assertThat(board.board(MarketBoardService.Segment.ALL, MarketBoardService.Sort.HIGH52, null, 10).rows())
            .extracting(MarketBoardService.Row::tradingSymbol).containsExactly("LARGEUP-EQ");
        assertThat(board.board(MarketBoardService.Segment.ALL, MarketBoardService.Sort.LOW52, null, 10).rows())
            .extracting(MarketBoardService.Row::tradingSymbol).containsExactly("LARGEDN-EQ");
        assertThat(board.board(MarketBoardService.Segment.MID, MarketBoardService.Sort.CHANGE, null, 10).rows())
            .extracting(MarketBoardService.Row::tradingSymbol).containsExactly("MIDUP-EQ");
        assertThat(board.board(MarketBoardService.Segment.NIFTY50, MarketBoardService.Sort.CHANGE, null, 10).rows())
            .extracting(MarketBoardService.Row::tradingSymbol).containsExactly("LARGEUP-EQ");
        assertThat(board.board(MarketBoardService.Segment.ALL, MarketBoardService.Sort.CHANGE,
                "financial services", 10).rows())
            .extracting(MarketBoardService.Row::tradingSymbol).containsExactly("LARGEUP-EQ", "LARGEDN-EQ");

        var row = board.board(MarketBoardService.Segment.ALL, MarketBoardService.Sort.GAINERS, null, 1).rows().get(0);
        assertThat(row.changePct()).isEqualByComparingTo("10.00");
        assertThat(row.capSegment()).isEqualTo("LARGE");
        assertThat(row.tradedValue()).isEqualByComparingTo("110000000");
    }

    @Test
    void segmentWithoutOfficialListIsUnavailableNotInferred() {
        givenMarket();
        var micro = service(AFTER_CLOSE).board(MarketBoardService.Segment.MICRO,
            MarketBoardService.Sort.GAINERS, null, 10);
        assertThat(micro.available()).isFalse();
        assertThat(micro.rows()).isEmpty();
    }

    @Test
    void rowsFromNonTradingDaysOrTheFutureAreExcluded() {
        when(repository.universes()).thenReturn(List.of());
        when(repository.storedQuotes()).thenReturn(List.of(
            quote(1, "FUTURE-EQ", null, Set.of(), "110", "100", 1, null, null, AFTER_CLOSE.plusSeconds(60)),
            quote(2, "HOLIDAY-EQ", null, Set.of(), "110", "100", 1, null, null, TODAY_TICK)));
        var board = service(AFTER_CLOSE);
        when(calendar.isTradingDay(TODAY)).thenReturn(false);
        var summary = board.summary();
        assertThat(summary.freshness().state()).isEqualTo("UNAVAILABLE");
        assertThat(summary.segments().get(0).quoted()).isZero();
    }

    @Test
    void intradayRefreshNeverCallsTheProviderOutsideTheCashSessionOrWithoutDemand() {
        givenMarket();
        when(feed.available()).thenReturn(true);
        var closed = service(AFTER_CLOSE);
        closed.summary();
        closed.refreshTick();
        verify(feed, never()).details(anyList());

        var noDemand = service(Instant.parse("2026-11-13T05:00:00Z"));
        noDemand.refreshTick();
        verify(feed, never()).details(anyList());
    }

    @Test
    void rejectsUnknownSegmentAndSort() {
        assertThatThrownBy(() -> MarketBoardService.parseSegment("NANO")).isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> MarketBoardService.parseSort("RANDOM")).isInstanceOf(MarketDataException.class);
    }
}
