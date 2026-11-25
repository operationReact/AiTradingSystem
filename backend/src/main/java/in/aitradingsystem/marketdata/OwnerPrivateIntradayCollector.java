package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Independent owner-only PAPER research ingestion. The existing shared
 * OwnerUniverseHistoryBackfill stays OFF when multiple customers are present.
 * These real provider bars never enter market_candle or the Golden Source.
 */
@Component
@ConditionalOnProperty(prefix="minttick.owner-feed",
    name={"enabled","private-intraday-enabled"},havingValue="true")
public class OwnerPrivateIntradayCollector {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    private static final List<String> PILOT=List.of(
        "RELIANCE-EQ","TCS-EQ","INFY-EQ","HDFCBANK-EQ","AXISBANK-EQ");
    private final InstrumentMasterService instruments;
    private final AppAngelOneMarketFeedService feed;
    private final HistoricalCandleParser parser;
    private final OwnerPrivateIntradayRepository repository;
    private final Clock clock;
    private final String ownerSubject;
    private Instant retryAfter=Instant.EPOCH;
    private int providerFailures;
    private int next;

    @org.springframework.beans.factory.annotation.Autowired
    public OwnerPrivateIntradayCollector(InstrumentMasterService instruments,
        AppAngelOneMarketFeedService feed,HistoricalCandleParser parser,
        OwnerPrivateIntradayRepository repository,
        @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject) {
        this(instruments,feed,parser,repository,ownerSubject,Clock.systemUTC());
    }

    OwnerPrivateIntradayCollector(InstrumentMasterService instruments,
        AppAngelOneMarketFeedService feed,HistoricalCandleParser parser,
        OwnerPrivateIntradayRepository repository,String ownerSubject,Clock clock) {
        this.instruments=instruments;
        this.feed=feed;
        this.parser=parser;
        this.repository=repository;
        this.ownerSubject=ownerSubject;
        this.clock=clock;
    }

    @Scheduled(fixedDelayString="${minttick.owner-feed.private-intraday-poll-ms:20000}")
    public synchronized void collectOne() {
        Instant now=clock.instant();
        if (now.isBefore(retryAfter) || ownerSubject.isBlank() || !feed.available()) return;
        // A bounded real provider history read may include the current
        // session. Only *completed* 5m/1H intervals may enter the archive;
        // real-time LTP is never used to construct a candle.
        ZonedDateTime local=now.atZone(IST);
        boolean weekday=local.getDayOfWeek()!=DayOfWeek.SATURDAY
                && local.getDayOfWeek()!=DayOfWeek.SUNDAY;
        boolean currentSessionCandidate=weekday
                && !local.toLocalTime().isBefore(LocalTime.of(9,22))
                && local.toLocalTime().isBefore(LocalTime.of(15,40));
        boolean completedToday=weekday
                && !local.toLocalTime().isBefore(LocalTime.of(15,40));
        LocalDate through=currentSessionCandidate || completedToday
                ? local.toLocalDate() : local.toLocalDate().minusDays(1);
        LocalDate from=through.minusDays(13);
        Instant completenessCutoff=now.minus(Duration.ofMinutes(2));
        for (int checked=0;checked<PILOT.size()*2;checked++) {
            int slot=(next++)%(PILOT.size()*2);
            String symbol=PILOT.get(slot/2);
            String interval=slot%2==0?"FIVE_MINUTE":"ONE_HOUR";
            Instrument instrument=instruments.activeInstrumentBySymbol("NSE",symbol).orElse(null);
            if (instrument==null||!"NSE".equals(instrument.exchange())||
                    !symbol.equals(instrument.tradingSymbol())||
                    instrument.symbolToken()==null||
                    !instrument.symbolToken().matches("[0-9]{1,12}")) continue;
            String token=instrument.symbolToken();
            Duration minimumSpacing=currentSessionCandidate
                    ? ("FIVE_MINUTE".equals(interval)
                        ? Duration.ofMinutes(5) : Duration.ofMinutes(30))
                    : Duration.ofHours(12);
            if (!repository.claim(ownerSubject,token,interval,now,minimumSpacing)) continue;
            try {
                var raw=feed.historicalCandles("NSE",token,interval,
                    LocalDateTime.of(from,LocalTime.of(9,15)).format(FORMAT),
                    (currentSessionCandidate
                        ? LocalDateTime.of(through,local.toLocalTime())
                        : LocalDateTime.of(through,LocalTime.of(15,30)))
                            .format(FORMAT));
                var bars=parser.parse(raw);
                if (bars.size()>2000) throw new IllegalStateException("Oversize source");
                Instant previous=null;
                var completed=new ArrayList<HistoricalCandleParser.CandleValues>();
                Duration duration="FIVE_MINUTE".equals(interval)
                        ? Duration.ofMinutes(5) : Duration.ofHours(1);
                for (var bar:bars) {
                    var date=bar.candleTime().atZone(IST).toLocalDate();
                    var time=bar.candleTime().atZone(IST).toLocalTime();
                    if (date.isBefore(from)||date.isAfter(through)||
                            time.isBefore(LocalTime.of(9,15))||
                            time.isAfter(LocalTime.of(15,30))||
                            previous!=null&&!bar.candleTime().isAfter(previous)) {
                        throw new IllegalStateException("Invalid private intraday timestamps");
                    }
                    previous=bar.candleTime();
                    // Historical source can contain a still-forming bar.
                    // Do not store until the full requested interval ended
                    // with a further two-minute source-settlement margin.
                    if (!bar.candleTime().plus(duration).isAfter(completenessCutoff)) {
                        completed.add(bar);
                    }
                }
                repository.save(ownerSubject,token,symbol,interval,through,now,completed);
                providerFailures=0;
                return;
            } catch (RuntimeException providerError) {
                repository.failed(ownerSubject,token,interval);
                providerFailures=Math.min(6,providerFailures+1);
                retryAfter=now.plus(Duration.ofSeconds(Math.min(3600,60L<<providerFailures)));
                return;
            }
        }
    }
}
