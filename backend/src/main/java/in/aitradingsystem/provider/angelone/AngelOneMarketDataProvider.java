package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.HistoricalCandleParser;
import in.aitradingsystem.marketdata.MarketDataProvider;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** SmartAPI implementation of the broker-neutral read-only market-data contract. */
@Component
@ConditionalOnProperty(prefix = "app.market-data", name = "provider",
        havingValue = "ANGEL_ONE", matchIfMissing = true)
public class AngelOneMarketDataProvider implements MarketDataProvider {
    private final AngelOneQuoteService quotes;
    private final AngelOneHistoricalService history;
    private final HistoricalCandleParser parser;

    public AngelOneMarketDataProvider(AngelOneQuoteService quotes,
                                      AngelOneHistoricalService history,
                                      HistoricalCandleParser parser) {
        this.quotes = quotes;
        this.history = history;
        this.parser = parser;
    }

    @Override
    public String source() {
        return "ANGEL_ONE_SMARTAPI";
    }

    @Override
    public BigDecimal lastTradedPrice(Instrument instrument) {
        return quotes.ltp(instrument.exchange(), instrument.tradingSymbol(), instrument.symbolToken());
    }

    @Override
    public List<HistoricalCandleParser.CandleValues> historicalCandles(
            String exchange, String symbolToken, String interval, String fromDate, String toDate) {
        return parser.parse(history.fetch(exchange, symbolToken, interval, fromDate, toDate));
    }
}
