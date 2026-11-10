package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.math.BigDecimal;
import java.util.List;

/**
 * Explicit private historical data provider: authenticated with the
 * server's SSM-backed app feed, never a customer-supplied token. Not a Spring
 * MarketDataProvider bean, so it cannot replace the public/provider-neutral
 * default adapter by accident.
 */
public final class OwnerAngelOneHistoricalProvider implements MarketDataProvider {
    private final AppAngelOneMarketFeedService feed;
    private final HistoricalCandleParser parser;

    public OwnerAngelOneHistoricalProvider(AppAngelOneMarketFeedService feed,
                                           HistoricalCandleParser parser) {
        this.feed = feed;
        this.parser = parser;
    }

    @Override
    public String source() {
        return "ANGEL_ONE_SMARTAPI";
    }

    @Override
    public BigDecimal lastTradedPrice(Instrument instrument) {
        return feed.quote(instrument);
    }

    @Override
    public List<HistoricalCandleParser.CandleValues> historicalCandles(
            String exchange, String symbolToken, String interval, String fromDate, String toDate) {
        return parser.parse(feed.historicalCandles(exchange, symbolToken, interval, fromDate, toDate));
    }
}
