package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser.Detail;
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
        assertThat(stale.indices().get(0).freshness()).isEqualTo("STALE");

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
            null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(index), List.of());
        var stored = MarketOverviewService.asStored(overview);
        assertThat(stored.source()).isEqualTo("STORED_LAST_OVERVIEW");
        assertThat(stored.generatedAt()).isEqualTo(NOW);
        assertThat(stored.indices().get(0).freshness()).isEqualTo("LAST_CLOSE");
        assertThat(stored.indices().get(0).ltp()).isEqualByComparingTo("24000");
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

        var overview = MarketOverviewService.compute(NOW, members, tokens, stocks, List.of(), Map.of());

        assertThat(overview.ideas()).extracting(i -> i.tradingSymbol() + ":" + i.signal())
            .containsExactly("AAA-EQ:BREAKOUT", "BBB-EQ:MOMENTUM", "CCC-EQ:GAP_UP");
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

