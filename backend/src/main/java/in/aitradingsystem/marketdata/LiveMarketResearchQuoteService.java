package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AngelOneFullQuote;
import in.aitradingsystem.provider.angelone.AngelOneFullQuoteService;
import org.springframework.stereotype.Service;

/** Binds provider FULL quotes to an exact active catalogue row; no fuzzy or historical-token fallback is allowed. */
@Service
public class LiveMarketResearchQuoteService {
    public static final String CONTRACT_VERSION = "angelone-full-quote.v1";

    private final InstrumentMasterService instruments;
    private final AngelOneFullQuoteService quotes;

    public LiveMarketResearchQuoteService(InstrumentMasterService instruments, AngelOneFullQuoteService quotes) {
        this.instruments = instruments;
        this.quotes = quotes;
    }

    public LiveMarketResearchQuote quote(long instrumentId) {
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        AngelOneFullQuote quote = quotes.fullQuote(instrument.exchange(), instrument.symbolToken());
        if (!instrument.exchange().equals(quote.exchange())
                || !instrument.tradingSymbol().equals(quote.tradingSymbol())
                || !instrument.symbolToken().equals(quote.symbolToken())) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE,
                    "CATALOGUE_QUOTE_IDENTITY_MISMATCH",
                    "Provider quote identity did not match the active instrument catalogue");
        }
        return new LiveMarketResearchQuote(
                CONTRACT_VERSION,
                instrument.id(),
                instrument.catalogueVersion(),
                quote.exchange(),
                quote.tradingSymbol(),
                quote.symbolToken(),
                quote.ltp(),
                quote.bestBidPrice(),
                quote.bestBidQuantity(),
                quote.bestAskPrice(),
                quote.bestAskQuantity(),
                quote.spreadBps(),
                quote.tradeVolume(),
                quote.exchangeFeedTime(),
                quote.availableAt(),
                "ANGEL_ONE_SMARTAPI_FULL",
                false);
    }
}
