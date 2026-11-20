package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Never routes through shared market_candle; exact Cognito owner identity. */
@RestController
@RequestMapping("/api/customer/owner-history/v1")
public final class OwnerPrivateIntradayController {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final String ownerSubject;
    private final InstrumentMasterService instruments;
    private final OwnerPrivateIntradayRepository archive;

    public OwnerPrivateIntradayController(
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject,
            InstrumentMasterService instruments,OwnerPrivateIntradayRepository archive) {
        this.ownerSubject=ownerSubject;
        this.instruments=instruments;
        this.archive=archive;
    }

    @GetMapping("/intraday-candles")
    public ResponseEntity<CustomerMarketCandleController.CustomerCandlePage> candles(
            Authentication authentication,@RequestParam long instrumentId,
            @RequestParam String interval,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDate) {
        if (ownerSubject.isBlank() || !(authentication instanceof JwtAuthenticationToken jwt)
                || !ownerSubject.equals(jwt.getToken().getSubject())
                || jwt.getAuthorities().stream().noneMatch(a->"ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Owner-only intraday archive");
        }
        if (instrumentId<1 || !(interval.equals("FIVE_MINUTE")||interval.equals("ONE_HOUR"))
                || fromDate==null||toDate==null||fromDate.isAfter(toDate)
                || Duration.between(fromDate.atStartOfDay(IST),
                    toDate.plusDays(1).atStartOfDay(IST)).toDays()>65
                || toDate.isAfter(LocalDate.now(IST))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid intraday request");
        }
        final Instrument instrument;
        try {
            instrument=instruments.requireActiveInstrument(instrumentId);
        } catch (InstrumentMasterException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Instrument unavailable");
        }
        String token=instrument.symbolToken();
        if (!"NSE".equals(instrument.exchange())
                || instrument.tradingSymbol()==null
                || !instrument.tradingSymbol().endsWith("-EQ")
                || token==null||!token.matches("[0-9]{1,12}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No owner intraday data");
        }
        var rows=archive.candles(ownerSubject,token,interval,
            fromDate.atStartOfDay(IST).toInstant(),
            toDate.plusDays(1).atStartOfDay(IST).toInstant());
        var page=new CustomerMarketCandleController.CustomerCandlePage(
            instrumentId,interval,rows,0,500,rows.size(),false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }
}
