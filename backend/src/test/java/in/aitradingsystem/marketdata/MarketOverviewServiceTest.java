package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser.Detail;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketOverviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-09T04:00:00Z");
    private static final Instant TRADED = Instant.parse("2026-11-09T03:59:00Z");
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 9);

    @Test
    void indexExpectationUsesVerifiedNseTokenNotVariableProviderDisplayName() {
        var expected = MarketOverviewService.indexExpectations(List.of(
            new MarketOverviewRepository.IndexDefinition(
                "NIFTY50", "Nifty 50", "99926000"),
            new MarketOverviewRepository.IndexDefinition(
                "BANKNIFTY", "Nifty Bank", "99926009")));
        assertThat(expected).hasSize(2);
        assertThat(expected.get(0).symbolToken()).isEqualTo("99926000");
        assertThat(expected.get(1).symbolToken()).isEqualTo("99926009");
        assertThat(expected).allSatisfy(row ->
            assertThat(row.tradingSymbol()).isNull());
    }

    @Test
    void failedEquityBulkDoesNotEraseVerifiedNiftyIndexQuote() {
        var repository = mock(MarketOverviewRepository.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var store = mock(MarketOverviewSnapshotStore.class);
        when(store.load()).thenReturn(Optional.empty());
        when(calendar.session(AS_OF)).thenReturn(new MarketSession(AS_OF,
            java.time.ZonedDateTime.of(AS_OF, java.time.LocalTime.of(9, 15),
                java.time.ZoneId.of("Asia/Kolkata")),
            java.time.ZonedDateTime.of(AS_OF, java.time.LocalTime.of(15, 30),
                java.time.ZoneId.of("Asia/Kolkata")), true));
        when(repository.universe("NIFTY50")).thenReturn(List.of(
            new MarketOverviewRepository.Member(
                "AAA-EQ", "Alpha", "Banks", AS_OF, "src")));
        when(repository.resolveEquityTokens(List.of("AAA-EQ")))
            .thenReturn(Map.of("AAA-EQ", "1"));
        when(repository.indices()).thenReturn(List.of(
            new MarketOverviewRepository.IndexDefinition(
                "NIFTY50", "Nifty 50", "99926000")));
        when(feed.details(anyList())).thenAnswer(call -> {
            List<in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser.Expected> request =
                call.getArgument(0);
            if ("1".equals(request.getFirst().symbolToken())) {
                throw new MarketDataException("PROVIDER_RATE_LIMITED",
                    "Equity bulk request temporarily unavailable");
            }
            assertThat(request.getFirst().symbolToken()).isEqualTo("99926000");
            assertThat(request.getFirst().tradingSymbol()).isNull();
            return Map.of("99926000", detail("24000", "23950", "23980"));
        });

        var service = new MarketOverviewService(repository, feed, calendar,
            Clock.fixed(NOW, ZoneOffset.UTC), store);
        var overview = service.overview();

        assertThat(overview.indices()).hasSize(1);
        assertThat(overview.indices().getFirst().indexCode()).isEqualTo("NIFTY50");
        assertThat(overview.indices().getFirst().ltp()).isEqualByComparingTo("24000");
        assertThat(overview.breadth().total()).isZero();
        assertThat(overview.universe().returned()).isZero();
        assertThat(overview.ideas()).isEmpty();
        verify(store).save(overview);
    }

    @Test
    void holidayStampedBulkQuotesCannotProduceFalseMarketEvidence() {
        var holiday = LocalDate.of(2026, 11, 10);
        var now = Instant.parse("2026-11-10T05:30:00Z");
        var repository = mock(MarketOverviewRepository.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var store = mock(MarketOverviewSnapshotStore.class);
        when(store.load()).thenReturn(Optional.empty());
        when(calendar.session(holiday)).thenReturn(new MarketSession(holiday,
            java.time.ZonedDateTime.of(holiday, java.time.LocalTime.of(9, 15),
                java.time.ZoneId.of("Asia/Kolkata")),
            java.time.ZonedDateTime.of(holiday, java.time.LocalTime.of(15, 30),
                java.time.ZoneId.of("Asia/Kolkata")), false));
        when(repository.universe("NIFTY50")).thenReturn(List.of(
            new MarketOverviewRepository.Member("AAA-EQ", "Alpha", "Banks", AS_OF, "src")));
        when(repository.resolveEquityTokens(List.of("AAA-EQ"))).thenReturn(Map.of("AAA-EQ", "1"));
        when(repository.indices()).thenReturn(List.of());
        when(feed.details(anyList())).thenReturn(Map.of(
            "1", detailAt("110", "105", "100", "2026-11-10T05:28:40Z")));

        var service = new MarketOverviewService(repository, feed, calendar,
            Clock.fixed(now, ZoneOffset.UTC), store);

        assertThatThrownBy(service::overview)
            .isInstanceOf(MarketDataException.class)
            .hasMessageContaining("no verified constituent or index quotes");
        verify(store, never()).save(any(MarketOverviewService.Overview.class));
    }

    @Test
    void storedOverviewWithHolidayExchangeTimestampIsNotPresentedAsLastTrade() {
        var holidayAt = Instant.parse("2026-11-10T05:28:40Z");
        var repository = mock(MarketOverviewRepository.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var store = mock(MarketOverviewSnapshotStore.class);
        var snapshot = new MarketOverviewService.Overview(holidayAt,
            MarketOverviewService.SOURCE, "NIFTY50_CONSTITUENTS", holidayAt,
            new MarketOverviewService.Universe("NIFTY50", AS_OF, 50, 50, 1),
            new MarketOverviewService.Breadth(1, 0, 0, 1), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of());
        when(store.load()).thenReturn(Optional.of(snapshot));
        when(calendar.isInsideCashSession(holidayAt)).thenReturn(false);

        var service = new MarketOverviewService(repository, feed, calendar,
            Clock.fixed(holidayAt.plusSeconds(5), ZoneOffset.UTC), store);

        assertThat(service.storedOverview()).isEmpty();
        verify(calendar).isInsideCashSession(holidayAt);
    }

    @Test
    void equityRefreshPreservesPreviouslyVerifiedNiftyIndexWithoutInventingLive() {
        var repository = mock(MarketOverviewRepository.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var store = mock(MarketOverviewSnapshotStore.class);
        var definitions = List.of(new MarketOverviewRepository.IndexDefinition(
            "NIFTY50", "Nifty 50", "99926000"));
        var member = new MarketOverviewRepository.Member("AAA-EQ", "Alpha", "Banks", AS_OF, "src");
        when(repository.universe("NIFTY50")).thenReturn(List.of(member));
        when(repository.indices()).thenReturn(definitions);
        when(repository.resolveEquityTokens(List.of("AAA-EQ"))).thenReturn(Map.of("AAA-EQ", "1"));
        when(feed.details(anyList())).thenAnswer(call -> {
            List<in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser.Expected> request =
                call.getArgument(0);
            return "1".equals(request.getFirst().symbolToken())
                ? Map.of("1", detail("110", "105", "100"))
                : Map.of(); // Index provider batch failed while equity succeeded.
        });
        var priorIndexAt = Instant.parse("2026-11-09T03:55:00Z");
        var priorIndex = new MarketOverviewService.IndexRow(
            "NIFTY50", "Nifty 50", new BigDecimal("24000"),
            new BigDecimal("23980"), new BigDecimal("0.08"),
            new BigDecimal("23950"), new BigDecimal("24100"), new BigDecimal("23900"),
            0, priorIndexAt, "LIVE");
        var last = new MarketOverviewService.Overview(NOW.minusSeconds(60),
            MarketOverviewService.SOURCE, "NIFTY50_CONSTITUENTS", priorIndexAt,
            new MarketOverviewService.Universe("NIFTY50", AS_OF, 50, 50, 50),
            new MarketOverviewService.Breadth(30, 20, 0, 50), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(priorIndex), List.of());
        when(store.load()).thenReturn(Optional.of(last));
        when(calendar.isInsideCashSession(priorIndexAt)).thenReturn(true);
        when(calendar.session(AS_OF)).thenReturn(new MarketSession(AS_OF,
            java.time.ZonedDateTime.of(AS_OF, java.time.LocalTime.of(9, 15),
                java.time.ZoneId.of("Asia/Kolkata")),
            java.time.ZonedDateTime.of(AS_OF, java.time.LocalTime.of(15, 30),
                java.time.ZoneId.of("Asia/Kolkata")), true));

        var service = new MarketOverviewService(repository, feed, calendar,
            Clock.fixed(NOW, ZoneOffset.UTC), store);
        var overview = service.overview();

        assertThat(overview.universe().returned()).isEqualTo(1);
        assertThat(overview.indices()).hasSize(1);
        assertThat(overview.indices().getFirst().ltp()).isEqualByComparingTo("24000");
        assertThat(overview.indices().getFirst().exchangeAt()).isEqualTo(priorIndexAt);
        assertThat(overview.indices().getFirst().freshness()).isEqualTo("LAST_CLOSE");
        assertThat(overview.ideas()).isEmpty();
        verify(store).save(overview);
    }

    @Test
    void totalProviderOutageCannotCreateEmptySavedOverviewSnapshot() {
        var repository = mock(MarketOverviewRepository.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var store = mock(MarketOverviewSnapshotStore.class);
        when(store.load()).thenReturn(Optional.empty());
        when(repository.universe("NIFTY50")).thenReturn(List.of(
            new MarketOverviewRepository.Member(
                "AAA-EQ", "Alpha", "Banks", AS_OF, "src")));
        when(repository.resolveEquityTokens(List.of("AAA-EQ")))
            .thenReturn(Map.of("AAA-EQ", "1"));
        when(repository.indices()).thenReturn(List.of(
            new MarketOverviewRepository.IndexDefinition(
                "NIFTY50", "Nifty 50", "99926000")));
        when(feed.details(anyList())).thenThrow(
            new MarketDataException("PROVIDER_UNAVAILABLE", "No current verified provider data"));

        var service = new MarketOverviewService(repository, feed, calendar,
            Clock.fixed(NOW, ZoneOffset.UTC), store);
        assertThatThrownBy(service::overview)
            .isInstanceOf(MarketDataException.class)
            .hasMessageContaining("no verified constituent or index quotes");
        verify(store, never()).save(
            org.mockito.ArgumentMatchers.any(MarketOverviewService.Overview.class));
    }

    @Test
    void computesBreadthMoversGapSectorsAndIndicesFromVerifiedRowsOnly() {
        var members = List.of(
            new MarketOverviewRepository.Member("AAA-EQ", "Alpha", "Banks", AS_OF, "src"),
            new MarketOverviewRepository.Member("BBB-EQ", "Beta", "Banks", AS_OF, "src"),
            new MarketOverviewRepository.Member("CCC-EQ", "Gamma", "IT", AS_OF, "src"),
            new MarketOverviewRepository.Member("DDD-EQ", "Delta", "IT", AS_OF, "src"),
            new MarketOverviewRepository.Member("EEE-EQ", "Epsilon", "IT", AS_OF, "src"));
        var tokens = Map.of("AAA-EQ", "1", "BBB-EQ", "2", "CCC-EQ", "3", "EEE-EQ", "5");
        var stocks = Map.of(
            "1", detail("110", "105", "100"),
            "2", detail("90", "99", "100"),
            "3", detail("100", "100", "100"));
        var indexDefs = List.of(new MarketOverviewRepository.IndexDefinition("NIFTY50", "Nifty 50", "99926000"));
        var indices = Map.of("99926000", detail("24000", "23950", "23980"));

        var overview = MarketOverviewService.compute(NOW, members, tokens, stocks, indexDefs, indices);

        assertThat(overview.universe().members()).isEqualTo(5);
        assertThat(overview.universe().resolved()).isEqualTo(4);
        assertThat(overview.universe().returned()).isEqualTo(3);
        assertThat(overview.universe().sourceAsOf()).isEqualTo(AS_OF);
        assertThat(overview.breadthScope()).isEqualTo("NIFTY50_CONSTITUENTS");
        assertThat(overview.breadth().advancers()).isEqualTo(1);
        assertThat(overview.breadth().decliners()).isEqualTo(1);
        assertThat(overview.breadth().unchanged()).isEqualTo(1);
        assertThat(overview.gainers()).extracting(m -> m.tradingSymbol()).containsExactly("AAA-EQ");
        assertThat(overview.losers()).extracting(m -> m.tradingSymbol()).containsExactly("BBB-EQ");
        assertThat(overview.gainers().get(0).changePct()).isEqualByComparingTo("10.00");
        assertThat(overview.gapUp()).extracting(m -> m.tradingSymbol()).containsExactly("AAA-EQ");
        assertThat(overview.gapUp().get(0).gapPct()).isEqualByComparingTo("5.00");
        assertThat(overview.gapDown()).extracting(m -> m.tradingSymbol()).containsExactly("BBB-EQ");
        assertThat(overview.sectors()).extracting(s -> s.sector()).containsExactly("Banks", "IT");
        assertThat(overview.sectors().get(0).members()).isEqualTo(2);
        assertThat(overview.sectors().get(0).avgChangePct()).isEqualByComparingTo("0.00");
        assertThat(overview.sectors().get(1).members()).isEqualTo(1);
        assertThat(overview.indices()).hasSize(1);
        assertThat(overview.indices().get(0).changePct()).isEqualByComparingTo("0.08");
        assertThat(overview.exchangeAsOf()).isEqualTo(TRADED);
    }

    @Test
    void emptyProviderDataProducesNoBreadthOrMoversAndNoIndices() {
        var members = List.of(new MarketOverviewRepository.Member("AAA-EQ", "Alpha", "Banks", AS_OF, "src"));

        var overview = MarketOverviewService.compute(NOW, members, Map.of(), Map.of(),
            List.of(), Map.of());

        assertThat(overview.universe().resolved()).isZero();
        assertThat(overview.breadth().total()).isZero();
        assertThat(overview.gainers()).isEmpty();
        assertThat(overview.sectors()).isEmpty();
        assertThat(overview.indices()).isEmpty();
        assertThat(overview.exchangeAsOf()).isNull();
    }

    @Test
    void indexRowsAreLabelledLiveStaleOrLastCloseAgainstTodaysSession() {
        var indexDefs = List.of(new MarketOverviewRepository.IndexDefinition("NIFTY50", "Nifty 50", "99926000"));
        var open = new MarketOverviewService.IndexSession(true, Instant.parse("2026-11-09T03:45:00Z"));

        var live = MarketOverviewService.compute(NOW, List.of(), Map.of(), Map.of(), indexDefs,
            Map.of("99926000", detail("24000", "23950", "23980")), open);
        assertThat(live.indices().get(0).freshness()).isEqualTo("LIVE");

        var stale = MarketOverviewService.compute(NOW, List.of(), Map.of(), Map.of(), indexDefs,
            Map.of("99926000", detailAt("24000", "23950", "23980", "2026-11-09T03:00:00Z")), open);
        assertThat(stale.indices().get(0).freshness()).isEqualTo("LAST_CLOSE");

        var expired = MarketOverviewService.compute(NOW, List.of(), Map.of(), Map.of(), indexDefs,
            Map.of("99926000", detailAt("24000", "23950", "23980", "2026-11-09T03:55:00Z")), open);
        assertThat(expired.indices().get(0).freshness()).isEqualTo("LAST_CLOSE");

        var future = MarketOverviewService.compute(NOW, List.of(), Map.of(), Map.of(), indexDefs,
            Map.of("99926000", detailAt("24000", "23950", "23980", "2026-11-09T04:05:00Z")), open);
        assertThat(future.indices().get(0).freshness()).isEqualTo("STALE");

        var closed = MarketOverviewService.compute(NOW, List.of(), Map.of(), Map.of(), indexDefs,
            Map.of("99926000", detail("24000", "23950", "23980")), MarketOverviewService.IndexSession.CLOSED);
        assertThat(closed.indices().get(0).freshness()).isEqualTo("LAST_CLOSE");
    }

    @Test
    void storedOverviewIsLabelledStoredAndIndicesAreLastClose() {
        var index = new MarketOverviewService.IndexRow("NIFTY50", "Nifty 50", new BigDecimal("24000"),
            new BigDecimal("23900"), new BigDecimal("0.42"), null, null, null, 0L, TRADED, "LIVE");
        var overview = new MarketOverviewService.Overview(NOW, "ANGEL_ONE_SMARTAPI_FULL_BULK",
            "NIFTY50_CONSTITUENTS", TRADED, new MarketOverviewService.Universe("NIFTY50", AS_OF, 50, 50, 50),
            null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(index),
            List.of(new MarketOverviewService.Idea("AAA-EQ", "Alpha", new BigDecimal("110"),
                new BigDecimal("10"), new BigDecimal("5"), "BREAKOUT", TRADED)));
        var stored = MarketOverviewService.asStored(overview);
        assertThat(stored.source()).isEqualTo("STORED_LAST_OVERVIEW");
        assertThat(stored.generatedAt()).isEqualTo(NOW);
        assertThat(stored.indices().get(0).freshness()).isEqualTo("LAST_CLOSE");
        assertThat(stored.indices().get(0).ltp()).isEqualByComparingTo("24000");
        assertThat(stored.ideas()).isEmpty(); // Historical screening signals must never be actionable.
    }

    @Test
    void ideasAreRuleScreenHitsWithBreakoutTakingPriorityOverMomentumAndGap() {
        var members = List.of(
            new MarketOverviewRepository.Member("AAA-EQ", "Alpha", "Banks", AS_OF, "src"),
            new MarketOverviewRepository.Member("BBB-EQ", "Beta", "IT", AS_OF, "src"),
            new MarketOverviewRepository.Member("CCC-EQ", "Gamma", "IT", AS_OF, "src"),
            new MarketOverviewRepository.Member("DDD-EQ", "Delta", "Pharma", AS_OF, "src"));
        var tokens = Map.of("AAA-EQ", "1", "BBB-EQ", "2", "CCC-EQ", "3", "DDD-EQ", "4");
        var stocks = Map.of(
            "1", detailWith52High("110", "105", "100", "110"),
            "2", detailWith52High("103", "101", "100", "150"),
            "3", detailWith52High("100.5", "101.5", "100", "150"),
            "4", detailWith52High("100.2", "100.1", "100", "150"));

        var marketOpen = new MarketOverviewService.IndexSession(true, Instant.parse("2026-11-09T03:45:00Z"));
        var overview = MarketOverviewService.compute(NOW, members, tokens, stocks, List.of(), Map.of(),
            marketOpen);

        assertThat(overview.ideas()).extracting(i -> i.tradingSymbol() + ":" + i.signal())
            .containsExactly("AAA-EQ:BREAKOUT", "BBB-EQ:MOMENTUM", "CCC-EQ:GAP_UP");
    }

    @Test
    void marketIdeasDisappearWhenClosedOrTicksAreStaleOrFromTheFuture() {
        var member = new MarketOverviewRepository.Member("AAA-EQ", "Alpha", "Banks", AS_OF, "src");
        var tokens = Map.of("AAA-EQ", "1");
        var session = new MarketOverviewService.IndexSession(true, Instant.parse("2026-11-09T03:45:00Z"));
        var fresh = Map.of("1", detailWith52High("110", "105", "100", "110"));

        assertThat(MarketOverviewService.compute(NOW, List.of(member), tokens, fresh, List.of(), Map.of(),
            MarketOverviewService.IndexSession.CLOSED).ideas()).isEmpty();
        assertThat(MarketOverviewService.compute(NOW, List.of(member), tokens,
            Map.of("1", quoteAt("2026-11-09T03:55:00Z")), List.of(), Map.of(), session).ideas()).isEmpty();
        assertThat(MarketOverviewService.compute(NOW, List.of(member), tokens,
            Map.of("1", quoteAt("2026-11-09T04:05:00Z")), List.of(), Map.of(), session).ideas()).isEmpty();
        assertThat(MarketOverviewService.compute(NOW, List.of(member), tokens, fresh, List.of(), Map.of(),
            session).ideas()).hasSize(1);
    }

    private static Detail quoteAt(String instant) {
        return new Detail(new BigDecimal("110"), new BigDecimal("105"), new BigDecimal("110"),
            new BigDecimal("105"), new BigDecimal("100"), 1000L, Instant.parse(instant),
            null, new BigDecimal("110"), null, 0L, 0L, List.of(), List.of());
    }

    private static Detail detailWith52High(String ltp, String open, String previousClose, String week52High) {
        return new Detail(new BigDecimal(ltp), new BigDecimal(open), new BigDecimal(ltp).max(new BigDecimal(open)),
            new BigDecimal(ltp).min(new BigDecimal(open)), new BigDecimal(previousClose), 1000L,
            Instant.parse("2026-11-09T03:59:00Z"), null, new BigDecimal(week52High), null, 0L, 0L, List.of(), List.of());
    }

    private static Detail detail(String ltp, String open, String previousClose) {
        return detailAt(ltp, open, previousClose, "2026-11-09T03:59:00Z");
    }

    private static Detail detailAt(String ltp, String open, String previousClose, String at) {
        return new Detail(new BigDecimal(ltp), new BigDecimal(open), new BigDecimal(ltp).max(new BigDecimal(open)),
            new BigDecimal(ltp).min(new BigDecimal(open)), new BigDecimal(previousClose), 1000L, Instant.parse(at),
            null, null, null, 0L, 0L, List.of(), List.of());
    }
}

